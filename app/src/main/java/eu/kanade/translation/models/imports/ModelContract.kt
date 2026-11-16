package eu.kanade.translation.models.imports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-supplied manifest describing an imported model variant. Every field is
 * DECLARED metadata: it becomes VERIFIED only after an app-side check. Missing
 * optional fields render as unverified, never as silently-assumed defaults.
 */
@Serializable
data class ModelContract(
    /** Consumer seam, e.g. "OCR_MANGAOCR". Must match a seam the app knows. */
    val featureSeam: String,
    /** Main model artifact file name inside the import (used for artifact metadata checks). */
    val artifactFile: String,
    /** "ONNX" | "TFLITE". */
    val format: String,
    val variantVersion: String = "1.0.0",
    val quantization: String? = null,
    val inputs: List<TensorSpec> = emptyList(),
    val outputs: List<TensorSpec> = emptyList(),
    /** Declared preprocessing steps; executed only by consumers that implement them. */
    val preprocessing: List<String> = emptyList(),
    val postprocessing: List<String> = emptyList(),
    val runtime: RuntimeRequirements = RuntimeRequirements(),
    val constraints: VariantConstraints = VariantConstraints(),
    /** Optional trusted publisher digests (path → sha256); identity hashes are computed separately at import. */
    val publisherSha256: Map<String, String> = emptyMap(),
    val licenseNote: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): ModelContract = json.decodeFromString(text)

        fun encode(contract: ModelContract): String = json.encodeToString(contract)
    }
}

@Serializable
data class TensorSpec(
    val name: String,
    /** null = dynamic dimension; dynamic shapes can never be statically contract-checked. */
    val shape: List<Long?>,
    val dtype: String,
    val layout: String = "OTHER",
)

@Serializable
data class RuntimeRequirements(
    val engine: String? = null,
    val minRuntimeVersion: String? = null,
    val buildProvenance: String? = null,
    /**
     * Declared custom op / native library requirements. Any entry causes an
     * outright REJECT: executable payloads are never loaded.
     */
    val customOps: List<String> = emptyList(),
)

@Serializable
data class VariantConstraints(
    val abi: String? = null,
    val vendor: String? = null,
    val socClass: String? = null,
    val osMin: String? = null,
    val driverRange: String? = null,
)

/**
 * The TRUSTED per-seam contract owned by the app (never by the importer). L1
 * compares actual artifact metadata against this; seams without a declared
 * trusted contract stay UNVERIFIED at L1.
 */
data class FeatureContract(
    val featureSeam: String,
    val declared: Boolean,
    val inputs: List<TensorSpec> = emptyList(),
    val outputs: List<TensorSpec> = emptyList(),
)

enum class ValidationLevel { L0_IMPORTED, L1_CONTRACT_CHECKED, L2_RUNTIME_LOAD, L3_INFERENCE_OK, L4_SEMANTIC_OK }

sealed interface StageResult {
    data object Passed : StageResult
    data class Unverified(val reason: String) : StageResult
    data class Rejected(val reason: String, val hint: String? = null) : StageResult
}

/** Explicit trust ladder state; higher levels are never inferred from lower ones. */
data class ValidationReport(
    val variantId: String,
    val levels: Map<ValidationLevel, StageResult>,
) {
    fun trustState(): TrustState = when {
        levels[ValidationLevel.L0_IMPORTED] is StageResult.Rejected ||
            levels[ValidationLevel.L1_CONTRACT_CHECKED] is StageResult.Rejected -> TrustState.REJECTED
        levels[ValidationLevel.L0_IMPORTED] == StageResult.Passed &&
            levels[ValidationLevel.L1_CONTRACT_CHECKED] == StageResult.Passed -> TrustState.VALIDATED
        levels[ValidationLevel.L0_IMPORTED] == StageResult.Passed -> TrustState.IMPORTED
        else -> TrustState.UNTRUSTED
    }

    /** Contract is usable for opt-in selection when L0 passed and L1 is not rejected; unverified L1 needs explicit acknowledgement. */
    fun l1Unverified(): Boolean =
        levels[ValidationLevel.L1_CONTRACT_CHECKED] is StageResult.Unverified
}

enum class TrustState { UNTRUSTED, IMPORTED, VALIDATED, REJECTED, QUARANTINED }

data class VariantStatus(
    val variantId: String,
    val featureSeam: String,
    val version: String,
    val trustState: TrustState,
    val report: ValidationReport?,
    val storageBytes: Long,
    val originDisplayName: String,
    val importedAtEpochMs: Long,
    /** Import-computed identity digests; authenticity is the separate publisher flags below. */
    val fileDigests: Map<String, String>,
    val publisherDigestsProvided: Boolean = false,
    val publisherDigestsMatched: Boolean = false,
)

sealed interface ImportOutcome {
    data class Imported(val variantId: String, val version: String, val report: ValidationReport) : ImportOutcome
    data class Rejected(val reason: String, val hint: String?) : ImportOutcome
}
