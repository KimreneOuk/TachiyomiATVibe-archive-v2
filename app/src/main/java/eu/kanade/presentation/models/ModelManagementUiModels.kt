package eu.kanade.presentation.models

import eu.kanade.translation.models.DeleteBlockReason
import eu.kanade.translation.models.ModelPackDescriptor
import eu.kanade.translation.models.PackFailure
import eu.kanade.translation.models.PackInstallState
import eu.kanade.translation.models.PackProgress
import eu.kanade.translation.models.PackStatus
import eu.kanade.translation.models.Portability

/** How the screen labels a pack row; the composable resolves localized strings. */
enum class PackUiState { NOT_INSTALLABLE, INSTALLABLE, INSTALLING, INSTALLED, FAILED }

enum class DeleteBlockUi { LIVE_LEASES, ESSENTIAL_ACK, NOT_INSTALLED }

/**
 * Data-only view of one pack row: no Android types, so the mapping stays
 * JVM-testable and the composable only resolves labels.
 */
data class ModelPackUi(
    val id: String,
    val title: String,
    val state: PackUiState,
    val version: String?,
    val previousVersionKept: String?,
    val storageBytes: Long,
    val downloadBytes: Long,
    val deviceLocked: Boolean,
    val qualified: Boolean,
    val progress: PackProgress?,
    val failureMessage: String?,
    val failureRetryable: Boolean,
)

/** Actions the screen exposes for a row; every enabled action is a real manager operation. */
data class ModelPackActions(
    val install: Boolean,
    val cancel: Boolean,
    val verify: Boolean,
    val delete: Boolean,
)

fun modelPackUi(status: PackStatus, descriptor: ModelPackDescriptor?): ModelPackUi {
    val installState = status.installState
    val failure = (installState as? PackInstallState.Failed)?.failure
    return ModelPackUi(
        id = status.id,
        title = status.title,
        state = when (installState) {
            is PackInstallState.NotInstallable -> PackUiState.NOT_INSTALLABLE
            is PackInstallState.Installable -> PackUiState.INSTALLABLE
            is PackInstallState.Installing -> PackUiState.INSTALLING
            is PackInstallState.Installed -> PackUiState.INSTALLED
            is PackInstallState.Failed -> PackUiState.FAILED
        },
        version = (installState as? PackInstallState.Installed)?.version,
        previousVersionKept = (installState as? PackInstallState.Installed)?.previousKept,
        storageBytes = status.storageBytes,
        downloadBytes = descriptor?.totalBytes ?: 0L,
        deviceLocked = descriptor?.primaryVariant?.deviceConstraint != null,
        qualified = status.portability.qualification is Portability.Qualification.DeviceQualified,
        progress = (installState as? PackInstallState.Installing)?.progress,
        failureMessage = failure?.message,
        failureRetryable = failure?.retryable ?: false,
    )
}

fun modelPackActions(pack: ModelPackUi): ModelPackActions = when (pack.state) {
    PackUiState.INSTALLABLE -> ModelPackActions(install = true, cancel = false, verify = false, delete = false)
    PackUiState.INSTALLING -> ModelPackActions(install = false, cancel = true, verify = false, delete = false)
    PackUiState.INSTALLED -> ModelPackActions(install = false, cancel = false, verify = true, delete = true)
    // A failed install only re-offers install when the failure is retryable;
    // a non-retryable failure (device incompatibility, internal error) stays read-only.
    PackUiState.FAILED -> ModelPackActions(install = pack.failureRetryable, cancel = false, verify = false, delete = false)
    PackUiState.NOT_INSTALLABLE -> ModelPackActions(install = false, cancel = false, verify = false, delete = false)
}

fun deleteBlockUi(reason: DeleteBlockReason): DeleteBlockUi = when (reason) {
    DeleteBlockReason.LIVE_LEASES -> DeleteBlockUi.LIVE_LEASES
    DeleteBlockReason.ESSENTIAL_ACK_REQUIRED -> DeleteBlockUi.ESSENTIAL_ACK
    DeleteBlockReason.NOT_INSTALLED -> DeleteBlockUi.NOT_INSTALLED
}

/** Human-readable byte count; pure so tests pin the format. Negative reads as unknown. */
fun formatBytesForUi(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
