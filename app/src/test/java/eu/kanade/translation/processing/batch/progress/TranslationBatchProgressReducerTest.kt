package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.persistence.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchProgressReducerTest {

    @Test
    fun `stage counts expose exact succeeded failed skipped processed total`() {
        val pages = mapOf(
            "ready" to PageTranslation(ocrStatus = StageStatus.READY),
            "failed" to PageTranslation(ocrStatus = StageStatus.FAILED),
            "skipped" to PageTranslation(ocrStatus = StageStatus.SKIPPED),
            "pending" to PageTranslation(),
        )
        val count = TranslationBatchProgressTracker.computeSnapshot(
            pages,
            eu.kanade.translation.model.Translation.State.TRANSLATING,
        )
            .perStage.getValue(BatchPhase.OCR)

        count.succeeded shouldBe 1
        count.failed shouldBe 1
        count.skipped shouldBe 1
        count.processed shouldBe 3
        count.total shouldBe 4
    }

    @Test
    fun `terminal failure and textless skip are processed terminal work`() {
        val failed = TranslationBatchProgressTracker.computeSnapshot(
            mapOf(
                "page" to
                    PageTranslation(
                        ocrStatus = StageStatus.FAILED,
                        translationStatus = StageStatus.FAILED,
                        inpaintStatus = StageStatus.FAILED,
                        renderStatus = StageStatus.FAILED,
                    ),
            ),
            eu.kanade.translation.model.Translation.State.ERROR,
        )
        failed.fraction shouldBe 1f
        failed.perStage.getValue(BatchPhase.OCR).failed shouldBe 1

        val textless = TranslationBatchProgressTracker.computeSnapshot(
            mapOf(
                "page" to
                    PageTranslation(
                        ocrStatus = StageStatus.READY,
                        translationStatus = StageStatus.SKIPPED,
                        inpaintStatus = StageStatus.SKIPPED,
                        renderStatus = StageStatus.SKIPPED,
                    ),
            ),
            eu.kanade.translation.model.Translation.State.TRANSLATING,
        )
        textless.pages.single().stage shouldBe TranslationProgressStage.DONE
        textless.perStage.getValue(BatchPhase.TRANSLATE).skipped shouldBe 1
    }
}
