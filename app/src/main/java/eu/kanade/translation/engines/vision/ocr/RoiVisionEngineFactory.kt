package eu.kanade.translation.engines.vision.ocr

import android.content.Context
import eu.kanade.translation.engines.inpainting.PageInpaintingEngine
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import eu.kanade.translation.engines.vision.detection.OnnxPageTextDetector
import eu.kanade.translation.engines.vision.detection.OnnxPanelDetector
import eu.kanade.translation.engines.vision.segmentation.OnnxBubbleSegmenter
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDevicePolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDynamicPageBatchPolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRollingP95HysteresisDowngradePolicy
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

/**
 * VisionEngineFactory stage: builds and wires the ROI engine's sub-engines
 * (text detector, OCR backend, panel detector, bubble segmenter, inpainters).
 * Operates on the engine's state through the receiver; no state is owned here.
 */
internal suspend fun RoiPageRecognitionEngine.initialize() {
        initMutex.withLock {
            if (initialized) return
            initFailed = false
            val startTime = System.nanoTime()
            try {
                val paths = modelStore.ensureModels()
                // Auto-routing: the paddle ONNX fallback requests QNN GPU
                // strictly; the factory probes it end-to-end and falls back to
                // a labelled CPU session (cached for the process lifetime).
                val paddleProvider = if (ocrModel == OcrModel.PADDLEOCR_V6_SMALL) {
                    PaddleOcrSessionFactory.resolve(PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU)
                } else {
                    // The free-text mask-only detector used by non-Paddle OCR
                    // keeps its existing automatic/global route. The selector
                    // is scoped to the v6 recognizer + line detector.
                    null
                }
                val visionRoute = HardwareDiscoveryEngine.resolveVisionGpuRoute()
                // LiteRT fp32 vision flatbuffers are strictly optional: when
                // none are deployed, every vision engine stays on its ONNX path.
                val liteRtVision = if (modelStore.liteRtVisionAssetsAvailable()) {
                    try {
                        modelStore.ensureLiteRtVision()
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: LiteRT vision deployment failed; ONNX remains authoritative"
                        }
                        null
                    }
                } else {
                    null
                }
                logcat(LogPriority.INFO) { "ONNX init: starting detector initialization (visionRoute=$visionRoute)" }
                val pageTextDetector = OnnxPageTextDetector()
                val liteRtPageText = liteRtVision?.pageTextDetector
                if (liteRtPageText != null) {
                    pageTextDetector.initializeLiteRt(liteRtPageText, preferGpu = true) {
                        pageTextDetector.initialize(paths.detectorModel, requestedHardwareRoute = visionRoute)
                    }
                } else {
                    pageTextDetector.initialize(paths.detectorModel, requestedHardwareRoute = visionRoute)
                }
                detector = pageTextDetector
                logcat(LogPriority.INFO) { "ONNX init: detector OK (provider=${pageTextDetector.executionProviderLabel}), starting OCR initialization (language=$language, model=$ocrModel)" }
                // panel detector is optional context; best-effort
                // init mirrors paddleDet (missing asset -> null, never blocks OCR).
                paths.panelDetectorModel?.let { panelModelFile ->
                    try {
                        val panel = OnnxPanelDetector()
                        val liteRtPanel = liteRtVision?.panelDetector
                        if (liteRtPanel != null) {
                            panel.initializeLiteRt(liteRtPanel, preferGpu = true) {
                                panel.initialize(panelModelFile, requestedHardwareRoute = visionRoute)
                            }
                        } else {
                            panel.initialize(panelModelFile, requestedHardwareRoute = visionRoute)
                        }
                        panelDetector = panel
                        logcat(LogPriority.INFO) { "ONNX init: panel detector OK (provider=${panel.executionProviderLabel})" }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: panel detector failed; panel-aware translation context disabled"
                        }
                    }
                }
                paths.bubbleSegmenterModel?.let { bubbleModelFile ->
                    try {
                        val segmenter = OnnxBubbleSegmenter()
                        val liteRtBubble = liteRtVision?.bubbleSegmenter
                        if (liteRtBubble != null) {
                            segmenter.initializeLiteRt(liteRtBubble, preferGpu = true) {
                                segmenter.initialize(bubbleModelFile, requestedHardwareRoute = visionRoute)
                            }
                        } else {
                            segmenter.initialize(bubbleModelFile, requestedHardwareRoute = visionRoute)
                        }
                        bubbleSegmenter = segmenter
                        logcat(LogPriority.INFO) { "ONNX init: bubble segmenter OK (provider=${segmenter.executionProviderLabel})" }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: bubble segmenter failed; falling back to heuristic masking"
                        }
                    }
                }
                roiOcrEngine = when (ocrModel) {
                    OcrModel.MANGAOCR -> MangaOcrEngine().also { engine ->
                        val liteRtEncoder = liteRtVision?.ocrEncoder
                        val liteRtInit = liteRtVision?.ocrDecoderInit
                        val liteRtStep = liteRtVision?.ocrDecoderStep
                        if (liteRtEncoder != null && liteRtInit != null && liteRtStep != null) {
                            engine.initializeLiteRt(liteRtEncoder, liteRtInit, liteRtStep, paths.ocrVocab) {
                                engine.initialize(paths.ocrEncoder, paths.ocrDecoderInit, paths.ocrDecoderStep, paths.ocrVocab)
                            }
                        } else {
                            engine.initialize(paths.ocrEncoder, paths.ocrDecoderInit, paths.ocrDecoderStep, paths.ocrVocab)
                        }
                    }
                    OcrModel.PADDLEOCR_V6_SMALL -> PaddleOcrV6SmallEngine().also { engine ->
                        val paddlePaths = modelStore.ensurePaddleOcrV6Small()
                        val activation = PaddleOcrDevicePolicy.currentPinnedB1(
                            requestedProvider = paddleProvider?.requested,
                        )
                        paddleBatchGovernor = PaddleOcrRollingP95HysteresisDowngradePolicy(
                            initialBatchSize = activation.activeBatchSize,
                            maximumBatchSize = activation.activeBatchSize,
                        )
                        paddleBatchGovernorReason = activation.reason
                        val providerConfiguration = if (activation.forceCpuB1EmergencyFallback) {
                            PaddleOcrProviderOverride.cpuB1EmergencyFallback()
                        } else {
                            null
                        }

                        var liteRtInitialized = false
                        if (modelStore.paddleOcrV6LiteRtAssetsAvailable()) {
                            var liteRtDetector: PaddleOcrV6DetEngine? = null
                            try {
                                val liteRtPaths = modelStore.ensurePaddleOcrV6LiteRt()
                                engine.initializeLiteRt(
                                    modelFilesByWidth = liteRtPaths.recognitionModelsByWidth,
                                    dictionaryFile = liteRtPaths.dictionary,
                                    preferGpu = true,
                                    allowXnnpackFallback = true,
                                    numThreads = 4,
                                    fallbackInitializer = {
                                        engine.initialize(
                                            paddlePaths.recognitionModel,
                                            paddlePaths.dictionary,
                                            providerResolution = paddleProvider,
                                            providerConfiguration = providerConfiguration,
                                        )
                                    },
                                )
                                liteRtDetector = PaddleOcrV6DetEngine().also {
                                    it.initializeLiteRt(
                                        modelFile = liteRtPaths.detectorModel,
                                        preferGpu = true,
                                        allowXnnpackFallback = true,
                                        numThreads = 4,
                                    )
                                }
                                paddleDet = liteRtDetector
                                liteRtInitialized = true
                                logcat(LogPriority.INFO) {
                                    "LiteRT init: PaddleOCR v6 recognizer and detector configured " +
                                        "(device=gpu, CPU threads=4, CPU fallback enabled)"
                                }
                            } catch (e: Exception) {
                                liteRtDetector?.close()
                                paddleDet = null
                                engine.close()
                                logcat(LogPriority.WARN, e) {
                                    "LiteRT PaddleOCR initialization failed; initializing ONNX recognizer/detector"
                                }
                            }
                        }

                        if (!liteRtInitialized) {
                            engine.initialize(
                                paddlePaths.recognitionModel,
                                paddlePaths.dictionary,
                                providerResolution = paddleProvider,
                                providerConfiguration = providerConfiguration,
                            )
                            if (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable()) {
                                try {
                                    val detPaths = modelStore.ensurePaddleOcrV6Det()
                                    paddleDet = PaddleOcrV6DetEngine().also {
                                        it.initialize(
                                            detPaths.detectionModel,
                                            providerResolution = paddleProvider,
                                        )
                                    }
                                    logcat(LogPriority.INFO) { "ONNX init: PaddleOCR det OK (replaces ink-gap heuristic)" }
                                } catch (e: Exception) {
                                    logcat(LogPriority.WARN, e) {
                                        "ONNX init: PaddleOCR det failed; falling back to ink-gap heuristic"
                                    }
                                }
                            } else {
                                logcat(LogPriority.WARN) {
                                    "ONNX init: PaddleOCR det asset missing; using ink-gap heuristic"
                                }
                            }
                        }

                        paddleDynamicBatchPolicy = if (activation.dynamicPageBatch) {
                            val totalRamBytes = try {
                                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.totalMem
                            } catch (_: Throwable) {
                                0L // unknown RAM -> most conservative tier
                            }
                            PaddleOcrDynamicPageBatchPolicy(
                                dictionarySize = engine.dictionarySize,
                                outputBudgetBytes = PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes),
                            )
                        } else {
                            null
                        }

                        paddlePageOcrCoordinator = PaddlePageOcrCoordinator(
                            engine = engine,
                            validatedBatchSize = activation.activeBatchSize,
                            dynamicCeiling = this::paddleDynamicCeiling,
                        )
                        engine
                    }
                    OcrModel.MLKIT -> MlKitRoiOcrEngine(language)
                }
                // load PaddleOCR-v6 det for the INPAINTER (free-text
                // erase mask) whenever the asset is available, independent of OCR
                // model. Previously gated behind a removed experimental flag, so
                // the default MANGAOCR path never got Paddle-driven masking. The
                // PADDLEOCR_V6_SMALL branch above already built it for rec.
                if (ocrModel != OcrModel.PADDLEOCR_V6_SMALL && paddleDet == null) {
                    if (modelStore.paddleOcrV6LiteRtAssetsAvailable()) {
                        try {
                            val liteRtPaths = modelStore.ensurePaddleOcrV6LiteRt()
                            paddleDet = PaddleOcrV6DetEngine().also {
                                it.initializeLiteRt(
                                    modelFile = liteRtPaths.detectorModel,
                                    preferGpu = true,
                                    allowXnnpackFallback = true,
                                    numThreads = 4,
                                )
                            }
                            logcat(LogPriority.INFO) {
                                "LiteRT init: PaddleOCR det OK (free-text erase mask for $ocrModel)"
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) {
                                "LiteRT init: PaddleOCR det failed for inpainter; checking ONNX"
                            }
                        }
                    }
                    if (paddleDet == null && (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable())) {
                        try {
                            val detPaths = modelStore.ensurePaddleOcrV6Det()
                            paddleDet = PaddleOcrV6DetEngine().also {
                                it.initialize(detPaths.detectionModel)
                            }
                            logcat(LogPriority.INFO) {
                                "ONNX init: PaddleOCR det OK (free-text erase mask)"
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) {
                                "ONNX init: PaddleOCR det failed; free-text erase falls back to detector-v4 boxes"
                            }
                        }
                    } else if (paddleDet == null) {
                        logcat(LogPriority.WARN) {
                            "PaddleOCR det asset missing; free-text erase falls back to detector-v4 boxes"
                        }
                    }
                }
                logcat(LogPriority.INFO) {
                    "ONNX init: OCR OK (backend=${roiOcrEngine!!::class.simpleName}), " +
                        "preparing inpainting (mode=$inpaintingMode model=$neuralInpaintModel)"
                }
                val localInpainting = NeuralInpaintingCoordinator(
                    neuralModel = neuralInpaintModel,
                    hardwareOverride = inpaintingHardwareOverride,
                )
                localInpainting.paddleDetector = paddleDet
                var localLiteRT: LiteRTMangaInpaintingEngine? = null
                if (inpaintingMode.initializesNeuralSessions) {
                    // BOTH selectable neural models execute through the shared
                    // LiteRT GPU runtime. AOT_GAN uses the optimized FP16
                    // TFLite (aot-512-regen-gap) via its tensor adapter â€” the
                    // legacy ONNX AOT ladder is no longer instantiated or
                    // routed to for the selected model.
                    logcat(LogPriority.INFO) {
                        "LiteRT init: preparing $neuralInpaintModel via LiteRT GPU-first runtime"
                    }
                    localLiteRT = LiteRTMangaInpaintingEngine(context, neuralModel = neuralInpaintModel)
                } else {
                    logcat(LogPriority.INFO) { "LiteRT init: FAST inpainting mode; skipping neural session initialization" }
                }
                inpainting = localInpainting
                litertInpainting = localLiteRT
                pageInpainter = PageInpaintingEngine(
                    mode = inpaintingMode,
                    inpainter = localInpainting,
                    litertInpainter = localLiteRT,
                    neuralModel = neuralInpaintModel,
                )
                initialized = true
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                logcat(LogPriority.INFO) { "RoiPageRecognitionEngine initialized in ${elapsedMs}ms" }
            } catch (e: Exception) {
                initFailed = true
                android.util.Log.e("PaddleOCR", "RoiPageRecognitionEngine init failed", e)
                logcat(LogPriority.ERROR, e) { "RoiPageRecognitionEngine init failed, marking unavailable" }
                throw e
            }
        }
    }
