package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchProgressReconcilerTest {

    private fun colorizeOnlyPage(key: String, coloringStatus: String) = PageTranslation(
        sourceFileName = key,
        ocrStatus = StageStatus.SKIPPED,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloredFromOriginal = true,
        coloringStatus = coloringStatus,
    )

    @Test
    fun `colorize-only run with isolated coloring failures completes with warnings`() {
        val pages = mapOf(
            "p0" to colorizeOnlyPage("p0", StageStatus.READY),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
            "p2" to colorizeOnlyPage("p2", StageStatus.READY),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1", "p2"),
            activeGeneration = 0L,
            includeColoringFailures = true,
        )

        result.chapterStatus shouldBe Translation.State.READY_WITH_WARNINGS
        result.doneCount shouldBe 2
        result.failedCount shouldBe 1
    }

    @Test
    fun `colorize-only run where nothing colored is a retryable error`() {
        val pages = mapOf(
            "p0" to colorizeOnlyPage("p0", StageStatus.FAILED),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
            includeColoringFailures = true,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.failedCount shouldBe 2
    }

    @Test
    fun `translation lane still reports a clean TRANSLATED chapter without the coloring flag`() {
        // The translation lane's coloring failures intentionally never
        // re-fail a page; without includeColoringFailures the chapter
        // projection must stay TRANSLATED (existing contract).
        val pages = mapOf(
            "p0" to readyPage("p0"),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.TRANSLATED
    }

    private fun readyPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "$key.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun block() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
