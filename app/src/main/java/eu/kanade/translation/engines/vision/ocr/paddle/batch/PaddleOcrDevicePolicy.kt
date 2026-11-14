package eu.kanade.translation.engines.vision.ocr.paddle.batch

import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.engines.runtime.onnx.DeviceCapability
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

typealias PaddleOcrBatchActivationPolicy = PaddleOcrDevicePolicy

enum class PaddleOcrEvidence {
    CONFIRMED,
    LIKELY,
    UNTESTED,
    FAILED,
}

/** One provider/width/batch cell in the device validation matrix. */
data class PaddleOcrMatrixCombination(
    val provider: PaddleOcrProviderTarget,
    val batchSize: PaddleOcrBatchSize,
    val widthBucket: PaddleOcrWidthBucket,
)

/**
 * Device facts and post-run approvals kept outside the recognizer engine.
 * Empty approvals are intentional: an unknown or stale device profile cannot
 * silently activate B4/B8 in production.
 */
data class PaddleOcrDeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val socModel: String,
    val androidApi: Int,
    val primaryAbi: String,
    val totalRamBytes: Long,
    val evidenceByCombination: Map<PaddleOcrMatrixCombination, PaddleOcrEvidence> = emptyMap(),
) {

    fun evidenceFor(combination: PaddleOcrMatrixCombination): PaddleOcrEvidence =
        evidenceByCombination[combination] ?: PaddleOcrEvidence.UNTESTED

    fun isConfirmed(combination: PaddleOcrMatrixCombination): Boolean =
        evidenceFor(combination) == PaddleOcrEvidence.CONFIRMED

    fun confirmedCombinations(): Set<PaddleOcrMatrixCombination> =
        evidenceByCombination.filterValues { it == PaddleOcrEvidence.CONFIRMED }.keys

    companion object {
        fun untested(
            manufacturer: String = "unknown",
            model: String = "unknown",
            device: String = "unknown",
            socModel: String = "unknown",
            androidApi: Int = 0,
            primaryAbi: String = "unknown",
            totalRamBytes: Long = 0L,
        ): PaddleOcrDeviceProfile = PaddleOcrDeviceProfile(
            manufacturer = manufacturer,
            model = model,
            device = device,
            socModel = socModel,
            androidApi = androidApi,
            primaryAbi = primaryAbi,
            totalRamBytes = totalRamBytes,
        )

        fun current(): PaddleOcrDeviceProfile = PaddleOcrDeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            socModel = DeviceCapability.socModel,
            androidApi = Build.VERSION.SDK_INT,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            totalRamBytes = 0L,
        )
    }
}

data class PaddleOcrThermalPolicy(
    val maxAllowedSeverity: Int = 2,
    val recoverySeverity: Int = 1,
) {
    init {
        require(maxAllowedSeverity >= 0)
        require(recoverySeverity >= 0)
        require(recoverySeverity <= maxAllowedSeverity)
    }

    fun blocksAcceleration(thermalSeverity: Int): Boolean = thermalSeverity > maxAllowedSeverity
}

data class PaddleOcrBatchActivation(
    val activeBatchSize: PaddleOcrBatchSize,
    val providerTarget: PaddleOcrProviderTarget?,
    val forceCpuB1EmergencyFallback: Boolean,
    val reason: String,
    /** Whole-page dynamic chunking armed; the tier acts as the governor cap. */
    val dynamicPageBatch: Boolean = false,
)

/**
 * Device/profile gate for staged Paddle batching. The recognizer engine does
 * not own this policy or thermal state; it receives only the resulting batch
 * size/provider choice at its integration boundary.
 */
object PaddleOcrDevicePolicy {

    /**
     * Auto-routing entry point: batch size is pinned to the production B1
     * default (no dynamic whole-page batching); only the resolved hardware
     * provider feeds the activation decision.
     */
    fun currentPinnedB1(
        requestedProvider: PaddleOcrExecutionProvider?,
    ): PaddleOcrBatchActivation = current(
        requestedBatchSize = PaddleOcrBatchSize.B1,
        requestedProvider = requestedProvider?.toBatchProvider(),
        dynamicPageBatch = false,
    )

    fun current(
        requestedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
        profile: PaddleOcrDeviceProfile = PaddleOcrDeviceProfile.untested(),
        widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        thermalSeverity: Int = 0,
        requestedProvider: PaddleOcrProviderTarget? = null,
        dynamicPageBatch: Boolean = false,
    ): PaddleOcrBatchActivation {
        val buildConfigBatch = when (BuildConfig.PADDLE_BATCHING_REQUESTED_BATCH) {
            8 -> PaddleOcrBatchSize.B8
            4 -> PaddleOcrBatchSize.B4
            2 -> PaddleOcrBatchSize.B2
            else -> PaddleOcrBatchSize.B1
        }
        // The staged cap exists because fixed B8 was never device-validated;
        // dynamic mode carries its own memory-derived ceiling, so it is exempt.
        val requestedBatch = if (BuildConfig.PADDLE_BATCHING_STAGED && !dynamicPageBatch) {
            requestedBatchSize.capAt(buildConfigBatch)
        } else {
            requestedBatchSize
        }
        return resolveActivation(
            stagedEnabled = BuildConfig.PADDLE_BATCHING_STAGED,
            requestedBatchSize = requestedBatch,
            requestedProvider = requestedProvider,
            profile = profile,
            widthBucket = widthBucket,
            thermalSeverity = thermalSeverity,
            debugProvisionalOptIn = BuildConfig.DEBUG && requestedBatchSize != PaddleOcrBatchSize.B1,
        ).copy(dynamicPageBatch = dynamicPageBatch)
    }

    private fun PaddleOcrBatchSize.capAt(cap: PaddleOcrBatchSize): PaddleOcrBatchSize =
        if (value <= cap.value) this else cap

    private fun PaddleOcrExecutionProvider.toBatchProvider(): PaddleOcrProviderTarget = when (this) {
        PaddleOcrExecutionProvider.CPU -> PaddleOcrProviderTarget.CPU
        PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU -> PaddleOcrProviderTarget.QNN_GPU
        PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP -> PaddleOcrProviderTarget.QNN_HTP
    }

    /**
     * The only safe activation when a staged cell fails its device gate.
     * Callers pass this configuration to the provider seam; the recognizer
     * itself does not choose or hide a provider fallback.
     */
    fun emergencyCpuB1(): PaddleOcrBatchActivation = PaddleOcrBatchActivation(
        activeBatchSize = PaddleOcrBatchSize.B1,
        providerTarget = PaddleOcrProviderTarget.CPU,
        forceCpuB1EmergencyFallback = true,
        reason = "explicit_cpu_b1_emergency_fallback",
    )

    fun resolveActivation(
        stagedEnabled: Boolean,
        requestedBatchSize: PaddleOcrBatchSize,
        requestedProvider: PaddleOcrProviderTarget?,
        profile: PaddleOcrDeviceProfile,
        widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        thermalSeverity: Int = 0,
        thermalPolicy: PaddleOcrThermalPolicy = PaddleOcrThermalPolicy(),
        debugProvisionalOptIn: Boolean = false,
    ): PaddleOcrBatchActivation {
        if (requestedBatchSize == PaddleOcrBatchSize.B1) {
            return PaddleOcrBatchActivation(
                activeBatchSize = PaddleOcrBatchSize.B1,
                providerTarget = requestedProvider,
                forceCpuB1EmergencyFallback = false,
                reason = "b1_default",
            )
        }
        if (!stagedEnabled) {
            return emergency("staged_flag_off")
        }
        if (requestedProvider == null) {
            return emergency("provider_not_selected")
        }
        val combination = PaddleOcrMatrixCombination(requestedProvider, requestedBatchSize, widthBucket)
        if (!profile.isConfirmed(combination)) {
            if (debugProvisionalOptIn) {
                if (thermalPolicy.blocksAcceleration(thermalSeverity)) {
                    return emergency("thermal_guard")
                }
                return PaddleOcrBatchActivation(
                    activeBatchSize = requestedBatchSize,
                    providerTarget = requestedProvider,
                    forceCpuB1EmergencyFallback = false,
                    reason = "debug_provisional_optin",
                )
            }
            return emergency("combination_not_confirmed")
        }
        if (thermalPolicy.blocksAcceleration(thermalSeverity)) {
            return emergency("thermal_guard")
        }
        return PaddleOcrBatchActivation(
            activeBatchSize = requestedBatchSize,
            providerTarget = requestedProvider,
            forceCpuB1EmergencyFallback = false,
            reason = "confirmed",
        )
    }

    private fun emergency(reason: String): PaddleOcrBatchActivation = PaddleOcrBatchActivation(
        activeBatchSize = PaddleOcrBatchSize.B1,
        providerTarget = PaddleOcrProviderTarget.CPU,
        forceCpuB1EmergencyFallback = true,
        reason = reason,
    )
}
