package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterListAssemblerTest {

    private val manga = Manga.create().copy(
        id = 1L,
        title = "Test Manga",
        source = 1L,
        favorite = true,
        initialized = true,
    )

    private fun chapter(id: Long, name: String = "Chapter $id") = Chapter.create().copy(
        id = id,
        mangaId = manga.id,
        name = name,
    )

    @Test
    fun `toBaseChapterListItems builds base items with selection and carried states`() = runBlocking {
        val downloadManager = mockk<DownloadManager> {
            every { getQueuedDownloadOrNull(any()) } returns null
            every { isChapterDownloaded(any(), any(), any(), any()) } returns false
        }
        val translationManager = mockk<TranslationManager> {
            every { getQueuedTranslationOrNull(any()) } returns null
            every { pendingTranslationRequests } returns MutableStateFlow(emptyMap())
        }
        val snapshots = ChapterTranslationSnapshotRegistry()
        val translationController = mockk<TranslationProgressController>(relaxed = true)

        val assembler = ChapterListAssembler(
            downloadManager = downloadManager,
            translationManager = translationManager,
            snapshots = snapshots,
            translationController = translationController,
            getSuccessState = { null },
            updateSuccessState = {},
        )

        val chapters = listOf(chapter(1L), chapter(2L))
        val items = assembler.toBaseChapterListItems(chapters, manga, selectedChapterIds = setOf(2L))

        items.size shouldBe 2
        items[0].selected shouldBe false
        items[1].selected shouldBe true
        items[0].downloadState shouldBe Download.State.NOT_DOWNLOADED
        items[1].downloadState shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    fun `toBaseChapterListItems starts controller for queued translations`() = runBlocking {
        val queued = mockk<Translation> {
            every { status } returns Translation.State.TRANSLATING
        }
        val downloadManager = mockk<DownloadManager> {
            every { getQueuedDownloadOrNull(any()) } returns null
            every { isChapterDownloaded(any(), any(), any(), any()) } returns true
        }
        val translationManager = mockk<TranslationManager> {
            every { getQueuedTranslationOrNull(1L) } returns queued
            every { pendingTranslationRequests } returns MutableStateFlow(emptyMap())
        }
        val snapshots = ChapterTranslationSnapshotRegistry()
        val translationController = mockk<TranslationProgressController>(relaxed = true)

        val assembler = ChapterListAssembler(
            downloadManager = downloadManager,
            translationManager = translationManager,
            snapshots = snapshots,
            translationController = translationController,
            getSuccessState = { null },
            updateSuccessState = {},
        )

        assembler.toBaseChapterListItems(listOf(chapter(1L)), manga)

        verify(exactly = 1) {
            translationController.start(1L, Translation.State.TRANSLATING)
        }
    }

    @Test
    fun `resolveTranslationStatesAndPatch queries status and patches state`() = runBlocking {
        val downloadManager = mockk<DownloadManager>()
        val translationManager = mockk<TranslationManager> {
            coEvery { getChapterTranslationStatus(1L, any(), any(), any(), any()) } returns Translation.State.TRANSLATED
            coEvery { getChapterTranslationStatus(2L, any(), any(), any(), any()) } returns Translation.State.ERROR
            every { getQueuedTranslationOrNull(any()) } returns null
            every { pendingTranslationRequests } returns MutableStateFlow(emptyMap())
        }
        val snapshots = ChapterTranslationSnapshotRegistry()
        val translationController = mockk<TranslationProgressController>(relaxed = true)

        var currentState: MangaScreenModel.State.Success? = null
        val successState = MangaScreenModel.State.Success(
            manga = manga,
            source = mockk(relaxed = true),
            isFromSource = false,
            chapters = listOf(
                ChapterList.Item(chapter(1L), Download.State.DOWNLOADED, downloadProgress = 0),
                ChapterList.Item(chapter(2L), Download.State.DOWNLOADED, downloadProgress = 0),
            ),
            availableScanlators = emptySet(),
            excludedScanlators = emptySet(),
        )
        currentState = successState

        val assembler = ChapterListAssembler(
            downloadManager = downloadManager,
            translationManager = translationManager,
            snapshots = snapshots,
            translationController = translationController,
            getSuccessState = { currentState },
            updateSuccessState = { update ->
                currentState = update(currentState!!)
            },
        )

        assembler.resolveTranslationStatesAndPatch(manga, successState.chapters)

        currentState!!.chapters[0].translationState shouldBe Translation.State.TRANSLATED
        currentState!!.chapters[1].translationState shouldBe Translation.State.ERROR
        verify(exactly = 1) {
            translationController.start(2L, Translation.State.ERROR)
        }
    }
}
