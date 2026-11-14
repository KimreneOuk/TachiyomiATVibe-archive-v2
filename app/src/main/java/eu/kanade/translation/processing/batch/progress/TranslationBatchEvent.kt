package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.Translation


/**
 * Input to the batch-progress projection. Pipeline/store own all actual page state.
 *
 * The batch coordinator owns start and resume lifecycle. The progress
 * projection derives chapter framing from its ordered page keys and from
 * PagePhase, BatchFinished, and BatchAborted events.
 */
sealed class TranslationBatchEvent {

    /**
     *   the coordinator is rebuilding the envelope dispatch work
     * (resume hydration). Flips the projection into the REBUILDING phase and
     * carries the per-page adoption counter, so the progress sheet shows the
     * indeterminate live window and store-derived counters keep moving during
     * the potentially minutes-long rebuild instead of freezing on a stale
     * snapshot.
     */
    data class EnvelopePlanProgress(
        val done: Int,
        val total: Int,
    ) : TranslationBatchEvent()

    /**   the envelope plan committed (or was reused) — rebuild window ended. */
    data object EnvelopePlanCommitted : TranslationBatchEvent()

    data class BatchAborted(val reason: String, val failedPageKeys: Set<String>) : TranslationBatchEvent()
    data class BatchPaused(
        val anchorPageKey: String?,
        val completedPages: Int,
        val totalPages: Int,
        val retryableCount: Int,
        val reason: String,
        val nextEligibleRetryAtEpochMs: Long? = null,
        val retryablePageKeys: Set<String> = emptySet(),
        // A guarded-publication rejection means no durable result exists for
        // the affected page.
        val nonDurableFailure: Boolean = false,
        val nonDurableFailureReason: String? = null,
    ) : TranslationBatchEvent()
    data class BatchFinished(
        val state: Translation.State,
        val donePages: Int,
        val failedPages: Int,
        val partialPages: Int,
        val totalPages: Int,
        // Carried from ReconciliationResult so a
        // finished chapter keeps its non-durable warning visible.
        val nonDurableFailure: Boolean = false,
        val nonDurableFailureReason: String? = null,
    ) : TranslationBatchEvent()
}
