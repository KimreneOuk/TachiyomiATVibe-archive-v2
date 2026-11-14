package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.presentation.TranslationUiProjection
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal

/**
 * Assembles and resolves chapter list items for [MangaScreenModel].
 *
 * Implements the two-phase list approach:
 * 1. [toBaseChapterListItems] emits base items instantly from DB and cache without durable SAF lookups.
 * 2. [resolveTranslationStatesAndPatch] resolves durable translation badges in a background pass and patches
 *    the state atomically without flickering existing badges.
 */
internal class ChapterListAssembler(
    private val downloadManager: DownloadManager,
    private val translationManager: TranslationManager,
    private val snapshots: ChapterTranslationSnapshotRegistry,
    private val translationController: TranslationProgressController,
    private val getSuccessState: () -> MangaScreenModel.State.Success?,
    private val updateSuccessState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
) {

    /**
     * Base chapter items from DB + DownloadCache alone — no durable SAF lookup,
     * so the list renders instantly. Durable badges resolve in [resolveTranslationStatesAndPatch];
     * until then items carry over the badge already on screen.
     */
    suspend fun toBaseChapterListItems(
        chapters: List<Chapter>,
        manga: Manga,
        selectedChapterIds: Set<Long> = emptySet(),
    ): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val carriedStates = getSuccessState()?.chapters
            ?.associateBy({ it.chapter.id }) { it.translationState }
            .orEmpty()
        return chapters.map { chapter ->
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, manga.title, manga.source)
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
            val queuedTranslation = chapter.id?.let(translationManager::getQueuedTranslationOrNull)
            val translationRequest = chapter.id?.let { translationManager.pendingTranslationRequests.value[it] }
            val persistedTranslationState = if (downloadState == Download.State.DOWNLOADED) {
                carriedStates[chapter.id]
            } else {
                null
            }
            val translationState = TranslationUiProjection.chapterState(
                queuedState = queuedTranslation?.status,
                persistedState = persistedTranslationState,
                requestState = translationRequest,
                downloaded = downloadState == Download.State.DOWNLOADED,
            )
            if (queuedTranslation != null || translationRequest != null) {
                chapter.id?.let { id ->
                    translationController.start(id, translationState)
                }
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
                translationState = translationState,
                translationRequest = translationRequest,
            )
        }.carryingTranslationSnapshots(snapshots)
    }

    /**
     * Background badge pass for the two-phase list: resolves durable translation
     * states for downloaded chapters and patches the list in a diffed update.
     */
    suspend fun resolveTranslationStatesAndPatch(manga: Manga, baseItems: List<ChapterList.Item>) {
        val resolvedStates = mutableMapOf<Long, Translation.State?>()
        val terminalHintById = mutableMapOf<Long, Translation.State>()
        for (item in baseItems) {
            if (item.downloadState != Download.State.DOWNLOADED) continue
            val chapter = item.chapter
            val chapterId = chapter.id ?: continue
            val resolved = translationManager.getChapterTranslationStatus(
                chapter.id,
                chapter.name,
                chapter.scanlator,
                manga.title,
                manga.source,
            )
            resolvedStates[chapterId] = resolved
            if (resolved == Translation.State.ERROR || resolved == Translation.State.READY_WITH_WARNINGS) {
                terminalHintById[chapterId] = resolved
            }
        }
        val queuedStateById = resolvedStates.keys.associateWith { chapterId ->
            translationManager.getQueuedTranslationOrNull(chapterId)?.status
        }
        val pendingRequests = translationManager.pendingTranslationRequests.value

        updateSuccessState { current ->
            val patched = current.chapters.map { item ->
                val chapterId = item.chapter.id ?: return@map item
                if (!resolvedStates.containsKey(chapterId)) return@map item
                val translationState = TranslationUiProjection.chapterState(
                    queuedState = queuedStateById[chapterId],
                    persistedState = resolvedStates[chapterId],
                    requestState = pendingRequests[chapterId],
                    downloaded = true,
                )
                if (translationState == item.translationState) item else item.copy(translationState = translationState)
            }
            if (patched == current.chapters) current else current.copy(chapters = patched)
        }
        terminalHintById.forEach { (chapterId, hint) ->
            translationController.start(chapterId, hint)
        }
    }

    /**
     * Suspend convenience method: assembles base items and patches durable states.
     */
    suspend fun toChapterListItems(
        chapters: List<Chapter>,
        manga: Manga,
        selectedChapterIds: Set<Long> = emptySet(),
    ): List<ChapterList.Item> =
        toBaseChapterListItems(chapters, manga, selectedChapterIds).also {
            resolveTranslationStatesAndPatch(manga, it)
        }
}
