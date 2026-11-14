package eu.kanade.translation.engines.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import eu.kanade.translation.diagnostics.TelemetryTrace
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.Locale

object OnnxRuntimeProvider {

    internal fun logCompiledProviders(reason: String) {
        val compiledProviders = runCatching {
            OrtEnvironment.getAvailableProviders().joinToString(",") { it.getName() }
        }.getOrElse { error ->
            logcat(LogPriority.WARN, error) { "[onnx_runtime] provider query failed" }
            "query_failed:${error.javaClass.simpleName}"
        }
        logcat(LogPriority.INFO) {
            "[onnx_runtime] compiledProviders=$compiledProviders reason=$reason"
        }
    }

    val environment: OrtEnvironment by lazy {
        logcat { "Creating ONNX Runtime environment" }
        val environment = OrtEnvironment.getEnvironment()
        // Keep this emission outside the provider-query lambda so a query or
        // logger failure cannot silently remove the diagnostic from the init
        // trace. This reports compiled availability, not the selected route.
        logCompiledProviders("environment")
        environment
    }

    /**
     * Typed execution-provider registration result, reported by
     * [createSessionOptionsWithRegistration] from what actually
     * registered on the built options — never from
     * [HardwareDiscoveryEngine.activeRoute], which is device preference only.
     * The wire label (`providerSink` value) is the lowercase [wireLabel].
     */
    enum class RegisteredExecutionProvider { QNN_HTP, QNN_GPU, NNAPI, XNNPACK, CPU }

    /**
     * Session options together with the execution provider that actually
     * registered on them. [registered] is a registration fact only — it is
     * NOT execution proof; provenance requires
     * [ModelRoutingEngine.recordSuccessfulInference] after a real run.
     */
    class SessionOptionsWithRegistration(
        val options: OrtSession.SessionOptions,
        val registered: RegisteredExecutionProvider,
    )

    /**
     * A strict accelerator execution provider failed to register while
     * building session options. The options are already
     * closed by the builder when this is thrown; [OnnxRuntimeProvider.createSessionWithFallback]
     * owns the CPU retry so a default-CPU session can never masquerade as the
     * requested accelerator.
     */
    class AcceleratorRegistrationException(
        val route: HardwareDiscoveryEngine.HardwareRoute,
        cause: Throwable,
    ) : RuntimeException("Execution provider registration failed for route $route", cause)

    /**
     * Generic production QNN HTP provider options.
     * By default, only backend_type="htp" is specified, allowing ORT and QNN runtime
     * to automatically query the device's DSP capabilities and negotiate optimal settings
     * without hardcoding SoC or HTP architecture assumptions.
     *
     * Model-specific profiles or benchmark overrides can pass explicit options
     * (e.g. performanceMode, socModel, htpArch) when empirically justified.
     */
    internal fun buildGenericHtpOptions(
        performanceMode: String? = null,
        socModel: String? = null,
        htpArch: String? = null,
        extraOptions: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("backend_type", "htp")
        performanceMode?.let { put("htp_performance_mode", it) }
        socModel?.let { put("soc_model", it) }
        htpArch?.let { put("htp_arch", it) }
        putAll(extraOptions)
    }

    internal fun buildQnnProviderOptions(
        socModel: String? = null,
        htpArch: String? = null,
    ): Map<String, String> = buildGenericHtpOptions(socModel = socModel, htpArch = htpArch)

    /**
     * Generic typed options build. [OnnxRuntimeProvider.SessionOptionsWithRegistration]
     * is its ONNX Runtime-specific form.
     */
    internal class ProviderOptionsBuild<O>(
        val options: O,
        val registered: RegisteredExecutionProvider,
    )

    /**
     * Pure, ONNX Runtime-free core of the
     * [createSessionWithFallback] main path, generic over the concrete
     * option/session types so JVM provenance tests can drive it with inert
     * tokens (ai.onnxruntime classes refuse to initialize off-device — their
     * static initializers load the native library — so the native-typed
     * surface itself is not JVM-drivable).
     *
     * Contract executed here verbatim by [createSessionWithFallback]:
     * - a strict accelerator registration failure thrown by [buildRequested]
     *   is device-level: the CPU retry happens here, the model routing state
     *   is untouched (the builder already logged + tripped the circuit
     *   breaker), and the sink label is the CPU registration result;
     * - the sink receives the TYPED registration result of the options that
     *   actually opened — never derived from the device [route] — exactly
     *   once, and ONLY after [open] returned successfully;
     * - an [open] failure records the model failure (accelerator attempts
     *   only, via [recordModelFailure]) and retries exactly once on CPU, even
     *   when the failed attempt was already CPU (pre-existing behavior).
     */
    internal fun <O, S> openSessionWithHonestLabel(
        route: HardwareDiscoveryEngine.HardwareRoute,
        canUseAccelerator: Boolean,
        useXnnpack: Boolean,
        buildRequested: () -> ProviderOptionsBuild<O>,
        buildCpu: () -> ProviderOptionsBuild<O>,
        open: (O) -> S,
        closeOptions: (O) -> Unit,
        sink: (String) -> Unit,
        recordModelFailure: (Throwable) -> Unit,
        model: String = "unknown",
        isProbe: Boolean = false,
    ): S {
        val requested = try {
            buildRequested()
        } catch (registrationError: AcceleratorRegistrationException) {
            // A STRICT accelerator registration failure is device-level, not
            // model-level. This provider owns the CPU retry: the session is
            // created on the default CPU EP and labelled cpu — a default-CPU
            // session must never be labelled qnn_htp/qnn_gpu/nnapi/xnnpack.
            logcat(LogPriority.WARN, registrationError) {
                "Strict accelerator registration failed for route ${registrationError.route}; " +
                    "creating this session on default CPU"
            }
            val cpu = buildCpu()
            val cpuWire = cpu.registered.wireLabel
            val cpuStartNs = System.nanoTime()
            TelemetryTrace.log(
                "hardware",
                "session_create_attempt",
                "model" to model,
                "requestedProvider" to cpuWire,
                "isProbe" to isProbe,
            )
            return try {
                val session = open(cpu.options)
                val cpuDurationMs = (System.nanoTime() - cpuStartNs) / 1_000_000.0
                TelemetryTrace.log(
                    "hardware",
                    "session_create_success",
                    "model" to model,
                    "actualProvider" to cpuWire,
                    "durationMs" to String.format(Locale.US, "%.2f", cpuDurationMs),
                )
                session.also { sink(cpuWire) }
            } finally {
                closeQuietly(cpu.options, closeOptions)
            }
        }

        // Describes the ATTEMPT in failure logs only — never the sink label.
        val routeName = when {
            canUseAccelerator -> route.name
            useXnnpack -> "XNNPACK"
            else -> "CPU"
        }
        val requestedWire = requested.registered.wireLabel
        val startNs = System.nanoTime()
        TelemetryTrace.log(
            "hardware",
            "session_create_attempt",
            "model" to model,
            "requestedProvider" to requestedWire,
            "isProbe" to isProbe,
        )
        return try {
            val session = open(requested.options)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
            TelemetryTrace.log(
                "hardware",
                "session_create_success",
                "model" to model,
                "actualProvider" to requestedWire,
                "durationMs" to String.format(Locale.US, "%.2f", durationMs),
            )
            session.also { sink(requestedWire) }
        } catch (error: Throwable) {
            closeQuietly(requested.options, closeOptions)
            if (canUseAccelerator) {
                recordModelFailure(error)
                val rawReason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                val sanitizedReason = rawReason.take(256).replace("\r", " ").replace("\n", " ").trim()
                TelemetryTrace.log(
                    "hardware",
                    "session_create_failure",
                    "model" to model,
                    "requestedProvider" to requestedWire,
                    "errorType" to error.javaClass.simpleName,
                    "errorReason" to sanitizedReason,
                    "fallbackProvider" to "cpu",
                )
            }
            logcat(LogPriority.WARN, error) {
                "Session creation with $routeName failed (model likely not fully partitionable on this EP); " +
                    "retrying this model on CPU only (device health is probe-gated; circuit breaker not tripped)"
            }
            val cpu = buildCpu()
            val cpuWire = cpu.registered.wireLabel
            val cpuStartNs = System.nanoTime()
            TelemetryTrace.log(
                "hardware",
                "session_create_attempt",
                "model" to model,
                "requestedProvider" to cpuWire,
                "isProbe" to isProbe,
            )
            try {
                val session = open(cpu.options)
                val cpuDurationMs = (System.nanoTime() - cpuStartNs) / 1_000_000.0
                TelemetryTrace.log(
                    "hardware",
                    "session_create_success",
                    "model" to model,
                    "actualProvider" to cpuWire,
                    "durationMs" to String.format(Locale.US, "%.2f", cpuDurationMs),
                )
                session.also { sink(cpuWire) }
            } finally {
                closeQuietly(cpu.options, closeOptions)
            }
        } finally {
            closeQuietly(requested.options, closeOptions)
        }
    }

    private fun <O> closeQuietly(options: O, close: (O) -> Unit) {
        try {
            close(options)
        } catch (_: Throwable) {}
    }

    /**
     * Create an ORT session, retrying on CPU if the requested execution provider
     * fails during session creation. With the strict CPU-fallback-disabled
     * accelerator options a graph the EP cannot fully take throws at creation
     * (instead of silently degrading to CPU), so this retry path means
     * "this model is not partitionable on the latched EP" — the model alone
     * drops to CPU. Device health is gated by the [HardwareDiscoveryEngine]
     * probe, so a per-model failure does not trip the circuit breaker;
     * runtime execution failures on accelerator sessions still do.
     *
     * [providerSink] receives the provider that actually served the session
     * ("qnn_htp", "qnn_gpu", "nnapi", "xnnpack", or "cpu") for honest
     * per-engine route logging. The label is the
     * TYPED registration result of the options actually used — it NEVER
     * derives from [HardwareDiscoveryEngine.activeRoute] alone — and it is
     * emitted only after `createSession` succeeds. A strict accelerator
     * registration failure is owned here: the retry session is created on the
     * default CPU EP and labelled `cpu`, never the requested accelerator.
     * Session creation does not prove inference succeeded. Routing support is
     * marked only by
     * [ModelRoutingEngine.recordSuccessfulInference] after a real run.
     */
    fun createSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
    ): OrtSession =
        OnnxRuntimeProvider.openSessionWithFallback(
            modelPath = modelPath,
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheFile = contextCacheFile,
            configure = configure,
            providerSink = providerSink,
            requestedHardwareRoute = requestedHardwareRoute,
        )

    /**
     * Opens a session for one explicit Paddle provider-matrix cell.
     *
     * Unlike [createSessionWithFallback], this method never recreates an
     * accelerator failure on CPU. QNN/NNAPI options already carry
     * `session.disable_cpu_ep_fallback=1`; registration and session creation
     * errors propagate to the cell so a failed provider cannot be reported as
     * a successful accelerator measurement. The CPU cell is intentionally the
     * only cell that requests XNNPACK/default CPU behavior.
     */
    fun createSessionForPaddleProvider(
        modelPath: String,
        configuration: PaddleOcrProviderOverride,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
    ): OrtSession =
        OnnxRuntimeProvider.openPaddleProviderSession(
            modelPath = modelPath,
            configuration = configuration,
            configure = configure,
            providerSink = providerSink,
        )

    /**
     * Dedicated strict QNN session options. HTP is the default backend; callers
     * can supply `backend_type=gpu` for a strict Adreno session. CPU fallback
     * is disabled so backend or partition failures surface during creation
     * instead of silently degrading to the CPU EP. When [contextCacheFile] is
     * provided, ORT persists the compiled HTP context binary there.
     *
     * A failed QNN registration propagates to the caller (options are closed
     * first) so a CPU session can never be created here and masquerade as QNN;
     * callers decide their own fallback and circuit-breaker policy.
     */
    fun createQnnHtpSessionOptions(
        contextCacheFile: File? = null,
        strictCpuFallbackDisabled: Boolean = true,
        qnnOptions: Map<String, String> = buildGenericHtpOptions(),
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions =
        OnnxRuntimeProvider.buildQnnHtpSessionOptions(
            contextCacheFile = contextCacheFile,
            strictCpuFallbackDisabled = strictCpuFallbackDisabled,
            qnnOptions = qnnOptions,
            configure = configure,
        )

    /**
     * Dedicated options for the fixed AOT baseline. XNNPACK is preferred but NOT
     * required: the onnxruntime-android-qnn artifact does not compile XNNPACK,
     * so a fatal requirement would break all AOT inpainting on that build. A
     * registration failure logs loudly and falls back to the default CPU EP;
     * [registrationSink] reports whether XNNPACK actually registered so callers
     * can label their route honestly.
     */
    fun createRequiredXnnpackSessionOptions(
        registrationSink: (Boolean) -> Unit = {},
    ): OrtSession.SessionOptions =
        OnnxRuntimeProvider.buildRequiredXnnpackSessionOptions(registrationSink = registrationSink)

    /**
     * Dedicated strict NNAPI options for fixed AOT only. CPU fallback is disabled,
     * and both config/EP failures deliberately propagate to the session creator.
     */
    fun createStrictNnapiSessionOptions(): OrtSession.SessionOptions =
        OnnxRuntimeProvider.buildStrictNnapiSessionOptions()

    /**
     * Builds session options and returns the typed registration result for
     * the provider that was added. A strict accelerator registration failure
     * propagates as [AcceleratorRegistrationException] (options are closed
     * first) so the caller ([createSessionWithFallback]) owns the CPU retry.
     * An XNNPACK registration failure resolves to
     * [RegisteredExecutionProvider.CPU] because the default CPU provider
     * remains in use.
     */
    fun createSessionOptionsWithRegistration(
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        routeOverride: HardwareDiscoveryEngine.HardwareRoute? = null,
        tripCircuitBreakerOnRegistrationFailure: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): SessionOptionsWithRegistration =
        OnnxRuntimeProvider.buildSessionOptionsWithRegistration(
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheFile = contextCacheFile,
            routeOverride = routeOverride,
            tripCircuitBreakerOnRegistrationFailure = tripCircuitBreakerOnRegistrationFailure,
            configure = configure,
        )

    fun createSessionOptions(
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        routeOverride: HardwareDiscoveryEngine.HardwareRoute? = null,
        tripCircuitBreakerOnRegistrationFailure: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions =
        createSessionOptionsWithRegistration(
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheFile = contextCacheFile,
            configure = configure,
            routeOverride = routeOverride,
            tripCircuitBreakerOnRegistrationFailure = tripCircuitBreakerOnRegistrationFailure,
        ).options
}

/** Lowercase wire label used by `providerSink` and per-engine route logging. */
internal val OnnxRuntimeProvider.RegisteredExecutionProvider.wireLabel: String
    get() = when (this) {
        OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_HTP -> "qnn_htp"
        OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU -> "qnn_gpu"
        OnnxRuntimeProvider.RegisteredExecutionProvider.NNAPI -> "nnapi"
        OnnxRuntimeProvider.RegisteredExecutionProvider.XNNPACK -> "xnnpack"
        OnnxRuntimeProvider.RegisteredExecutionProvider.CPU -> "cpu"
    }
