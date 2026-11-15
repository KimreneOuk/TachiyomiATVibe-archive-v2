package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterPageReconciler

data class ReconciliationResult(
    val chapterStatus: Translation.State,
    val strandedPages: Map<String, String>,
    val unexpectedPageKeys: Set<String>,
    val doneCount: Int,
    val failedCount: Int,
    val partialCount: Int,
    val paused: Boolean = false,
    val anchorPageKey: String? = null,
    val pendingCount: Int = 0,
    val retryableCount: Int = 0,
    val terminalCount: Int = failedCount,
    val nextEligibleRetryAtEpochMs: Long? = null,
    val pauseReason: String? = null,
    /** True when the result is an in-memory-only stop after publication rejection. */
    val nonDurableFailure: Boolean = false,
)

object BatchProgressReconciler {

    /**
     * Legacy projection that wraps [ChapterPageReconciler.reconcile].
     * Pinned by [BatchPostPassProjectionTest] as a bug anchor characterizing
     * why COMPLETED batch runs require [reconcileFlaggedCompleted].
     */
    fun reconcile(
        pageMap: Map<String, PageTranslationView>,
        orderedKeys: List<String>,
        activeGeneration: Long,
    ): ReconciliationResult {
        val expectedKeys = orderedKeys.distinct()
        val unexpectedPageKeys = ChapterPageReconciler.findUnexpectedPageKeys(pageMap, expectedKeys)
        if (expectedKeys.isEmpty()) {
            return ReconciliationResult(
                chapterStatus = Translation.State.ERROR,
                strandedPages = emptyMap(),
                unexpectedPageKeys = unexpectedPageKeys,
                doneCount = 0,
                failedCount = 0,
                partialCount = 0,
                paused = false,
            )
        }

        val projection = ChapterPageReconciler.reconcile(
            pageMap = pageMap,
            orderedKeys = expectedKeys,
            activeGeneration = activeGeneration,
            unexpectedPageKeys = unexpectedPageKeys,
        )
        return ReconciliationResult(
            chapterStatus = projection.chapterStatus,
            strandedPages = projection.strandedPages,
            unexpectedPageKeys = projection.unexpectedPageKeys,
            doneCount = projection.doneCount,
            failedCount = projection.failedCount,
            partialCount = projection.partialCount,
            terminalCount = projection.failedCount,
        )
    }

    /**
     * [includeColoringFailures] is set for COLORIZE-ONLY runs: a page whose
     * durable coloring failed is a flagged page needing attention (chapter
     * READY_WITH_WARNINGS), not silent done work — the run deliberately
     * continues past isolated page failures, so the terminal projection must
     * surface them. A colorize-only chapter where NOTHING colored is a
     * retryable ERROR instead of a hollow "complete with warnings".
     */
    fun reconcileFlaggedCompleted(
        pageMap: Map<String, PageTranslationView>,
        orderedKeys: List<String>,
        activeGeneration: Long,
        includeColoringFailures: Boolean = false,
    ): ReconciliationResult {
        val expectedKeys = orderedKeys.distinct()
        val unexpectedPageKeys = pageMap.keys - expectedKeys.toSet()
        var doneCount = 0
        var partialCount = 0
        var displayFailedCount = 0
        var coloringFailedCount = 0
        for (pageKey in expectedKeys) {
            val page = pageMap[pageKey]
            when {
                page?.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL -> {
                    partialCount++
                }
                //  display-tail drain: a page whose translate+inpaint work
                // is terminal but whose committed display never landed takes
                // the FINALIZE typed terminal (render FAILED, durable retryable
                // LAYOUT failure). The run still completes — as a warning, not
                // a clean TRANSLATED and not a stranded ERROR: the page
                // surfaces in the pages-need-attention UI with its specific
                // reason and re-drains on the next run.
                page != null &&
                    page.renderStatus == eu.kanade.translation.model.StageStatus.FAILED &&
                    (
                        page.translationStatus == eu.kanade.translation.model.StageStatus.READY ||
                            page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL
                        ) -> {
                    displayFailedCount++
                }
                includeColoringFailures &&
                    page != null &&
                    page.coloringStatus == eu.kanade.translation.model.StageStatus.FAILED -> {
                    coloringFailedCount++
                }
                else -> {
                    doneCount++
                }
            }
        }
        return ReconciliationResult(
            chapterStatus = when {
                includeColoringFailures && coloringFailedCount > 0 && doneCount == 0 -> Translation.State.ERROR
                includeColoringFailures && coloringFailedCount > 0 -> Translation.State.READY_WITH_WARNINGS
                partialCount > 0 || displayFailedCount > 0 -> Translation.State.READY_WITH_WARNINGS
                else -> Translation.State.TRANSLATED
            },
            strandedPages = emptyMap(),
            unexpectedPageKeys = unexpectedPageKeys,
            doneCount = doneCount,
            failedCount = if (includeColoringFailures) coloringFailedCount else 0,
            partialCount = partialCount,
            terminalCount = if (includeColoringFailures) coloringFailedCount else 0,
        )
    }
}
