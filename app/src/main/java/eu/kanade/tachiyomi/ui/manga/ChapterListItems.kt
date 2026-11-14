package eu.kanade.tachiyomi.ui.manga

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        // TachiyomiAT
        val translationState: Translation.State = Translation.State.NOT_TRANSLATED,
        // Rich batch translation progress for indicator and drawer (null = inactive).
        val translationProgress: TranslationProgressSnapshot? = null,
        /** Immediate request acknowledgement before queue/tracker creation. */
        val translationRequest: TranslationRequestState? = null,
        val downloadProgress: Int,
        val selected: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

/** Enqueues translation-driven chapter downloads and guarantees downloader runs. */
internal fun enqueueTranslationDownloads(
    downloadManager: DownloadManager,
    manga: Manga,
    chapters: List<Chapter>,
) {
    if (chapters.isEmpty()) return
    downloadManager.downloadChapters(manga, chapters)
    // A failed download remains in the downloader queue with ERROR
    // status. Put it back at the front when this is an explicit
    // retry of a previously failed pending request.
    chapters.forEach { chapter ->
        if (downloadManager.getQueuedDownloadOrNull(chapter.id)?.status == Download.State.ERROR) {
            downloadManager.startDownloadNow(chapter.id)
        }
    }
    downloadManager.startDownloads()
}

internal fun MangaScreenModel.State.Success.updateChapterItem(
    chapterId: Long?,
    transform: (ChapterList.Item) -> ChapterList.Item?,
): MangaScreenModel.State.Success {
    if (chapterId == null) return this
    val index = chapters.indexOfFirst { it.id == chapterId }
    if (index < 0) return this
    val currentItem = chapters[index]
    val updatedItem = transform(currentItem) ?: return this
    if (updatedItem === currentItem) return this
    val newChapters = chapters.toMutableList().apply {
        set(index, updatedItem)
    }
    return copy(chapters = newChapters)
}
