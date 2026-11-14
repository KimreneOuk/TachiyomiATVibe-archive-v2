package eu.kanade.translation.engines.runtime.onnx

import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Named session-options builders: strict QNN (HTP/GPU), XNNPACK-if-present,
 * strict NNAPI, and the shared fixed-AOT option set. Extends the provider
 * object.
 */

internal fun OnnxRuntimeProvider.buildQnnHtpSessionOptions(
    contextCacheFile: File? = null,
    strictCpuFallbackDisabled: Boolean = true,
    qnnOptions: Map<String, String> = buildGenericHtpOptions(),
    configure: (OrtSession.SessionOptions) -> Unit = {},
): OrtSession.SessionOptions {
    val backendName = if (qnnOptions["backend_type"] == "gpu") "QNN Adreno GPU" else "QNN HTP NPU"
    logcat(LogPriority.INFO) { "ONNX session options using Qualcomm $backendName provider (contextCacheFile=$contextCacheFile, options=$qnnOptions)" }
    val options = OrtSession.SessionOptions()
    try {
        options.apply {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val threads = (cpuCores / 2).coerceIn(2, 4)
            setInterOpNumThreads(threads)
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            runCatching { setCPUArenaAllocator(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) { "setCPUArenaAllocator(false) rejected; arena will stay on" }
                }
            runCatching { setMemoryPatternOptimization(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) { "setMemoryPatternOptimization(false) rejected; mem-pattern will stay on" }
                }

            addQnn(qnnOptions)
            if (strictCpuFallbackDisabled) {
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
            }
            if (contextCacheFile != null) {
                contextCacheFile.parentFile?.mkdirs()
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", contextCacheFile.absolutePath)
            }
            logcat(LogPriority.INFO) {
                "Successfully added $backendName EP with options: $qnnOptions " +
                    "strictCpuFallbackDisabled=$strictCpuFallbackDisabled contextCacheFile=$contextCacheFile"
            }
            configure(this)
        }
        return options
    } catch (error: Throwable) {
        try {
            options.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        throw error
    }
}

internal fun OnnxRuntimeProvider.buildRequiredXnnpackSessionOptions(
    registrationSink: (Boolean) -> Unit = {},
): OrtSession.SessionOptions =
    configureOwnedAotOptions { options ->
        try {
            options.addXnnpack(java.util.HashMap<String, String>())
            registrationSink(true)
            logcat(LogPriority.INFO) { "ONNX fixed AOT options provider=XNNPACK registered=true" }
        } catch (error: Throwable) {
            registrationSink(false)
            logcat(LogPriority.WARN, error) {
                "ONNX fixed AOT options provider=XNNPACK registered=false fallback=CPU (XNNPACK not in this build)"
            }
        }
    }

internal fun OnnxRuntimeProvider.buildStrictNnapiSessionOptions(): OrtSession.SessionOptions =
    configureOwnedAotOptions { options ->
        options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        options.addNnapi()
        logcat(LogPriority.INFO) {
            "ONNX fixed AOT options provider=NNAPI strictCpuFallbackDisabled=true"
        }
    }

private inline fun OnnxRuntimeProvider.configureOwnedAotOptions(
    configure: (OrtSession.SessionOptions) -> Unit,
): OrtSession.SessionOptions {
    val options = createBaseAotOptions()
    try {
        configure(options)
        return options
    } catch (error: Throwable) {
        try {
            options.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        throw error
    }
}

private fun OnnxRuntimeProvider.createBaseAotOptions(): OrtSession.SessionOptions =
    OrtSession.SessionOptions().apply {
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        setInterOpNumThreads(threads)
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
