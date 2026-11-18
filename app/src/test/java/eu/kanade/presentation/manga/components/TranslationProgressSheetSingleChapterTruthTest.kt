package eu.kanade.presentation.manga.components

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TranslationProgressSheetSingleChapterTruthTest {

    private fun baseSnapshot(
        chapterId: Long = 146L,
        state: Translation.State = Translation.State.TRANSLATING,
        donePages: Int = 12,
        totalPages: Int = 26,
        isTrusted: Boolean = true,
        batchPhase: TranslationBatchPhase = TranslationBatchPhase.FIRST_PASS,
    ): TranslationProgressSnapshot = TranslationProgressSnapshot.empty(chapterId, state)
        .copy(
            donePages = donePages,
            totalPages = totalPages,
            expectedPageCountTrusted = isTrusted,
            batchPhase = batchPhase,
        )

    @Test
    fun `trusted totals produce valid progress percentage and count`() {
        val ring = TranslationProgressSheetProjection.computeRingProjection(
            baseSnapshot(donePages = 12, totalPages = 26, isTrusted = true),
        )

        ring.isIndeterminate shouldBe false
        ring.fraction shouldBe (12f / 26f)
        ring.percentText shouldBe "46%"
        ring.pagesText shouldBe "12 / 26"
    }

    @Test
    fun `untrusted totals render indeterminate without fake percentage`() {
        val ring = TranslationProgressSheetProjection.computeRingProjection(
            baseSnapshot(donePages = 8, totalPages = 22, isTrusted = false),
        )

        ring.isIndeterminate shouldBe true
        ring.percentText.shouldBeNull()
        ring.pagesText shouldBe "8 pages · source total unknown"
    }

    @Test
    fun `untrusted zero pages renders source total unknown`() {
        val ring = TranslationProgressSheetProjection.computeRingProjection(
            baseSnapshot(donePages = 0, totalPages = 0, isTrusted = false),
        )

        ring.isIndeterminate shouldBe true
        ring.percentText.shouldBeNull()
        ring.pagesText shouldBe "Source total unknown"
    }

    @Test
    fun `non-durable failure gates completion and forces not saved banner and chip`() {
        val snapshot = baseSnapshot(state = Translation.State.READY_WITH_WARNINGS)
            .copy(
                batchPhase = TranslationBatchPhase.FINISHED,
                nonDurableFailure = true,
                nonDurableFailureReason = "Storage write failed",
                colorizeOnly = false,
            )

        val banner = TranslationProgressSheetProjection.computeBanner(snapshot, isWifiWaiting = false)
        banner.shouldNotBeNull()
        banner.type shouldBe SheetBannerType.NOT_SAVED
        banner.message shouldBe "Translation not saved — retry required"

        val chip = TranslationProgressSheetProjection.computePhaseChipText(snapshot, isResuming = false)
        chip shouldBe "Translation not saved"
    }

    @Test
    fun `colorize-only non-durable failure specifies colorization in banner and chip`() {
        val snapshot = baseSnapshot(state = Translation.State.READY_WITH_WARNINGS)
            .copy(
                batchPhase = TranslationBatchPhase.FINISHED,
                nonDurableFailure = true,
                colorizeOnly = true,
            )

        val banner = TranslationProgressSheetProjection.computeBanner(snapshot, isWifiWaiting = false)
        banner.shouldNotBeNull()
        banner.message shouldBe "Colorization not saved — retry required"

        val chip = TranslationProgressSheetProjection.computePhaseChipText(snapshot, isResuming = false)
        chip shouldBe "Colorization not saved"
    }

    @Test
    fun `completion oracle permits Completed only when zero pages need attention and no failures exist`() {
        val cleanSnapshot = baseSnapshot(
            state = Translation.State.TRANSLATED,
            donePages = 26,
            totalPages = 26,
            batchPhase = TranslationBatchPhase.FINISHED,
        )

        val chipClean = TranslationProgressSheetProjection.computePhaseChipText(cleanSnapshot, isResuming = false)
        chipClean shouldBe "Completed"

        val failedSnapshot = cleanSnapshot.copy(
            state = Translation.State.ERROR,
            failedCount = 2,
        )
        val chipFailed = TranslationProgressSheetProjection.computePhaseChipText(failedSnapshot, isResuming = false)
        chipFailed shouldBe "Failed"
    }

    @Test
    fun `download waiting and failed states project truthful banners`() {
        val waitingSnapshot = baseSnapshot().copy(
            requestState = TranslationRequestState(
                chapterId = 146L,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
            ),
        )

        val wifiBanner = TranslationProgressSheetProjection.computeBanner(waitingSnapshot, isWifiWaiting = true)
        wifiBanner.shouldNotBeNull()
        wifiBanner.type shouldBe SheetBannerType.WIFI_WAITING
        wifiBanner.message shouldBe "Download waiting for Wi-Fi"

        val waitingBanner = TranslationProgressSheetProjection.computeBanner(waitingSnapshot, isWifiWaiting = false)
        waitingBanner.shouldNotBeNull()
        waitingBanner.type shouldBe SheetBannerType.WAITING_DOWNLOAD
        waitingBanner.message shouldBe "Queued for translation — starts when the download finishes"

        val failedSnapshot = baseSnapshot().copy(
            requestState = TranslationRequestState(
                chapterId = 146L,
                phase = TranslationRequestPhase.DOWNLOAD_FAILED,
                reason = "Network timeout",
            ),
        )
        val failedBanner = TranslationProgressSheetProjection.computeBanner(failedSnapshot, isWifiWaiting = false)
        failedBanner.shouldNotBeNull()
        failedBanner.type shouldBe SheetBannerType.DOWNLOAD_FAILED
        failedBanner.message shouldBe "Download failed — Network timeout"
    }

    @Test
    fun `grouped failures project detail in banner`() {
        val groupedSnapshot = baseSnapshot().copy(
            state = Translation.State.ERROR,
            failedCount = 3,
            groupedFailures = mapOf(
                "OCR timeout" to listOf("001.jpg", "002.jpg"),
                "Rate limit exceeded" to listOf("003.jpg"),
            ),
        )

        val banner = TranslationProgressSheetProjection.computeBanner(groupedSnapshot, isWifiWaiting = false)
        banner.shouldNotBeNull()
        banner.type shouldBe SheetBannerType.FAILED
        banner.message shouldContain "3 pages"
        banner.message shouldContain "OCR timeout"
        banner.message shouldContain "Rate limit exceeded"
    }

    @Test
    fun `telemetry suppresses fake remaining estimate and derives speed`() {
        val snapshotNoRemaining = baseSnapshot().copy(
            elapsedMs = 252_000L, // 4m 12s
            estimatedRemainingMs = null,
            donePages = 12,
        )

        val telemetry1 = TranslationProgressSheetProjection.computeTelemetry(snapshotNoRemaining)
        telemetry1.elapsedText shouldBe "4m 12s"
        telemetry1.remainingText shouldBe "—"
        telemetry1.speedText shouldBe "2.9 pages/min"

        val snapshotWithRemaining = snapshotNoRemaining.copy(
            estimatedRemainingMs = 120_000L,
            pagesPerMinute = 3.5f,
        )
        val telemetry2 = TranslationProgressSheetProjection.computeTelemetry(snapshotWithRemaining)
        telemetry2.remainingText shouldBe "~2m 0s"
        telemetry2.speedText shouldBe "3.5 pages/min"
    }

    @Test
    fun `single chapter summary formats this chapter card correctly`() {
        val summaryRunning = TranslationProgressSheetProjection.computeThisChapterSummary(
            baseSnapshot(donePages = 12, totalPages = 26, isTrusted = true),
            chapterTitle = "Chapter 146",
        )

        summaryRunning.title shouldBe "Chapter 146"
        summaryRunning.statusLabel shouldBe "Translating · Page 13 of 26"
        summaryRunning.percentText shouldBe "46%"
        summaryRunning.isTranslating shouldBe true

        val summaryUntrusted = TranslationProgressSheetProjection.computeThisChapterSummary(
            baseSnapshot(donePages = 5, totalPages = 20, isTrusted = false),
            chapterTitle = "Chapter 146",
        )
        summaryUntrusted.statusLabel shouldBe "Translating · 5 pages"
        summaryUntrusted.percentText.shouldBeNull()
    }

    @Test
    fun `pause requested chip displays pausing and suppresses confirmed paused semantics`() {
        val snapshot = baseSnapshot(state = Translation.State.TRANSLATING).copy(
            activeStages = setOf(TranslationProgressStage.OCR),
        )

        val chipRequested = TranslationProgressSheetProjection.computePhaseChipText(
            snapshot = snapshot,
            isResuming = false,
            isPauseRequested = true,
        )
        chipRequested shouldBe "Pausing..."

        val chipNotRequested = TranslationProgressSheetProjection.computePhaseChipText(
            snapshot = snapshot,
            isResuming = false,
            isPauseRequested = false,
        )
        chipNotRequested shouldContain "Detection & OCR"
    }

    @Test
    fun `terminal state is false when paused or pause reason present`() {
        val snapshotPaused = baseSnapshot(state = Translation.State.PAUSED)
            .copy(batchPhase = TranslationBatchPhase.FINISHED)
        TranslationProgressSheetProjection.isTerminalState(snapshotPaused) shouldBe false

        val snapshotReason = baseSnapshot(state = Translation.State.TRANSLATING)
            .copy(pauseReason = "User paused", batchPhase = TranslationBatchPhase.FINISHED)
        TranslationProgressSheetProjection.isTerminalState(snapshotReason) shouldBe false
    }
}
