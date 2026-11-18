package eu.kanade.translation.presentation

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The guarded-publication (not saved) chapter truth must not conflate a
 * colorize-only run with translation work: same severity and retry affordance,
 * colorization-named copy.
 */
class TranslationUiTruthNotSavedCopyTest {

    private fun notSavedSnapshot(colorizeOnly: Boolean): TranslationProgressSnapshot =
        TranslationProgressSnapshot.empty(1L)
            .copy(
                state = Translation.State.READY_WITH_WARNINGS,
                nonDurableFailure = true,
                nonDurableFailureReason = "guarded publication rejected",
                colorizeOnly = colorizeOnly,
            )

    @Test
    fun `translation not-saved keeps the translation-named truth`() {
        val decision = TranslationUiTruth.chapterSurfaceDecision(null, notSavedSnapshot(colorizeOnly = false))

        val truth = decision.truth.shouldNotBeNull()
        truth.label shouldBe "Translation not saved — retry required."
        truth.contentDescription shouldBe
            "Translation was produced but could not be saved; no durable result is available. Retry is required."
    }

    @Test
    fun `colorize-only not-saved names colorization without translation conflation`() {
        val decision = TranslationUiTruth.chapterSurfaceDecision(null, notSavedSnapshot(colorizeOnly = true))

        val truth = decision.truth.shouldNotBeNull()
        truth.label shouldBe "Colorization not saved — retry required."
        truth.contentDescription shouldBe
            "Colorization was produced but could not be saved; no durable result is available. Retry is required."
    }
}
