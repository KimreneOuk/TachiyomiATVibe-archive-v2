package eu.kanade.translation.processing

import com.hippo.unifile.UniFile
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isColorizeOnlyComplete
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.PageWriteOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/**
 * Ordered batch translation coordinator for full chapter execution.
 *
 * Architecture:
 * 1. Ordered source inventory -> pre-register totals in [ChapterTranslationStore].
 * 2. Durable progress rebuilding: scans existing valid committed page bundles and skips them.
 * 3. Full-chapter OCR barrier: detects and recognizes text across all remaining pages first.
 * 4. Bounded 2-task concurrency: translation chunk loop and sequential inpainting loop overlap.
 * 5. Fail-closed resume safety: errors or cancellation never wipe committed bundles on disk.
 */
internal class ChapterBatchExecutor(
    private val processor: TranslationProcessor,
    private val recognitionEngineProvider: () -> PageRecognitionEngine,
    private val translatorProvider: () -> TextTranslator,
    private val prepareEngines: suspend () -> Unit = {},
) {

    data class Progress(
        val chapterKey: String,
        val completedPages: Int,
        val totalPages: Int,
        val currentStage: String,
        val isFinished: Boolean = false,
        val error: String? = null,
    ) {
        val fraction: Float get() = if (totalPages > 0) completedPages.toFloat() / totalPages else 0f
    }

    /**
     * Runs the page stages for one admitted chapter. Queue admission and
     * active-run state remain owned by [BatchTranslationCoordinator].
     */
    suspend fun execute(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
        chunkSize: Int = 5,
        onProgress: ((Progress) -> Unit)? = null,
        colorizeOnly: Boolean = false,
        prepareStream: (suspend (String) -> Unit)? = null,
    ): Progress = if (colorizeOnly) {
        executeColorizeOnly(
            chapterKey = chapterKey,
            orderedPageKeys = orderedPageKeys,
            streamProvider = streamProvider,
            store = store,
            companionDir = companionDir,
            onProgress = onProgress,
            prepareStream = prepareStream,
        )
    } else {
        executeBatch(
            chapterKey = chapterKey,
            orderedPageKeys = orderedPageKeys,
            streamProvider = streamProvider,
            store = store,
            companionDir = companionDir,
            inpaintingEnabled = inpaintingEnabled,
            chunkSize = chunkSize,
            onProgress = onProgress,
        )
    }

    /**
     * Colorize-only pass: decode each original page and colorize it directly —
     * no OCR barrier, no translation, no inpaint, no translator/OCR engine
     * preparation (colorization inits lazily). Pages already carrying real OCR
     * data are never clobbered; colorized-from-original pages are the resume
     * scan's completed set. [prepareStream] is awaited before each decode so a
     * streamed chapter's page can be fetched from its source on demand.
     *
     * A per-page colorization failure (decode, engine, upscale, commit) is
     * isolated: the page is durably marked FAILED with [PageTranslation.coloringError]
     * and the run continues, because each page's colorization is independent
     * work. The chapter then completes as READY_WITH_WARNINGS (see
     * [BatchProgressReconciler.reconcileFlaggedCompleted]) and the failed pages
     * retry on the next colorize run. Only RUN-LEVEL failures — the colorization
     * runtime being unavailable, or a streamed page that cannot be fetched from
     * the source (transient, affects every remaining page) — end the run early.
     */
    private suspend fun executeColorizeOnly(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        onProgress: ((Progress) -> Unit)?,
        prepareStream: (suspend (String) -> Unit)? = null,
    ): Progress {
        val totalCount = orderedPageKeys.size
        val currentState = store.state.value

        val completedKeys = orderedPageKeys.filter { key ->
            val page = currentState[key]
            // OCR-READY pages keep their real data (never clobbered). Coloring
            // reuse is NOT asserted here: identity-unverified coloring is
            // re-verified against the fresh decode fingerprint in the loop.
            page?.ocrStatus == StageStatus.READY
        }.toSet()

        var completedCount = completedKeys.size
        var failedCount = 0
        publish(Progress(chapterKey, completedCount, totalCount, "RESUME_CHECK"), onProgress)
        logcat(LogPriority.INFO) {
            "BatchColorizeOnly: chapter $chapterKey has $completedCount/$totalCount pages already complete"
        }

        val remainingKeys = orderedPageKeys.filter { it !in completedKeys }
        if (remainingKeys.isEmpty()) {
            val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(finalProgress, onProgress)
            return finalProgress
        }

        // First call builds the colorization runtime; absence (missing assets or
        // a failed session) is a chapter-level failure, not a per-page skip.
        val coloringEngine = processor.activeColorizationEngine()
        if (coloringEngine == null) {
            val err = "Colorization is disabled or its model is unavailable"
            logcat(LogPriority.ERROR) { "BatchColorizeOnly: $err for chapter $chapterKey" }
            val failProgress = Progress(chapterKey, completedCount, totalCount, "FAILED", isFinished = true, error = err)
            publish(failProgress, onProgress)
            return failProgress
        }

        // The 30s heartbeat runs beside the page loop, so a hung fetch, a hung
        // model run, or a dead process is visible as heartbeats that stop
        // advancing — with the page key and stage it stopped on.
        val telemetry = ColorizeRunTelemetry(chapterKey = chapterKey, totalCount = totalCount)
        return coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(ColorizeRunTelemetry.HEARTBEAT_INTERVAL_MS)
                    telemetry.logHeartbeat()
                }
            }
            try {
                runColorizeOnlyPages(
                    processor = processor,
                    chapterKey = chapterKey,
                    remainingKeys = remainingKeys,
                    totalCount = totalCount,
                    initialCompleted = completedCount,
                    streamProvider = streamProvider,
                    store = store,
                    companionDir = companionDir,
                    onProgress = onProgress,
                    prepareStream = prepareStream,
                    coloringEngine = coloringEngine,
                    telemetry = telemetry,
                )
            } finally {
                ticker.cancel()
                telemetry.logRunEnd()
            }
        }
    }

    /** Page traversal for one colorize-only run; [telemetry] feeds the 30s heartbeat. */
    private suspend fun runColorizeOnlyPages(
        processor: TranslationProcessor,
        chapterKey: String,
        remainingKeys: List<String>,
        totalCount: Int,
        initialCompleted: Int,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        onProgress: ((Progress) -> Unit)?,
        prepareStream: (suspend (String) -> Unit)?,
        coloringEngine: PageColorizationEngine,
        telemetry: ColorizeRunTelemetry,
    ): Progress {
        var completedCount = initialCompleted
        var failedCount = 0
        for ((pageIndex, pageKey) in remainingKeys.withIndex()) {
            telemetry.pageStart(pageIndex, pageKey)
            val pageStarted = System.nanoTime()
            try {
                prepareStream?.invoke(pageKey)
                telemetry.stageDecode()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                val err = "Failed to fetch page image for $pageKey during colorize pass: ${t.message}"
                logcat(LogPriority.ERROR) { "BatchColorizeOnly: $err" }
                store.updatePageFromCurrentSnapshot(pageKey, "batch:colorize-fetch-failed") { current ->
                    (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                        ocrStatus = StageStatus.SKIPPED
                        translationStatus = StageStatus.SKIPPED
                        inpaintStatus = StageStatus.SKIPPED
                        coloringStatus = StageStatus.FAILED
                        coloringError = err
                    }
                }
                val failProgress = Progress(chapterKey, completedCount, totalCount, "FAILED", isFinished = true, error = err)
                publish(failProgress, onProgress)
                return failProgress
            }
            val fetchFinished = System.nanoTime()
            val decoded = processor.decodeImage(pageKey, { streamProvider(pageKey) }) { recognitionEngineProvider() }
            val decodeFinished = System.nanoTime()
            if (decoded == null) {
                val err = "Failed to decode page image for $pageKey during colorize pass"
                logcat(LogPriority.ERROR) { "BatchColorizeOnly: $err" }
                store.updatePageFromCurrentSnapshot(pageKey, "batch:colorize-decode-failed") { current ->
                    (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                        ocrStatus = StageStatus.SKIPPED
                        translationStatus = StageStatus.SKIPPED
                        inpaintStatus = StageStatus.SKIPPED
                        coloringStatus = StageStatus.FAILED
                        coloringError = err
                    }
                }
                // Isolated page failure: the decode is page-local (corrupt or
                // oversized image), so remaining pages still get colored.
                failedCount++
                telemetry.pageFailed()
                publish(Progress(chapterKey, completedCount, totalCount, "COLORING"), onProgress)
                continue
            }
            // Input-identity line: identical fingerprints across different page
            // keys here mean the stream resolution fed the same image twice.
            logcat(LogPriority.INFO) {
                "BatchColorizeOnly: page ${pageIndex + 1}/$totalCount key=$pageKey " +
                    "fp=${decoded.sourceFingerprint?.take(12) ?: "null"} bytes=${decoded.sourceBytesSize} " +
                    "decoded=${decoded.bitmap.width}x${decoded.bitmap.height} " +
                    "orig=${decoded.originalWidth}x${decoded.originalHeight} sample=${decoded.sampleSize}"
            }
            // Reuse only identity-verified coloring: the stored fingerprint
            // must match the bytes just decoded; otherwise recolor.
            val prior = store.state.value[pageKey]
            if (
                prior?.isColorizeOnlyComplete == true &&
                prior.sourceFingerprint != null &&
                prior.sourceFingerprint == decoded.sourceFingerprint
            ) {
                processor.releaseDecoded(decoded)
                logcat(LogPriority.INFO) {
                    "[ColorizeTiming] chapter=$chapterKey page=$pageKey result=reused " +
                        "fetchMs=${(fetchFinished - pageStarted) / 1_000_000} " +
                        "decodeMs=${(decodeFinished - fetchFinished) / 1_000_000}"
                }
                completedCount++
                telemetry.pageReused()
                publish(Progress(chapterKey, completedCount, totalCount, "COLORING"), onProgress)
                continue
            }
            telemetry.stageColor()
            val page = try {
                // Live indicator: holders and the batch tracker see the page
                // mid-run instead of only the final commit.
                store.markColorizeRunning(pageKey, "batch:colorize-running")
                PageTranslation(sourceFileName = pageKey).apply {
                    imgWidth = decoded.bitmap.width.toFloat()
                    imgHeight = decoded.bitmap.height.toFloat()
                    originalImgWidth = decoded.originalWidth.toFloat()
                    originalImgHeight = decoded.originalHeight.toFloat()
                    decodeSampleSize = decoded.sampleSize
                    sourceFingerprint = decoded.sourceFingerprint
                    ocrStatus = StageStatus.SKIPPED
                    translationStatus = StageStatus.SKIPPED
                    inpaintStatus = StageStatus.SKIPPED
                    originalImageFallback = true
                    updatedAt = System.currentTimeMillis()
                    val upscaler = processor.activeUpscalingEngine()
                    processor.colorize(
                        pageKey = pageKey,
                        page = this,
                        coloringEngine = coloringEngine,
                        companionDir = if (upscaler != null) null else companionDir,
                        base = decoded.bitmap,
                        fromOriginal = true,
                    )
                    if (upscaler != null && coloringStatus == StageStatus.READY) {
                        try {
                            processor.upscale(pageKey, this, upscaler, companionDir)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            coloringStatus = StageStatus.FAILED
                            coloringError = t.message ?: "Upscaling failed"
                        }
                    }
                }
            } finally {
                processor.releaseDecoded(decoded)
            }

            val colorFinished = System.nanoTime()
            val commit = try {
                processor.handOffColoredBitmapForDisplay(page)
                store.updatePageFromCurrentSnapshot(pageKey, "batch:colorize-commit") { page }
            } finally {
                processor.releasePageBitmaps(page)
            }
            val committed = System.nanoTime()
            logcat(LogPriority.INFO) {
                "[ColorizeTiming] chapter=$chapterKey page=$pageKey model=${coloringEngine.javaClass.simpleName} " +
                    "fetchMs=${(fetchFinished - pageStarted) / 1_000_000} " +
                    "decodeMs=${(decodeFinished - fetchFinished) / 1_000_000} " +
                    "colorAndPublishMs=${(colorFinished - decodeFinished) / 1_000_000} " +
                    "commitMs=${(committed - colorFinished) / 1_000_000} " +
                    "totalMs=${(committed - pageStarted) / 1_000_000} status=${page.coloringStatus}"
            }
            if (commit is ChapterTranslationStore.PatchResult.Rejected || page.coloringStatus != StageStatus.READY) {
                val error = if (commit is ChapterTranslationStore.PatchResult.Rejected) {
                    "Failed to save colorized page $pageKey: ${commit.reason}"
                } else {
                    "Failed to colorize page $pageKey: ${page.coloringError ?: "no colorized image was produced"}"
                }
                logcat(LogPriority.ERROR) { "BatchColorizeOnly: $error" }
                // Isolated page failure: the page's durable state carries
                // coloringError (or the superseded RUNNING marker heals via
                // the stranded-page sweep). Keep coloring the rest.
                failedCount++
                telemetry.pageFailed()
                publish(Progress(chapterKey, completedCount, totalCount, "COLORING"), onProgress)
                continue
            }
            completedCount++
            telemetry.pageDone()
            publish(
                Progress(chapterKey, completedCount, totalCount, "COLORING"),
                onProgress,
            )
        }

        // The terminal chapter status (TRANSLATED vs READY_WITH_WARNINGS vs
        // retryable ERROR) is derived from the store by the coordinator's
        // reconciler; a warning-free run and a run with isolated failures both
        // finish here instead of aborting the queue entry.
        val finalProgress = Progress(chapterKey, completedCount + failedCount, totalCount, "COMPLETED", isFinished = true)
        publish(finalProgress, onProgress)
        return finalProgress
    }

    private suspend fun executeBatch(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        inpaintingEnabled: Boolean,
        chunkSize: Int,
        onProgress: ((Progress) -> Unit)?,
    ): Progress {
        val totalCount = orderedPageKeys.size
        val currentState = store.state.value

        // 1. Resume scan: identify already-completed pages from durable store
        val completedKeys = orderedPageKeys.filter { key ->
            val page = currentState[key]
            page?.translationStatus == StageStatus.READY || page?.isTextlessTerminal == true
        }.toSet()

        var completedCount = completedKeys.size
        publish(Progress(chapterKey, completedCount, totalCount, "RESUME_CHECK"), onProgress)
        logcat(LogPriority.INFO) {
            "BatchTranslationCoordinator: chapter $chapterKey has $completedCount/$totalCount pages already completed"
        }

        val remainingKeys = orderedPageKeys.filter { it !in completedKeys }
        if (remainingKeys.isEmpty()) {
            val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(finalProgress, onProgress)
            return finalProgress
        }

        prepareEngines()
        val recognitionEngine = recognitionEngineProvider()
        val translator = translatorProvider()

        // 2. Full-Chapter OCR Barrier
        publish(Progress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
        val analyzedPages = LinkedHashMap<String, PageTranslation>()

        for (pageKey in remainingKeys) {
            val existing = store.state.value[pageKey]
            if (existing != null && existing.ocrStatus == StageStatus.READY && existing.blocks.isNotEmpty()) {
                // Reuse existing OCR blocks from store
                analyzedPages[pageKey] = existing.toDraft()
            } else {
                val decoded = processor.decodeImage(pageKey, { streamProvider(pageKey) }) { recognitionEngine }
                if (decoded == null) {
                    val err = "Failed to decode page image for $pageKey during OCR pass"
                    logcat(LogPriority.ERROR) { err }
                    store.updatePageFromCurrentSnapshot(pageKey, "batch:decode-failed") { current ->
                        (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                            ocrStatus = StageStatus.FAILED
                            translationStatus = StageStatus.FAILED
                            inpaintStatus = StageStatus.FAILED
                            errorMessage = err
                        }
                    }
                    val failProgress = Progress(chapterKey, completedCount, totalCount, "FAILED", isFinished = true, error = err)
                    publish(failProgress, onProgress)
                    return failProgress
                }

                val page = try {
                    processor.analyze(pageKey, decoded.bitmap, recognitionEngine).apply {
                        sourceFingerprint = decoded.sourceFingerprint
                    }
                } finally {
                    processor.releaseDecoded(decoded)
                }
                analyzedPages[pageKey] = page
                // Judged BEFORE the OCR commit replaces the store entry: a
                // prior colorize-only result is valid only with matching
                // source identity.
                val priorColoredFingerprint = store.state.value[pageKey]
                    ?.takeIf { it.isColorizeOnlyComplete && it.sourceFingerprint != null }
                    ?.sourceFingerprint

                // Commit OCR state
                store.updatePageFromCurrentSnapshot(pageKey, "batch:ocr") { page }

                // Textless pages are complete immediately!
                if (page.blocks.isEmpty() || page.isTextlessTerminal) {
                    // A no-text page still colorizes its original when
                    // colorization is on and no identity-verified colored
                    // result exists — no detector is forced for it.
                    if (processor.activeColorizationEngine() != null &&
                        priorColoredFingerprint != page.sourceFingerprint
                    ) {
                        processor.colorizeOriginalPage(
                            pageKey = pageKey,
                            streamFn = { streamProvider(pageKey) },
                            store = store,
                            companionDir = companionDir,
                            origin = PageWriteOrigin.BATCH,
                        )
                    }
                    completedCount++
                    publish(Progress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
                }
            }
        }

        // Filter pages needing translation and inpaint
        val pagesNeedingWork = analyzedPages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }
        if (pagesNeedingWork.isEmpty()) {
            val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(finalProgress, onProgress)
            return finalProgress
        }

        // 3. Bounded 2-Task Concurrency: Translation loop + Sequential Inpainting loop
        publish(Progress(chapterKey, completedCount, totalCount, "TRANSLATING_AND_INPAINTING"), onProgress)
        val inpaintChannel = Channel<PageTranslation>(capacity = 2)

        coroutineScope {
            // Task 1: Translation loop (chunks of up to chunkSize)
            val translationTask = launch(Dispatchers.Default) {
                val entries = pagesNeedingWork.entries.toList()
                for (chunk in entries.chunked(chunkSize)) {
                    val chunkMap = chunk.associate { it.key to it.value.detachedCopy() }.toMutableMap()
                    processor.translateBatch(chunkMap, translator)

                    for ((pageKey, translatedPage) in chunkMap) {
                        store.updatePageFromCurrentSnapshot(pageKey, "batch:translate") { translatedPage }
                        inpaintChannel.send(translatedPage)
                    }
                }
                inpaintChannel.close()
            }

            // Task 2: Inpainting loop (bounded sequential processing)
            val inpaintingTask = launch(Dispatchers.Default) {
                for (translatedPage in inpaintChannel) {
                    val pageKey = checkNotNull(translatedPage.sourceFileName)

                    if (inpaintingEnabled) {
                        val decoded = processor.decodeImage(pageKey, { streamProvider(pageKey) }) { recognitionEngine }
                        if (decoded != null) {
                            try {
                                processor.inpaint(pageKey, decoded.bitmap, translatedPage, recognitionEngine, companionDir)
                            } finally {
                                processor.releaseDecoded(decoded)
                            }
                        } else {
                            translatedPage.inpaintStatus = StageStatus.READY
                            translatedPage.originalImageFallback = true
                        }
                    } else {
                        translatedPage.inpaintStatus = StageStatus.READY
                        translatedPage.originalImageFallback = true
                    }

                    // Colorization is a derived post-inpaint stage that never
                    // re-fails the page (colorize owns that contract).
                    val coloringEngine = processor.activeColorizationEngine()
                    val upscaler = processor.activeUpscalingEngine()
                    if (coloringEngine != null && translatedPage.cleanedBitmap != null) {
                        processor.colorize(
                            pageKey = pageKey,
                            page = translatedPage,
                            coloringEngine = coloringEngine,
                            companionDir = if (upscaler != null) null else companionDir,
                        )
                    } else if (coloringEngine != null) {
                        translatedPage.coloringStatus = StageStatus.SKIPPED
                    }

                    if (upscaler != null) {
                        try {
                            processor.upscale(pageKey, translatedPage, upscaler, companionDir)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            if (translatedPage.coloredBitmap != null) {
                                translatedPage.coloringStatus = StageStatus.FAILED
                                translatedPage.coloringError = t.message ?: "Upscaling failed"
                            } else {
                                translatedPage.translationStatus = StageStatus.FAILED
                                translatedPage.translationError = t.message ?: "Upscaling failed"
                            }
                        }
                    }

                    // Commit final bundle
                    try {
                        processor.handOffColoredBitmapForDisplay(translatedPage)
                        store.updatePageFromCurrentSnapshot(pageKey, "batch:commit") { translatedPage }
                    } finally {
                        processor.releasePageBitmaps(translatedPage)
                    }

                    completedCount++
                    publish(
                        Progress(
                            chapterKey = chapterKey,
                            completedPages = completedCount,
                            totalPages = totalCount,
                            currentStage = "INPAINTING",
                        ),
                        onProgress,
                    )
                }
            }

            translationTask.join()
            inpaintingTask.join()
        }

        val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
        publish(finalProgress, onProgress)
        return finalProgress
    }

    private fun publish(
        value: Progress,
        onProgress: ((Progress) -> Unit)?,
    ) {
        onProgress?.invoke(value)
    }
}

/**
 * Thread-safe progress snapshot for the colorize-only run heartbeat: the page
 * loop writes it, a 30s ticker reads it, so a stopped run (hung fetch, hung
 * model call, killed process) shows up as heartbeats frozen on one page key
 * and stage instead of a silent ten-minute log gap.
 */
private class ColorizeRunTelemetry(
    private val chapterKey: String,
    private val totalCount: Int,
) {
    private val startMillis = System.currentTimeMillis()

    @Volatile private var pageIndex = 0

    @Volatile private var pageKey: String? = null

    @Volatile private var stage = "RESUME_CHECK"

    @Volatile private var completed = 0

    @Volatile private var failed = 0

    @Volatile private var reused = 0

    @Volatile private var lastPageMillis = 0L

    @Volatile private var pageStartMillis = 0L

    @Volatile private var lastAdvanceMillis = System.currentTimeMillis()

    fun pageStart(index: Int, key: String) {
        pageIndex = index
        pageKey = key
        stage = "FETCH"
        pageStartMillis = System.currentTimeMillis()
    }

    fun stageDecode() {
        stage = "DECODE"
    }

    fun stageColor() {
        stage = "COLOR"
    }

    fun pageDone() {
        completed++
        pageEnded()
    }

    fun pageReused() {
        reused++
        pageEnded()
    }

    fun pageFailed() {
        failed++
        pageEnded()
    }

    private fun pageEnded() {
        val now = System.currentTimeMillis()
        lastPageMillis = if (pageStartMillis > 0) now - pageStartMillis else 0
        stage = "COLORING"
        lastAdvanceMillis = now
    }

    fun logHeartbeat() {
        val now = System.currentTimeMillis()
        val elapsedS = (now - startMillis) / 1000
        val stalledS = (now - lastAdvanceMillis) / 1000
        logcat(LogPriority.INFO) {
            "[ColorizeHeartbeat] chapter=$chapterKey t=${elapsedS}s " +
                "page=${pageIndex + 1}/$totalCount key=$pageKey stage=$stage " +
                "done=$completed failed=$failed reused=$reused " +
                (if (lastPageMillis > 0) "lastPageMs=$lastPageMillis " else "") +
                "noAdvanceFor=${stalledS}s"
        }
        if (stalledS >= STALL_WARN_SECONDS) {
            logcat(LogPriority.WARN) {
                "[ColorizeHeartbeat] chapter=$chapterKey no page advance for ${stalledS}s " +
                    "(page=${pageIndex + 1} key=$pageKey stage=$stage) — fetch or model run may be stalled"
            }
        }
    }

    fun logRunEnd() {
        val elapsedS = (System.currentTimeMillis() - startMillis) / 1000
        logcat(LogPriority.INFO) {
            "[ColorizeHeartbeat] run ended chapter=$chapterKey t=${elapsedS}s " +
                "done=$completed failed=$failed reused=$reused total=$totalCount " +
                "stage=$stage lastKey=$pageKey"
        }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val STALL_WARN_SECONDS = 90L
    }
}
