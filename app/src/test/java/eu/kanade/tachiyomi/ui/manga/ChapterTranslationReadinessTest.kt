package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The readiness classifier is pure download-state reading (condition: the
 * S2 probe is strictly read-only). It never consults or writes ledger state.
 */
class ChapterTranslationReadinessTest {

    private fun pages(vararg states: Page.State): List<Page> =
        states.mapIndexed { index, state -> Page(index, "u$index", "iu$index").apply { status = state } }

    @Test
    fun `downloaded chapter without a queued download is ready`() {
        chapterTranslationReadiness(isDownloaded = true, queuedDownloadPages = null) shouldBe
            ChapterTranslationReadiness.Ready
    }

    @Test
    fun `downloaded chapter with a complete queued download is ready`() {
        chapterTranslationReadiness(
            isDownloaded = true,
            queuedDownloadPages = pages(Page.State.READY, Page.State.READY),
        ) shouldBe ChapterTranslationReadiness.Ready
    }

    @Test
    fun `downloaded chapter with a partial queued download is mid-download with honest counts`() {
        chapterTranslationReadiness(
            isDownloaded = true,
            queuedDownloadPages = pages(Page.State.READY, Page.State.READY, Page.State.QUEUE, Page.State.QUEUE),
        ) shouldBe ChapterTranslationReadiness.MidDownload(downloadedPageCount = 2, expectedSourcePageCount = 4)
    }

    @Test
    fun `downloaded chapter with an unknown-count queued download is mid-download without totals`() {
        chapterTranslationReadiness(
            isDownloaded = true,
            queuedDownloadPages = emptyList(),
        ) shouldBe ChapterTranslationReadiness.MidDownload(downloadedPageCount = 0, expectedSourcePageCount = null)
    }

    @Test
    fun `undownloaded chapter needs a download regardless of queue state`() {
        chapterTranslationReadiness(isDownloaded = false, queuedDownloadPages = null) shouldBe
            ChapterTranslationReadiness.NeedsDownload
        chapterTranslationReadiness(
            isDownloaded = false,
            queuedDownloadPages = pages(Page.State.QUEUE),
        ) shouldBe ChapterTranslationReadiness.NeedsDownload
    }
}
