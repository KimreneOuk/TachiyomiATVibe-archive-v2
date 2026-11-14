package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationBatchProgressTrackerTest {
    private fun readyPage() = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "target",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    @Test
    fun `rebuild from store publishes durable ready page progress`() = runTest {
        val page = readyPage()
        val store = ChapterTranslationStore(
            initialPages = mapOf("001.jpg" to page),
        )
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg"), this)

        // The total is nonzero immediately — it derives from the
        // ordered work keys at construction, never from an empty store read.
        tracker.snapshot.value.totalPages shouldBe 1

        tracker.rebuildFromStore()
        val snapshot = tracker.snapshot.value

        snapshot.totalPages shouldBe 1
        snapshot.donePages shouldBe 1
        snapshot.pages.single().pageKey shouldBe "001.jpg"
        snapshot.pages.single().processed shouldBe true
        snapshot.pages.single().displayReady shouldBe true
        BatchPhase.entries.forEach { phase ->
            snapshot.perStage.getValue(phase).succeeded shouldBe 1
        }
        tracker.close()
    }

    @Test
    fun `failure groups carry the page's best reason, never a generic unknown`() = runTest {
        // Stranded-page handling: the group key is the page's best available
        // reason (activeError: per-stage errors, then errorMessage) — a failed
        // page whose reason lives in a stage field used to fall into the
        // generic "Unknown error" bucket in the sheet's failure groups.
        val store = ChapterTranslationStore(
            initialPages = mapOf(
                "001.jpg" to PageTranslation(
                    ocrStatus = StageStatus.FAILED,
                    ocrError = "onnx session unavailable",
                ),
                "002.jpg" to PageTranslation(
                    translationStatus = StageStatus.FAILED,
                ).apply {
                    // body var, not a constructor parameter
                    errorMessage = "stranded page reconciled at FINALIZE: no translatable text"
                },
            ),
        )
        val tracker = TranslationBatchProgressTracker(
            1,
            store,
            listOf("001.jpg", "002.jpg"),
            this,
        )
        tracker.rebuildFromStore()

        val groups = tracker.snapshot.value.groupedFailures
        groups.getValue("onnx session unavailable") shouldBe listOf("001.jpg")
        groups.getValue("stranded page reconciled at FINALIZE: no translatable text") shouldBe
            listOf("002.jpg")
        tracker.close()
    }

    @Test
    fun `AI progress rebuild derives durable success and failure without claiming buffered work`() = runTest {
        val store = ChapterTranslationStore(
            initialPages = mapOf(
                "001.jpg" to PageTranslation(translationStatus = StageStatus.READY),
                "002.jpg" to PageTranslation(translationStatus = StageStatus.FAILED),
                "003.jpg" to PageTranslation(),
            ),
        )
        val tracker = TranslationBatchProgressTracker(1, store, listOf("001.jpg", "002.jpg", "003.jpg"), this)

        tracker.rebuildFromStore()

        tracker.snapshot.value.aiSucceededPages shouldBe 1
        tracker.snapshot.value.aiFailedPages shouldBe 1
        tracker.snapshot.value.aiPendingPages shouldBe 1
        tracker.snapshot.value.aiBufferedPages shouldBe 0
        tracker.close()
    }

    @Test
    fun `finish emits immutable terminal snapshot`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(1, store, emptyList(), this)
        tracker.finish(
            ReconciliationResult(
                chapterStatus = Translation.State.TRANSLATED,
                strandedPages = emptyMap(),
                unexpectedPageKeys = emptySet(),
                doneCount = 0,
                failedCount = 0,
                partialCount = 0,
            ),
        )
        runCurrent()
        tracker.snapshot.value.batchPhase shouldBe TranslationBatchPhase.FINISHED
        tracker.snapshot.value.state shouldBe Translation.State.TRANSLATED
    }

    @Test
    fun `terminal snapshot is available after terminal event without waiting for later emissions`() = runTest {
        val store = ChapterTranslationStore()
        val tracker = TranslationBatchProgressTracker(1, store, emptyList(), this)

        tracker.finish(
            ReconciliationResult(Translation.State.TRANSLATED, emptyMap(), emptySet(), 0, 0, 0),
        )

        tracker.awaitTerminalSnapshot().batchPhase shouldBe TranslationBatchPhase.FINISHED
    }
}
