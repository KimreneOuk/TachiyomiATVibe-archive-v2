package eu.kanade.translation.models

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Identifies a pack in the embedded prototype catalog (e.g. "mangaocr"). */
typealias PackId = String

enum class PackRole { ESSENTIAL, OPTIONAL }

enum class PackSourceKind {
    /** Files come from the APK's build-time verified assets; digests are pinned at install. */
    ASSET_MIRROR,
    /** Local directory source, used by the test-only sandbox. */
    LOCAL_DIR,
    /** Files streamed from a user import (document picker); digests computed from real bytes. */
    USER_IMPORT,
    HTTP,
}

enum class ModelFormat { ONNX, TFLITE }

enum class ExecutionTarget { CPU, GPU, NPU }

/**
 * Format portability and device qualification are separate axes: a portable
 * format is not evidence the route is validated on a device class.
 */
data class Portability(
    val format: ModelFormat,
    val qualification: Qualification,
) {
    sealed interface Qualification {
        data object Unqualified : Qualification
        data class DeviceQualified(val deviceClass: String, val evidenceRef: String) : Qualification
    }
}

data class ModelFileSpec(
    /** File name inside the pack version directory, e.g. "encoder.onnx". */
    val path: String,
    val sizeBytes: Long,
    /**
     * Pinned SHA-256 for strict verification. Null only for mirror/local
     * sources: the digest is computed from source bytes at install and pinned
     * into the active record. HTTP sources must always pin digests.
     */
    val sha256: String?,
    /** Source reference (asset path, local relative path, URL). */
    val sourceRef: String? = null,
)

data class ModelVariantDescriptor(
    val target: ExecutionTarget,
    val format: ModelFormat,
    val files: List<ModelFileSpec>,
    /** Device class restriction, e.g. "SM8650"; null when unrestricted. */
    val deviceConstraint: String? = null,
)

data class ModelPackDescriptor(
    val id: PackId,
    val version: String,
    val role: PackRole,
    val title: String,
    val licenseNote: String,
    val sourceKind: PackSourceKind,
    val portability: Portability,
    val variants: List<ModelVariantDescriptor>,
    /** Consumer seam this pack can bind to, e.g. "OCR_MANGAOCR"; null when not yet bound to a seam. */
    val featureSeam: String? = null,
) {
    val primaryVariant: ModelVariantDescriptor get() = variants.first()
    fun fileSpecs(): List<ModelFileSpec> = primaryVariant.files

    /**
     * Upper bound on bytes to stage. Unknown sizes (-1 sentinels) clamp to zero
     * so a negative total can never bypass the free-space check, and the fold
     * saturates instead of overflowing on hostile specs.
     */
    val totalBytes: Long
        get() = fileSpecs().fold(0L) { acc, spec ->
            val v = spec.sizeBytes.coerceAtLeast(0L)
            if (acc > Long.MAX_VALUE - v) Long.MAX_VALUE else acc + v
        }
}

data class PackProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val filesDone: Int,
    val filesTotal: Int,
)

sealed interface PackInstallState {
    /** No usable source exists for this pack on this build. */
    data object NotInstallable : PackInstallState
    data object Installable : PackInstallState
    data class Installing(val progress: PackProgress?) : PackInstallState
    data class Installed(val version: String, val previousKept: String?) : PackInstallState
    data class Failed(val failure: PackFailure) : PackInstallState
}

sealed interface PackFailure {
    val message: String
    val retryable: Boolean

    data class SourceUnavailable(override val message: String) : PackFailure {
        override val retryable = true
    }

    data class VerificationMismatch(val path: String, override val message: String) : PackFailure {
        override val retryable = true
    }

    data class StorageInsufficient(val requiredBytes: Long, val availableBytes: Long) : PackFailure {
        override val retryable = true
        override val message: String =
            "Insufficient storage: required $requiredBytes bytes, available $availableBytes bytes"
    }

    data object Cancelled : PackFailure {
        override val retryable = true
        override val message = "Install cancelled; the active version is untouched"
    }

    data object MeteredConfirmationRequired : PackFailure {
        override val retryable = true
        override val message = "Confirm before downloading on a metered network"
    }

    data class DeviceIncompatible(val deviceClass: String, override val message: String) : PackFailure {
        override val retryable = false
    }

    data class Internal(override val message: String) : PackFailure {
        override val retryable = false
    }
}

data class PackStatus(
    val id: PackId,
    val title: String,
    val role: PackRole,
    val installState: PackInstallState,
    val storageBytes: Long,
    val sourceKind: PackSourceKind,
    val portability: Portability,
)

sealed interface InstallResult {
    data class Installed(val version: String) : InstallResult
    data class AlreadyInstalled(val version: String) : InstallResult
    data class Failed(val failure: PackFailure) : InstallResult
}

enum class DeleteBlockReason { LIVE_LEASES, ESSENTIAL_ACK_REQUIRED, NOT_INSTALLED }

sealed interface DeleteResult {
    data class Deleted(val freedBytes: Long) : DeleteResult
    data class Blocked(val reason: DeleteBlockReason) : DeleteResult
}

sealed interface VerifyResult {
    data object Verified : VerifyResult
    data class Corrupt(val problems: List<String>) : VerifyResult
    data object NotInstalled : VerifyResult
}

sealed interface CacheClearResult {
    data class Cleared(val freedBytes: Long) : CacheClearResult
    data class Blocked(val reason: String) : CacheClearResult
}

data class LeasedFile(val logicalPath: String, val file: File, val sha256: String)

/**
 * A lease pins one verified pack version for a consumer's lifetime. Files are
 * resolved at acquire time and never drift to another version; deletion of the
 * pinned version is refused while the lease is live.
 */
interface PackLease {
    val packId: PackId
    val version: String
    fun files(): List<LeasedFile>
    fun filesByName(): Map<String, File>
    /** Non-suspending unregister, safe from close() paths; reclamation runs on the next manager operation. */
    fun release()
}

/**
 * Acquisition, activation and deletion share one per-pack commit lock, so a
 * lease pins a version either before or after an activation — never a mixed set.
 */
interface ModelPackManager {
    val statuses: StateFlow<List<PackStatus>>

    /** Disk-as-truth reconcile: sweeps stale staging dirs, rebuilds statuses. Never sweeps versions on a missing index. */
    suspend fun refresh()

    suspend fun install(packId: PackId, confirmMetered: Boolean = false): InstallResult
    suspend fun cancel(packId: PackId)
    suspend fun delete(packId: PackId, acknowledgeEssential: Boolean = false): DeleteResult
    suspend fun verify(packId: PackId): VerifyResult

    /** Clears extracted bundled-cache files (re-extracted from APK assets on next use). Never frees APK bytes. */
    suspend fun clearExtractedCache(cachedFileNames: List<String>): CacheClearResult

    /**
     * Null when no version is active; null for user-imported variants until
     * runtime validation (L2/L3) exists — imports never bind to consumers.
     * [owner] records the session Job for diagnostics; blocking stays
     * fail-closed (an unreleased lease blocks deletion/GC until release or
     * process death), because cancellation is not proof native work ended.
     */
    suspend fun acquire(packId: PackId, owner: Job? = null): PackLease?
    suspend fun release(lease: PackLease)

    /** Recorded identity digests of the active version; null when not installed. */
    suspend fun digestsFor(packId: PackId): Map<String, String>?
}
