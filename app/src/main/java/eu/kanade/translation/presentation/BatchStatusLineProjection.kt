package eu.kanade.translation.presentation

import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.BatchHeroPhase
import eu.kanade.translation.model.BatchHeroProjection
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import java.text.DateFormat
import java.util.Date

/**
 * Chapter-level batch status-line truth. One priority chain (requestState →
 * queuePosition → pauseReason → coordinator phase+counters → batchPhase)
 * resolved ONCE here, rendered by every batch status surface. Each line
 * carries a stable [BatchStatusLineKind] (surfaces resolve NEW wording from
 * string resources through it) plus the English fallback/source wording
 * (legacy rendering stays byte-identical where no new resource exists yet).
 * The vocabulary + line type live in BatchStatusLine.kt; [TranslationUiTruth]
 * stays the facade every surface (sheet, reader bar, tests) imports.
 */
object BatchStatusLineProjection {

    // ------------------------------------------------------------------
    //  completion oracle: the celebratory "Completed / All pages
    // translated" state is a CLAIM about every page, so it is gated on the
    // run's failure/attention facts — never on the batch phase alone. The
    // device trace that motivated this  showed the sheet's green
    // "Completed" pill and "All pages translated" subtitle while the same
    // run's trace recorded outcome=failure and "1 pages need attention":
    // FINISHED (which ERROR/READY_WITH_WARNINGS chapters also map to) was
    // treated as completion regardless of unresolved pages. Every completed
    // surface keys on [isCompletedOutcome]; a run that fails the oracle
    // renders the attention/failed wording instead. Presentation truth only
    // — no pipeline state is added or changed.
    // ------------------------------------------------------------------

    /** One page's attention fact: a failed stage/AI attempt or a PARTIAL (retryable, never clean success) result. A page whose committed display is ready and non-partial is success, matching the sheet's page-chip precedence. */
    private fun pageNeedsAttention(page: TranslationProgressSnapshot.Page): Boolean = when {
        page.displayReady && !page.partial -> false
        page.stage == TranslationProgressStage.FAILED -> true
        page.aiState == AiPageProgressState.FAILED -> true
        page.partial -> true
        else -> false
    }

    /**
     *  completion oracle: whether any page in the snapshot needs user
     * attention — the batch failure counter, the failure groups, a
     * non-durable (unsaved) result, or a per-page failed/partial fact.
     */
    fun hasPagesNeedingAttention(snapshot: TranslationProgressSnapshot): Boolean =
        snapshot.failedCount > 0 ||
            snapshot.nonDurableFailure ||
            snapshot.groupedFailures.isNotEmpty() ||
            snapshot.pages.any(::pageNeedsAttention)

    /**
     *  completion oracle: the count behind the "N pages need attention"
     * wording. Never zero while attention exists, so an attention run whose
     * facts live outside the per-page list (unsaved result) still reads
     * honestly.
     */
    fun pagesNeedingAttentionCount(snapshot: TranslationProgressSnapshot): Int {
        if (!hasPagesNeedingAttention(snapshot)) return 0
        return maxOf(
            snapshot.pages.count(::pageNeedsAttention),
            snapshot.failedCount,
            1,
        )
    }

    /**
     *  completion oracle: the run may render "Completed / All pages
     * translated" ONLY when the batch phase finished AND zero pages need
     * attention AND the run outcome is not failed/paused/aborted/unsaved. A
     * run with failure or attention pages NEVER classifies as completed.
     */
    fun isCompletedOutcome(snapshot: TranslationProgressSnapshot): Boolean =
        snapshot.batchPhase == TranslationBatchPhase.FINISHED &&
            !snapshot.aborted &&
            !snapshot.nonDurableFailure &&
            snapshot.pauseReason == null &&
            snapshot.state != Translation.State.ERROR &&
            snapshot.state != Translation.State.PAUSED &&
            !hasPagesNeedingAttention(snapshot)

    /**
     * The single chapter-level status-line priority chain:
     * requestState → queuePosition → pauseReason → coordinator
     * rebuild/restore phase + counters → batchPhase. The sheet subtitle and
     * the reader bottom bar both derive their copy from this mapping (via
     * [readerBarLine] for the bar's short wording), so no surface can
     * contradict another or freeze on stale "Batch X/Y" copy during a
     * resume rebuild.
     */
    fun batchStatusLine(
        snapshot: TranslationProgressSnapshot,
        isResuming: Boolean = false,
    ): BatchStatusLine = when {
        isResuming -> BatchStatusLine(
            kind = BatchStatusLineKind.RESUMING,
            fallback = "Scanning completed pages & resuming batch...",
        )

        else -> requestStatusLine(snapshot)
            ?: queueStatusLine(snapshot)
            ?: pausedStatusLine(snapshot)
            ?: rebuildStatusLine(snapshot)
            ?: batchPhaseStatusLine(snapshot)
    }

    /** Priority 1: the immediate pre-tracker request acknowledgement. */
    private fun requestStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        val request = snapshot.requestState ?: return null
        val base = when (request.phase) {
            TranslationRequestPhase.STARTING -> "Translation accepted — preparing batch..."
            TranslationRequestPhase.PREPARING -> "Preparing translation batch..."
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                "Waiting for chapter download before translation"
            TranslationRequestPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
            TranslationRequestPhase.CANCELLED ->
                "Translation cancelled — the chapter download was cancelled or removed"
            TranslationRequestPhase.ADMISSION_FAILED ->
                "Translation could not be queued — check the source and translation settings"
        }
        val kind = when (request.phase) {
            TranslationRequestPhase.STARTING -> BatchStatusLineKind.REQUEST_STARTING
            TranslationRequestPhase.PREPARING -> BatchStatusLineKind.REQUEST_PREPARING
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD
            TranslationRequestPhase.DOWNLOAD_FAILED -> BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED
            TranslationRequestPhase.CANCELLED -> BatchStatusLineKind.REQUEST_CANCELLED
            TranslationRequestPhase.ADMISSION_FAILED -> BatchStatusLineKind.REQUEST_ADMISSION_FAILED
        }
        val suffix = request.reason.orEmpty().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        return BatchStatusLine(kind = kind, fallback = base + suffix)
    }

    /** Priority 2: truthful queue position (never implies it can resume now). */
    private fun queueStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        if (snapshot.state != Translation.State.QUEUE) return null
        val position = snapshot.queuePosition
        val total = snapshot.queueTotal
        return if (position != null && total != null && position > 1) {
            BatchStatusLine(
                kind = BatchStatusLineKind.QUEUE_POSITION,
                formatArgs = listOf(position, total),
                fallback = queuedPositionLabel(position, total),
            )
        } else {
            BatchStatusLine(
                kind = BatchStatusLineKind.QUEUED_READY,
                fallback = "Queued — ready to resume remaining pages",
            )
        }
    }

    /** Priority 3: a durable pause with its reason (and retry time, when known). */
    private fun pausedStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        val paused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        if (!paused) return null
        val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() }
            ?: if (snapshot.pausedForCharging) {
                "waiting for charging"
            } else {
                "Provider work is temporarily unavailable"
            }
        val next = snapshot.nextEligibleRetryAtEpochMs?.let { retryAt ->
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(retryAt))
        }
        return BatchStatusLine(
            kind = BatchStatusLineKind.PAUSED,
            fallback = if (next == null) "Paused — $reason" else "Paused — $reason · retry after $next",
        )
    }

    /** Priority 4: the coordinator's rebuild/restore phase with its counters. */
    private fun rebuildStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? =
        when (snapshot.batchPhase) {
            TranslationBatchPhase.REBUILDING -> BatchStatusLine(
                kind = BatchStatusLineKind.REBUILDING,
                fallback = "Rebuilding pipeline…",
            )
            TranslationBatchPhase.RESTORING -> {
                val payload = snapshot.rebuildProgress
                BatchStatusLine(
                    kind = BatchStatusLineKind.RESTORING,
                    formatArgs = listOf(
                        payload?.restoredPages ?: 0,
                        payload?.totalPages ?: 0,
                    ),
                    fallback = "Restoring ${payload?.restoredPages ?: 0} of " +
                        "${payload?.totalPages ?: 0} pages…",
                )
            }
            else -> null
        }

    /** Priority 5: the remaining batch phases (the legacy subtitle wording). */
    private fun batchPhaseStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine =
        when (snapshot.batchPhase) {
            TranslationBatchPhase.IDLE -> when (snapshot.state) {
                Translation.State.TRANSLATING -> BatchStatusLine(
                    kind = BatchStatusLineKind.BUILDING_CONTEXT,
                    fallback = "Building context & scanning completed pages...",
                )
                else -> BatchStatusLine(
                    kind = BatchStatusLineKind.NO_ACTIVE_BATCH,
                    fallback = "No active batch in progress",
                )
            }
            TranslationBatchPhase.FIRST_PASS -> firstPassStatusLine(snapshot)
            TranslationBatchPhase.FINALIZING -> BatchStatusLine(
                kind = BatchStatusLineKind.FINALIZING,
                fallback = "Finalizing translated chapter...",
            )
            TranslationBatchPhase.FINISHED -> when {
                //  completion oracle: only a run with zero attention pages
                // and no failed/paused/aborted outcome may claim completion.
                isCompletedOutcome(snapshot) -> BatchStatusLine(
                    kind = BatchStatusLineKind.COMPLETED,
                    // A colorize-only run never claims translation truth.
                    fallback = if (snapshot.colorizeOnly) {
                        "All pages colored and ready to read"
                    } else {
                        "All pages translated and ready to read"
                    },
                )
                else -> BatchStatusLine(
                    kind = BatchStatusLineKind.ATTENTION_REQUIRED,
                    formatArgs = listOf(pagesNeedingAttentionCount(snapshot)),
                    fallback = "${pagesNeedingAttentionCount(snapshot)} pages need attention",
                )
            }
            // The rebuild/restore kinds are resolved by [rebuildStatusLine]
            // (higher priority); this branch is unreachable through
            // [batchStatusLine] and exists only for exhaustiveness.
            TranslationBatchPhase.REBUILDING,
            TranslationBatchPhase.RESTORING,
            -> rebuildStatusLine(snapshot)
                ?: BatchStatusLine(
                    kind = BatchStatusLineKind.REBUILDING,
                    fallback = "Rebuilding pipeline…",
                )
        }

    /** FIRST_PASS stage detail — the legacy subtitle's stage-aware wording. */
    private fun firstPassStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine = when {
        snapshot.activeStages.contains(TranslationProgressStage.TRANSLATE) -> {
            val progress = snapshot.aiProgress
            val detail = listOfNotNull(
                "${progress.pending} pending".takeIf { progress.pending > 0 },
                "${progress.buffered} buffered".takeIf { progress.buffered > 0 },
                "${progress.running} running/retrying".takeIf { progress.running > 0 },
                "${progress.failed} failed".takeIf { progress.failed > 0 },
            ).joinToString(", ")
            BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_STAGES,
                fallback = if (detail.isBlank()) {
                    "Translating dialogue with AI model..."
                } else {
                    "AI translation: $detail"
                },
            )
        }
        snapshot.activeStages.contains(TranslationProgressStage.OCR) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Reading and detecting page text...",
        )
        snapshot.activeStages.contains(TranslationProgressStage.INPAINT) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Cleaning speech bubbles...",
        )
        snapshot.colorizeOnly || snapshot.activeStages.contains(TranslationProgressStage.COLORING) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Coloring page...",
        )
        snapshot.activeStages.contains(TranslationProgressStage.RENDER) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Rendering English text overlays...",
        )
        else -> when (val hero = BatchHeroProjection.of(snapshot)) {
            is BatchHeroProjection.Numeric -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_PAGES,
                formatArgs = listOf(snapshot.donePages, snapshot.totalPages),
                fallback = if (snapshot.colorizeOnly) {
                    "Coloring pages (${snapshot.donePages}/${snapshot.totalPages})"
                } else {
                    "Translating pages (${snapshot.donePages}/${snapshot.totalPages})"
                },
            )
            is BatchHeroProjection.Phase -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_STAGES,
                fallback = heroPhaseSubtitle(hero),
            )
        }
    }

    /** Subtitle copy for unknown-total phases. */
    private fun heroPhaseSubtitle(hero: BatchHeroProjection.Phase): String {
        val percent = hero.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return when (hero.phase) {
            BatchHeroPhase.ACCEPTED -> "Translation accepted — preparing batch..."
            BatchHeroPhase.WAITING_FOR_DOWNLOAD -> "Waiting for chapter download before translation"
            BatchHeroPhase.DOWNLOADING -> "Downloading chapter$percent..."
            BatchHeroPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
            BatchHeroPhase.PREPARING -> "Preparing translation batch..."
            BatchHeroPhase.QUEUED -> "Queued — ready to resume remaining pages"
            BatchHeroPhase.PAUSED -> "Paused"
            BatchHeroPhase.FINALIZING -> "Finalizing translated chapter..."
            BatchHeroPhase.COMPLETED -> "All pages translated and ready to read"
            BatchHeroPhase.FAILED_NO_PAGES -> "Translation failed — chapter has no readable pages"
            BatchHeroPhase.CANCELLED ->
                "Translation cancelled — the chapter download was cancelled or removed"
            BatchHeroPhase.ADMISSION_FAILED ->
                "Translation could not be queued — check the source and translation settings"
            BatchHeroPhase.UNKNOWN_TOTAL ->
                hero.donePages?.let { "$it pages available · source total unknown" }
                    ?: "Source page total unknown"
        }
    }

    /**
     * The reader bottom bar's short rendering of the same truth. Returns null
     * exactly when the bar must stay hidden. Live phases keep their short
     * wording; terminal requests, retry timing, and building context mirror the sheet.
     */
    fun readerBarLine(
        snapshot: TranslationProgressSnapshot,
        isBatchSession: Boolean = true,
    ): BatchStatusLine? {
        if (!isBatchSession) return null
        val request = snapshot.requestState
        val isPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        val isBuildingContext = snapshot.batchPhase == TranslationBatchPhase.IDLE &&
            snapshot.state == Translation.State.TRANSLATING
        val isVisible = request != null ||
            isPaused ||
            snapshot.aborted ||
            isBuildingContext ||
            snapshot.batchPhase != TranslationBatchPhase.IDLE
        if (!isVisible) return null
        val coloring = snapshot.colorizeOnly
        val retryAt = snapshot.nextEligibleRetryAtEpochMs?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        }
        return when {
            request?.phase == TranslationRequestPhase.STARTING -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_STARTING,
                fallback = "Translation accepted — preparing",
            )
            request?.phase == TranslationRequestPhase.PREPARING -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_PREPARING,
                fallback = "Preparing translation batch",
            )
            request?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD,
                fallback = "Waiting for chapter download",
            )
            request?.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> {
                val reason = request?.reason.orEmpty()
                    .takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                BatchStatusLine(
                    kind = BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED,
                    fallback = "Download failed — retry to continue$reason",
                )
            }
            // Terminal request outcomes outrank a stale pause: the generation
            // fence keeps them terminal even when the chapter still carries a pause flag.
            request?.phase == TranslationRequestPhase.CANCELLED -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_CANCELLED,
                fallback = "Translation cancelled — the chapter download was cancelled or removed",
            )
            request?.phase == TranslationRequestPhase.ADMISSION_FAILED -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_ADMISSION_FAILED,
                fallback = "Translation could not be queued — check the source and translation settings",
            )
            snapshot.aborted -> BatchStatusLine(
                kind = BatchStatusLineKind.ATTENTION_REQUIRED,
                fallback = if (coloring) {
                    "Colorization failed — " + snapshot.abortedReason.orEmpty()
                        .ifBlank { "retry the chapter" }.take(120)
                } else {
                    "Translation failed — " + snapshot.abortedReason.orEmpty()
                        .ifBlank { "retry the chapter" }.take(120)
                },
            )
            isPaused && snapshot.pausedForCharging -> BatchStatusLine(
                kind = BatchStatusLineKind.PAUSED,
                fallback = if (coloring) {
                    "Colorization paused — waiting for charging"
                } else {
                    "Translation paused — waiting for charging"
                },
            )
            isPaused -> BatchStatusLine(
                kind = BatchStatusLineKind.PAUSED,
                fallback = when {
                    retryAt != null && coloring -> "Colorization paused — retry after $retryAt"
                    retryAt != null -> "Translation paused — retry after $retryAt"
                    coloring -> "Colorization paused"
                    else -> "Translation paused"
                },
            )
            snapshot.batchPhase == TranslationBatchPhase.REBUILDING -> BatchStatusLine(
                kind = BatchStatusLineKind.REBUILDING,
                fallback = "Rebuilding pipeline…",
            )
            snapshot.batchPhase == TranslationBatchPhase.RESTORING -> {
                val remaining = snapshot.rebuildProgress?.remainingPages ?: 0
                BatchStatusLine(
                    kind = BatchStatusLineKind.RESTORING,
                    formatArgs = listOf(remaining),
                    fallback = "Restoring $remaining pages…",
                )
            }
            isBuildingContext -> BatchStatusLine(
                kind = BatchStatusLineKind.BUILDING_CONTEXT,
                fallback = "Building context & scanning completed pages...",
            )
            snapshot.totalPages > 0 -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_PAGES,
                formatArgs = listOf(snapshot.donePages, snapshot.totalPages),
                fallback = if (coloring) {
                    "Coloring ${snapshot.donePages}/${snapshot.totalPages} pages…"
                } else {
                    "Batch ${snapshot.donePages}/${snapshot.totalPages} pages"
                },
            )
            else -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_PREPARING,
                fallback = if (coloring) "Preparing colorization…" else "Preparing translation batch",
            )
        }
    }

    /** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 4 -> "4th", 11-13 -> "th". */
    fun ordinalSuffixOf(value: Int): String {
        val mod100 = value % 100
        val suffix = when {
            mod100 in 11..13 -> "th"
            else -> when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    /** Queue position, e.g. "Queued (2nd of 3) — ...". */
    fun queuedPositionLabel(position: Int, total: Int): String =
        "Queued (${ordinalSuffixOf(position)} of $total) — waiting for earlier batches"
}
