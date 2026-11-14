package eu.kanade.translation.engines.inpainting.aot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.InpaintPadOps
import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.PushPullGradient
import eu.kanade.translation.engines.inpainting.RegionDispatch
import eu.kanade.translation.engines.inpainting.resolveDispatch
import eu.kanade.translation.engines.inpainting.toAotBackend
import eu.kanade.translation.engines.runtime.onnx.DeviceCapability
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.processing.memory.DirectBufferPool
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Model-agnostic neural inpainting coordinator. It owns the shared region
 * routing (bubble text vs free text) and delegates model execution to the
 * selected [NeuralInpaintModel] through the [freeTextNeuralFill] and
 * [bubbleNeuralFill] delegates. Production neural-model execution is owned by
 * the shared LiteRT GPU engine; this coordinator handles region routing and
 * classical fallback behavior.
 */
class NeuralInpaintingCoordinator(
    val performanceMode: String? = "burst",
    val neuralModel: NeuralInpaintModel = NeuralInpaintModel.AOT_GAN,
    val hardwareOverride: InpaintingHardwareOverride = InpaintingHardwareOverride.CPU,
) {

    companion object {
        internal const val MAX_INFERENCE_DIM = 768
        internal const val MAX_TOTAL_PIXELS = MAX_INFERENCE_DIM * MAX_INFERENCE_DIM

        // PaddleOCR-v6 solid-box erase-mask tunables. Defaults match
        // the validated prototype; kept
        // internal (no settings surface).
        internal const val PADDLE_CROP_PAD = 12
        internal const val PADDLE_THRESH = 0.15f
        internal const val PADDLE_BOX_THRESH = 0.24f
        internal const val PADDLE_UNCLIP_RATIO = 1.6f
        internal const val REPORT_FREE_TEXT_PAD = 2
        internal const val REPORT_FREE_TEXT_DILATE = 3
        internal const val REPORT_AOT_CONTEXT = 512
        internal const val REPORT_FREE_TEXT_FEATHER = 8
        internal const val REPORT_BUBBLE_TILE_SIZE = 448
        internal const val REPORT_BUBBLE_TILE_OVERLAP = 64
        internal const val REPORT_BUBBLE_SMOOTH_PASSES = 12
        internal const val REPORT_INPAINT_CONTEXT = 64
        internal const val BUBBLE_SEG_MASK_EROSION = 5

        // distance-field feather ramp (px) blending the neural
        // output with the page. Replaces the earlier 2â€“6px box-blur cliff that
        // exposed the erase-box rectangle. See [BubbleMaskBuilder.featherAlphaField].
        internal const val FEATHER_RAMP_PX = 12

        // Worst-case heap bytes per pixel for one neural bubble tile (source
        // crop, mask bytes, mask pixels, padded source/mask tensors, decoded
        // candidate, alpha field, blend output). Used by
        // [bubbleNeuralMemoryGateTripped] as a conservative upper bound.
        internal const val NEURAL_TILE_WORKING_BYTES_PER_PIXEL = 32L
    }

    private val scratchLock = Any()

    // pooled DIRECT buffers for the variable-shape inpaint tensors
    // (memory: maxPoolSize=2 bounds resident native memory; the active infer
    // region is exposed via the buffer limit).
    internal val imgInputPool = DirectBufferPool(
        bufferCapacityBytes = 3 * MAX_TOTAL_PIXELS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )
    internal val maskInputPool = DirectBufferPool(
        bufferCapacityBytes = MAX_TOTAL_PIXELS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    internal var sharedImgPixels: IntArray? = null
    internal var sharedMaskPixels: IntArray? = null
    internal var sharedResultPixels: IntArray? = null

    internal fun getImgPixels(): IntArray {
        return sharedImgPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedImgPixels = it }
    }
    internal fun getMaskPixels(): IntArray {
        return sharedMaskPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedMaskPixels = it }
    }
    internal fun getResultPixels(): IntArray {
        return sharedResultPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedResultPixels = it }
    }

    fun clearScratch() {
        synchronized(scratchLock) {
            sharedImgPixels = null
            sharedMaskPixels = null
            sharedResultPixels = null
        }
    }

    internal var fixedSession: OrtSession? = null
    internal var fixedSessionRoute = "fixed_cpu"
    internal var fixedNnapiSession: OrtSession? = null
    internal var fixedQnnSession: OrtSession? = null
    internal var fixedQnnBackend: AotExecutionCoordinator.Backend? = null
    internal var fixedQnnModelId: String? = null
    internal var dynamicSession: OrtSession? = null
    internal var dynamicSessionRoute = "dynamic_cpu"
    internal val nnapiHealth = NnapiHealthMonitor()
    var paddleDetector: eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine? = null

    /**
     * Optional LiteRT neural fill for Paddle-refined free-text groups. If it
     * returns null, an explicitly initialized legacy ONNX session may be
     * tried; without one, the group uses the classical free-text path.
     */
    var freeTextNeuralFill: ((image: Bitmap, groupBoxes: List<IntArray>) -> Bitmap?)? = null

    /**
     * Optional LiteRT neural fill for one bubble stroke-mask tile. The mask is
     * the bubble text-line / eroded stroke mask in the square crop, never the
     * full bubble box. A null result lets an explicitly initialized legacy
     * ONNX session try; when unavailable, the caller uses the solid median.
     */
    var bubbleNeuralFill: ((image: Bitmap, localMask: ByteArray, crop: IntArray, side: Int) -> IntArray?)? = null

    /**
     * Route tag of the most recently accepted neural candidate
     * (fixed_qnn_htp / fixed_qnn_gpu / fixed_nnapi / fixed_xnnpack / fixed_cpu /
     * dynamic_xnnpack / dynamic_cpu), for honest page-level perf logging.
     * Null before the first acceptance.
     */
    @Volatile
    var lastAcceptedRoute: String? = null
        internal set

    /** Actual route used for the most recent bubble fill. */
    @Volatile
    var lastBubbleRoute: String? = null
        internal set

    /** True when this run used a backend below the selected mode's requested path. */
    @Volatile
    var lastRunDegraded: Boolean = false
        internal set

    // Load only the selected neural model's ONNX sessions. The AOT-GAN
    // provenance is recorded in scripts/models.manifest; the LiteRT Manga LaMa
    // model initializes inside its own engine and never reaches this path.
    fun initialize(
        fixedModelFile: File?,
        dynamicModelFile: File?,
    ) {
        fixedSession = initializeSession(fixedModelFile, AotModelContract.Kind.FIXED_512, "fixed")
        dynamicSession = initializeSession(dynamicModelFile, AotModelContract.Kind.DYNAMIC, "dynamic")
        val selectedAccelerator = hardwareOverride.toAotBackend()
        when (selectedAccelerator) {
            AotExecutionCoordinator.Backend.QNN_HTP,
            AotExecutionCoordinator.Backend.QNN_GPU,
            -> if (fixedModelFile != null && fixedModelFile.exists()) {
                fixedQnnModelId = ModelRoutingEngine.resolveModelId(fixedModelFile.absolutePath)
                fixedQnnSession = initializeQnnSession(fixedModelFile, selectedAccelerator)
                if (fixedQnnSession != null) fixedQnnBackend = selectedAccelerator
            }
            AotExecutionCoordinator.Backend.NNAPI -> {
                nnapiHealth.resetForNewSession()
                fixedNnapiSession = initializeStrictNnapiSession(fixedModelFile)
            }
            null -> Unit
            else -> error("Unexpected AOT accelerator candidate $selectedAccelerator")
        }
        logcat(LogPriority.INFO) {
            "[inpaint] init fixedSession=${fixedSession != null} fixedRoute=$fixedSessionRoute " +
                "fixedQnn=${fixedQnnSession != null} fixedNnapi=${fixedNnapiSession != null} " +
                "dynamic=${dynamicSession != null} dynamicRoute=$dynamicSessionRoute " +
                "requestedHardware=$hardwareOverride " +
                DeviceCapability.describe()
        }
    }

    fun inpaintRegions(
        image: Bitmap,
        boxes: List<IntArray>,
        labels: List<Int>? = null,
        dispatch: RegionDispatch = InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true),
        blocks: List<eu.kanade.translation.model.TranslationBlock>? = null,
    ): Bitmap = runInpaintRegions(image, boxes, labels, dispatch, blocks)

    /**
     * True when this coordinator's ONNX AOT-GAN sessions are usable. The
     * LiteRT Manga LaMa model never initializes here â€” its availability is
     * reported by the LiteRT engine and reaches this class through the
     * [freeTextNeuralFill] / [bubbleNeuralFill] delegates.
     */
    fun isInitialized(): Boolean = when (neuralModel) {
        NeuralInpaintModel.AOT_GAN ->
            fixedSession != null || dynamicSession != null || fixedQnnSession != null || fixedNnapiSession != null
        NeuralInpaintModel.LAMA_LITERT_GPU -> false
    }

    /**
     * Morphological binary erosion for segmentation masks.
     * Erodes by [radius] px (default [BUBBLE_SEG_MASK_EROSION]) within component bounds
     * to protect hand-drawn stroke borders of speech bubbles from being erased.
     * If a small bubble completely vanishes from erosion, falls back to a milder
     * radius (radius / 2) or retains original pixels.
     */
    internal fun erodeBinaryMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        boundsList: List<List<Int>>,
        radius: Int = BUBBLE_SEG_MASK_EROSION,
    ): ByteArray {
        if (radius <= 0) return mask
        val eroded = ByteArray(width * height)
        val r2 = radius * radius
        for (bounds in boundsList) {
            val minX = bounds[0].coerceIn(0, width)
            val minY = bounds[1].coerceIn(0, height)
            val maxX = bounds[2].coerceIn(minX, width)
            val maxY = bounds[3].coerceIn(minY, height)
            var erodedCount = 0
            for (y in minY until maxY) {
                val rowOffset = y * width
                for (x in minX until maxX) {
                    if (mask[rowOffset + x] == 0.toByte()) continue
                    var keep = true
                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) {
                            keep = false
                            break
                        }
                        val nRowOffset = ny * width
                        for (dx in -radius..radius) {
                            if (dx * dx + dy * dy <= r2) {
                                val nx = x + dx
                                if (nx < 0 || nx >= width || mask[nRowOffset + nx] == 0.toByte()) {
                                    keep = false
                                    break
                                }
                            }
                        }
                        if (!keep) break
                    }
                    if (keep) {
                        eroded[rowOffset + x] = 1
                        erodedCount++
                    }
                }
            }
            if (erodedCount == 0) {
                val milderRadius = max(1, radius / 2)
                val m2 = milderRadius * milderRadius
                for (y in minY until maxY) {
                    val rowOffset = y * width
                    for (x in minX until maxX) {
                        if (mask[rowOffset + x] == 0.toByte()) continue
                        var keep = true
                        for (dy in -milderRadius..milderRadius) {
                            val ny = y + dy
                            if (ny < 0 || ny >= height) {
                                keep = false
                                break
                            }
                            val nRowOffset = ny * width
                            for (dx in -milderRadius..milderRadius) {
                                if (dx * dx + dy * dy <= m2) {
                                    val nx = x + dx
                                    if (nx < 0 || nx >= width || mask[nRowOffset + nx] == 0.toByte()) {
                                        keep = false
                                        break
                                    }
                                }
                            }
                            if (!keep) break
                        }
                        if (keep) {
                            eroded[rowOffset + x] = 1
                            erodedCount++
                        }
                    }
                }
                if (erodedCount == 0) {
                    for (y in minY until maxY) {
                        val rowOffset = y * width
                        for (x in minX until maxX) {
                            if (mask[rowOffset + x] != 0.toByte()) {
                                eroded[rowOffset + x] = 1
                            }
                        }
                    }
                }
            }
        }
        return eroded
    }

    internal inner class PreparedFixedInput(
        val imageTensor: OnnxTensor,
        val maskTensor: OnnxTensor,
        val imageBuffer: FloatBuffer,
        val maskBuffer: FloatBuffer,
        val croppedMaskPixels: IntArray,
        val offset: Int,
        val grayscale: Boolean,
    ) : AutoCloseable {
        override fun close() {
            try {
                imageTensor.close()
            } finally {
                try {
                    maskTensor.close()
                } finally {
                    imgInputPool.release(imageBuffer)
                    maskInputPool.release(maskBuffer)
                }
            }
        }
    }

    /** Creates the immutable tensors once; both EP attempts consume these exact values. */
    internal fun prepareFixedInput(
        page: Bitmap,
        mask: Bitmap,
        crop: IntArray,
        side: Int,
    ): PreparedFixedInput {
        require(side in 1..InpaintPadOps.SIZE)
        val sourcePixels = IntArray(side * side)
        page.getPixels(sourcePixels, 0, side, crop[0], crop[1], side, side)
        val maskPixels = IntArray(side * side)
        mask.getPixels(maskPixels, 0, side, 0, 0, side, side)
        // Ring-median background for AOT-GAN (< 512 crops): replicate clamp
        // smears black border strokes into the receptive field and surfaces as
        // yellow/chromatic noise. Replicate padding stays isolated to LaMa.
        val cropMask = ByteArray(side * side)
        for (i in cropMask.indices) {
            if (InpaintPixelOps.maskValue(maskPixels[i]) > 127) cropMask[i] = 1
        }
        val background = PushPullGradient.localRingMedian(
            sourcePixels,
            side,
            side,
            cropMask,
            PushPullGradient.DEFAULT_RING,
        )
        val paddedPixels = getImgPixels()
        InpaintPadOps.padSquareInto(sourcePixels, side, background, paddedPixels)
        val paddedMask = getMaskPixels()
        InpaintPadOps.padSquareInto(maskPixels, side, 0, paddedMask)

        var totalChroma = 0
        val sampleStride = max(1, sourcePixels.size / 400)
        var samples = 0
        for (index in sourcePixels.indices step sampleStride) {
            val pixel = sourcePixels[index]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            totalChroma += max(red, max(green, blue)) - min(red, min(green, blue))
            samples++
        }

        val imageBuffer = imgInputPool.acquire()
        val maskBuffer = maskInputPool.acquire()
        var imageTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        try {
            val offset = AotFixedTensorContract.writeInputs(
                paddedImage = paddedPixels,
                paddedMask = paddedMask,
                sourceSize = side,
                imageBuffer = imageBuffer,
                maskBuffer = maskBuffer,
            )
            val environment = OnnxRuntimeProvider.environment
            imageTensor = OnnxTensor.createTensor(environment, imageBuffer, AotFixedTensorContract.imageShape())
            maskTensor = OnnxTensor.createTensor(environment, maskBuffer, AotFixedTensorContract.maskShape())
            return PreparedFixedInput(
                imageTensor = imageTensor,
                maskTensor = maskTensor,
                imageBuffer = imageBuffer,
                maskBuffer = maskBuffer,
                croppedMaskPixels = maskPixels,
                offset = offset,
                grayscale = totalChroma / samples < 15,
            )
        } catch (error: Throwable) {
            try {
                imageTensor?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            try {
                maskTensor?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            imgInputPool.release(imageBuffer)
            maskInputPool.release(maskBuffer)
            throw error
        }
    }

    fun close() {
        val fixed = fixedSession
        val nnapi = fixedNnapiSession
        val qnn = fixedQnnSession
        val dynamic = dynamicSession
        // Detach first so repeated/concurrent lifecycle teardown cannot close a
        // native handle twice. Each distinct session is still attempted when its
        // sibling close fails.
        fixedSession = null
        fixedNnapiSession = null
        fixedQnnSession = null
        fixedQnnBackend = null
        fixedQnnModelId = null
        dynamicSession = null
        AotSessionLifecycle.closeIndependently(fixed, dynamic, nnapi, qnn) { failure ->
            logcat(LogPriority.ERROR, failure.error) {
                "[inpaint] route=${failure.route} close=failed"
            }
        }
        clearScratch()
        imgInputPool.clear()
        maskInputPool.clear()
    }

    fun freeNativeSessions() {
        close()
    }

    /**
     * drop cross-call working buffers while staying usable.
     * The AOT pixel scratch and pooled tensor buffers are released so an OOM
     * recovery can allocate only for the next page it sees.
     */
    fun reclaimPooledMemory() {
        imgInputPool.clear()
        maskInputPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        clearScratch()
        imgInputPool.clear()
        maskInputPool.clear()
    }
}
