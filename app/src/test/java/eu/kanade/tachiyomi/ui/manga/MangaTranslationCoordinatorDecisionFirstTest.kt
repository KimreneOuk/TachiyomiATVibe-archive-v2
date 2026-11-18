package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.coordinator.BatchAdmissionContext
import eu.kanade.translation.coordinator.BatchAdmissionDecision
import eu.kanade.translation.coordinator.BatchQueueResumeResult
import eu.kanade.translation.coordinator.ChapterQueuePreflight
import eu.kanade.translation.coordinator.PartialDownloadChoice
import eu.kanade.translation.model.Translation
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences
import java.util.concurrent.atomic.AtomicReference

/** Decision-first single-chapter start (S2): the readiness probe is read-only
 *  and the request is created exactly once AFTER the user's decision through
 *  the atomic seam; a stale screen never adopts newer work and every guard
 *  entry is reachable blocked/unblocked. Multi-select keeps the ack-first
 *  group flow (active product behavior, not legacy code). */
class MangaTranslationCoordinatorDecisionFirstTest {

    private val manga = Manga.create().copy(id = 1L, title = "Manga", source = 1L, favorite = true)

    private fun chapterFixture(id: Long) = Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id")

    private fun item(id: Long) = ChapterList.Item(
        chapter = chapterFixture(id),
        downloadState = Download.State.DOWNLOADED,
        translationState = Translation.State.NOT_TRANSLATED,
        downloadProgress = 0,
    )

    private fun page(status: Page.State) = Page(0, "u", "iu").apply { this.status = status }

    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    init {
        every { translationManager.pendingTranslationRequests } returns
            MutableStateFlow(emptyMap<Long, eu.kanade.translation.model.TranslationRequestState>())
    }

    private var confirmPretranslate = false
    private var blocked = false
    private val confirmPref = mockk<Preference<Boolean>> {
        every { get() } answers { confirmPretranslate }
    }
    private val modePref = mockk<Preference<String>> {
        every { get() } returns "BALANCE"
    }
    private val neuralModelPref = mockk<Preference<NeuralInpaintModel>> {
        every { get() } returns NeuralInpaintModel.LAMA_LITERT_GPU
    }
    private val translationPreferences = mockk<TranslationPreferences> {
        every { translationInpaintingMode() } returns modePref
        every { translationInpaintingNeuralModel() } returns neuralModelPref
        every { translationConfirmPretranslate() } returns confirmPref
    }

    private val currentState = AtomicReference(
        MangaScreenModel.State.Success(
            manga = manga,
            source = mockk(),
            isFromSource = false,
            chapters = listOf(item(5L), item(6L)),
            availableScanlators = emptySet(),
            excludedScanlators = emptySet(),
        ),
    )

    private val uncaughtExceptions = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    private var capturedUndo: (() -> Unit)? = null

    private fun coordinator() = MangaTranslationCoordinator(
        translationManager = translationManager,
        downloadManager = downloadManager,
        translationPreferences = translationPreferences,
        translationSnapshots = ChapterTranslationSnapshotRegistry(),
        scope = CoroutineScope(
            Dispatchers.Unconfined + CoroutineExceptionHandler { _, throwable -> uncaughtExceptions.add(throwable) },
        ),
        getSuccessState = { currentState.get() },
        updateSuccessState = { reducer -> currentState.updateAndGet(reducer) },
        dismissDialog = { currentState.updateAndGet { it.copy(dialog = null) } },
        observeTranslationProgress = {},
        updateTranslationRequests = {},
        updateTranslationState = {},
        onTranslationCancelled = { onUndo -> capturedUndo = onUndo },
        inpaintStartBlocked = { blocked },
    )

    /** Readiness probes hop to IO; poll until the dialog settles. */
    private fun awaitUntil(what: String = "state", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            uncaughtExceptions.poll()?.let { throw AssertionError("coroutine crashed", it) }
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $what")
            }
            Thread.sleep(5)
        }
    }

    /** launchNonCancellable work lands on IO; poll until the mocked call settles. */
    private fun awaitVerify(what: String, verification: () -> Unit) {
        awaitUntil(what) { runCatching(verification).isSuccess }
        verification()
    }

    /** Chapter 5 on disk with a queued re-download two pages short of four. */
    private fun stubMidDownload() {
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        every { downloadManager.getQueuedDownloadOrNull(5L) } returns mockk {
            every { pages } returns listOf(
                page(Page.State.READY),
                page(Page.State.READY),
                page(Page.State.QUEUE),
                page(Page.State.QUEUE),
            )
        }
    }

    private fun stubReadyDownload() {
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        every { downloadManager.getQueuedDownloadOrNull(5L) } returns null
    }

    private fun stubNeedsDownload() {
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
        every { downloadManager.getQueuedDownloadOrNull(5L) } returns null
    }

    private fun stubRequestSeam(generation: Long?) {
        every { translationManager.beginTranslationRequestIfCurrent(5L, any()) } returns generation
    }

    private fun conflictRun(): Translation = Translation(
        source = mockk(relaxed = true),
        manga = manga,
        chapter = chapterFixture(9L),
    ).also { it.status = Translation.State.TRANSLATING }

    private fun runningConflict(run: Translation = conflictRun()) =
        ChapterQueuePreflight.RunningConflict(9L, "Chapter 9", run, run.runGeneration)

    private fun replaceDialog(
        run: Translation = conflictRun(),
        requestGeneration: Long = 60L,
    ) = MangaScreenModel.Dialog.RunningTranslationConflict(
        item(5L),
        runningConflict(run),
        requestGeneration,
    )

    @Test
    fun `mid-download start shows the decision dialog and creates no request`() {
        val coordinator = coordinator()
        stubMidDownload()

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)

        awaitUntil("decision dialog with readiness") {
            val dialog = currentState.get().dialog as? MangaScreenModel.Dialog.ConfirmTranslation
            val readiness = dialog?.readiness
            readiness is ChapterTranslationReadiness.MidDownload &&
                readiness.downloadedPageCount == 2 &&
                readiness.expectedSourcePageCount == 4
        }
        verify(exactly = 0) { translationManager.beginTranslationRequestIfCurrent(any(), any()) }
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        verify(exactly = 0) { translationManager.queueTranslationAfterDownloadIfCurrent(any(), any(), any()) }
    }

    @Test
    fun `decision dialog dismissal leaves zero ledger records`() {
        val coordinator = coordinator()
        stubMidDownload()
        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)
        awaitUntil { currentState.get().dialog is MangaScreenModel.Dialog.ConfirmTranslation }

        // Back-drop dismiss: the dialog closes with no decision and no request.
        currentState.updateAndGet { it.copy(dialog = null) }

        verify(exactly = 0) { translationManager.beginTranslationRequestIfCurrent(any(), any()) }
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        verify(exactly = 0) { translationManager.cancelTranslationRequestIfCurrent(any(), any()) }
    }

    @Test
    fun `translate-what-exists admits with probe context through the request seam`() {
        val coordinator = coordinator()
        stubMidDownload()
        val expected = slot<Long?>()
        every {
            translationManager.beginTranslationRequestIfCurrent(eq(5L), isNull())
        } answers {
            expected.captured = null
            60L
        }
        val admitted = slot<List<Chapter>>()
        val generations = slot<Map<Long, Long>>()
        val contexts = slot<Map<Long, BatchAdmissionContext>>()
        every {
            translationManager.translateChaptersIfCurrent(manga, capture(admitted), capture(generations), capture(contexts))
        } returns true

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)
        awaitUntil { currentState.get().dialog is MangaScreenModel.Dialog.ConfirmTranslation }
        coordinator.confirmChapterTranslation(item(5L), PartialDownloadChoice.TRANSLATE_WHAT_EXISTS)

        awaitUntil("probe-context admission") { admitted.isCaptured && generations.isCaptured && contexts.isCaptured }
        expected.captured shouldBe null
        verify(exactly = 1) { translationManager.beginTranslationRequestIfCurrent(5L, any()) }
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        admitted.captured.map { it.id } shouldBe listOf(5L)
        generations.captured shouldBe mapOf(5L to 60L)
        contexts.captured shouldBe mapOf(5L to BatchAdmissionContext(4, true))
        awaitUntil("progress drawer selected") {
            currentState.get().dialog is MangaScreenModel.Dialog.TranslationProgress
        }
    }

    @Test
    fun `finish-download-first requests once and queues behind the download`() {
        val coordinator = coordinator()
        stubMidDownload()
        // The enqueue path probes each chapter's queued download for ERROR
        // status; a relaxed Download mock would crash that read.
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        stubRequestSeam(60L)
        every { translationManager.queueTranslationAfterDownloadIfCurrent(manga, any(), any()) } returns true
        var downloadsStarted = false
        every { downloadManager.startDownloads() } answers { downloadsStarted = true }
        val enqueued = slot<List<Chapter>>()
        every { downloadManager.downloadChapters(manga, capture(enqueued), any()) } returns Unit

        coordinator.finishChapterDownloadFirst(item(5L), null)

        awaitUntil("download enqueued and started") { downloadsStarted && enqueued.isCaptured }
        verify(exactly = 1) { translationManager.beginTranslationRequestIfCurrent(5L, any()) }
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        verify(exactly = 1) { translationManager.queueTranslationAfterDownloadIfCurrent(manga, chapterFixture(5L), any()) }
        enqueued.captured.map { it.id } shouldBe listOf(5L)
    }

    @Test
    fun `stale screen aborts instead of adopting a newer request`() {
        val coordinator = coordinator()
        stubMidDownload()
        // The seam reports a newer request appeared since display: null.
        stubRequestSeam(null)

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)
        awaitUntil { currentState.get().dialog is MangaScreenModel.Dialog.ConfirmTranslation }
        coordinator.confirmChapterTranslation(item(5L), PartialDownloadChoice.TRANSLATE_WHAT_EXISTS)

        awaitUntil { uncaughtExceptions.isEmpty() }
        verify(exactly = 1) { translationManager.beginTranslationRequestIfCurrent(5L, any()) }
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        verify(exactly = 0) { translationManager.translateChaptersIfCurrent(any(), any(), any(), any()) }
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
    }

    @Test
    fun `ready chapter with confirm preference admits after preflight on decision`() {
        val coordinator = coordinator()
        confirmPretranslate = true
        stubReadyDownload()
        stubRequestSeam(60L)
        every { translationManager.translateChapterPreflight(manga, any()) } returns ChapterQueuePreflight.NoConflict

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)
        awaitUntil("confirm dialog with ready readiness") {
            (currentState.get().dialog as? MangaScreenModel.Dialog.ConfirmTranslation)
                ?.readiness is ChapterTranslationReadiness.Ready
        }
        coordinator.confirmChapterTranslation(item(5L))

        awaitUntil("single admission after preflight") {
            currentState.get().dialog is MangaScreenModel.Dialog.TranslationProgress
        }
        verify(exactly = 1) { translationManager.beginTranslationRequestIfCurrent(5L, any()) }
        verify(exactly = 1) { translationManager.translateChapterPreflight(manga, chapterFixture(5L)) }
        verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
    }

    @Test
    fun `needs-download start queues behind the download without a decision dialog`() {
        val coordinator = coordinator()
        stubNeedsDownload()
        stubRequestSeam(60L)
        every { translationManager.queueTranslationAfterDownloadIfCurrent(manga, any(), any()) } returns true
        val enqueued = slot<List<Chapter>>()
        every { downloadManager.downloadChapters(manga, capture(enqueued), any()) } returns Unit

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)

        awaitUntil("download enqueued without a decision dialog") {
            enqueued.isCaptured &&
                currentState.get().dialog is MangaScreenModel.Dialog.TranslationProgress
        }
        verify(exactly = 1) { translationManager.beginTranslationRequestIfCurrent(5L, any()) }
        verify(exactly = 1) { translationManager.queueTranslationAfterDownloadIfCurrent(manga, chapterFixture(5L), any()) }
    }

    @Test
    fun `running-conflict preflight does not flash the progress drawer`() {
        val coordinator = coordinator()
        stubReadyDownload()
        stubRequestSeam(60L)
        val run = conflictRun()
        every {
            translationManager.translateChapterPreflight(manga, any())
        } returns runningConflict(run)

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)

        awaitUntil("conflict dialog replaces any drawer") {
            currentState.get().dialog is MangaScreenModel.Dialog.RunningTranslationConflict
        }
        // The drawer opens only after a NoConflict preflight, so the conflict
        // state here is final until the user decides.
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
        (currentState.get().dialog as MangaScreenModel.Dialog.RunningTranslationConflict).requestGeneration shouldBe 60L
    }

    @Test
    fun `blocked replace-running shows the guard and cancels nothing`() {
        val coordinator = coordinator()
        blocked = true
        val run = conflictRun()
        currentState.updateAndGet {
            it.copy(dialog = replaceDialog(run))
        }

        coordinator.confirmReplaceRunningChapter(item(5L))

        (currentState.get().dialog is MangaScreenModel.Dialog.InpaintModelMissing) shouldBe true
        coVerify(exactly = 0) { translationManager.cancelRunningChapterForReplace(any(), any()) }
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
    }

    @Test
    fun `unblocked replace-running cancels the running chapter and starts the replacement`() {
        val coordinator = coordinator()
        val run = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returns true
        coEvery { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) } returns true
        currentState.updateAndGet {
            it.copy(dialog = replaceDialog(run))
        }

        coordinator.confirmReplaceRunningChapter(item(5L))

        awaitVerify("replacement start") {
            verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
        }
        coVerify(exactly = 1) { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) }
        currentState.get().dialog shouldBe null
    }

    @Test
    fun `blocked replace-running completes through the fast-inpainting re-entry`() {
        val coordinator = coordinator()
        blocked = true
        val run = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returns true
        coEvery { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) } returns true
        currentState.updateAndGet {
            it.copy(dialog = replaceDialog(run))
        }

        coordinator.confirmReplaceRunningChapter(item(5L))

        // The guard swapped the dialog; the captured conflict must still
        // drive the replacement once FAST continue fires.
        val guard = (currentState.get().dialog as? MangaScreenModel.Dialog.InpaintModelMissing)
            .shouldNotBeNull()
        // MangaScreenModel.continueTranslationWithFastInpainting order:
        // dismiss, then invoke the continuation.
        currentState.updateAndGet { it.copy(dialog = null) }
        guard.onContinue()

        awaitVerify("replacement start") {
            verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
        }
        coVerify(exactly = 1) { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) }
        currentState.get().dialog shouldBe null
    }

    @Test
    fun `finished conflict is re-preflighted before starting the replacement`() {
        val coordinator = coordinator()
        blocked = true
        val run = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returns true
        coEvery { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) } returns false
        every { translationManager.translateChapterPreflight(manga, chapterFixture(5L)) } returns
            ChapterQueuePreflight.NoConflict
        currentState.updateAndGet { it.copy(dialog = replaceDialog(run)) }

        coordinator.confirmReplaceRunningChapter(item(5L))
        val guard = (currentState.get().dialog as? MangaScreenModel.Dialog.InpaintModelMissing)
            .shouldNotBeNull()
        currentState.updateAndGet { it.copy(dialog = null) }
        guard.onContinue()

        awaitVerify("replacement start") {
            verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
        }
        coVerify(exactly = 1) { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) }
    }

    @Test
    fun `re-entry re-preflights a newer queued conflict without cancelling it`() {
        val coordinator = coordinator()
        blocked = true
        val run = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returns true
        coEvery { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) } returns false
        every { translationManager.translateChapterPreflight(manga, chapterFixture(5L)) } returns
            ChapterQueuePreflight.NoConflict
        currentState.updateAndGet { it.copy(dialog = replaceDialog(run)) }

        coordinator.confirmReplaceRunningChapter(item(5L))
        val guard = (currentState.get().dialog as? MangaScreenModel.Dialog.InpaintModelMissing)
            .shouldNotBeNull()
        currentState.updateAndGet { it.copy(dialog = null) }
        guard.onContinue()

        awaitVerify("replacement start") {
            verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
        }
        coVerify(exactly = 1) { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) }
    }

    @Test
    fun `re-entry shows a newer translating run of the same conflict chapter`() {
        val coordinator = coordinator()
        blocked = true
        val confirmedRun = conflictRun()
        val newerRun = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returns true
        coEvery {
            translationManager.cancelRunningChapterForReplace(confirmedRun, confirmedRun.runGeneration)
        } returns false
        every { translationManager.translateChapterPreflight(manga, chapterFixture(5L)) } returns
            runningConflict(newerRun)
        currentState.updateAndGet { it.copy(dialog = replaceDialog(confirmedRun)) }

        coordinator.confirmReplaceRunningChapter(item(5L))
        val guard = (currentState.get().dialog as? MangaScreenModel.Dialog.InpaintModelMissing)
            .shouldNotBeNull()
        currentState.updateAndGet { it.copy(dialog = null) }
        guard.onContinue()

        // The replace continuation runs on IO; the cancel call and the
        // refreshed-conflict dialog land on that worker, not this thread.
        awaitUntil("refreshed conflict dialog") {
            (currentState.get().dialog as? MangaScreenModel.Dialog.RunningTranslationConflict)
                ?.conflict?.run == newerRun
        }
        coVerify(exactly = 1) {
            translationManager.cancelRunningChapterForReplace(confirmedRun, confirmedRun.runGeneration)
        }
        (currentState.get().dialog as MangaScreenModel.Dialog.RunningTranslationConflict)
            .conflict.run shouldBe newerRun
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
    }

    @Test
    fun `replacement request cancelled while conflict cancellation waits is not admitted`() {
        val coordinator = coordinator()
        blocked = true
        val run = conflictRun()
        every { translationManager.isTranslationRequestCurrent(5L, 60L) } returnsMany listOf(true, false)
        coEvery { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) } returns true
        currentState.updateAndGet { it.copy(dialog = replaceDialog(run)) }

        coordinator.confirmReplaceRunningChapter(item(5L))
        val guard = (currentState.get().dialog as? MangaScreenModel.Dialog.InpaintModelMissing)
            .shouldNotBeNull()
        currentState.updateAndGet { it.copy(dialog = null) }
        guard.onContinue()

        awaitVerify("conflict cancellation") {
            coVerify(exactly = 1) { translationManager.cancelRunningChapterForReplace(run, run.runGeneration) }
        }
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
        verify(exactly = 0) { translationManager.translateChapterPreflight(any(), any()) }
    }

    @Test
    fun `blocked multi finish-download guard admits nothing`() {
        val coordinator = coordinator()
        blocked = true

        coordinator.finishDownloadBeforeTranslation(
            MangaScreenModel.Dialog.PartialDownloadTranslation(
                group = listOf(item(5L), item(6L)),
                decisions = emptyMap(),
                generations = mapOf(5L to 101L, 6L to 102L),
            ),
        )

        (currentState.get().dialog is MangaScreenModel.Dialog.InpaintModelMissing) shouldBe true
        verify(exactly = 0) { translationManager.queueTranslationAfterDownloadIfCurrent(any(), any(), any()) }
        verify(exactly = 0) { downloadManager.downloadChapters(any(), any(), any()) }
    }

    @Test
    fun `unblocked multi translate-what-exists admits fenced generations and contexts`() {
        val coordinator = coordinator()
        every { translationManager.pendingRequestGeneration(5L) } returns 101L
        every { translationManager.pendingRequestGeneration(6L) } returns 102L
        val generations = slot<Map<Long, Long>>()
        val contexts = slot<Map<Long, BatchAdmissionContext>>()
        every {
            translationManager.translateChaptersIfCurrent(manga, any(), capture(generations), capture(contexts))
        } returns true

        coordinator.translatePartialDownloadNow(
            MangaScreenModel.Dialog.PartialDownloadTranslation(
                group = listOf(item(5L), item(6L)),
                decisions = mapOf(
                    5L to BatchAdmissionDecision.Partial(downloadedPageCount = 4, expectedSourcePageCount = 20),
                    6L to BatchAdmissionDecision.UnknownCount,
                ),
                generations = mapOf(5L to 101L, 6L to 102L),
            ),
        )

        awaitUntil("fenced admission") { generations.isCaptured && contexts.isCaptured }
        generations.captured shouldBe mapOf(5L to 101L, 6L to 102L)
        contexts.captured shouldBe mapOf(
            5L to BatchAdmissionContext(20, true),
            6L to BatchAdmissionContext(null, true),
        )
    }

    @Test
    fun `unblocked multi finish-download queues behind downloads`() {
        val coordinator = coordinator()
        every { translationManager.pendingRequestGeneration(any()) } returns 101L
        every { translationManager.queueTranslationAfterDownloadIfCurrent(manga, any(), any()) } returns true
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        var downloadsStarted = false
        every { downloadManager.startDownloads() } answers { downloadsStarted = true }
        val enqueued = slot<List<Chapter>>()
        every { downloadManager.downloadChapters(manga, capture(enqueued), any()) } returns Unit

        coordinator.finishDownloadBeforeTranslation(
            MangaScreenModel.Dialog.PartialDownloadTranslation(
                group = listOf(item(5L), item(6L)),
                decisions = emptyMap(),
                generations = mapOf(5L to 101L, 6L to 102L),
            ),
        )

        awaitUntil("both chapters queued behind downloads") { downloadsStarted && enqueued.isCaptured }
        enqueued.captured.map { it.id } shouldBe listOf(5L, 6L)
    }

    @Test
    fun `stale multi-select group does not leak into a cancel-undo re-request`() {
        val coordinator = coordinator()
        stubReadyDownload()
        stubRequestSeam(60L)
        every { translationManager.translateChapterPreflight(manga, any()) } returns ChapterQueuePreflight.NoConflict
        every { translationManager.cancelTranslationRequest(5L) } returns true
        // Relaxed mocks fabricate a child mock even for nullable returns;
        // a null queued translation is what makes wasPendingRequest true.
        every { translationManager.getQueuedTranslationOrNull(5L) } returns null
        // Protected request: the undo re-enters confirmChapterTranslation,
        // the path that would replay a leaked pendingTranslationGroup.
        every { translationManager.isChapterTranslationProtected(5L) } returns true
        coordinator.pendingTranslationGroup = listOf(item(5L), item(6L))

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.CANCEL)
        val undo = capturedUndo.shouldNotBeNull()
        undo()

        awaitUntil("single undo admission on the cleared group") {
            currentState.get().dialog is MangaScreenModel.Dialog.TranslationProgress
        }
        // The stale [5,6] group must not reappear: only chapter 5 is admitted.
        coordinator.pendingTranslationGroup shouldBe emptyList()
        verify(exactly = 0) { translationManager.acknowledgeTranslationRequests(any()) }
        verify(exactly = 0) { translationManager.translateChaptersIfCurrent(any(), any(), any(), any()) }
        verify { translationManager.translateChapter(manga, chapterFixture(5L), 60L) }
    }

    @Test
    fun `unblocked retry admits and starts translation instead of no-op`() {
        val coordinator = coordinator()

        coordinator.retryBatchTranslation(5L)

        verify { translationManager.translateChapter(manga, chapterFixture(5L)) }
        verify { translationManager.startTranslation() }
    }

    @Test
    fun `unblocked resume requests typed batch resumption instead of no-op`() = runBlocking {
        val resumeStarted = CompletableDeferred<Unit>()
        coEvery { translationManager.resumeBatchQueue() } coAnswers {
            resumeStarted.complete(Unit)
            BatchQueueResumeResult.NoResumableWork
        }
        val coordinator = coordinator()

        coordinator.resumeTranslationQueue()

        withTimeout(5_000) { resumeStarted.await() }
        coVerify(exactly = 1) { translationManager.resumeBatchQueue() }
    }

    @Test
    fun `multi-select keeps the ack-first group flow`() {
        val coordinator = coordinator()
        stubNeedsDownload()
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        every { translationManager.queueTranslationAfterDownloadIfCurrent(manga, any(), any()) } returns true
        val acknowledged = slot<List<Chapter>>()
        every { translationManager.acknowledgeTranslationRequests(capture(acknowledged)) } answers { Unit }
        val enqueued = slot<List<Chapter>>()
        every { downloadManager.downloadChapters(manga, capture(enqueued), any()) } returns Unit
        every { translationManager.pendingRequestGeneration(any()) } returns 55L

        coordinator.runChapterTranslationActions(listOf(item(5L), item(6L)), ChapterTranslationAction.START)

        awaitUntil("group ack-first flow completes") {
            acknowledged.isCaptured && enqueued.isCaptured
        }
        acknowledged.captured.map { it.id } shouldBe listOf(5L, 6L)
        enqueued.captured.map { it.id } shouldBe listOf(5L, 6L)
    }
}
