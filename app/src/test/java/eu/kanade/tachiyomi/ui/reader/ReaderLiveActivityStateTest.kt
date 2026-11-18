package eu.kanade.tachiyomi.ui.reader

import eu.kanade.presentation.reader.components.ReaderActivityPulse
import eu.kanade.presentation.reader.components.ReaderLiveActivityAction
import eu.kanade.presentation.reader.components.ReaderLiveActivityFailureGroup
import eu.kanade.presentation.reader.components.deriveReaderLiveActivityState
import eu.kanade.translation.coordinator.BatchQueueResumeResult
import eu.kanade.translation.coordinator.SessionRejection
import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.presentation.BatchStatusLineKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.at.ATMR

/**
 * TachiyomiAT: projection rules for the reader live activity card and dock
 * LED. Pulse priority, honest totals (untrusted page counts never render a
 * percentage) and the single-contextual-action mapping are pinned here.
 */
class ReaderLiveActivityStateTest {

    private fun snapshot(
        state: Translation.State,
        pages: List<TranslationProgressSnapshot.Page> = emptyList(),
        colorizeOnly: Boolean = false,
        failedCount: Int = 0,
        queuePosition: Int? = null,
        queueTotal: Int? = null,
        nonDurableFailure: Boolean = false,
        batchPhase: TranslationBatchPhase = when (state) {
            Translation.State.TRANSLATED,
            Translation.State.READY_WITH_WARNINGS,
            Translation.State.ERROR,
            Translation.State.PAUSED,
            -> TranslationBatchPhase.FINISHED
            else -> TranslationBatchPhase.FIRST_PASS
        },
        groupedFailures: Map<String, List<String>> = emptyMap(),
        requestState: TranslationRequestState? = null,
        pauseReason: String? = null,
        nextEligibleRetryAtEpochMs: Long? = null,
        aborted: Boolean = false,
        abortedReason: String? = null,
        rebuildProgress: BatchRebuildProgress? = null,
        elapsedMs: Long = 0L,
        activePage: Int = 0,
        expectedPageCountTrusted: Boolean = pages.isNotEmpty(),
    ) = TranslationProgressSnapshot(
        chapterId = 42L,
        state = state,
        donePages = pages.count { it.stage == TranslationProgressStage.DONE },
        totalPages = pages.size,
        activePage = activePage,
        activePageKey = null,
        queuedCount = pages.count { it.stage == TranslationProgressStage.QUEUED },
        failedCount = failedCount,
        pages = pages,
        queuePosition = queuePosition,
        queueTotal = queueTotal,
        colorizeOnly = colorizeOnly,
        nonDurableFailure = nonDurableFailure,
        expectedPageCountTrusted = expectedPageCountTrusted,
        batchPhase = batchPhase,
        groupedFailures = groupedFailures,
        requestState = requestState,
        pauseReason = pauseReason,
        nextEligibleRetryAtEpochMs = nextEligibleRetryAtEpochMs,
        aborted = aborted,
        abortedReason = abortedReason,
        rebuildProgress = rebuildProgress,
        elapsedMs = elapsedMs,
    )

    private fun page(
        index: Int,
        stage: TranslationProgressStage,
        displayReady: Boolean = stage == TranslationProgressStage.DONE,
        partial: Boolean = false,
    ) = TranslationProgressSnapshot.Page(
        pageKey = "p$index",
        index = index,
        stage = stage,
        displayReady = displayReady,
        partial = partial,
    )

    @Test
    fun `no batch session reads idle with no action`() {
        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, null, isBatchSession = false)
        derived.pulse shouldBe ReaderActivityPulse.IDLE
        derived.action shouldBe null
    }

    @Test
    fun `running chapter with live pages projects running with pause action`() {
        val pages = listOf(
            page(1, TranslationProgressStage.DONE),
            page(2, TranslationProgressStage.TRANSLATE),
            page(3, TranslationProgressStage.QUEUED),
        )
        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, snapshot(Translation.State.TRANSLATING, pages), true)
        derived.pulse shouldBe ReaderActivityPulse.RUNNING
        derived.action shouldBe ReaderLiveActivityAction.Pause
        derived.focusStage shouldBe TranslationPillStage.TRANSLATING
        derived.focusPageNumber shouldBe 2
    }

    @Test
    fun `paused chapter maps to a chapter-scoped batch resume action`() {
        val pages = listOf(
            page(1, TranslationProgressStage.DONE),
            page(2, TranslationProgressStage.TRANSLATE),
        )
        val derived = deriveReaderLiveActivityState(Translation.State.PAUSED, snapshot(Translation.State.PAUSED, pages), true)
        derived.pulse shouldBe ReaderActivityPulse.PAUSED
        derived.action shouldBe ReaderLiveActivityAction.Resume(chapterId = 42L)
    }

    @Test
    fun `queued chapter maps to cancel with chapter id`() {
        val derived = deriveReaderLiveActivityState(
            Translation.State.QUEUE,
            snapshot(Translation.State.QUEUE, queuePosition = 2, queueTotal = 3),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.QUEUED
        derived.action shouldBe ReaderLiveActivityAction.CancelQueued(42L)
    }

    @Test
    fun `waiting for chapter download stays queued and keeps the request status`() {
        val request = TranslationRequestState(
            chapterId = 42L,
            phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
        )

        val derived = deriveReaderLiveActivityState(
            Translation.State.QUEUE,
            snapshot(Translation.State.QUEUE, requestState = request),
            true,
        )

        derived.pulse shouldBe ReaderActivityPulse.QUEUED
        derived.statusLine?.kind shouldBe BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD
        derived.action shouldBe ReaderLiveActivityAction.CancelQueued(42L)
    }

    @Test
    fun `halted batch with failures and nothing running reads attention without an unverified retry`() {
        val pages = listOf(
            page(1, TranslationProgressStage.DONE),
            page(2, TranslationProgressStage.FAILED),
            page(3, TranslationProgressStage.QUEUED),
        )
        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, snapshot(Translation.State.TRANSLATING, pages, failedCount = 1), true)
        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.action shouldBe null
    }

    @Test
    fun `durable error does not expose retry without a reader controller path`() {
        val snapshot = snapshot(
            state = Translation.State.ERROR,
            pages = listOf(page(1, TranslationProgressStage.FAILED)),
            failedCount = 1,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.ERROR, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.action shouldBe null
    }

    @Test
    fun `terminal success reads done without an action`() {
        val pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.DONE))
        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATED, snapshot(Translation.State.TRANSLATED, pages), true)
        derived.pulse shouldBe ReaderActivityPulse.DONE
        derived.action shouldBe null
    }

    @Test
    fun `completed batch without readable output does not claim chapter ready`() {
        val derived = deriveReaderLiveActivityState(
            Translation.State.TRANSLATED,
            snapshot(Translation.State.TRANSLATED),
            true,
        )

        derived.pulse shouldBe ReaderActivityPulse.DONE
        derived.canReadTranslated shouldBe false
        derived.titleRes shouldBe ATMR.strings.manga_batch_phase_completed
    }

    @Test
    fun `non-durable result never reads done and demands retry instead`() {
        val pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.DONE))
        val derived = deriveReaderLiveActivityState(
            Translation.State.READY_WITH_WARNINGS,
            snapshot(Translation.State.READY_WITH_WARNINGS, pages, nonDurableFailure = true),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.action shouldBe ReaderLiveActivityAction.Retry(chapterId = 42L)
        derived.titleRes shouldBe ATMR.strings.reader_activity_not_saved
        derived.notSaved shouldBe true
    }

    @Test
    fun `non-durable queued chapter stays queued with cancel`() {
        val derived = deriveReaderLiveActivityState(
            Translation.State.QUEUE,
            snapshot(Translation.State.QUEUE, queuePosition = 1, queueTotal = 2, nonDurableFailure = true),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.QUEUED
        derived.action shouldBe ReaderLiveActivityAction.CancelQueued(42L)
    }

    @Test
    fun `partial warning is attention and never reads as completion`() {
        val pages = listOf(
            page(1, TranslationProgressStage.DONE),
            page(2, TranslationProgressStage.DONE, partial = true),
        )
        val derived = deriveReaderLiveActivityState(
            Translation.State.READY_WITH_WARNINGS,
            snapshot(Translation.State.READY_WITH_WARNINGS, pages),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.action shouldBe null
        derived.notSaved shouldBe false
        derived.attentionCount shouldBe 1
    }

    @Test
    fun `non-durable colorize-only result uses coloring wording`() {
        val pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.DONE))
        val derived = deriveReaderLiveActivityState(
            Translation.State.READY_WITH_WARNINGS,
            snapshot(Translation.State.READY_WITH_WARNINGS, pages, colorizeOnly = true, nonDurableFailure = true),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.action shouldBe ReaderLiveActivityAction.Retry(chapterId = 42L)
        derived.titleRes shouldBe ATMR.strings.reader_activity_coloring_not_saved
    }

    @Test
    fun `non-durable paused chapter reads paused`() {
        val derived = deriveReaderLiveActivityState(
            Translation.State.PAUSED,
            snapshot(Translation.State.PAUSED, nonDurableFailure = true),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.PAUSED
        derived.action shouldBe ReaderLiveActivityAction.Resume(chapterId = 42L)
    }

    @Test
    fun `colorize-only run suppresses pipeline stage chips`() {
        val pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.COLORING))
        val derived = deriveReaderLiveActivityState(
            Translation.State.TRANSLATING,
            snapshot(Translation.State.TRANSLATING, pages, colorizeOnly = true),
            true,
        )
        derived.pulse shouldBe ReaderActivityPulse.RUNNING
        derived.stageChips shouldBe emptyList()
    }

    @Test
    fun `stage chips follow the snapshot per-phase counts in pipeline order`() {
        val pages = listOf(page(1, TranslationProgressStage.DONE))
        val base = snapshot(Translation.State.TRANSLATING, pages)
        val withStages = base.copy(
            perStage = mapOf(
                BatchPhase.TRANSLATE to eu.kanade.translation.model.StageCount(succeeded = 1, failed = 0, skipped = 0, total = 3),
                BatchPhase.OCR to eu.kanade.translation.model.StageCount(succeeded = 3, failed = 0, skipped = 0, total = 3),
            ),
        )
        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, withStages, true)
        derived.stageChips.map { it.phase } shouldBe listOf(BatchPhase.OCR, BatchPhase.TRANSLATE)
    }

    @Test
    fun `retry wait uses the shared status line and actual retry timestamp`() {
        val snapshot = snapshot(
            state = Translation.State.PAUSED,
            pauseReason = "Provider cooldown",
            nextEligibleRetryAtEpochMs = System.currentTimeMillis() + 60_000L,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.PAUSED, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.PAUSED
        derived.statusLine?.kind shouldBe BatchStatusLineKind.PAUSED
        derived.statusLine?.fallback?.contains("retry after") shouldBe true
        derived.action shouldBe ReaderLiveActivityAction.Resume(chapterId = 42L)
    }

    @Test
    fun `resume feedback preserves pause-unwinding and retry deadlines`() {
        val retryAt = 1_800_000_000_000L

        BatchQueueResumeResult.PauseUnwinding.toReaderBatchResumeFeedback() shouldBe
            ReaderBatchResumeFeedback.PauseUnwinding
        BatchQueueResumeResult.RetryDeferred(42L, retryAt).toReaderBatchResumeFeedback() shouldBe
            ReaderBatchResumeFeedback.RetryDeferred(retryAt)
        BatchQueueResumeResult.BatchRetryDeferred(mapOf(42L to retryAt)).toReaderBatchResumeFeedback() shouldBe
            ReaderBatchResumeFeedback.RetryDeferred(retryAt)
    }

    @Test
    fun `resume feedback rejects stale and session-conflicting chapter work`() {
        BatchQueueResumeResult.StaleQueueItem(42L).toReaderBatchResumeFeedback() shouldBe
            ReaderBatchResumeFeedback.Rejected
        BatchQueueResumeResult.SessionRejected(SessionRejection.READER_ACTIVE).toReaderBatchResumeFeedback() shouldBe
            ReaderBatchResumeFeedback.Rejected
    }

    @Test
    fun `building context uses status line when active page is queued`() {
        val pages = listOf(page(1, TranslationProgressStage.QUEUED))
        val snapshot = snapshot(
            state = Translation.State.TRANSLATING,
            pages = pages,
            batchPhase = TranslationBatchPhase.IDLE,
            activePage = 1,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.RUNNING
        derived.statusLine?.kind shouldBe BatchStatusLineKind.BUILDING_CONTEXT
        derived.focusStage shouldBe TranslationPillStage.QUEUED
        derived.focusPageNumber shouldBe 1
    }

    @Test
    fun `untrusted page count does not project trusted total`() {
        val snapshot = snapshot(
            state = Translation.State.TRANSLATING,
            pages = listOf(page(1, TranslationProgressStage.TRANSLATE)),
            expectedPageCountTrusted = false,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, snapshot, true)

        derived.trustedTotal shouldBe false
    }

    @Test
    fun `terminal request failure keeps its typed status and has no unverified retry action`() {
        val request = TranslationRequestState(
            chapterId = 42L,
            phase = TranslationRequestPhase.DOWNLOAD_FAILED,
            reason = "Storage unavailable",
            failureKind = TranslationRequestFailureKind.STORAGE,
        )
        val snapshot = snapshot(Translation.State.ERROR, requestState = request)

        val derived = deriveReaderLiveActivityState(Translation.State.ERROR, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED
        derived.statusLine?.fallback?.contains("Storage unavailable") shouldBe true
        derived.action shouldBe null
    }

    @Test
    fun `terminal request failure cannot borrow the non-durable warning retry`() {
        val request = TranslationRequestState(
            chapterId = 42L,
            phase = TranslationRequestPhase.DOWNLOAD_FAILED,
            reason = "Storage unavailable",
            failureKind = TranslationRequestFailureKind.STORAGE,
        )
        val snapshot = snapshot(
            Translation.State.READY_WITH_WARNINGS,
            nonDurableFailure = true,
            requestState = request,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.READY_WITH_WARNINGS, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED
        derived.titleRes shouldBe ATMR.strings.reader_activity_attention
        derived.action shouldBe null
    }

    @Test
    fun `non-durable retry requires both reader and snapshot warning states`() {
        val warningSnapshot = snapshot(
            Translation.State.READY_WITH_WARNINGS,
            nonDurableFailure = true,
        )
        val snapshotDisagrees = deriveReaderLiveActivityState(
            Translation.State.READY_WITH_WARNINGS,
            warningSnapshot.copy(state = Translation.State.ERROR),
            true,
        )
        val readerDisagrees = deriveReaderLiveActivityState(
            Translation.State.ERROR,
            warningSnapshot,
            true,
        )

        snapshotDisagrees.action shouldBe null
        readerDisagrees.action shouldBe null
    }

    @Test
    fun `aborted non-durable warning does not expose retry`() {
        val snapshot = snapshot(
            Translation.State.READY_WITH_WARNINGS,
            nonDurableFailure = true,
            aborted = true,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.READY_WITH_WARNINGS, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.ATTENTION_REQUIRED
        derived.titleRes shouldBe ATMR.strings.reader_activity_attention
        derived.action shouldBe null
    }

    @Test
    fun `cancelled request is shown as terminal status without retry`() {
        val request = TranslationRequestState(
            chapterId = 42L,
            phase = TranslationRequestPhase.CANCELLED,
            failureKind = TranslationRequestFailureKind.CANCELLED,
        )
        val snapshot = snapshot(Translation.State.NOT_TRANSLATED, requestState = request)

        val derived = deriveReaderLiveActivityState(Translation.State.NOT_TRANSLATED, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.REQUEST_CANCELLED
        derived.action shouldBe null
    }

    @Test
    fun `terminal request status takes priority over a stale pause`() {
        val request = TranslationRequestState(
            chapterId = 42L,
            phase = TranslationRequestPhase.CANCELLED,
            failureKind = TranslationRequestFailureKind.CANCELLED,
        )
        val snapshot = snapshot(
            Translation.State.PAUSED,
            requestState = request,
            pauseReason = "Provider cooldown",
        )

        val derived = deriveReaderLiveActivityState(Translation.State.PAUSED, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.REQUEST_CANCELLED
        derived.action shouldBe null
    }

    @Test
    fun `aborted batch is attention and cannot read as success`() {
        val snapshot = snapshot(
            state = Translation.State.ERROR,
            aborted = true,
            abortedReason = "Translation failed unexpectedly",
        )

        val derived = deriveReaderLiveActivityState(Translation.State.ERROR, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.ATTENTION
        derived.statusLine?.kind shouldBe BatchStatusLineKind.ATTENTION_REQUIRED
        derived.action shouldBe null
    }

    @Test
    fun `grouped failure reasons are summarized by page count`() {
        val snapshot = snapshot(
            state = Translation.State.ERROR,
            failedCount = 3,
            groupedFailures = mapOf(
                "OCR timeout" to listOf("p1"),
                "Rate limit exceeded" to listOf("p2", "p3"),
                "Source unavailable" to listOf("p4"),
            ),
        )

        val derived = deriveReaderLiveActivityState(Translation.State.ERROR, snapshot, true)

        derived.failureGroups shouldBe listOf(
            ReaderLiveActivityFailureGroup("Rate limit exceeded", 2),
            ReaderLiveActivityFailureGroup("OCR timeout", 1),
        )
        derived.hiddenFailureGroupCount shouldBe 1
    }

    @Test
    fun `restore progress is projected from the actual rebuild counters`() {
        val snapshot = snapshot(
            state = Translation.State.TRANSLATING,
            pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.QUEUED)),
            batchPhase = TranslationBatchPhase.RESTORING,
            rebuildProgress = BatchRebuildProgress(restoredPages = 2, totalPages = 5),
        )

        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATING, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.RUNNING
        derived.focusStage shouldBe TranslationPillStage.PREPARING
        derived.statusLine?.kind shouldBe BatchStatusLineKind.RESTORING
        derived.rebuildProgress shouldBe BatchRebuildProgress(restoredPages = 2, totalPages = 5)
        derived.stageChips shouldBe emptyList()
    }

    @Test
    fun `readable page count and elapsed time use snapshot values`() {
        val snapshot = snapshot(
            state = Translation.State.TRANSLATED,
            pages = listOf(page(1, TranslationProgressStage.DONE), page(2, TranslationProgressStage.DONE)),
            elapsedMs = 65_000L,
        )

        val derived = deriveReaderLiveActivityState(Translation.State.TRANSLATED, snapshot, true)

        derived.pulse shouldBe ReaderActivityPulse.DONE
        derived.canReadTranslated shouldBe true
        derived.readablePages shouldBe 2
        derived.elapsedMs shouldBe 65_000L
    }
}
