package eu.kanade.translation.engines.inpainting.aot

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.app.Application
import android.os.Build
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.engines.runtime.onnx.DeviceCapability
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.QnnContextCacheManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Session initialization stage: CPU/XNNPACK fallback sessions, the QNN
 * warm/cold context-cache protocol, and the strict NNAPI candidate. Extends
 * the coordinator; all session state lives on the receiver.
 */

internal fun NeuralInpaintingCoordinator.initializeQnnSession(modelFile: File, backend: AotExecutionCoordinator.Backend): OrtSession? {
    val route = when (backend) {
        AotExecutionCoordinator.Backend.QNN_HTP -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        AotExecutionCoordinator.Backend.QNN_GPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
        else -> error("Not a QNN backend: $backend")
    }
    val routeLabel = if (backend == AotExecutionCoordinator.Backend.QNN_GPU) "qnn_gpu" else "qnn_htp"
    val modelId = ModelRoutingEngine.resolveModelId(modelFile.absolutePath)
    if (!ModelRoutingEngine.isAcceleratorAttemptAllowed(modelId, route)) {
        logcat(LogPriority.INFO) { "[inpaint] route=$routeLabel model=$modelId is marked UNSUPPORTED; skipping" }
        return null
    }

    val memory = EngineMemoryBudget.nnapiMemorySnapshot()
    val memoryDecision = QnnSessionMemoryPrecheck.decide(
        modelSizeBytes = modelFile.length(),
        availableHeadroomBytes = memory.systemHeadroomBytes,
        lowMemory = memory.lowMemory,
    )
    if (!memoryDecision.allowed) {
        logcat(LogPriority.WARN) {
            "[inpaint] route=$routeLabel init=skipped reason=memory_precheck_${memoryDecision.reason} " +
                "model=$modelId modelBytes=${modelFile.length()} " +
                "systemHeadroom=${memoryDecision.availableHeadroomBytes?.div(1024L * 1024L)}MiB " +
                "requiredHeadroom=${memoryDecision.requiredHeadroomBytes / (1024L * 1024L)}MiB " +
                "lowMemory=${memory.lowMemory}"
        }
        return null
    }

    val app = try {
        Injekt.get<Application>()
    } catch (_: Throwable) {
        null
    }
    val qnnOptions = when (backend) {
        AotExecutionCoordinator.Backend.QNN_HTP -> OnnxRuntimeProvider.buildGenericHtpOptions(performanceMode = performanceMode)
        AotExecutionCoordinator.Backend.QNN_GPU -> mapOf("backend_type" to "gpu")
        else -> error("Not a QNN backend: $backend")
    }
    // 1. Warm load from precompiled context cache if valid (~300ms)
    if (app != null && backend == AotExecutionCoordinator.Backend.QNN_HTP) {
        val validCachedModel = QnnContextCacheManager.getValidCachedModel(app, modelFile, qnnOptions)
        if (validCachedModel != null) {
            var loadOpts: OrtSession.SessionOptions? = null
            try {
                loadOpts = OnnxRuntimeProvider.createQnnHtpSessionOptions(
                    contextCacheFile = null,
                    strictCpuFallbackDisabled = true,
                    qnnOptions = qnnOptions,
                )
                val session = OnnxRuntimeProvider.environment.createSession(validCachedModel.absolutePath, loadOpts)
                AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(session))
                ModelRoutingEngine.markSupported(modelId, route)
                logcat(LogPriority.INFO) {
                    "[inpaint] route=$routeLabel init=ok cached=true model=$modelId ${DeviceCapability.describe()}"
                }
                return session
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) {
                    "[inpaint] route=$routeLabel cached context load failed; invalidating cache and compiling fresh"
                }
                QnnContextCacheManager.invalidate(app, modelFile, qnnOptions)
            } finally {
                try {
                    loadOpts?.close()
                } catch (_: Throwable) {}
            }
        }
    }

    // 2. Cold compilation and atomic staging
    val stagingCtxFile = if (app != null && backend == AotExecutionCoordinator.Backend.QNN_HTP) {
        QnnContextCacheManager.prepareStaging(app, modelFile, qnnOptions)
    } else {
        null
    }

    var opts: OrtSession.SessionOptions? = null
    var created: OrtSession? = null
    return try {
        opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(
            contextCacheFile = stagingCtxFile,
            strictCpuFallbackDisabled = true,
            qnnOptions = qnnOptions,
        )
        created = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(created))
        if (app != null && stagingCtxFile != null) {
            QnnContextCacheManager.commitStaging(app, modelFile, qnnOptions)
        }
        ModelRoutingEngine.markSupported(modelId, route)
        logcat(LogPriority.INFO) {
            "[inpaint] route=$routeLabel init=ok cached=false generated=${stagingCtxFile != null} model=$modelId ${DeviceCapability.describe()}"
        }
        created
    } catch (error: Throwable) {
        try {
            created?.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        val canRetry = ModelRoutingEngine.recordFailure(modelId, route, error)
        logcat(LogPriority.ERROR, error) {
            "[inpaint] route=$routeLabel init=failed canRetry=$canRetry model=$modelId ${DeviceCapability.describe()}"
        }
        null
    } finally {
        try {
            opts?.close()
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "[inpaint] route=$routeLabel optionsClose=failed" }
        }
    }
}

internal fun NeuralInpaintingCoordinator.initializeStrictNnapiSession(modelFile: File?): OrtSession? {
    val memory = EngineMemoryBudget.nnapiMemorySnapshot()
    val providers = try {
        OrtEnvironment.getAvailableProviders()
    } catch (error: Throwable) {
        logcat(LogPriority.WARN, error) { "[inpaint] route=nnapi init=skipped reason=provider_query_failed ${DeviceCapability.describe()}" }
        return null
    }
    val decision = NnapiCapabilityGate.decide(
        NnapiCapabilityGate.Snapshot(
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            emulator = DeviceCapability.isProbablyEmulator,
            nnapiProviderCompiled = OrtProvider.NNAPI in providers,
            availableHeapBytes = memory.availableHeapBytes,
            systemHeadroomBytes = memory.systemHeadroomBytes,
            lowMemory = memory.lowMemory,
            healthy = nnapiHealth.isHealthy(),
        ),
    )
    if (!decision.eligible || modelFile == null || !modelFile.exists()) {
        logcat(LogPriority.INFO) {
            "[inpaint] route=nnapi nnapiCandidate=skipped reason=${if (modelFile?.exists() != true) "model_missing" else decision.reason} " +
                "strictCpuFallbackDisabled=true heapAvail=${memory.availableHeapBytes / (1024L * 1024L)}MiB " +
                "sysHeadroom=${memory.systemHeadroomBytes?.div(1024L * 1024L)}MiB lowMemory=${memory.lowMemory} ${DeviceCapability.describe()}"
        }
        return null
    }

    var opts: OrtSession.SessionOptions? = null
    var created: OrtSession? = null
    var nnapiSessionCreationFailure = false
    return try {
        // No runCatching around strict config, addNnapi, or createSession: a
        // failed strict candidate must be visible and must never become CPU.
        nnapiSessionCreationFailure = true
        opts = OnnxRuntimeProvider.createStrictNnapiSessionOptions()
        created = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        nnapiSessionCreationFailure = false
        AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(created))
        logcat(LogPriority.INFO) {
            "[inpaint] route=nnapi init=ok strictCpuFallbackDisabled=true model=${modelFile.name} ${DeviceCapability.describe()}"
        }
        created
    } catch (error: Throwable) {
        try {
            created?.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        if (nnapiSessionCreationFailure && error !is OutOfMemoryError) {
            nnapiHealth.disableForNativeException()
        }
        logcat(LogPriority.ERROR, error) {
            "[inpaint] route=nnapi init_failed strictCpuFallbackDisabled=true " +
                "health=${if (nnapiHealth.isHealthy()) "unchanged" else "disabled_native_exception"} ${DeviceCapability.describe()}"
        }
        null
    } finally {
        try {
            opts?.close()
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "[inpaint] route=nnapi optionsClose=failed" }
        }
    }
}

internal fun NeuralInpaintingCoordinator.initializeSession(
    modelFile: File?,
    kind: AotModelContract.Kind,
    route: String,
): OrtSession? {
    if (modelFile == null || !modelFile.exists()) {
        logcat(LogPriority.WARN) {
            "[inpaint] route=$route init=skipped reason=model_missing path=${modelFile?.absolutePath}"
        }
        return null
    }
    var provider = "cpu"
    var created: OrtSession? = null
    return try {
        created = OnnxRuntimeProvider.createSessionWithFallback(
            modelPath = modelFile.absolutePath,
            useXnnpack = true,
            disableIntraOpSpinning = true,
            providerSink = { registeredProvider -> provider = registeredProvider },
        )
        val contract = readContract(created)
        AotModelContract.validate(kind, contract)
        val executionRoute = "${route}_$provider"
        if (kind == AotModelContract.Kind.FIXED_512) {
            fixedSessionRoute = executionRoute
        } else {
            dynamicSessionRoute = executionRoute
        }
        logcat(LogPriority.INFO) {
            "[inpaint] route=$route init=ok provider=${provider.uppercase()} model=${modelFile.name} " +
                "image=${contract.imageShape.contentToString()} mask=${contract.maskShape.contentToString()} " +
                "output=${contract.outputShape.contentToString()}"
        }
        created
    } catch (error: Throwable) {
        try {
            created?.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        logcat(LogPriority.ERROR, error) {
            "[inpaint] route=$route init=failed provider=${provider.uppercase()} model=${modelFile.name}"
        }
        null
    }
}

internal fun NeuralInpaintingCoordinator.readContract(sess: OrtSession): AotModelContract.Contract {
    val inputs = sess.inputInfo
    val outputs = sess.outputInfo
    val image = inputs["image"]?.info as? ai.onnxruntime.TensorInfo
    val mask = inputs["mask"]?.info as? ai.onnxruntime.TensorInfo
    val output = outputs.values.singleOrNull()?.info as? ai.onnxruntime.TensorInfo
    return AotModelContract.Contract(
        inputNames = inputs.keys,
        outputCount = outputs.size,
        imageShape = image?.shape ?: longArrayOf(),
        maskShape = mask?.shape ?: longArrayOf(),
        outputShape = output?.shape ?: longArrayOf(),
    )
}
