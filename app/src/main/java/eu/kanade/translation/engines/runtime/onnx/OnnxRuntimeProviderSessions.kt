package eu.kanade.translation.engines.runtime.onnx

import ai.onnxruntime.OrtSession
import eu.kanade.translation.diagnostics.TelemetryTrace
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.Locale
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider.ProviderOptionsBuild

/**
 * Session-creation flows over the provider: the honest-label fallback contract
 * (pure and JVM-drivable), the model-level CPU-retry entry point, and the
 * strict Paddle provider-matrix cell. Extends the provider object; the
 * environment and routing state live in their owners.
 */

internal fun OnnxRuntimeProvider.openSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
    ): OrtSession {
        val modelName = ModelRoutingEngine.resolveModelId(modelPath)
        // Resolve the user's selected route before consulting model-level
        // compatibility state. Reading activeRoute first leaves the first
        // normal OCR session gated against the CPU default even when the
        // persisted accelerator preference has not yet been probed.
        val route = requestedHardwareRoute ?: if (useAccelerator) {
            HardwareDiscoveryEngine.resolveRoute()
        } else {
            HardwareDiscoveryEngine.activeRoute
        }
        val effectiveAccelerator = useAccelerator || (requestedHardwareRoute != null && requestedHardwareRoute != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK)
        // The accelerator attempt gate also allows the bounded recreation
        // attempt for TEMPORARY_FAILURE.
        val canUseAccelerator = effectiveAccelerator &&
            route != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK &&
            ModelRoutingEngine.isAcceleratorAttemptAllowed(modelName, route)

        if (effectiveAccelerator && !canUseAccelerator) {
            logcat(LogPriority.INFO) {
                "[model_routing] Model '$modelName' is known UNSUPPORTED on route $route; bypassing accelerator to CPU"
            }
            val cpuOpts = createSessionOptions(
                useAccelerator = false,
                useXnnpack = false,
                disableIntraOpSpinning = disableIntraOpSpinning,
                configure = configure,
            )
            val cpuStartNs = System.nanoTime()
            TelemetryTrace.log(
                "hardware",
                "session_create_attempt",
                "model" to modelName,
                "requestedProvider" to "cpu",
                "isProbe" to false,
            )
            return try {
                environment.createSession(modelPath, cpuOpts).also {
                    val durationMs = (System.nanoTime() - cpuStartNs) / 1_000_000.0
                    TelemetryTrace.log(
                        "hardware",
                        "session_create_success",
                        "model" to modelName,
                        "actualProvider" to "cpu",
                        "durationMs" to String.format(Locale.US, "%.2f", durationMs),
                    )
                    providerSink("cpu")
                }
            } finally {
                cpuOpts.close()
            }
        }

        return openSessionWithHonestLabel(
            route = route,
            canUseAccelerator = canUseAccelerator,
            useXnnpack = useXnnpack,
            buildRequested = {
                createSessionOptionsWithRegistration(
                    useAccelerator = canUseAccelerator,
                    useXnnpack = useXnnpack,
                    disableIntraOpSpinning = disableIntraOpSpinning,
                    contextCacheFile = contextCacheFile,
                    routeOverride = route.takeIf { canUseAccelerator },
                    configure = configure,
                ).let { ProviderOptionsBuild(it.options, it.registered) }
            },
            buildCpu = {
                createSessionOptionsWithRegistration(
                    useAccelerator = false,
                    useXnnpack = false,
                    disableIntraOpSpinning = disableIntraOpSpinning,
                    configure = configure,
                ).let { ProviderOptionsBuild(it.options, it.registered) }
            },
            open = { opts -> environment.createSession(modelPath, opts) },
            closeOptions = { opts -> opts.close() },
            sink = providerSink,
            recordModelFailure = { error -> ModelRoutingEngine.recordFailure(modelName, route, error) },
            model = modelName,
            isProbe = false,
        )
    }

internal fun OnnxRuntimeProvider.openPaddleProviderSession(
        modelPath: String,
        configuration: PaddleOcrProviderOverride,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
    ): OrtSession {
        val modelName = ModelRoutingEngine.resolveModelId(modelPath)
        val optionsWithRegistration = createSessionOptionsWithRegistration(
            useAccelerator = configuration.target.isAccelerator,
            useXnnpack = configuration.target == PaddleOcrProviderTarget.CPU,
            routeOverride = configuration.sessionRouteOverride,
            configure = configure,
        )
        val options = optionsWithRegistration.options
        val registeredLabel = optionsWithRegistration.registered.wireLabel
        if (configuration.target.isAccelerator && registeredLabel.isCpuLikeProvider()) {
            options.close()
            throw IllegalStateException(
                "Strict Paddle provider cell ${configuration.target} registered CPU provider '$registeredLabel'",
            )
        }
        val startNs = System.nanoTime()
        TelemetryTrace.log(
            "hardware",
            "session_create_attempt",
            "model" to modelName,
            "requestedProvider" to registeredLabel,
            "isProbe" to false,
        )
        return try {
            val session = environment.createSession(modelPath, options).also {
                val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
                TelemetryTrace.log(
                    "hardware",
                    "session_create_success",
                    "model" to modelName,
                    "actualProvider" to registeredLabel,
                    "durationMs" to String.format(Locale.US, "%.2f", durationMs),
                )
                // Registration is recorded here for diagnostics. The matrix
                // runner records execution provenance only after real inference.
                providerSink(registeredLabel)
            }
            session
        } catch (error: Throwable) {
            if (configuration.target.isAccelerator) {
                val rawReason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                val sanitizedReason = rawReason.take(256).replace("\r", " ").replace("\n", " ").trim()
                TelemetryTrace.log(
                    "hardware",
                    "session_create_failure",
                    "model" to modelName,
                    "requestedProvider" to registeredLabel,
                    "errorType" to error.javaClass.simpleName,
                    "errorReason" to sanitizedReason,
                    "fallbackProvider" to "none",
                )
            }
            throw error
        } finally {
            options.close()
        }
    }

private fun String.isCpuLikeProvider(): Boolean =
        equals("cpu", ignoreCase = true) || equals("uninitialized", ignoreCase = true) || isBlank()
