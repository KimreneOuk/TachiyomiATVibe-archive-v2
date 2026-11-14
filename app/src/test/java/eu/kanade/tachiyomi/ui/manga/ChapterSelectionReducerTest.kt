package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the pure selection semantics the screen model delegates to: range-fill
 * anchors as chapter ids, selections that survive filtering, and the explicit
 * no-anchor reset when anchors stop resolving after a filter or order change.
 *
 * [MangaScreenModel.State.Success.processedChapters] reaches the
 * download-filter domain extension, which reads `BasePreferences` through
 * Injekt — register a mock before any toggle runs (same fixture pattern as the
 * manga screen model tests use for `SourceManager`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterSelectionReducerTest {

    @BeforeAll
    fun setUpInjekt() {
        if (injektPrepared.compareAndSet(false, true)) {
            val preferenceStore = mockk<PreferenceStore> {
                every { getBoolean(any(), any()) } returns mockk {
                    every { get() } returns false
                }
            }
            Injekt.addSingleton(
                fullType<BasePreferences>(),
                BasePreferences(context = mockk(relaxed = true), preferenceStore = preferenceStore),
            )
        }
    }

    private val manga = Manga.create().copy(
        id = 1L,
        title = "Manga",
        source = 1L,
        favorite = true,
        initialized = true,
    )

    private fun chapter(id: Long, bookmark: Boolean = false) = Chapter.create().copy(
        id = id,
        mangaId = 1L,
        name = "Chapter $id",
        bookmark = bookmark,
    )

    private fun item(chapter: Chapter, selected: Boolean = false) = ChapterList.Item(
        chapter = chapter,
        downloadState = Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
        selected = selected,
    )

    private fun state(
        chapters: List<ChapterList.Item>,
        manga: Manga = this.manga,
        selectedChapterIds: Set<Long> = emptySet(),
        selectionAnchors: SelectionAnchors? = null,
    ): MangaScreenModel.State.Success = MangaScreenModel.State.Success(
        manga = manga,
        source = mockk(relaxed = true),
        isFromSource = false,
        chapters = chapters,
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
        selectedChapterIds = selectedChapterIds,
        selectionAnchors = selectionAnchors,
    )

    @Test
    fun `single tap select sets the id, flag, and a downward anchor`() {
        val next = ChapterSelectionReducer.toggle(
            state(listOf(item(chapter(5L)), item(chapter(6L)))),
            item(chapter(5L)),
            selected = true,
            userSelected = true,
        )

        next.selectedChapterIds shouldBe setOf(5L)
        next.chapters.first { it.id == 5L }.selected shouldBe true
        // Legacy semantics: a tap select leaves the first anchor unset so the
        // next downward long-press can extend the range.
        next.selectionAnchors shouldBe SelectionAnchors(null, 5L)
    }

    @Test
    fun `long-press after a tap fills the in-between range`() {
        val initial = state(listOf(item(chapter(5L)), item(chapter(6L)), item(chapter(7L))))
        val afterTap = ChapterSelectionReducer.toggle(initial, item(chapter(5L)), selected = true, userSelected = true)
        val afterLongPress = ChapterSelectionReducer.toggle(
            afterTap,
            item(chapter(7L)),
            selected = true,
            userSelected = true,
            fromLongPress = true,
        )

        afterLongPress.selectedChapterIds shouldBe setOf(5L, 6L, 7L)
        afterLongPress.chapters.map { it.selected } shouldBe listOf(true, true, true)
        afterLongPress.selectionAnchors shouldBe SelectionAnchors(null, 7L)
    }

    @Test
    fun `deselecting the first anchor moves the anchor to the first still-selected chapter`() {
        val initial = state(listOf(item(chapter(5L)), item(chapter(6L)), item(chapter(7L))))
        val s1 = ChapterSelectionReducer.toggle(
            initial,
            item(chapter(5L)),
            selected = true,
            userSelected = true,
            fromLongPress = true,
        )
        val s2 = ChapterSelectionReducer.toggle(s1, item(chapter(6L)), selected = true, userSelected = true)
        val s3 = ChapterSelectionReducer.toggle(s2, item(chapter(5L)), selected = false, userSelected = true)

        s3.selectedChapterIds shouldBe setOf(6L)
        s3.selectionAnchors shouldBe SelectionAnchors(6L, 6L)
    }

    @Test
    fun `setAll keeps chapters hidden by an active filter selected`() {
        val filteredManga = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        val initial = state(
            listOf(item(chapter(5L)), item(chapter(6L, bookmark = true))),
            manga = filteredManga,
        )
        withClue("precondition: chapter 5 is filtered out of the visible list") {
            initial.processedChapters.map { it.id } shouldBe listOf(6L)
        }

        val next = ChapterSelectionReducer.setAll(initial, selected = true)

        next.selectedChapterIds shouldBe setOf(5L, 6L)
    }

    @Test
    fun `invert flips every chapter and leaves ids absent from the list untouched`() {
        val initial = state(
            listOf(item(chapter(5L), selected = true), item(chapter(6L))),
            selectedChapterIds = setOf(5L, 99L),
        )

        val next = ChapterSelectionReducer.invert(initial)

        next.selectedChapterIds shouldBe setOf(99L, 6L)
        next.chapters.map { it.selected } shouldBe listOf(false, true)
    }

    @Test
    fun `stale anchors that no longer resolve do not range-fill unrelated chapters`() {
        val filteredManga = manga.copy(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)
        // Visible: 6, 7. The previous selection long-pressed chapter 9 (a full-list
        // index of 4); with the pre-refactor index anchors, long-pressing 7 at its
        // new index 1 would range-fill 2..<4 and crash on a two-item list.
        val initial = state(
            listOf(
                item(chapter(5L)),
                item(chapter(6L, bookmark = true)),
                item(chapter(7L, bookmark = true)),
                item(chapter(8L)),
                item(chapter(9L), selected = true),
            ),
            manga = filteredManga,
            selectedChapterIds = setOf(9L),
            selectionAnchors = SelectionAnchors(9L, 9L),
        )
        withClue("precondition: only 6 and 7 survive the filter") {
            initial.processedChapters.map { it.id } shouldBe listOf(6L, 7L)
        }

        val next = ChapterSelectionReducer.toggle(
            initial,
            item(chapter(7L)),
            selected = true,
            userSelected = true,
            fromLongPress = true,
        )

        // No range fill of hidden positions; clean first-selection anchors instead.
        next.selectedChapterIds shouldBe setOf(9L, 7L)
        next.selectionAnchors shouldBe SelectionAnchors(7L, 7L)
    }

    @Test
    fun `stampSelection reconciles flags against the latest ids`() {
        val items = listOf(item(chapter(5L), selected = true), item(chapter(6L)))

        val stamped = items.stampSelection(setOf(6L, 7L))

        stamped.map { it.selected } shouldBe listOf(false, true)
    }

    private companion object {
        val injektPrepared = AtomicBoolean(false)
    }
}
