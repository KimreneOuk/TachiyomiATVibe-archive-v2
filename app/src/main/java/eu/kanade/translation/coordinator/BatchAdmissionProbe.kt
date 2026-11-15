package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.model.Page

/**
 *  Admission decision for a chapter
 * whose download directory exists. The trigger resolves the chapter's
 * `Download` from `downloadManager.queueState` (the list the UI already
 * observes — no network) and passes the cross-check here; the probe is pure
 * and store-free.
 *
 * `evaluate` has exactly three outcomes:
 *  - [BatchAdmissionDecision.Complete] — the fetched source page list is
 *    present and matches the on-disk page count;
 *  - [BatchAdmissionDecision.Partial] — mismatch: the delta is carried, never
 *    invented;
 *  - [BatchAdmissionDecision.UnknownCount] — no fetched source page list
 *    (a null list, or an empty one: a Download with no fetched pages is not a
 *    trustworthy known-total-of-zero — design §3.5 risk table).
 */
sealed class BatchAdmissionDecision {
    data object Complete : BatchAdmissionDecision()

    data class Partial(
        val expectedSourcePageCount: Int,
        val downloadedPageCount: Int,
    ) : BatchAdmissionDecision()

    data object UnknownCount : BatchAdmissionDecision()
}

internal object BatchAdmissionProbe {

    /** Cross-checks on-disk count vs the fetched source list per the [BatchAdmissionDecision] outcome table. */
    fun evaluate(
        downloadedPageCount: Int,
        sourcePageList: List<Page>?,
    ): BatchAdmissionDecision = when {
        sourcePageList.isNullOrEmpty() -> BatchAdmissionDecision.UnknownCount
        downloadedPageCount >= sourcePageList.size -> BatchAdmissionDecision.Complete
        else -> BatchAdmissionDecision.Partial(
            expectedSourcePageCount = sourcePageList.size,
            downloadedPageCount = downloadedPageCount,
        )
    }
}

/** Typed so the screen model's dialog and the queued admission stamp cannot drift apart. */
enum class PartialDownloadChoice { FINISH_DOWNLOAD_FIRST, TRANSLATE_WHAT_EXISTS }

/** Probe outcome for honest pre-registration totals; sourceCountKnown=false is the legacy no-cross-check path. */
data class BatchAdmissionContext(
    val probedSourcePageCount: Int?,
    val sourceCountKnown: Boolean,
)
