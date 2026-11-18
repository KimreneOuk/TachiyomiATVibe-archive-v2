package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetGlobalLastVisited
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaLastVisited

class LibraryScreenModelGlobalResumeTest {

    private val getGlobalLastVisited = mockk<GetGlobalLastVisited>()
    private val getManga = mockk<GetManga>()
    private val getChapter = mockk<GetChapter>()

    @Test
    fun `globalResumeTarget emits target when favorites-only entry exists`() = runBlocking {
        val lastVisited = MangaLastVisited(
            mangaId = 1L,
            chapterId = 10L,
            lastVisitedPage = 5L,
            visitedAt = 123456789L,
        )
        val manga = Manga.create().copy(id = 1L, title = "Frieren", favorite = true)
        val chapter = Chapter.create().copy(id = 10L, mangaId = 1L, name = "Chapter 124")

        every { getGlobalLastVisited.subscribe(favoritesOnly = true) } returns flowOf(lastVisited)
        coEvery { getManga.await(1L) } returns manga
        coEvery { getChapter.await(10L) } returns chapter

        val target = getGlobalLastVisited.subscribe(favoritesOnly = true).first()
        target shouldNotBe null
        target!!.mangaId shouldBe 1L
        target.chapterId shouldBe 10L
        target.lastVisitedPage shouldBe 5L
    }

    @Test
    fun `globalResumeTarget handles null entry`() = runBlocking {
        every { getGlobalLastVisited.subscribe(favoritesOnly = true) } returns flowOf(null)

        val target = getGlobalLastVisited.subscribe(favoritesOnly = true).first()
        target shouldBe null
    }
}
