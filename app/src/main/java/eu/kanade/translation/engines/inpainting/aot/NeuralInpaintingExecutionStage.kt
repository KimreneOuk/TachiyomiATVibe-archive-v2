package eu.kanade.translation.engines.inpainting.aot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.engines.runtime.onnx.DeviceCapability
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import eu.kanade.translation.processing.memory.BitmapPool
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.PreparedFixedInput
import eu.kanade.translation.engines.inpainting.InpaintPadOps


/**
 * Fixed-512 neural execution stage: memory gating, the accelerator ladder
 * (QNN / NNAPI / XNNPACK / CPU / dynamic), and detach-on-runtime-failure.
 */

    /**
     * Runs the fixed-512 AOT path on an arbitrary local mask. Returns the
     * decoded candidate for the square crop, or null when memory/backend gates
     * reject neural execution. Callers own their classical fallback.
     */
internal fun NeuralInpaintingCoordinator.neuralFillSquare(
        image: Bitmap,
        localMaskBytes: ByteArray,
        crop: IntArray,
        side: Int,
        logTag: String,
        logExtra: String = "",
        onMemoryRejected: (String) -> Unit = {},
    ): IntArray? {
        require(side in 1..InpaintPadOps.SIZE)
        require(crop.size >= 4 && crop[2] - crop[0] == side && crop[3] - crop[1] == side)
        require(localMaskBytes.size.toLong() == side.toLong() * side)

        val modelTag = when (neuralModel) {
            NeuralInpaintModel.LAMA_LITERT_GPU -> "lama_litert_gpu"
            NeuralInpaintModel.AOT_GAN -> "aot"
        }
        val memoryDecision = EngineMemoryBudget.neuralInpaintDecision(
            pageWidth = image.width,
            pageHeight = image.height,
            cropWidth = side,
            cropHeight = side,
            sessionCount = neuralSessionCount(),
        )
        if (!memoryDecision.canRun) {
            EngineMemoryBudget.logSnapshot(
                tag = "skip_${logTag}_$modelTag",
                width = image.width,
                height = image.height,
                extra = "neuralMode=${memoryDecision.mode} model=$neuralModel " +
                    "sessions=${memoryDecision.sessionCount} " +
                    "nativeSystemReserve=${memoryDecision.nativeSystemReserveBytes / (1024L * 1024L)}MiB " +
                    "sysHeadroom=${memoryDecision.systemHeadroomBytes?.div(1024L * 1024L)}MiB " +
                    "reason=${memoryDecision.reason} crop=${side}x$side $logExtra",
            )
            onMemoryRejected("memory_${memoryDecision.reason}")
            return null
        }
        EngineMemoryBudget.logSnapshot(
            tag = "run_${logTag}_$modelTag",
            width = image.width,
            height = image.height,
            extra = "neuralMode=${memoryDecision.mode} sessions=${memoryDecision.sessionCount} " +
                "model=$neuralModel nativeSystemReserve=${memoryDecision.nativeSystemReserveBytes / (1024L * 1024L)}MiB " +
                "sysHeadroom=${memoryDecision.systemHeadroomBytes?.div(1024L * 1024L)}MiB crop=${side}x$side $logExtra",
        )

        val maskBitmap = BitmapPool.getALPHA8(side, side)
        var prepared: PreparedFixedInput? = null
        var preparationError: Throwable? = null
        var neuralOom = false
        val fixedSess = fixedSession
        val dynamicSess = dynamicSession
        return try {
            setAlphaMaskPixels(maskBitmap, localMaskBytes, side, side)
            val nnapiSession = fixedNnapiSession
            val memory = EngineMemoryBudget.nnapiMemorySnapshot()
            val useNnapi = nnapiSession != null &&
                NnapiCapabilityGate.decide(
                    NnapiCapabilityGate.Snapshot(
                        sdk = Build.VERSION.SDK_INT,
                        supportedAbis = Build.SUPPORTED_ABIS.toList(),
                        emulator = DeviceCapability.isProbablyEmulator,
                        nnapiProviderCompiled = true,
                        availableHeapBytes = memory.availableHeapBytes,
                        systemHeadroomBytes = memory.systemHeadroomBytes,
                        lowMemory = memory.lowMemory,
                        healthy = nnapiHealth.isHealthy(),
                    ),
                ).eligible
            if (fixedSess != null || fixedQnnSession != null || useNnapi) {
                try {
                    prepared = prepareFixedInput(image, maskBitmap, crop, side)
                } catch (error: Throwable) {
                    preparationError = error
                    loggedFailure("fixed_prepare", "exception", crop, side, error)
                }
            }
            fun closePreparedInput() {
                val current = prepared ?: return
                prepared = null
                try {
                    current.close()
                } catch (error: Throwable) {
                    logcat(LogPriority.WARN, error) { "[inpaint] fixed_input close=failed before neural fallback" }
                }
            }

            val selectedAccelerator = when {
                fixedQnnSession != null -> fixedQnnBackend
                useNnapi -> AotExecutionCoordinator.Backend.NNAPI
                else -> null
            }
            val preferredAccelerator = selectedAccelerator.takeIf { prepared != null }
            val cpuBackend = if (fixedSessionRoute.endsWith("_xnnpack")) {
                AotExecutionCoordinator.Backend.XNNPACK
            } else {
                AotExecutionCoordinator.Backend.CPU
            }
            val result = AotExecutionCoordinator.run(
                request = prepared,
                preferredAccelerator = preferredAccelerator,
                cpuBackend = cpuBackend,
                dynamicBackend = AotExecutionCoordinator.Backend.DYNAMIC.takeIf { dynamicSess != null },
                attempt = { request, backend ->
                    when (backend) {
                        AotExecutionCoordinator.Backend.QNN_HTP,
                        AotExecutionCoordinator.Backend.QNN_GPU,
                        -> fixedBackendAttempt(
                            backend = backend,
                            session = fixedQnnSession.takeIf { fixedQnnBackend == backend },
                            prepared = request,
                            preparationError = preparationError,
                            crop = crop,
                            side = side,
                        ).also { if (it is AotExecutionCoordinator.Attempt.Failed && it.error is OutOfMemoryError) neuralOom = true }

                        AotExecutionCoordinator.Backend.NNAPI -> fixedBackendAttempt(
                            backend = backend,
                            session = nnapiSession,
                            prepared = request,
                            preparationError = preparationError,
                            crop = crop,
                            side = side,
                        ).also { if (it is AotExecutionCoordinator.Attempt.Failed && it.error is OutOfMemoryError) neuralOom = true }

                        AotExecutionCoordinator.Backend.XNNPACK,
                        AotExecutionCoordinator.Backend.CPU,
                        -> fixedBackendAttempt(
                            backend = backend,
                            session = fixedSess,
                            prepared = request,
                            preparationError = preparationError,
                            crop = crop,
                            side = side,
                        ).also { if (it is AotExecutionCoordinator.Attempt.Failed && it.error is OutOfMemoryError) neuralOom = true }

                        AotExecutionCoordinator.Backend.DYNAMIC -> {
                            if (dynamicSess == null) {
                                AotExecutionCoordinator.Attempt.Unavailable("dynamic_session_unavailable")
                            } else {
                                tryNeuralCandidate(
                                    route = dynamicSessionRoute,
                                    session = dynamicSess,
                                    page = image,
                                    mask = maskBitmap,
                                    crop = crop,
                                    side = side,
                                    fixedShape = false,
                                ).also { if (it is AotExecutionCoordinator.Attempt.Failed && it.error is OutOfMemoryError) neuralOom = true }
                            }
                        }
                    }
                },
                telea = {
                    closePreparedInput()
                    if (neuralOom) {
                        BitmapPool.releaseAll()
                        System.gc()
                    }
                    null
                },
                onAcceleratorRuntimeFailure = { backend, error ->
                    when (backend) {
                        AotExecutionCoordinator.Backend.QNN_HTP,
                        AotExecutionCoordinator.Backend.QNN_GPU,
                        -> {
                            val route = when (backend) {
                                AotExecutionCoordinator.Backend.QNN_HTP -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
                                AotExecutionCoordinator.Backend.QNN_GPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
                                else -> throw IllegalStateException("Not a QNN backend: $backend")
                            }
                            fixedQnnModelId?.let { modelId -> ModelRoutingEngine.recordFailure(modelId, route, error) }
                            closeAndDetachQnn("execution_failed", error)
                        }

                        AotExecutionCoordinator.Backend.NNAPI -> {
                            if (error !is OutOfMemoryError) {
                                nnapiHealth.disableForNativeException()
                                closeAndDetachNnapi("runtime_exception", error)
                            }
                        }

                        else -> Unit
                    }
                },
            )
            when (result) {
                is AotExecutionCoordinator.Result.Neural -> result.value
                is AotExecutionCoordinator.Result.Telea -> result.value
            }
        } finally {
            val current = prepared
            prepared = null
            try {
                current?.close()
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "[inpaint] fixed_input close=failed" }
            } finally {
                BitmapPool.putALPHA8(maskBitmap)
            }
        }
    }

internal fun NeuralInpaintingCoordinator.neuralSessionCount(): Int = when (neuralModel) {
        NeuralInpaintModel.AOT_GAN ->
            (if (fixedSession != null) 1 else 0) +
                (if (fixedQnnSession != null) 1 else 0) +
                (if (fixedNnapiSession != null && nnapiHealth.isHealthy()) 1 else 0) +
                (if (dynamicSession != null) 1 else 0)
        NeuralInpaintModel.LAMA_LITERT_GPU -> 0
    }


internal fun NeuralInpaintingCoordinator.fixedBackendAttempt(
        backend: AotExecutionCoordinator.Backend,
        session: OrtSession?,
        prepared: PreparedFixedInput?,
        preparationError: Throwable?,
        crop: IntArray,
        side: Int,
    ): AotExecutionCoordinator.Attempt<IntArray> {
        if (session == null) {
            return preparationError?.let { AotExecutionCoordinator.Attempt.Failed(it, runtimeFailure = false) }
                ?: AotExecutionCoordinator.Attempt.Unavailable("${backend.name.lowercase()}_session_unavailable")
        }
        if (prepared == null) {
            return preparationError?.let { AotExecutionCoordinator.Attempt.Failed(it, runtimeFailure = false) }
                ?: AotExecutionCoordinator.Attempt.Unavailable("fixed_input_unavailable")
        }
        val route = when (backend) {
            AotExecutionCoordinator.Backend.QNN_HTP -> "fixed_qnn_htp"
            AotExecutionCoordinator.Backend.QNN_GPU -> "fixed_qnn_gpu"
            AotExecutionCoordinator.Backend.NNAPI -> "fixed_nnapi"
            AotExecutionCoordinator.Backend.XNNPACK -> "fixed_xnnpack"
            AotExecutionCoordinator.Backend.CPU -> "fixed_cpu"
            AotExecutionCoordinator.Backend.DYNAMIC -> error("Dynamic backend must use the dynamic session path")
        }
        return runPreparedFixedCandidate(route, backend, session, prepared, crop, side)
    }

internal fun NeuralInpaintingCoordinator.runPreparedFixedCandidate(
        route: String,
        backend: AotExecutionCoordinator.Backend,
        session: OrtSession,
        prepared: PreparedFixedInput,
        crop: IntArray,
        side: Int,
    ): AotExecutionCoordinator.Attempt<IntArray> {
        val started = System.nanoTime()
        val inference = try {
            session.run(mapOf("image" to prepared.imageTensor, "mask" to prepared.maskTensor))
        } catch (oom: OutOfMemoryError) {
            loggedFailure(route, "oom", crop, side, oom, started)
            return AotExecutionCoordinator.Attempt.Failed(oom, runtimeFailure = false)
        } catch (error: Throwable) {
            loggedFailure(route, "runtime_exception", crop, side, error, started)
            return AotExecutionCoordinator.Attempt.Failed(error, runtimeFailure = true)
        }
        return try {
            inference.use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                val candidate = AotFixedTensorContract.decodeOutput(
                    output = output.floatBuffer,
                    outputShape = shape,
                    sourceSize = side,
                    offset = prepared.offset,
                    grayscale = prepared.grayscale,
                )
                val stats = AotOutputGuard.inspect(candidate, prepared.croppedMaskPixels, side, side)
                if (AotOutputGuard.classify(stats)) {
                    logcat(LogPriority.WARN) {
                        "[inpaint] route=$route output_diagnostic=suspicious_uniform " +
                            "mean=${stats.mean} variance=${stats.variance} channelDelta=${stats.channelDelta} " +
                            "masked=${stats.maskedCount} decision=none"
                    }
                }
                lastAcceptedRoute = route
                logcat(LogPriority.INFO) {
                    "[inpaint] route=$route accepted totalMs=${elapsedMs(started)} crop=${side}x$side " +
                        "tensor=512x512 offset=${prepared.offset},${prepared.offset} outputDiagnostic=only"
                }
                AotExecutionCoordinator.Attempt.Success(backend, candidate)
            }
        } catch (oom: OutOfMemoryError) {
            loggedFailure(route, "oom", crop, side, oom, started)
            AotExecutionCoordinator.Attempt.Failed(oom, runtimeFailure = false)
        } catch (error: Throwable) {
            loggedFailure(route, "output_contract_exception", crop, side, error, started)
            AotExecutionCoordinator.Attempt.Failed(error, runtimeFailure = false)
        }
    }

internal fun NeuralInpaintingCoordinator.closeAndDetachNnapi(reason: String, error: Throwable? = null) {
        val session = fixedNnapiSession ?: return
        fixedNnapiSession = null
        try {
            session.close()
        } catch (closeError: Throwable) {
            error?.addSuppressed(closeError)
            logcat(LogPriority.ERROR, closeError) { "[inpaint] route=nnapi close=failed reason=$reason" }
        }
        logcat(LogPriority.WARN, error) { "[inpaint] route=fixed_nnapi disabled reason=$reason" }
    }

internal fun NeuralInpaintingCoordinator.closeAndDetachQnn(reason: String, error: Throwable? = null) {
        val session = fixedQnnSession ?: return
        fixedQnnSession = null
        val backend = fixedQnnBackend
        fixedQnnBackend = null
        val route = when (backend) {
            AotExecutionCoordinator.Backend.QNN_GPU -> "fixed_qnn_gpu"
            else -> "fixed_qnn_htp"
        }
        try {
            session.close()
        } catch (closeError: Throwable) {
            error?.addSuppressed(closeError)
            logcat(LogPriority.ERROR, closeError) { "[inpaint] route=$route close=failed reason=$reason" }
        }
        logcat(LogPriority.WARN, error) { "[inpaint] route=$route disabled reason=$reason" }
    }

internal fun NeuralInpaintingCoordinator.tryNeuralCandidate(
        route: String,
        session: OrtSession,
        page: Bitmap,
        mask: Bitmap,
        crop: IntArray,
        side: Int,
        fixedShape: Boolean,
    ): AotExecutionCoordinator.Attempt<IntArray> {
        var working: Bitmap? = null
        val started = System.nanoTime()
        return try {
            val candidate = BitmapPool.getARGB8888(side, side)
            working = candidate
            Canvas(candidate).drawBitmap(
                page,
                android.graphics.Rect(crop[0], crop[1], crop[2], crop[3]),
                android.graphics.Rect(0, 0, side, side),
                null,
            )
            inpaint(
                sess = session,
                image = candidate,
                maskBitmap = mask,
                cropBounds = intArrayOf(0, 0, side, side),
                maskAlreadyCropped = true,
                featherRampPx = 0,
                fixedShape = fixedShape,
                route = route,
            )
            val pixels = IntArray(side * side)
            candidate.getPixels(pixels, 0, side, 0, 0, side, side)
            lastAcceptedRoute = route
            logcat(LogPriority.INFO) {
                "[inpaint] route=$route accepted totalMs=${elapsedMs(started)} crop=${side}x$side " +
                    "tensor=${if (fixedShape) "512x512" else "${side}x$side"} offset=${crop[0]},${crop[1]} outputDiagnostic=only"
            }
            AotExecutionCoordinator.Attempt.Success(AotExecutionCoordinator.Backend.DYNAMIC, pixels)
        } catch (oom: OutOfMemoryError) {
            loggedFailure(route, "oom", crop, side, oom, started)
            AotExecutionCoordinator.Attempt.Failed(oom, runtimeFailure = false)
        } catch (error: Throwable) {
            loggedFailure(route, "exception", crop, side, error, started)
            AotExecutionCoordinator.Attempt.Failed(error, runtimeFailure = true)
        } finally {
            working?.let { BitmapPool.putARGB8888(it) }
        }
    }

internal fun NeuralInpaintingCoordinator.elapsedMs(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

internal fun NeuralInpaintingCoordinator.loggedFailure(
        route: String,
        reason: String,
        crop: IntArray,
        side: Int,
        error: Throwable?,
        started: Long = System.nanoTime(),
    ) {
        logcat(LogPriority.WARN, error) {
            "[inpaint] route=$route result=fallback reason=$reason totalMs=${elapsedMs(started)} crop=${side}x$side " +
                "tensor=${if (route.startsWith("fixed")) "512x512" else "${side}x$side"} offset=${crop[0]},${crop[1]} " +
                "outputDiagnostic=only"
        }
    }

internal fun NeuralInpaintingCoordinator.setAlphaMaskPixels(maskBitmap: Bitmap, maskBytes: ByteArray, width: Int, height: Int) {
        val maskPixels = IntArray(width * height)
        for (i in maskBytes.indices) {
            if (maskBytes[i] != 0.toByte()) maskPixels[i] = 0xFFFFFFFF.toInt()
        }
        maskBitmap.setPixels(maskPixels, 0, width, 0, 0, width, height)
    }
