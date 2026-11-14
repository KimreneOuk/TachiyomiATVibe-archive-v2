package eu.kanade.translation.engines.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.PageInpaintingEngine
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.vision.detection.OnnxPageTextDetector
import eu.kanade.translation.engines.vision.detection.OnnxPanelDetector
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.engines.vision.ocr.RoiOcrEngine
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDynamicPageBatchPolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRollingP95HysteresisDowngradePolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket
import eu.kanade.translation.engines.vision.segmentation.OnnxBubbleSegmenter
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import kotlinx.coroutines.sync.Mutex
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong

class RoiPageRecognitionEngine(
    internal val context: Context,
    internal val language: TextRecognizerLanguage,
    internal val ocrModel: OcrModel,
    internal val inpaintingMode: InpaintingMode = InpaintingMode.QUALITY,
    internal val neuralInpaintModel: NeuralInpaintModel = NeuralInpaintModel.DEFAULT,
    internal val inpaintingHardwareOverride: InpaintingHardwareOverride = InpaintingHardwareOverride.CPU,
) : PageRecognitionEngine {

    internal val modelStore = OnnxModelStore(context)
    internal var detector: OnnxPageTextDetector? = null

    /**
     * optional YOLO26-nano manga panel detector. Best-effort
     * init (mirrors paddleDet): when the asset is missing or fails to load,
     * this stays null and panel assignment is skipped â€” translation proceeds
     * panel-less exactly as before. Never blocks OCR/inpaint.
     */
    internal var panelDetector: OnnxPanelDetector? = null

    /**
     * optional YOLO11-seg manga bubble segmenter.
     * Drives the precise Interior Median Solid Fill and Symmetrical Growth.
     */
    internal var bubbleSegmenter: OnnxBubbleSegmenter? = null
    internal var roiOcrEngine: RoiOcrEngine? = null
    internal var paddlePageOcrCoordinator: PaddlePageOcrCoordinator? = null
    internal var paddleBatchGovernor: PaddleOcrRollingP95HysteresisDowngradePolicy? = null
    internal var paddleBatchGovernorReason = "not_paddle"
    internal var paddleDynamicBatchPolicy: PaddleOcrDynamicPageBatchPolicy? = null

    /** Governor-aware dynamic chunk ceiling; null when fixed-tier batching is active. */
    internal fun paddleDynamicCeiling(bucket: PaddleOcrWidthBucket): Int? {
        val dynamicPolicy = paddleDynamicBatchPolicy ?: return null
        val ceiling = dynamicPolicy.ceilingFor(bucket)
        val governorCap = paddleBatchGovernor?.activeBatchSize
            ?.takeIf { it != PaddleOcrBatchSize.B8 }
            ?.value
        return if (governorCap != null) minOf(ceiling, governorCap) else ceiling
    }
    internal val paddlePageGenerationCounter = AtomicLong(0L)

    /**
     * PP-OCRv6 small **det** engine, used to split a vertical-text
     * ROI into individual text lines for the PaddleOCR rec path. Only built when
     * the selected OCR model is [OcrModel.PADDLEOCR_V6_SMALL] (the only
     * horizontal-line rec engine). Stays null otherwise; the rec path checks for
     * null and falls back to the ink-gap heuristic. See
     * `docs/superpowers/specs/2026-06-23-paddleocr-v6-det-onnx-integration-design.md`.
     */
    internal var paddleDet: PaddleOcrV6DetEngine? = null
    internal var inpainting: NeuralInpaintingCoordinator? = null
    internal var litertInpainting: LiteRTMangaInpaintingEngine? = null
    internal var pageInpainter: PageInpaintingEngine? = null

    @Volatile
    var lastDetectMs: Double = 0.0
        internal set

    @Volatile
    var lastSegmentMs: Double = 0.0
        internal set

    @Volatile
    var lastOcrMs: Double = 0.0
        internal set

    @Volatile
    var lastInpaintMs: Double = 0.0
        internal set

    @Volatile
    var lastLeafCount: Int = 0
        internal set

    @Volatile
    var lastBoxCount: Int = 0
        internal set

    @Volatile
    var lastProvider: String = "cpu"
        internal set

    @Volatile
    internal var initialized = false
    internal var initFailed = false
    internal val initMutex = Mutex()

    /**
     * serializes native ONNX inference (analyze/inpaint) against
     * close(). The batch path can call close() before acquiring the translator
     * permit, so this is the primary defense. analyze()/inpaint() hold it across
     * each native OrtSession.run(); close() tryLocks it before freeing sessions.
     * If held at close time, close() logs, skips native free, and leaves [closed]
     * as the backstop â€” degrades to leak-instead-of-SIGSEGV. close() is non-suspend
     * (main thread), so it uses tryLock rather than runBlocking.
     */
    internal val nativeGuard = Mutex()

    // cooperative close flag. close() can race an in-flight
    // analyze()/inpaint() and free native ONNX sessions mid-call past a
    // suspension point (SIGSEGV, not catchable). analyze()/inpaint() poll this
    // before each ONNX call and bail cleanly; [nativeGuard] closes the window.
    @Volatile
    internal var closed = false

    // cached translation_diagnostics pref. Resolved lazily (not at
    // construction) to avoid an Injekt cycle during init; cached after first read.
    @Volatile
    internal var translationDiagnosticsEnabled: Boolean = false

    @Volatile
    internal var diagnosticsResolved: Boolean = false
    internal fun resolveDiagnostics(): Boolean {
        if (diagnosticsResolved) return translationDiagnosticsEnabled
        val prefs = try {
            Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
        } catch (_: Throwable) {
            null
        }
        translationDiagnosticsEnabled = prefs?.translationDiagnostics()?.get() ?: false
        diagnosticsResolved = true
        return translationDiagnosticsEnabled
    }

    // cached reading-order pref (AUTO derives RTL from source
    // language). Resolved lazily to avoid an Injekt cycle during init.
    @Volatile
    internal var readingOrderRtl: Boolean = true

    @Volatile
    internal var readingOrderResolved: Boolean = false
    internal fun resolveReadingOrderRtl(): Boolean {
        if (readingOrderResolved) return readingOrderRtl
        val prefs = try {
            Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
        } catch (_: Throwable) {
            null
        }
        val pref = prefs?.translationReadingOrder()?.get()
            ?: tachiyomi.domain.translation.TranslationReadingOrder.AUTO
        readingOrderRtl = when (pref) {
            tachiyomi.domain.translation.TranslationReadingOrder.AUTO ->
                language == TextRecognizerLanguage.JAPANESE
            tachiyomi.domain.translation.TranslationReadingOrder.RTL_MANGA -> true
            tachiyomi.domain.translation.TranslationReadingOrder.LTR_COMIC -> false
        }
        readingOrderResolved = true
        return readingOrderRtl
    }

    val isAvailable: Boolean
        get() = !initFailed && (initialized || modelStore.modelsAvailable() || modelStore.assetsAvailable())

    /** True only after the selected model's LiteRT GPU session is built and usable. */
    fun neuralInpaintAvailable(): Boolean = litertInpainting?.isAvailable() == true

    /**
     * Warms up engine sessions outside the timed recognition region.
     */
    suspend fun warmUp() {
        if (isAvailable && !initialized) {
            initialize()
        }
    }
    override suspend fun analyze(bitmap: Bitmap): PageTranslation = runAnalyzeFlow(bitmap)

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? = runInpaintFlow(bitmap, pageTranslation)

    internal fun freeNativeSessions() {
        try {
            detector?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing detector" }
        } finally {
            detector = null
        }
        try {
            roiOcrEngine?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing roiOcrEngine" }
        } finally {
            roiOcrEngine = null
            paddlePageOcrCoordinator = null
            paddleBatchGovernor = null
            paddleDynamicBatchPolicy = null
            paddleBatchGovernorReason = "not_paddle"
        }
        try {
            paddleDet?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing paddleDet" }
        } finally {
            paddleDet = null
        }
        try {
            inpainting?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing inpainting" }
        } finally {
            inpainting = null
        }
        try {
            litertInpainting?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing litertInpainting" }
        } finally {
            litertInpainting = null
        }
        try {
            panelDetector?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing panelDetector" }
        } finally {
            panelDetector = null
        }
        try {
            bubbleSegmenter?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing bubbleSegmenter" }
        } finally {
            bubbleSegmenter = null
        }
        pageInpainter = null
        initialized = false
    }

    override fun close() {
        // set the cooperative close flag FIRST so an in-flight
        // analyze()/inpaint() polling [closed] between ONNX calls bails cleanly
        // before touching a session freed below (avoids a native crash).
        closed = true
        // tryLock (non-suspend, never blocks the main thread). Every
        // close() call site is guarded by the translator permit, so the lock
        // should be free; tryLock is defense-in-depth. If it fails, defer the
        // session free to the lock holder (leak-instead-of-SIGSEGV).
        val nativeDrained = nativeGuard.tryLock()
        if (!nativeDrained) {
            logcat(LogPriority.WARN) {
                "RoiPageRecognitionEngine.close: nativeGuard held (unexpected â€” permit guard " +
                    "should prevent an in-flight native run at close time); deferring native " +
                    "session free to the lock holder to avoid use-after-free or leak."
            }
            return
        }
        try {
            freeNativeSessions()
        } finally {
            nativeGuard.unlock()
        }
    }

    override fun reclaimPooledMemory() {
        // free off-heap pooled state held by sub-engines WITHOUT
        // tearing them down. Called by OOM recovery so native pressure from one
        // page doesn't carry into the next. Guarded so partial init is a no-op.
        try {
            detector?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            roiOcrEngine?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            paddleDet?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            inpainting?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            panelDetector?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            bubbleSegmenter?.reclaimPooledMemory()
        } catch (_: Exception) {}
    }

    override fun forceReleaseNativeBuffers() {
        // Memory-pressure callbacks may race an in-flight native run. Never
        // drain child pools while the worker owns nativeGuard: skip this call
        // (leak-instead-of-SIGSEGV, mirroring close()). The worker's own
        // finally releases its pools after the guarded native call completes,
        // and the next memory-pressure callback will retry.
        //
        // The skip-vs-drain decision forwards to TranslationSafetyPrimitives so
        // the P0-1 invariant (no drain while the native lock is held) is
        // unit-testable without an ONNX engine â€” see drainChildBuffersGuarded.
        val lockAcquired = nativeGuard.tryLock()
        if (!lockAcquired) {
            logcat(LogPriority.WARN) {
                "RoiPageRecognitionEngine.forceReleaseNativeBuffers: nativeGuard held; " +
                    "skipping pooled-buffer release to avoid native use-after-free"
            }
            return
        }
        try {
            val engines = listOfNotNull(
                detector?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                roiOcrEngine?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                paddleDet?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                inpainting?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                panelDetector?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                bubbleSegmenter?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
            )
            TranslationSafetyPrimitives.drainChildBuffersGuarded(lockHeld = false, engines = engines)
        } finally {
            nativeGuard.unlock()
        }
    }
}
