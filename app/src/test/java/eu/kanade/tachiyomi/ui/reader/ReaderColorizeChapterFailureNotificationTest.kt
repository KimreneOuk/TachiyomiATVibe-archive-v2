package eu.kanade.tachiyomi.ui.reader

import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderColorizeChapterFailureNotificationTest {

    @Test
    fun `fresh colorize-only failure notifies once`() {
        val observedStates = listOf(
            Translation.State.NOT_TRANSLATED,
            Translation.State.QUEUE,
            Translation.State.TRANSLATING,
            Translation.State.ERROR,
            Translation.State.ERROR,
        )

        observedStates.zipWithNext { previous, current ->
            shouldNotifyColorizeOnlyChapterFailure(previous, current, colorizeOnly = true)
        } shouldBe listOf(false, false, true, false)
    }

    @Test
    fun `queued colorize-only failure still notifies when active emission is skipped`() {
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.QUEUE,
            currentState = Translation.State.ERROR,
            colorizeOnly = true,
        ) shouldBe true
    }

    @Test
    fun `restored error and chapter re-entry do not notify`() {
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.NOT_TRANSLATED,
            currentState = Translation.State.ERROR,
            colorizeOnly = true,
        ) shouldBe false
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.ERROR,
            currentState = Translation.State.ERROR,
            colorizeOnly = true,
        ) shouldBe false
    }

    @Test
    fun `standard translation failure does not use colorization notice`() {
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.TRANSLATING,
            currentState = Translation.State.ERROR,
            colorizeOnly = false,
        ) shouldBe false
    }

    @Test
    fun `retry after an error can notify for its new run`() {
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.ERROR,
            currentState = Translation.State.QUEUE,
            colorizeOnly = true,
        ) shouldBe false
        shouldNotifyColorizeOnlyChapterFailure(
            previousState = Translation.State.QUEUE,
            currentState = Translation.State.ERROR,
            colorizeOnly = true,
        ) shouldBe true
    }
}
