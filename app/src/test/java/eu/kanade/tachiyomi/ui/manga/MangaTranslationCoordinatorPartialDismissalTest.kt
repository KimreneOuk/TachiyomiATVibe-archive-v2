package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.coordinator.BatchAdmissionDecision
import eu.kanade.translation.model.Translation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Dismissal of the partial-download decision (back-drop, Cancel, or the
 * model-missing guard replacing it) resolves exactly the acknowledged
 * group's requests, generation-fenced; queued downloads stay untouched.
 */
class MangaTranslationCoordinatorPartialDismissalTest {

    private val manga = Manga.create().copy(id = 1L, title = "Manga", source = 1L, favorite = true)

    private fun chapterFixture(id: Long) = Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id")

    private fun item(id: Long) = ChapterList.Item(
        chapter = chapterFixture(id),
        downloadState = Download.State.QUEUE,
        translationState = Translation.State.NOT_TRANSLATED,
        downloadProgress = 0,
    )

    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    init {
        every { translationManager.pendingTranslationRequests } returns
            MutableStateFlow(emptyMap<Long, eu.kanade.translation.model.TranslationRequestState>())
        every { translationManager.pendingRequestGeneration(any()) } returns 7L
    }

    private var modePrefValue = "BALANCE"
    private var blocked = true
    private val modePref = mockk<Preference<String>> {
        every { get() } answers { modePrefValue }
    }
    private val neuralModelPref = mockk<Preference<NeuralInpaintModel>> {
        every { get() } returns NeuralInpaintModel.LAMA_LITERT_GPU
    }
    private val translationPreferences = mockk<TranslationPreferences> {
        every { translationInpaintingMode() } returns modePref
        every { translationInpaintingNeuralModel() } returns neuralModelPref
        every { translationConfirmPretranslate() } returns mockk {
            every { get() } returns false
        }
    }

    private var currentState = MangaScreenModel.State.Success(
        manga = manga,
        source = mockk(),
        isFromSource = false,
        chapters = listOf(item(5L), item(6L)),
        availableScanlators = emptySet(),
        excludedScanlators = emptySet(),
    )

    private val requestedUpdates = mutableListOf<Map<Long, eu.kanade.translation.model.TranslationRequestState>>()

    private fun coordinator() = MangaTranslationCoordinator(
        translationManager = translationManager,
        downloadManager = downloadManager,
        translationPreferences = translationPreferences,
        translationSnapshots = ChapterTranslationSnapshotRegistry(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        getSuccessState = { currentState },
        updateSuccessState = { reducer -> currentState = reducer(currentState) },
        dismissDialog = { currentState = currentState.copy(dialog = null) },
        observeTranslationProgress = {},
        updateTranslationRequests = { requestedUpdates += it },
        updateTranslationState = {},
        inpaintStartBlocked = { blocked },
    )

    private fun partialDialog() = MangaScreenModel.Dialog.PartialDownloadTranslation(
        group = listOf(item(5L), item(6L)),
        decisions = mapOf(
            5L to BatchAdmissionDecision.Partial(downloadedPageCount = 4, expectedSourcePageCount = 20),
            6L to BatchAdmissionDecision.UnknownCount,
        ),
        generations = mapOf(5L to 101L, 6L to 102L),
    )

    private fun assertGroupCancelledWithCapturedGenerations() {
        verify(exactly = 1) { translationManager.cancelTranslationRequestIfCurrent(5L, 101L) }
        verify(exactly = 1) { translationManager.cancelTranslationRequestIfCurrent(6L, 102L) }
        verify(exactly = 0) { translationManager.cancelTranslationRequest(any()) }
        verify { downloadManager wasNot Called }
        requestedUpdates.size shouldBe 1
    }

    @Test
    fun `dismissing the decision cancels exactly the group's requests generation-fenced`() = runTest {
        val coordinator = coordinator()
        currentState = currentState.copy(dialog = partialDialog())

        coordinator.dismissPartialDownloadDecision(partialDialog())

        assertGroupCancelledWithCapturedGenerations()
        currentState.dialog shouldBe null
    }

    @Test
    fun `stale dialog passes its captured generations, never the newer request's`() = runTest {
        val coordinator = coordinator()
        // A newer request exists now (different generation); the stale dialog
        // must still reference only the generations captured at display time.
        every { translationManager.pendingRequestGeneration(5L) } returns 999L
        every { translationManager.pendingRequestGeneration(6L) } returns 999L

        coordinator.dismissPartialDownloadDecision(partialDialog())

        assertGroupCancelledWithCapturedGenerations()
    }

    @Test
    fun `guard dialog replacing the decision resolves the group on back-drop dismissal`() = runTest {
        val coordinator = coordinator()

        coordinator.finishDownloadBeforeTranslation(partialDialog())

        val guard = currentState.dialog as? MangaScreenModel.Dialog.InpaintModelMissing
        guard.shouldNotBeNull()
        guard!!.onDismiss.shouldNotBeNull()

        guard.onDismiss!!.invoke()

        assertGroupCancelledWithCapturedGenerations()
        currentState.dialog shouldBe null
    }

    @Test
    fun `guard dialog Cancel button routes through the same dismissal as back`() = runTest {
        val coordinator = coordinator()

        coordinator.finishDownloadBeforeTranslation(partialDialog())
        val guard = currentState.dialog as MangaScreenModel.Dialog.InpaintModelMissing

        // MangaScreen's dismissButton and onDismissRequest both call the
        // model function under test with the guard dialog.
        coordinator.dismissInpaintModelMissingDialog(guard)

        assertGroupCancelledWithCapturedGenerations()
        currentState.dialog shouldBe null
    }

    @Test
    fun `guard without a resolver dismisses plainly and cancels nothing`() = runTest {
        val coordinator = coordinator()
        currentState = currentState.copy(
            dialog = MangaScreenModel.Dialog.InpaintModelMissing("BALANCE", "Manga LaMa", onContinue = {}),
        )

        coordinator.dismissInpaintModelMissingDialog(
            currentState.dialog as MangaScreenModel.Dialog.InpaintModelMissing,
        )

        verify(exactly = 0) { translationManager.cancelTranslationRequestIfCurrent(any(), any()) }
        currentState.dialog shouldBe null
    }

    @Test
    fun `continue path of the replaced decision still finishes the download first`() = runTest {
        val coordinator = coordinator()
        val dialog = partialDialog()

        coordinator.finishDownloadBeforeTranslation(dialog)
        modePrefValue = "FAST"
        blocked = false
        (currentState.dialog as MangaScreenModel.Dialog.InpaintModelMissing).onContinue()

        verify(exactly = 0) { translationManager.cancelTranslationRequestIfCurrent(any(), any()) }
        verify(exactly = 1) {
            translationManager.queueTranslationAfterDownloadIfCurrent(manga, chapterFixture(5L), any())
        }
        verify(exactly = 1) {
            translationManager.queueTranslationAfterDownloadIfCurrent(manga, chapterFixture(6L), any())
        }
    }

    @Test
    fun `confirm-path guard has no dismissal resolution because nothing is acknowledged yet`() = runTest {
        val coordinator = coordinator()

        coordinator.runChapterTranslationActions(listOf(item(5L)), ChapterTranslationAction.START)

        val guard = currentState.dialog as? MangaScreenModel.Dialog.InpaintModelMissing
        checkNotNull(guard) { "model-missing guard should show for a blocked start" }
        guard.onDismiss.shouldBeNull()
        verify(exactly = 0) { translationManager.cancelTranslationRequestIfCurrent(any(), any()) }
    }
}
