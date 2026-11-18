package eu.kanade.presentation.manga.components

import dev.icerock.moko.resources.StringResource
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.presentation.BatchStatusLineProjection
import tachiyomi.i18n.at.ATMR
import java.util.Locale

data class ProgressRingProjection(
    val isIndeterminate: Boolean,
    val fraction: Float,
    val percentText: String?,
    val pagesText: String,
)

enum class SheetBannerType {
    WIFI_WAITING,
    WAITING_DOWNLOAD,
    DOWNLOAD_FAILED,
    ADMISSION_FAILED,
    NOT_SAVED,
    ABORTED,
    FAILED,
}

data class SheetBannerProjection(
    val type: SheetBannerType,
    val message: String,
    val resource: StringResource? = null,
    val isError: Boolean,
    val isWarning: Boolean = false,
)

data class SingleChapterDetailsSummary(
    val chapterId: Long,
    val title: String,
    val statusLabel: String,
    val state: Translation.State,
    val isTranslating: Boolean,
    val donePages: Int,
    val totalPages: Int?,
    val progressFraction: Float?,
    val percentText: String?,
)

data class TelemetryProjection(
    val elapsedText: String,
    val remainingText: String,
    val speedText: String,
)

/**
 * Pure projection models for single-chapter batch progress sheet.
 * Encodes backend truth rules from canvas boards 04, 05, and 06:
 * untrusted totals render indeterminate, nonDurableFailure gates completion,
 * ETA stays em-dash until produced, and multi-chapter rows remain secondary.
 */
object TranslationProgressSheetProjection {

    fun computeRingProjection(snapshot: TranslationProgressSnapshot): ProgressRingProjection {
        val total = snapshot.totalPages
        val done = snapshot.donePages
        val isTrusted = snapshot.expectedPageCountTrusted && total > 0

        if (!isTrusted) {
            val label = if (done > 0) {
                "$done pages · source total unknown"
            } else {
                "Source total unknown"
            }
            return ProgressRingProjection(
                isIndeterminate = true,
                fraction = 0f,
                percentText = null,
                pagesText = label,
            )
        }

        val fraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val percent = (fraction * 100).toInt()
        return ProgressRingProjection(
            isIndeterminate = false,
            fraction = fraction,
            percentText = "$percent%",
            pagesText = "$done / $total",
        )
    }

    fun computeBanner(
        snapshot: TranslationProgressSnapshot,
        isWifiWaiting: Boolean,
    ): SheetBannerProjection? {
        if (isWifiWaiting) {
            return SheetBannerProjection(
                type = SheetBannerType.WIFI_WAITING,
                message = "Download waiting for Wi-Fi",
                resource = ATMR.strings.manga_batch_download_wifi_warning,
                isError = false,
                isWarning = true,
            )
        }

        if (snapshot.nonDurableFailure) {
            val msg = if (snapshot.colorizeOnly) {
                "Colorization not saved — retry required"
            } else {
                "Translation not saved — retry required"
            }
            return SheetBannerProjection(
                type = SheetBannerType.NOT_SAVED,
                message = msg,
                isError = true,
            )
        }

        if (snapshot.aborted) {
            val reason = snapshot.abortedReason?.takeIf { it.isNotBlank() }
            val msg = if (reason != null) "Batch aborted: $reason" else "Translation cancelled"
            return SheetBannerProjection(
                type = SheetBannerType.ABORTED,
                message = msg,
                resource = if (reason == null) ATMR.strings.manga_batch_phase_cancelled else null,
                isError = true,
            )
        }

        val req = snapshot.requestState
        if (req?.phase == TranslationRequestPhase.DOWNLOAD_FAILED) {
            val reason = req.reason?.takeIf { it.isNotBlank() }
            val msg = listOfNotNull("Download failed", reason).joinToString(" — ")
            return SheetBannerProjection(
                type = SheetBannerType.DOWNLOAD_FAILED,
                message = msg,
                resource = if (reason == null) ATMR.strings.manga_batch_phase_download_failed else null,
                isError = true,
            )
        }

        if (req?.phase == TranslationRequestPhase.ADMISSION_FAILED) {
            val reason = req.reason?.takeIf { it.isNotBlank() }
            val msg = listOfNotNull("Translation could not be queued", reason).joinToString(" — ")
            return SheetBannerProjection(
                type = SheetBannerType.ADMISSION_FAILED,
                message = msg,
                isError = true,
            )
        }

        if (req?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD) {
            return SheetBannerProjection(
                type = SheetBannerType.WAITING_DOWNLOAD,
                message = "Queued for translation — starts when the download finishes",
                isError = false,
                isWarning = false,
            )
        }

        if (snapshot.groupedFailures.isNotEmpty()) {
            val count = snapshot.groupedFailures.values.sumOf { it.size }.coerceAtLeast(snapshot.failedCount)
            val reasons = snapshot.groupedFailures.keys.joinToString(", ")
            val msg = "Translation failed ($count pages) — $reasons"
            return SheetBannerProjection(
                type = SheetBannerType.FAILED,
                message = msg,
                isError = true,
            )
        }

        if (snapshot.state == Translation.State.ERROR && snapshot.batchPhase == TranslationBatchPhase.FINISHED) {
            val reason = snapshot.requestState?.reason?.takeIf { it.isNotBlank() }
            val msg = listOfNotNull("Translation failed — retry available", reason).joinToString(" — ")
            return SheetBannerProjection(
                type = SheetBannerType.FAILED,
                message = msg,
                isError = true,
            )
        }

        return null
    }

    fun computePhaseChipText(
        snapshot: TranslationProgressSnapshot,
        isResuming: Boolean = false,
        isPauseRequested: Boolean = false,
    ): String {
        val isSnapshotPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        val total = snapshot.totalPages
        val done = snapshot.donePages
        val isTrusted = snapshot.expectedPageCountTrusted && total > 0

        return when {
            isResuming -> "Resuming batch..."
            isPauseRequested -> "Pausing..."
            snapshot.nonDurableFailure -> if (snapshot.colorizeOnly) "Colorization not saved" else "Translation not saved"
            snapshot.aborted || snapshot.requestState?.phase == TranslationRequestPhase.CANCELLED -> "Translation cancelled"
            snapshot.requestState?.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> "Download failed"
            snapshot.requestState?.phase == TranslationRequestPhase.ADMISSION_FAILED -> "Admission failed"
            snapshot.requestState?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> "Waiting for download"
            snapshot.requestState?.phase == TranslationRequestPhase.STARTING ||
                snapshot.requestState?.phase == TranslationRequestPhase.PREPARING -> "Preparing batch"
            isSnapshotPaused -> "Paused"
            BatchStatusLineProjection.isCompletedOutcome(snapshot) -> "Completed"
            snapshot.batchPhase == TranslationBatchPhase.FINISHED &&
                (snapshot.state == Translation.State.ERROR || snapshot.failedCount > 0) -> "Failed"
            snapshot.batchPhase == TranslationBatchPhase.FINISHED &&
                snapshot.state == Translation.State.READY_WITH_WARNINGS -> "Warnings"
            snapshot.batchPhase == TranslationBatchPhase.REBUILDING -> "Rebuilding pipeline"
            snapshot.batchPhase == TranslationBatchPhase.RESTORING -> {
                val p = snapshot.rebuildProgress
                "Restoring ${p?.restoredPages ?: 0}/${p?.totalPages ?: 0}"
            }
            else -> {
                val stageLabels = mapOf(
                    TranslationProgressStage.OCR to "Detection & OCR",
                    TranslationProgressStage.TRANSLATE to "AI Translation",
                    TranslationProgressStage.INPAINT to "Bubble Cleaning",
                    TranslationProgressStage.COLORING to "Coloring",
                    TranslationProgressStage.RENDER to "Text Overlay",
                )
                val activeStage = snapshot.activeStages.firstOrNull { stageLabels.containsKey(it) }
                val currentPage = (snapshot.activePage.takeIf { it > 0 } ?: (done + 1))
                val pageSuffix = if (isTrusted) {
                    "Page ${currentPage.coerceIn(1, total)} of $total"
                } else {
                    "Page $currentPage"
                }

                when {
                    activeStage != null -> "${stageLabels.getValue(activeStage)} · $pageSuffix"
                    snapshot.batchPhase == TranslationBatchPhase.FIRST_PASS -> {
                        if (isTrusted) {
                            "Translating and rendering pages: $done/$total"
                        } else {
                            "Preparing translation batch..."
                        }
                    }
                    snapshot.batchPhase == TranslationBatchPhase.FINALIZING -> "Finishing translation updates"
                    snapshot.state == Translation.State.QUEUE -> "Queued for translation"
                    else -> "Queued for translation"
                }
            }
        }
    }

    fun computeTelemetry(snapshot: TranslationProgressSnapshot): TelemetryProjection {
        val elapsedSeconds = snapshot.elapsedMs.takeIf { it > 0 }?.let { it / 1000 }
        val elapsedText = elapsedSeconds?.let(::formatSeconds) ?: "—"

        val remainingText = snapshot.estimatedRemainingMs?.takeIf { it > 0 }
            ?.let { "~" + formatSeconds(it / 1000) }
            ?: "—"

        val speedPagesPerMin = snapshot.pagesPerMinute?.takeIf { it > 0.05f }
            ?: elapsedSeconds?.takeIf { it > 5 && snapshot.donePages > 0 }
                ?.let { (snapshot.donePages.toFloat() / it) * 60f }
        val speedText = speedPagesPerMin?.let { String.format(Locale.US, "%.1f pages/min", it) } ?: "—"

        return TelemetryProjection(
            elapsedText = elapsedText,
            remainingText = remainingText,
            speedText = speedText,
        )
    }

    fun computeThisChapterSummary(
        snapshot: TranslationProgressSnapshot,
        chapterTitle: String?,
    ): SingleChapterDetailsSummary {
        val title = chapterTitle?.takeIf { it.isNotBlank() } ?: "Chapter ${snapshot.chapterId}"
        val isTrusted = snapshot.expectedPageCountTrusted && snapshot.totalPages > 0
        val total = if (isTrusted) snapshot.totalPages else null
        val done = snapshot.donePages
        val isTranslating = (
            snapshot.batchPhase != TranslationBatchPhase.IDLE ||
                snapshot.state == Translation.State.TRANSLATING
            ) &&
            snapshot.state != Translation.State.PAUSED &&
            snapshot.pauseReason == null &&
            !snapshot.aborted

        val fraction = if (isTrusted && snapshot.totalPages > 0) {
            (done.toFloat() / snapshot.totalPages.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        val percentText = fraction?.let { "${(it * 100).toInt()}%" }

        val statusLabel = when {
            snapshot.nonDurableFailure -> if (snapshot.colorizeOnly) "Colorization not saved" else "Translation not saved"
            snapshot.aborted -> "Cancelled"
            snapshot.state == Translation.State.ERROR -> "Failed"
            snapshot.requestState?.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> "Download failed"
            snapshot.requestState?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> "Waiting for download"
            snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null -> "Paused"
            BatchStatusLineProjection.isCompletedOutcome(snapshot) || snapshot.state == Translation.State.TRANSLATED ->
                if (total != null) "Done · $done pages" else "Done · $done pages translated"
            snapshot.state == Translation.State.READY_WITH_WARNINGS -> "Warnings"
            isTranslating -> {
                if (isTrusted) {
                    val page = (snapshot.activePage.takeIf { it > 0 } ?: (done + 1)).coerceIn(1, snapshot.totalPages)
                    "Translating · Page $page of ${snapshot.totalPages}"
                } else {
                    "Translating · $done pages"
                }
            }
            snapshot.state == Translation.State.QUEUE -> "Queued"
            else -> "Queued"
        }

        return SingleChapterDetailsSummary(
            chapterId = snapshot.chapterId,
            title = title,
            statusLabel = statusLabel,
            state = snapshot.state,
            isTranslating = isTranslating,
            donePages = done,
            totalPages = total,
            progressFraction = fraction,
            percentText = percentText,
        )
    }

    fun isTerminalState(snapshot: TranslationProgressSnapshot): Boolean {
        val isSnapshotPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        if (isSnapshotPaused) return false

        return snapshot.batchPhase == TranslationBatchPhase.FINISHED ||
            snapshot.aborted ||
            snapshot.requestState?.phase == TranslationRequestPhase.CANCELLED ||
            snapshot.requestState?.phase == TranslationRequestPhase.DOWNLOAD_FAILED ||
            snapshot.requestState?.phase == TranslationRequestPhase.ADMISSION_FAILED ||
            snapshot.state == Translation.State.ERROR ||
            snapshot.state == Translation.State.TRANSLATED ||
            (snapshot.state == Translation.State.READY_WITH_WARNINGS && snapshot.batchPhase == TranslationBatchPhase.FINISHED)
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
