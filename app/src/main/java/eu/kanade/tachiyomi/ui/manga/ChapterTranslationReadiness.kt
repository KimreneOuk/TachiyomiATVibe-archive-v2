package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.coordinator.BatchAdmissionDecision
import eu.kanade.translation.coordinator.BatchAdmissionProbe

/**
 * Read-only pre-flight summary for a single chapter, shown before any
 * request is acknowledged. Computed from download state and the
 * [BatchAdmissionProbe] alone — it never writes ledger state.
 */
sealed interface ChapterTranslationReadiness {
    data object Ready : ChapterTranslationReadiness
    data object NeedsDownload : ChapterTranslationReadiness
    data class MidDownload(
        val downloadedPageCount: Int,
        val expectedSourcePageCount: Int?,
    ) : ChapterTranslationReadiness
}

internal fun chapterTranslationReadiness(
    isDownloaded: Boolean,
    queuedDownloadPages: List<Page>?,
): ChapterTranslationReadiness = when {
    !isDownloaded -> ChapterTranslationReadiness.NeedsDownload
    queuedDownloadPages == null -> ChapterTranslationReadiness.Ready
    else -> when (
        val decision = BatchAdmissionProbe.evaluate(
            downloadedPageCount = queuedDownloadPages.count { it.status == Page.State.READY },
            sourcePageList = queuedDownloadPages,
        )
    ) {
        BatchAdmissionDecision.Complete -> ChapterTranslationReadiness.Ready
        is BatchAdmissionDecision.Partial -> ChapterTranslationReadiness.MidDownload(
            downloadedPageCount = decision.downloadedPageCount,
            expectedSourcePageCount = decision.expectedSourcePageCount,
        )
        BatchAdmissionDecision.UnknownCount -> ChapterTranslationReadiness.MidDownload(0, null)
    }
}
