package eu.kanade.presentation.manga.components

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterRowOverflowMenuTest {
    private fun request(phase: TranslationRequestPhase) = TranslationRequestState(chapterId = 1L, phase = phase)

    @Test
    fun `translate starts or retries idle and terminal work`() {
        assertEquals(
            ChapterTranslationAction.START,
            chapterOverflowTranslationAction(Translation.State.NOT_TRANSLATED, null),
        )
        listOf(
            TranslationRequestPhase.DOWNLOAD_FAILED,
            TranslationRequestPhase.ADMISSION_FAILED,
            TranslationRequestPhase.CANCELLED,
        ).forEach { phase ->
            assertEquals(
                ChapterTranslationAction.START,
                chapterOverflowTranslationAction(Translation.State.NOT_TRANSLATED, request(phase)),
            )
        }
        listOf(Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS, Translation.State.ERROR).forEach {
            assertEquals(ChapterTranslationAction.START, chapterOverflowTranslationAction(it, null))
        }
    }

    @Test
    fun `translate opens progress for live or paused work`() {
        listOf(
            TranslationRequestPhase.STARTING,
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
            TranslationRequestPhase.PREPARING,
        ).forEach { phase ->
            assertEquals(
                ChapterTranslationAction.DETAILS,
                chapterOverflowTranslationAction(Translation.State.NOT_TRANSLATED, request(phase)),
            )
        }
        listOf(Translation.State.QUEUE, Translation.State.TRANSLATING, Translation.State.PAUSED).forEach {
            assertEquals(ChapterTranslationAction.DETAILS, chapterOverflowTranslationAction(it, null))
        }
    }

    @Test
    fun `download is enabled only when a download can be started`() {
        assertTrue(chapterOverflowDownloadEnabled(Download.State.NOT_DOWNLOADED))
        assertTrue(chapterOverflowDownloadEnabled(Download.State.ERROR))
        assertFalse(chapterOverflowDownloadEnabled(Download.State.QUEUE))
        assertFalse(chapterOverflowDownloadEnabled(Download.State.DOWNLOADING))
        assertFalse(chapterOverflowDownloadEnabled(Download.State.DOWNLOADED))
    }

    @Test
    fun `row glyph is error for failures, progress for live work, none otherwise`() {
        assertEquals(
            TranslationRowRequestGlyph.ERROR,
            translationRowRequestGlyph(request(TranslationRequestPhase.DOWNLOAD_FAILED)),
        )
        assertEquals(
            TranslationRowRequestGlyph.ERROR,
            translationRowRequestGlyph(request(TranslationRequestPhase.ADMISSION_FAILED)),
        )
        listOf(
            TranslationRequestPhase.STARTING,
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
            TranslationRequestPhase.PREPARING,
        ).forEach { phase ->
            assertEquals(TranslationRowRequestGlyph.PROGRESS, translationRowRequestGlyph(request(phase)))
        }
        assertEquals(
            TranslationRowRequestGlyph.NONE,
            translationRowRequestGlyph(request(TranslationRequestPhase.CANCELLED)),
        )
        assertEquals(TranslationRowRequestGlyph.NONE, translationRowRequestGlyph(null))
    }
}
