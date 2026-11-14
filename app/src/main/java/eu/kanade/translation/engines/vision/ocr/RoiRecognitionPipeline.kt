package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import eu.kanade.translation.diagnostics.DeviceStageNormalization
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceModel
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.inpainting.PageInpaintingEngine
import eu.kanade.translation.engines.inpainting.PageInpaintingPlanner
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.engines.vision.webtoon.WebtoonSlidingDetector
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchLatencyNormalizer
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrP95Action
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Pipeline stage: the timed recognition flow (detect -> segment -> OCR ->
 * dedupe -> panel assign) and the inpaint flow, over the engine's staged
 * sub-engines. Operates on the engine's state through the receiver.
 */
internal fun RoiPageRecognitionEngine.recordPaddleBatchLatencies(
        engine: PaddleOcrV6SmallEngine,
        completedCoordinator: PaddlePageOcrCoordinator,
    ) {
        val governor = paddleBatchGovernor ?: return
        completedCoordinator.lastBatchTrace.forEach { trace ->
            val previous = governor.activeBatchSize
            val sampleMs = PaddleOcrBatchLatencyNormalizer.normalize(
                batchLatencyMs = trace.batchLatencyMs,
                leafCount = trace.leafIdentities.size,
                targetBatchSize = governor.maximumBatchSize,
            )
            val decision = governor.record(sampleMs)
            if (decision.action != PaddleOcrP95Action.HOLD) {
                paddleBatchGovernorReason = decision.reason
                logcat(LogPriority.INFO) {
                    "[paddle_batch_governor] action=${decision.action.name} " +
                        "from=${previous.value} to=${decision.activeBatchSize.value} " +
                        "p95Ms=${decision.rollingP95Ms ?: "n/a"} cap=${governor.maximumBatchSize.value} " +
                        "reason=${decision.reason}"
                }
            }
        }
        if (governor.activeBatchSize != completedCoordinator.validatedBatchSize) {
            paddlePageOcrCoordinator = PaddlePageOcrCoordinator(
                engine = engine,
                validatedBatchSize = governor.activeBatchSize,
                dynamicCeiling = this::paddleDynamicCeiling,
            )
        }
    }

internal suspend fun RoiPageRecognitionEngine.runAnalyzeFlow(bitmap: Bitmap): PageTranslation {
        if (!initialized) initialize()
        // bail before any ONNX call if the engine was closed
        // cooperatively (stop()/language-change raced this call). See [closed].
        if (closed) throw IllegalStateException("ONNX recognition engine closed before analyze")
        // Capture engine refs into locals: close() can run concurrently (it does
        // NOT hold translatorPermit) and nulls these fields mid-analyze. Throwing
        // a catchable IllegalStateException surfaces a retryable failure instead
        // of an NPE crash.
        val localDetector = detector
            ?: throw IllegalStateException("ONNX detector closed mid-analyze")
        val localOcrEngine = roiOcrEngine
            ?: throw IllegalStateException("ONNX OCR engine closed mid-analyze")
        var localPaddlePageCoordinator = paddlePageOcrCoordinator
        val startTime = System.nanoTime()
        EngineMemoryBudget.logSnapshot("analyze_start", bitmap.width, bitmap.height)
        // hold nativeGuard across detect + the per-ROI OCR loop so
        // close() cannot free a native session out from under an in-flight
        // OrtSession.run(). Each native pass (detect + every recognize()) must
        // be inside this critical section.
        var detectMs = 0.0
        var segmentMs = 0.0
        var ocrMs = 0.0
        // Correlated engine stages. The run arrives through the
        // installed TranslationTrace element; outside a traced coroutine every
        // span is a fail-open NO_OP. openRecognitionSpan tracks whichever
        // engine stage is currently open so the outer catch below can settle
        // it with a typed failure (never leaks an unclosed stage_start).
        var openRecognitionSpan: TranslationStageSpan? = null
        val analyzed = try {
            nativeGuard.withLock {
                localPaddlePageCoordinator = paddlePageOcrCoordinator
                val pagePaddlePageCoordinator = localPaddlePageCoordinator
                if (closed) throw IllegalStateException("ONNX recognition engine closed before detect")
                val sourcePixels = TranslationTrace.currentRun()?.sourcePixels
                    ?.takeIf { it > 0L }
                    ?: bitmap.width.toLong() * bitmap.height.toLong()
                val detectSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.DETECT,
                    provider = TranslationPipelineDiagnostics.providerFromLabel(localDetector.executionProviderLabel),
                    model = TranslationTraceModel.PAGE_DETECTOR,
                    normalizationUnits = sourcePixels,
                )
                openRecognitionSpan = detectSpan
                val detectStart = System.nanoTime()
                val detections = try {
                    WebtoonSlidingDetector.detectSliding(bitmap) { localDetector.detect(it) }
                } catch (t: Throwable) {
                    detectSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                    openRecognitionSpan = null
                    throw t
                }
                detectSpan.end(
                    TranslationTraceOutcome.SUCCESS,
                    items = detections.size,
                    registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(localDetector.executionProviderLabel),
                )
                openRecognitionSpan = null
                detectMs = (System.nanoTime() - detectStart) / 1_000_000.0

                val segmentStart = System.nanoTime()
                val segmenter = bubbleSegmenter
                val bubbleMasks = if (segmenter != null) {
                    val segmentSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.SEGMENT,
                        provider = TranslationPipelineDiagnostics.providerFromLabel(segmenter.executionProviderLabel),
                        model = TranslationTraceModel.BUBBLE_SEGMENTER,
                        normalizationUnits = sourcePixels,
                    )
                    openRecognitionSpan = segmentSpan
                    try {
                        WebtoonSlidingDetector.segmentSliding(bitmap) { segmenter.segment(it) }
                    } catch (t: Throwable) {
                        segmentSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                        openRecognitionSpan = null
                        throw t
                    }.also { masks ->
                        segmentSpan.end(
                            TranslationTraceOutcome.SUCCESS,
                            items = masks.size,
                            registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(segmenter.executionProviderLabel),
                        )
                        openRecognitionSpan = null
                    }
                } else {
                    emptyList()
                }
                segmentMs = (System.nanoTime() - segmentStart) / 1_000_000.0
                val bubbles = detections.filter { it.label == 0 }
                val textDetections = detections.filter { it.label == 1 || it.label == 2 }
                val lockedPageTranslation = PageTranslation(
                    imgWidth = bitmap.width.toFloat(),
                    imgHeight = bitmap.height.toFloat(),
                    recognitionEngine = "onnx",
                    detectionCount = detections.size,
                    ocrStatus = StageStatus.RUNNING,
                    updatedAt = System.currentTimeMillis(),
                )
                val geometricallyDeduped = dedupeTextDetections(textDetections, bubbles)
                val filteredDetections = suppressCrossLabelDuplicates(geometricallyDeduped, bubbles)
                // stash on the per-page translation so concurrent pages
                // can't overwrite each other's data before inpaint() reads it back.
                lockedPageTranslation.allTextDetections =
                    filteredDetections + (textDetections.filter { it !in filteredDetections && it !in geometricallyDeduped })
                logcat(LogPriority.INFO) {
                    "ONNX recognition detections: bubbles=${bubbles.size} text=${textDetections.size} " +
                        "geometricText=${geometricallyDeduped.size} filteredText=${filteredDetections.size}"
                }
                val lockedRecognizedBlocks = mutableListOf<RecognizedBlock>()
                val engine = localOcrEngine
                val isWebtoonMode = WebtoonSlidingDetector.isTallImage(bitmap.width, bitmap.height) ||
                    language == TextRecognizerLanguage.KOREAN ||
                    !resolveReadingOrderRtl()
                val isVerticalLanguage = (language == TextRecognizerLanguage.JAPANESE) &&
                    !isWebtoonMode

                val ocrStart = System.nanoTime()
                val ocrSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.OCR,
                    provider = TranslationPipelineDiagnostics.providerFromLabel(localOcrEngine.executionProviderLabel),
                    normalizationUnits = filteredDetections.size.toLong(),
                    model = when (localOcrEngine) {
                        is MangaOcrEngine -> TranslationTraceModel.MANGA_OCR
                        is PaddleOcrV6SmallEngine -> TranslationTraceModel.PADDLE_OCR
                        else -> TranslationTraceModel.NONE
                    },
                )
                openRecognitionSpan = ocrSpan
                val paddleRegionResults = if (
                    engine is PaddleOcrV6SmallEngine && pagePaddlePageCoordinator != null
                ) {
                    val traceIdentity = TranslationTrace.currentRun()?.identity
                    val pageId = traceIdentity?.page?.takeUnless { it.isBlank() || it == "none" }
                        ?: "roi-page"
                    val pageGeneration = PaddleOcrPageGeneration(
                        pageId = pageId,
                        generation = paddlePageGenerationCounter.incrementAndGet(),
                    )
                    val mode = when (traceIdentity?.mode) {
                        TranslationTraceMode.AUTO -> PaddlePageOcrMode.AUTO
                        TranslationTraceMode.BATCH -> PaddlePageOcrMode.CHAPTER
                        TranslationTraceMode.MANUAL,
                        null,
                        -> PaddlePageOcrMode.MANUAL
                    }
                    pagePaddlePageCoordinator.recognizePage(
                        pageGeneration = pageGeneration,
                        bitmap = bitmap,
                        detections = filteredDetections,
                        paddleDet = paddleDet,
                        language = language,
                        isVerticalLanguage = isVerticalLanguage,
                        mode = mode,
                        isClosed = { closed },
                    ).also { results ->
                        val waitMs = pagePaddlePageCoordinator.lastBatchTrace.sumOf {
                            it.queueWaitMs + it.admissionWaitMs
                        }
                        logcat(LogPriority.INFO) {
                            "Paddle page generation=${pageGeneration.generation} mode=${mode.name.lowercase()} " +
                                "leaves=${pagePaddlePageCoordinator.lastResolvedLeafCount} " +
                                "batches=${pagePaddlePageCoordinator.lastBatchTrace.size} waitMs=$waitMs"
                        }
                        recordPaddleBatchLatencies(engine, pagePaddlePageCoordinator)
                    }
                } else {
                    null
                }
                if (!engine.prefersHorizontalText) {
                    if (closed) throw IllegalStateException("ONNX recognition engine closed before batch OCR")
                    val crops = filteredDetections.map { detection ->
                        cropBitmap(bitmap, detection.bbox[0], detection.bbox[1], detection.bbox[2], detection.bbox[3])
                    }
                    val batchResults = try {
                        engine.recognizeBatchWithConf(crops)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed batch OCR recognition" }
                        emptyList()
                    } finally {
                        crops.forEach { it.recycle() }
                    }

                    for (i in filteredDetections.indices) {
                        val detection = filteredDetections[i]
                        val bbox = detection.bbox
                        val rawText = batchResults.getOrNull(i)?.first ?: ""
                        val text = if (OcrTextFilter.isUsable(rawText, language)) rawText else ""

                        if (resolveDiagnostics()) {
                            logcat(LogPriority.INFO) {
                                "[ocr_block] box=[${bbox[0].toInt()},${bbox[1].toInt()},${bbox[2].toInt()},${bbox[3].toInt()}] " +
                                    "size=${(bbox[2] - bbox[0]).toInt()}x${(bbox[3] - bbox[1]).toInt()} rotated=no " +
                                    "chars=${text.length}"
                            }
                        }
                        val boxWidth = (bbox[2] - bbox[0]).toFloat()
                        val boxHeight = (bbox[3] - bbox[1]).toFloat()
                        val centerX = (bbox[0] + bbox[2]) / 2.0
                        val centerY = (bbox[1] + bbox[3]) / 2.0
                        val rawParent = selectParentBubble(detection, bbox, bubbles, centerX, centerY)
                        val parentBbox = rawParent?.let { rp ->
                            val siblings = bubbles.filter { it !== rp }.map { it.bbox }
                            trimParentBbox(rp.bbox, bbox, siblings)
                        } ?: rawParent?.bbox
                        val renderColors = RenderColorEstimator.estimate(
                            bitmap,
                            bbox[0],
                            bbox[1],
                            bbox[2],
                            bbox[3],
                            parentBbox,
                        )
                        val direction = if (isVerticalLanguage && boxHeight > boxWidth * 1.2f) "TTB" else "LTR"
                        lockedRecognizedBlocks.add(
                            RecognizedBlock(
                                detection = detection,
                                block = TranslationBlock(
                                    text = text,
                                    width = boxWidth,
                                    height = boxHeight,
                                    x = bbox[0].toFloat(),
                                    y = bbox[1].toFloat(),
                                    symWidth = boxWidth * 0.1f,
                                    symHeight = boxHeight * 0.1f,
                                    angle = 0f,
                                    label = detection.label,
                                    score = detection.score,
                                    parentX = parentBbox?.get(0)?.toFloat() ?: 0f,
                                    parentY = parentBbox?.get(1)?.toFloat() ?: 0f,
                                    parentWidth = parentBbox?.let { (it[2] - it[0]).toFloat() } ?: 0f,
                                    parentHeight = parentBbox?.let { (it[3] - it[1]).toFloat() } ?: 0f,
                                    textColor = renderColors.first,
                                    strokeColor = renderColors.second,
                                    strokeWidth = renderColors.third,
                                    direction = direction,
                                    segmentationMask = bubbleMasks.firstOrNull { rle ->
                                        rle.overlapPixels(centerX.toInt(), centerY.toInt(), centerX.toInt() + 1, centerY.toInt() + 1) > 0
                                    },
                                ),
                            ),
                        )
                    }
                } else {
                    for ((detectionIndex, detection) in filteredDetections.withIndex()) {
                        // The Paddle adapter has already completed every final
                        // line/glyph leaf for this page. Other horizontal engines
                        // stay on the original sequential crop path below.
                        if (paddleRegionResults != null) {
                            if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR mapping")
                            val result = paddleRegionResults.getOrNull(detectionIndex)
                                ?: error("Paddle page result missing for region=$detectionIndex")
                            val text = result.text
                            val rotatedForOcr = result.rotatedForOcr
                            if (resolveDiagnostics()) {
                                logcat(LogPriority.INFO) {
                                    "[ocr_block] box=[${detection.bbox[0].toInt()},${detection.bbox[1].toInt()},${detection.bbox[2].toInt()},${detection.bbox[3].toInt()}] " +
                                        "size=${(detection.bbox[2] - detection.bbox[0]).toInt()}x${(detection.bbox[3] - detection.bbox[1]).toInt()} " +
                                        "rotated=${if (rotatedForOcr) "90ccw" else "no"} chars=${text.length}"
                                }
                            }
                            appendRecognizedBlock(
                                bitmap = bitmap,
                                detection = detection,
                                text = text,
                                isVerticalLanguage = isVerticalLanguage,
                                bubbles = bubbles,
                                bubbleMasks = bubbleMasks,
                                lockedRecognizedBlocks = lockedRecognizedBlocks,
                            )
                            continue
                        }

                        // cooperative close â€” bail out of the per-ROI OCR
                        // loop if close() ran between iterations, before the native call.
                        if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
                        // horizontal-line engines (PaddleOCR) need context
                        // padding to avoid edge-effect failures; native-vertical engines
                        // get tight unbounded crops.
                        val pad = 12
                        val unpaddedCrop = cropBitmap(bitmap, detection.bbox[0], detection.bbox[1], detection.bbox[2], detection.bbox[3])
                        val crop = cropBitmap(bitmap, detection.bbox[0] - pad, detection.bbox[1] - pad, detection.bbox[2] + pad, detection.bbox[3] + pad)
                        val bbox = detection.bbox
                        val boxWidthPre = (bbox[2] - bbox[0]).toFloat()
                        val boxHeightPre = (bbox[3] - bbox[1]).toFloat()
                        val tallVertical = isVerticalLanguage && boxHeightPre > boxWidthPre * 1.5f
                        val paddleMultiLine = paddleDet != null
                        val paddleVerticalHeuristic = isVerticalLanguage &&
                            paddleDet == null &&
                            boxHeightPre > boxWidthPre * 1.5f
                        var rotatedForOcr = false
                        val text = try {
                            when {
                                paddleMultiLine -> {
                                    rotatedForOcr = tallVertical
                                    var rawText = recognizeMultiLine(engine, crop, paddleDet, tallVertical)
                                    if (rawText.isEmpty() && isVerticalLanguage && boxHeightPre > boxWidthPre) {
                                        rotatedForOcr = true
                                        val fallbackText = recognizeMultiLine(engine, unpaddedCrop, null, verticalFallback = true)
                                        if (fallbackText.isNotEmpty()) rawText = fallbackText
                                    }
                                    rawText
                                }
                                paddleVerticalHeuristic -> {
                                    rotatedForOcr = true
                                    recognizeMultiLine(engine, unpaddedCrop, paddleDet, verticalFallback = true)
                                }
                                else -> {
                                    val rawText = engine.recognizeWithConf(crop).first
                                    if (OcrTextFilter.isUsable(rawText, language)) rawText else ""
                                }
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Failed to recognize text in box" }
                            ""
                        } finally {
                            if (crop !== unpaddedCrop) {
                                crop.recycle()
                            }
                            unpaddedCrop.recycle()
                        }
                        // per-block OCR diagnostics, gated by the opt-in
                        // translation_diagnostics pref so recognition quality is inspectable.
                        if (resolveDiagnostics()) {
                            logcat(LogPriority.INFO) {
                                "[ocr_block] box=[${bbox[0].toInt()},${bbox[1].toInt()},${bbox[2].toInt()},${bbox[3].toInt()}] " +
                                    "size=${(bbox[2] - bbox[0]).toInt()}x${(bbox[3] - bbox[1]).toInt()} " +
                                    "rotated=${if (rotatedForOcr) "90ccw" else "no"} chars=${text.length}"
                            }
                        }
                        appendRecognizedBlock(
                            bitmap = bitmap,
                            detection = detection,
                            text = text,
                            isVerticalLanguage = isVerticalLanguage,
                            bubbles = bubbles,
                            bubbleMasks = bubbleMasks,
                            lockedRecognizedBlocks = lockedRecognizedBlocks,
                        )
                    }
                }
                ocrMs = (System.nanoTime() - ocrStart) / 1_000_000.0
                ocrSpan.end(
                    TranslationTraceOutcome.SUCCESS,
                    items = lockedRecognizedBlocks.size,
                    registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(localOcrEngine.executionProviderLabel),
                )
                openRecognitionSpan = null
                RecognizedAnalyzeResult(lockedPageTranslation, lockedRecognizedBlocks)
            }
        } catch (t: Throwable) {
            // Settle whichever engine stage was still open (detect/segment/ocr)
            // so a mid-recognition throw never leaves a stage_start unclosed.
            openRecognitionSpan?.end(TranslationTraceOutcome.FAILURE, error = t)
            openRecognitionSpan = null
            throw t
        } finally {
            if (closed && initialized) {
                if (nativeGuard.tryLock()) {
                    try {
                        freeNativeSessions()
                    } finally {
                        nativeGuard.unlock()
                    }
                }
            }
        }
        val pageTranslation = analyzed.pageTranslation
        val recognizedBlocks = analyzed.recognizedBlocks
        val finalRecognizedBlocks = removePostOcrDuplicateBlocks(recognizedBlocks)
        // second dedupe pass. removePostOcrDuplicateBlocks only
        // collapses identical-text overlaps; this collapses remaining geometric
        // overlaps (cross-label, differing-text) so two overlapping boxes never
        // reach the renderer.
        val dedupedBlocks = OcrBlockDeduplication.dedupeGeometricOverlaps(
            finalRecognizedBlocks.map { it.block },
        )
        val droppedByGeoDedupe = finalRecognizedBlocks.size - dedupedBlocks.size
        if (droppedByGeoDedupe > 0) {
            logcat(LogPriority.INFO) {
                "Geometric block dedupe dropped $droppedByGeoDedupe overlapping block(s) after OCR"
            }
        }
        pageTranslation.blocks.addAll(dedupedBlocks)
        pageTranslation.ocrBlockCount = pageTranslation.blocks.size
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.updatedAt = System.currentTimeMillis()
        // assign OCR blocks to panels now that the block list is
        // frozen. See assignPanels() for the conservative no-fallback policy.
        assignPanels(bitmap, pageTranslation)
        // capture the durable inpaint mask at OCR time, before
        // translation/watermark filtering removes blocks and before the page is
        // persisted (allTextDetections is @Transient). Persisting it means a
        // resumed batch still erases detector-only + watermark regions, not just
        // the surviving OCR blocks. See PageInpaintingPlanner + contract #14.
        pageTranslation.inpaintMaskBoxes = PageInpaintingPlanner.computeMask(pageTranslation)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val paddleBatchSize = localPaddlePageCoordinator?.validatedBatchSize?.value ?: 1
        val paddleBatched = localPaddlePageCoordinator != null && paddleBatchSize > 1
        val paddleDynamic = localPaddlePageCoordinator != null && paddleDynamicBatchPolicy != null
        val paddleGovernorReason = if (localPaddlePageCoordinator != null) {
            paddleBatchGovernorReason
        } else {
            "not_paddle"
        }
        logcat(LogPriority.INFO) {
            // Per-engine provider labels report the providers actually used;
            // a device-wide route can differ from an individual engine's route.
            "[translation_perf] " +
                "providers(detector=${detector?.executionProviderLabel ?: "n/a"}, " +
                "segmenter=${bubbleSegmenter?.executionProviderLabel ?: "n/a"}, " +
                "panel=${panelDetector?.executionProviderLabel ?: "n/a"}, " +
                "ocr=${localOcrEngine.executionProviderLabel}) " +
                "stage=recognition total=${elapsedMs}ms detector=${detectMs}ms segmenter=${segmentMs}ms " +
                "ocr=${ocrMs}ms (blocks=${pageTranslation.blocks.size} batched=$paddleBatched " +
                "batchSize=$paddleBatchSize dynamic=$paddleDynamic governorReason=$paddleGovernorReason)"
        }
        logcat(LogPriority.INFO) {
            "RoiPageRecognitionEngine analyzed ${pageTranslation.blocks.size} blocks " +
                "emptyOcr=${pageTranslation.blocks.count { it.text.isBlank() }} in ${elapsedMs}ms"
        }
        lastDetectMs = detectMs
        lastSegmentMs = segmentMs
        lastOcrMs = ocrMs
        lastLeafCount = localPaddlePageCoordinator?.lastResolvedLeafCount ?: pageTranslation.detectionCount
        lastBoxCount = pageTranslation.detectionCount
        lastProvider = localOcrEngine.executionProviderLabel
        return pageTranslation
    }

internal suspend fun RoiPageRecognitionEngine.runInpaintFlow(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        if (!initialized) initialize()
        if (closed) {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            pageTranslation.errorMessage = "ONNX recognition engine closed before inpaint"
            pageTranslation.updatedAt = System.currentTimeMillis()
            return null
        }
        // Hold nativeGuard across inpaint so close() cannot free the AOT
        // inpainter's native session mid-run. Re-check [closed] inside the
        // lock. PageInpaintingEngine owns box and mask computation.
        // The correlated inpaint stage reports AOT provenance from the
        // inpainter's execution-proven route (lastAcceptedRoute) after
        // inference as `provenProvider`; this adapter has no registration
        // result to report.
        val inpaintSpan = TranslationTrace.beginStage(
            TranslationTraceStage.INPAINT,
            normalizationUnits = DeviceStageNormalization.regionArea(pageTranslation.blocks),
            model = if (inpaintingMode.initializesNeuralSessions && inpainting?.isInitialized() == true) {
                TranslationTraceModel.AOT_GAN
            } else {
                TranslationTraceModel.NONE
            },
        )
        val inpaintStart = System.nanoTime()
        val result = try {
            nativeGuard.withLock {
                if (closed) {
                    pageTranslation.inpaintStatus = StageStatus.FAILED
                    pageTranslation.errorMessage = "ONNX recognition engine closed before inpaint"
                    pageTranslation.updatedAt = System.currentTimeMillis()
                    null
                } else {
                    val activePageInpainter = pageInpainter ?: PageInpaintingEngine(
                        mode = inpaintingMode,
                        inpainter = inpainting ?: NeuralInpaintingCoordinator(
                            neuralModel = neuralInpaintModel,
                            hardwareOverride = inpaintingHardwareOverride,
                        ),
                        litertInpainter = litertInpainting,
                        neuralModel = neuralInpaintModel,
                    )
                    activePageInpainter.inpaint(bitmap, pageTranslation)
                }
            }
        } catch (t: Throwable) {
            inpaintSpan.end(TranslationTraceOutcome.FAILURE, error = t)
            throw t
        } finally {
            if (closed && initialized) {
                if (nativeGuard.tryLock()) {
                    try {
                        freeNativeSessions()
                    } finally {
                        nativeGuard.unlock()
                    }
                }
            }
        }
        val inpaintMs = (System.nanoTime() - inpaintStart) / 1_000_000.0
        lastInpaintMs = inpaintMs
        inpaintSpan.end(
            if (result == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS,
            provenProvider = TranslationPipelineDiagnostics.providerFromLabel(inpainting?.lastAcceptedRoute),
        )
        logcat(LogPriority.INFO) {
            // inpaintRoute reports the provider that actually handled inference.
            "[translation_perf] " +
                "stage=inpainting total=${inpaintMs}ms mode=$inpaintingMode maskBoxes=${pageTranslation.inpaintMaskBoxes.size} " +
                "inpaintRoute=${inpainting?.lastAcceptedRoute ?: "n/a"}"
        }
        return result
    }
