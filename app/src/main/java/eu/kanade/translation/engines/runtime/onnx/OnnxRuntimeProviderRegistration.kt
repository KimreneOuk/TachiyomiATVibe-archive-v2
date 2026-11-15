package eu.kanade.translation.engines.runtime.onnx

import ai.onnxruntime.OrtSession
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider.AcceleratorRegistrationException
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider.RegisteredExecutionProvider
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider.SessionOptionsWithRegistration
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Route-to-EP registration: builds session options for the selected hardware
 * route, trips the circuit breaker on strict registration failures, and
 * reports the typed registration result (a registration fact, never
 * execution proof).
 */

internal fun OnnxRuntimeProvider.buildSessionOptionsWithRegistration(
    useAccelerator: Boolean = false,
    useXnnpack: Boolean = false,
    disableIntraOpSpinning: Boolean = false,
    contextCacheFile: File? = null,
    routeOverride: HardwareDiscoveryEngine.HardwareRoute? = null,
    tripCircuitBreakerOnRegistrationFailure: Boolean = true,
    configure: (OrtSession.SessionOptions) -> Unit = {},
): SessionOptionsWithRegistration {
    // Session-option construction is the first reliably captured point on
    // device traces; repeat the additive diagnostic here when lazy ORT
    // environment initialization predates logcat capture.
    logCompiledProviders("session_options")
    val route = routeOverride ?: when {
        useAccelerator -> HardwareDiscoveryEngine.resolveRoute()
        useXnnpack -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        else -> null
    }

    when (route) {
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP ->
            logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (strict)" }
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU ->
            logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN Adreno GPU provider (strict)" }
        HardwareDiscoveryEngine.HardwareRoute.NNAPI ->
            logcat(LogPriority.INFO) { "ONNX session options using Hardware Accelerator (NNAPI, strict)" }
        HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK ->
            logcat(LogPriority.INFO) { "ONNX session options using XNNPACK CPU provider" }
        null ->
            logcat(LogPriority.INFO) { "ONNX session options using CPU execution provider" }
    }

    // Strict accelerator registration failures propagate to the session
    // creator; XNNPACK failures resolve to CPU.
    var strictRegistrationFailure: Pair<HardwareDiscoveryEngine.HardwareRoute, Throwable>? = null
    var xnnpackRegistrationFailed = false

    val options = OrtSession.SessionOptions().apply {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val threads = (cpuCores / 2).coerceIn(2, 4)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        runCatching { setCPUArenaAllocator(true) }
        runCatching { setMemoryPatternOptimization(true) }
        if (disableIntraOpSpinning) {
            runCatching {
                // Must be set before the execution provider is registered.
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }.onFailure { e ->
                logcat(LogPriority.WARN, e) {
                    "Could not disable ORT intra-op spinning for this session"
                }
            }
        }
        when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> {
                runCatching {
                    addQnn(buildQnnProviderOptions())
                    // A QNN device failure at session creation is non-fatal in
                    // ORT and silently assigns all nodes to the CPU EP; strict
                    // mode makes it throw so this route label stays truthful.
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    if (contextCacheFile != null) {
                        contextCacheFile.parentFile?.mkdirs()
                        addConfigEntry("ep.context_enable", "1")
                        addConfigEntry("ep.context_file_path", contextCacheFile.absolutePath)
                    }
                    logcat(LogPriority.INFO) { "Successfully added QNN HTP EP (strict)" }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to add QNN HTP EP, falling back to CPU!" }
                    if (tripCircuitBreakerOnRegistrationFailure) {
                        HardwareDiscoveryEngine.tripCircuitBreaker("qnn_htp_add_failed", e)
                    }
                    strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP to e
                }
            }
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> {
                runCatching {
                    addQnn(mapOf("backend_type" to "gpu"))
                    addConfigEntry("session.disable_cpu_ep_fallback", "0")
                    if (contextCacheFile != null) {
                        contextCacheFile.parentFile?.mkdirs()
                        addConfigEntry("ep.context_enable", "1")
                        addConfigEntry("ep.context_file_path", contextCacheFile.absolutePath)
                    }
                    logcat(LogPriority.INFO) { "Successfully added QNN GPU EP (hybrid fallback enabled)" }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to add QNN GPU EP, falling back to CPU!" }
                    if (tripCircuitBreakerOnRegistrationFailure) {
                        HardwareDiscoveryEngine.tripCircuitBreaker("qnn_gpu_add_failed", e)
                    }
                    strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU to e
                }
            }
            HardwareDiscoveryEngine.HardwareRoute.NNAPI -> {
                runCatching {
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addNnapi()
                    logcat(LogPriority.INFO) { "Successfully added NNAPI EP (strict)" }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to add NNAPI EP, falling back to CPU!" }
                    if (tripCircuitBreakerOnRegistrationFailure) {
                        HardwareDiscoveryEngine.tripCircuitBreaker("nnapi_add_failed", e)
                    }
                    strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.NNAPI to e
                }
            }
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK -> {
                runCatching {
                    addXnnpack(java.util.HashMap<String, String>())
                    logcat(LogPriority.INFO) { "Successfully added XNNPACK EP" }
                }.onFailure { e ->
                    // The options keep the default CPU provider and
                    // the reported registration resolves to CPU so the
                    // session is labelled honestly.
                    xnnpackRegistrationFailed = true
                    logcat(LogPriority.ERROR, e) { "Failed to add XNNPACK EP!" }
                }
            }
            null -> {}
        }
        configure(this)
    }

    strictRegistrationFailure?.let { (failedRoute, error) ->
        try {
            options.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        throw AcceleratorRegistrationException(failedRoute, error)
    }

    val registered = when (route) {
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> RegisteredExecutionProvider.QNN_HTP
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> RegisteredExecutionProvider.QNN_GPU
        HardwareDiscoveryEngine.HardwareRoute.NNAPI -> RegisteredExecutionProvider.NNAPI
        HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK ->
            if (xnnpackRegistrationFailed) {
                RegisteredExecutionProvider.CPU
            } else {
                RegisteredExecutionProvider.XNNPACK
            }
        null -> RegisteredExecutionProvider.CPU
    }
    return SessionOptionsWithRegistration(options, registered)
}
