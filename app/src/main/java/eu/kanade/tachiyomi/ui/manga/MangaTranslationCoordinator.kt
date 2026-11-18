package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.Dialog
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.State
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.coordinator.BatchAdmissionContext
import eu.kanade.translation.coordinator.BatchAdmissionDecision
import eu.kanade.translation.coordinator.BatchAdmissionProbe
import eu.kanade.translation.coordinator.BatchQueueResumeResult
import eu.kanade.translation.coordinator.ChapterQueuePreflight
import eu.kanade.translation.coordinator.PartialDownloadChoice
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.litert.LiteRtInpaintingAssetManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.ChapterResetReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences

/** Coordinates translation batching, acknowledgement, confirmation, and resets for MangaScreenModel. */
internal class MangaTranslationCoordinator(
    private val translationManager: TranslationManager,
    private val downloadManager: DownloadManager,
    private val translationPreferences: TranslationPreferences,
    private val translationSnapshots: ChapterTranslationSnapshotRegistry,
    private val scope: CoroutineScope,
    private val getSuccessState: () -> State.Success?,
    private val updateSuccessState: ((State.Success) -> State.Success) -> Unit,
    private val dismissDialog: () -> Unit,
    private val observeTranslationProgress: (Long) -> Unit,
    private val updateTranslationRequests: (Map<Long, TranslationRequestState>) -> Unit,
    private val updateTranslationState: (Translation) -> Unit,
    private val onTranslationsQueuedBehindDownloads: () -> Unit = {},
    private val onTranslationCancelled: (onUndo: () -> Unit) -> Unit = {},
    private val onTranslationResumePaused: () -> Unit = {},
    private val inpaintStartBlocked: () -> Boolean = { false },
) {
    internal var pendingTranslationGroup: List<ChapterList.Item> = emptyList()

    /**
     * True when the start may proceed; otherwise shows the model-missing
     * dialog whose continue re-runs [continueTranslation] after a FAST
     * switch. [onDismiss] resolves an acknowledged group when dismissed.
     */
    private fun ensureInpaintModelAvailable(
        onDismiss: (() -> Unit)? = null,
        continueTranslation: () -> Unit,
    ): Boolean {
        if (!inpaintStartBlocked()) return true
        val modeName = InpaintingMode.fromPref(translationPreferences.translationInpaintingMode().get()).name
        val modelName = LiteRtInpaintingAssetManager.displayNameFor(
            translationPreferences.translationInpaintingNeuralModel().get(),
        )
        updateSuccessState {
            it.copy(dialog = Dialog.InpaintModelMissing(modeName, modelName, continueTranslation, onDismiss))
        }
        return false
    }

    fun runChapterTranslationActions(
        item: ChapterList.Item,
        action: ChapterTranslationAction,
    ) {
        runChapterTranslationActions(listOf(item), action)
    }

    private fun openTranslationProgressDrawer(item: ChapterList.Item) {
        val chapterId = item.chapter.id ?: return
        observeTranslationProgress(chapterId)
        updateSuccessState { it.copy(dialog = Dialog.TranslationProgress(chapterId)) }
    }

    fun runChapterTranslationActions(
        items: List<ChapterList.Item>,
        action: ChapterTranslationAction,
    ) {
        if (items.isEmpty()) return
        val item = items.first()
        // A new user action invalidates a previous START's pending group, so
        // a dismissed confirm dialog can never leak its selection into a
        // later non-START re-entry.
        pendingTranslationGroup = if (action == ChapterTranslationAction.START) items else emptyList()
        when (action) {
            ChapterTranslationAction.START -> {
                if (items.size == 1) {
                    startChapterTranslation(items.first())
                } else if (translationPreferences.translationConfirmPretranslate().get()) {
                    showConfirmTranslationDialog(items)
                } else {
                    confirmChapterTranslation(item)
                }
            }
            ChapterTranslationAction.DETAILS -> openTranslationProgressDrawer(item)
            ChapterTranslationAction.CANCEL -> {
                items.forEach { targetItem ->
                    val activeTranslation = translationManager.getQueuedTranslationOrNull(targetItem.chapter.id)
                    val wasPendingRequest = activeTranslation == null &&
                        translationManager.isChapterTranslationProtected(targetItem.chapter.id)
                    if (activeTranslation != null) {
                        translationManager.cancelQueuedTranslation(activeTranslation)
                        updateTranslationState(activeTranslation.apply { status = Translation.State.NOT_TRANSLATED })
                    } else if (!translationManager.cancelTranslationRequest(targetItem.chapter.id)) {
                        return@forEach
                    } else {
                        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
                    }
                    val manga = getSuccessState()?.manga
                    onTranslationCancelled {
                        if (wasPendingRequest) {
                            confirmChapterTranslation(targetItem)
                        } else if (
                            manga != null &&
                            ensureInpaintModelAvailable {
                                translationManager.translateChapter(manga, targetItem.chapter)
                            }
                        ) {
                            translationManager.translateChapter(manga, targetItem.chapter)
                        }
                    }
                }
            }
            ChapterTranslationAction.DELETE -> showChapterResetDialog(item)
        }
    }

    fun retryBatchTranslation(chapterId: Long) {
        val manga = getSuccessState()?.manga
        val item = getSuccessState()?.chapters?.firstOrNull { it.id == chapterId }
        if (manga == null || item == null) {
            logcat(LogPriority.WARN) { "TachiyomiAT batch retry aborted: chapter=$chapterId missing from screen state" }
            return
        }
        if (!ensureInpaintModelAvailable { retryBatchTranslation(chapterId) }) return
        translationManager.translateChapter(manga, item.chapter)
        // Retry used to be a silent no-op whenever admission or the queue
        // rejected the chapter; surface the resulting queue state.
        val queued = translationManager.getQueuedTranslationOrNull(chapterId)
        logcat(LogPriority.INFO) {
            "TachiyomiAT batch retry chapter=${item.chapter.name} queueStatus=${queued?.status ?: "not-enqueued"}"
        }
        translationManager.startTranslation()
    }

    fun showChapterResetDialog(item: ChapterList.Item) {
        updateSuccessState { it.copy(dialog = Dialog.ChapterReset(item)) }
    }

    fun resetChapter(item: ChapterList.Item) = runChapterReset(item) {
        deleteTranslation(item.chapter, it.manga, it.source)
    }

    private fun runChapterReset(
        item: ChapterList.Item,
        action: suspend TranslationManager.(State.Success) -> ChapterResetReceipt,
    ) {
        scope.launchNonCancellable {
            try {
                val state = getSuccessState() ?: return@launchNonCancellable
                val receipt = action(translationManager, state)
                if (!receipt.completed) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT chapter reset incomplete: chapter=${item.chapter.name} failures=${receipt.failures}"
                    }
                    return@launchNonCancellable
                }
                item.chapter.id?.let(translationSnapshots::forget)
                updateSuccessState { current ->
                    current.updateChapterItem(item.chapter.id) { it.copy(translationState = Translation.State.NOT_TRANSLATED) }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun showConfirmTranslationDialog(item: ChapterList.Item) {
        showConfirmTranslationDialog(listOf(item))
    }

    fun showConfirmTranslationDialog(items: List<ChapterList.Item>) {
        val primary = items.first()
        updateSuccessState {
            it.copy(dialog = Dialog.ConfirmTranslation(primary, items))
        }
    }

    /**
     * Decision-first single-chapter start: compute the readiness read-only,
     * present the choices, and acknowledge only after the user picks one.
     * The generation at display time rides in the dialog so a stale
     * callback can never adopt newer work.
     */
    fun startChapterTranslation(item: ChapterList.Item) {
        val manga = getSuccessState()?.manga ?: return
        if (!ensureInpaintModelAvailable { startChapterTranslation(item) }) return
        scope.launch {
            val displayedGeneration = item.chapter.id?.let(translationManager::pendingRequestGeneration)
            val readiness = withIOContext {
                chapterTranslationReadiness(
                    isDownloaded = downloadManager.isChapterDownloaded(
                        item.chapter.name,
                        item.chapter.scanlator,
                        manga.title,
                        manga.source,
                        skipCache = true,
                    ),
                    queuedDownloadPages = item.chapter.id
                        ?.let(downloadManager::getQueuedDownloadOrNull)?.pages,
                )
            }
            if (readiness is ChapterTranslationReadiness.MidDownload ||
                translationPreferences.translationConfirmPretranslate().get()
            ) {
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.ConfirmTranslation(item, listOf(item), readiness, displayedGeneration),
                    )
                }
            } else {
                proceedChapterTranslation(manga, item, PartialDownloadChoice.TRANSLATE_WHAT_EXISTS, displayedGeneration)
            }
        }
    }

    /** "Finish download first" from the decision dialog: acknowledge once, wait behind the download. */
    fun finishChapterDownloadFirst(item: ChapterList.Item, expectedGeneration: Long?) {
        val manga = getSuccessState()?.manga ?: return
        if (!ensureInpaintModelAvailable { finishChapterDownloadFirst(item, expectedGeneration) }) return
        pendingTranslationGroup = emptyList()
        proceedChapterTranslation(manga, item, PartialDownloadChoice.FINISH_DOWNLOAD_FIRST, expectedGeneration)
    }

    fun confirmChapterTranslation(
        item: ChapterList.Item,
        decision: PartialDownloadChoice = PartialDownloadChoice.TRANSLATE_WHAT_EXISTS,
        expectedGeneration: Long? = null,
    ) {
        val manga = getSuccessState()?.manga ?: return
        // pendingTranslationGroup stays pending while blocked so the dialog's
        // continue action re-enters with the original full group.
        if (!ensureInpaintModelAvailable { confirmChapterTranslation(item, decision, expectedGeneration) }) return
        val group = pendingTranslationGroup
            .takeIf { it.any { candidate -> candidate.chapter.id == item.chapter.id } }
            ?: listOf(item)
        pendingTranslationGroup = emptyList()
        if (group.size == 1) {
            proceedChapterTranslation(manga, item, decision, expectedGeneration)
            return
        }

        translationManager.acknowledgeTranslationRequests(chapters = group.map { it.chapter })
        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
        openTranslationProgressDrawer(item)

        scope.launch {
            val generations = group.mapNotNull { candidate ->
                candidate.chapter.id?.let { chapterId ->
                    chapterId to translationManager.pendingRequestGeneration(chapterId)
                }
            }.filter { (_, generation) -> generation != null }
                .associate({ (chapterId, generation) -> chapterId to generation!! })
            val requestedGroup = group.filter { candidate ->
                candidate.chapter.id?.let { chapterId -> generations.containsKey(chapterId) } == true
            }
            if (requestedGroup.isEmpty()) return@launch
            val (downloaded, awaitingDownload) = withIOContext {
                requestedGroup.partition {
                    downloadManager.isChapterDownloaded(
                        it.chapter.name,
                        it.chapter.scanlator,
                        manga.title,
                        manga.source,
                        skipCache = true,
                    )
                }
            }
            queueAwaitingDownloadTranslations(manga, awaitingDownload) { generations[it] }

            val pendingDownloaded = downloaded.filter { candidate ->
                val chapterId = candidate.chapter.id ?: return@filter false
                val generation = generations[chapterId] ?: return@filter false
                translationManager.isTranslationRequestCurrent(chapterId, generation)
            }
            if (pendingDownloaded.isEmpty()) return@launch

            val partialProbes: Map<Long, BatchAdmissionDecision> =
                pendingDownloaded.mapNotNull { candidate ->
                    val chapterId = candidate.chapter.id ?: return@mapNotNull null
                    val queuedDownload = downloadManager.getQueuedDownloadOrNull(chapterId)
                        ?: return@mapNotNull null
                    val decision = BatchAdmissionProbe.evaluate(
                        downloadedPageCount = queuedDownload.pages?.count {
                            it.status == eu.kanade.tachiyomi.source.model.Page.State.READY
                        } ?: 0,
                        sourcePageList = queuedDownload.pages,
                    )
                    if (decision == BatchAdmissionDecision.Complete) null else chapterId to decision
                }.toMap()

            suspend fun admitDownloaded(candidates: List<ChapterList.Item>) {
                if (candidates.isEmpty()) return
                if (candidates.size > 1) {
                    translationManager.translateChaptersIfCurrent(
                        manga,
                        candidates.map { it.chapter },
                        generations,
                    )
                    return
                }
                val target = candidates.single()
                val targetGeneration = generations[target.chapter.id]
                if (targetGeneration == null) return
                target.chapter.id?.let { chapterId ->
                    translationManager.markTranslationRequestPreparingIfCurrent(chapterId, targetGeneration)
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate START: chapter=${target.chapter.name} manga=${manga.title} lastPageRead=${target.chapter.lastPageRead}"
                }
                when (val preflight = translationManager.translateChapterPreflight(manga, target.chapter)) {
                    is ChapterQueuePreflight.NoConflict ->
                        launchTranslateChapter(manga, target.chapter, targetGeneration)
                    is ChapterQueuePreflight.RunningConflict -> {
                        updateSuccessState {
                            it.copy(dialog = Dialog.RunningTranslationConflict(target, preflight, targetGeneration))
                        }
                    }
                }
            }

            if (partialProbes.isNotEmpty()) {
                admitDownloaded(
                    pendingDownloaded.filter { candidate ->
                        candidate.chapter.id !in partialProbes
                    },
                )
                val partialItems = pendingDownloaded.filter { candidate ->
                    candidate.chapter.id in partialProbes
                }
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.PartialDownloadTranslation(
                            group = partialItems,
                            decisions = partialProbes,
                            generations = generations,
                        ),
                    )
                }
                return@launch
            }
            admitDownloaded(pendingDownloaded)
        }
    }

    /** Requests the start through the atomic acknowledge/rearm/adopt seam —
     *  null means the caller went stale and must not adopt newer work — then
     *  routes the chapter; the drawer opens only after the preflight resolves. */
    private fun proceedChapterTranslation(
        manga: Manga,
        item: ChapterList.Item,
        decision: PartialDownloadChoice,
        expectedGeneration: Long?,
    ) {
        val chapterId = item.chapter.id ?: return
        val generation =
            translationManager.beginTranslationRequestIfCurrent(chapterId, expectedGeneration) ?: return
        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
        scope.launch {
            val (downloaded, queuedPages) = withIOContext {
                downloadManager.isChapterDownloaded(
                    item.chapter.name,
                    item.chapter.scanlator,
                    manga.title,
                    manga.source,
                    skipCache = true,
                ) to item.chapter.id?.let(downloadManager::getQueuedDownloadOrNull)?.pages
            }
            if (decision == PartialDownloadChoice.FINISH_DOWNLOAD_FIRST || !downloaded) {
                openTranslationProgressDrawer(item)
                withIOContext { queueAwaitingDownloadTranslations(manga, listOf(item), { generation }) }
                return@launch
            }
            val context = readAdmissionContext(queuedPages)
            if (context != null) {
                openTranslationProgressDrawer(item)
                translationManager.translateChaptersIfCurrent(
                    manga,
                    listOf(item.chapter),
                    mapOf(chapterId to generation),
                    mapOf(chapterId to context),
                )
                return@launch
            }
            translationManager.markTranslationRequestPreparingIfCurrent(chapterId, generation)
            logcat(LogPriority.INFO) {
                "TachiyomiAT translate START: chapter=${item.chapter.name} manga=${manga.title} " +
                    "lastPageRead=${item.chapter.lastPageRead}"
            }
            when (val preflight = translationManager.translateChapterPreflight(manga, item.chapter)) {
                is ChapterQueuePreflight.NoConflict -> {
                    openTranslationProgressDrawer(item)
                    launchTranslateChapter(manga, item.chapter, generation)
                }
                is ChapterQueuePreflight.RunningConflict -> {
                    updateSuccessState {
                        it.copy(dialog = Dialog.RunningTranslationConflict(item, preflight, generation))
                    }
                }
            }
        }
    }

    /** Read-only fresh probe evidence for the admission stamp; null = no cross-check. */
    private fun readAdmissionContext(queuedPages: List<eu.kanade.tachiyomi.source.model.Page>?): BatchAdmissionContext? =
        when (
            val decision = queuedPages?.let {
                BatchAdmissionProbe.evaluate(
                    downloadedPageCount = it.count { page ->
                        page.status == eu.kanade.tachiyomi.source.model.Page.State.READY
                    },
                    sourcePageList = it,
                )
            }
        ) {
            null, BatchAdmissionDecision.Complete -> null
            is BatchAdmissionDecision.Partial -> BatchAdmissionContext(
                probedSourcePageCount = decision.expectedSourcePageCount,
                sourceCountKnown = true,
            )
            BatchAdmissionDecision.UnknownCount -> BatchAdmissionContext(null, true)
        }

    fun resumeChapterTranslation(item: ChapterList.Item, force: Boolean = false) {
        val chapterId = item.chapter.id ?: return
        if (!ensureInpaintModelAvailable { resumeChapterTranslation(item, force) }) return
        scope.launchIO {
            val result = translationManager.resumeBatchChapter(chapterId, force)
            logcat(LogPriority.INFO) { "TachiyomiAT resume chapter=${item.chapter.name} result=$result" }
            if (result !is BatchQueueResumeResult.Resumed &&
                result != BatchQueueResumeResult.AlreadyRunning
            ) {
                onTranslationResumePaused()
            }
        }
    }

    /** Resumes every currently eligible batch entry after the worker has drained. */
    fun resumeTranslationQueue() {
        resumeBatchQueue { result ->
            if (result !is BatchQueueResumeResult.Resumed &&
                result != BatchQueueResumeResult.AlreadyRunning
            ) {
                onTranslationResumePaused()
            }
        }
    }

    fun resumeBatchQueue(onResult: (BatchQueueResumeResult) -> Unit) {
        if (!ensureInpaintModelAvailable { resumeBatchQueue(onResult) }) return
        scope.launchIO {
            val result = translationManager.resumeBatchQueue()
            scope.launch { onResult(result) }
        }
    }

    fun cancelPendingTranslationRequest(item: ChapterList.Item) {
        item.chapter.id?.let { chapterId ->
            if (translationManager.cancelTranslationRequest(chapterId)) {
                updateTranslationRequests(translationManager.pendingTranslationRequests.value)
            }
        }
        dismissDialog()
    }

    fun confirmReplaceRunningChapter(item: ChapterList.Item) {
        val state = getSuccessState() ?: return
        val manga = state.manga
        // Captured before the guard swaps the dialog: the guard's
        // FAST-continue re-entry must not re-read the replaced dialog state.
        val dialog = state.dialog as? Dialog.RunningTranslationConflict ?: return
        if (dialog.item.chapter.id != item.chapter.id) return
        if (!ensureInpaintModelAvailable {
                replaceRunningChapter(manga, dialog.item, dialog.conflict, dialog.requestGeneration)
            }
        ) {
            return
        }
        replaceRunningChapter(manga, dialog.item, dialog.conflict, dialog.requestGeneration)
    }

    /** Continuation shared by the direct confirm and the guard's FAST re-entry. */
    private fun replaceRunningChapter(
        manga: Manga,
        item: ChapterList.Item,
        preflight: ChapterQueuePreflight.RunningConflict,
        requestGeneration: Long,
    ) {
        dismissDialog()
        scope.launchNonCancellable {
            val chapterId = item.chapter.id ?: return@launchNonCancellable
            if (!translationManager.isTranslationRequestCurrent(chapterId, requestGeneration)) return@launchNonCancellable
            val conflictCancelled = translationManager.cancelRunningChapterForReplace(
                preflight.run,
                preflight.runGeneration,
            )
            if (!translationManager.isTranslationRequestCurrent(chapterId, requestGeneration)) return@launchNonCancellable
            if (conflictCancelled) {
                translationManager.translateChapter(manga, item.chapter, requestGeneration)
                return@launchNonCancellable
            }

            when (val refreshed = translationManager.translateChapterPreflight(manga, item.chapter)) {
                is ChapterQueuePreflight.NoConflict ->
                    translationManager.translateChapter(manga, item.chapter, requestGeneration)
                is ChapterQueuePreflight.RunningConflict -> {
                    updateSuccessState { current ->
                        if (current.dialog != null || current.manga.id != manga.id) {
                            current
                        } else {
                            current.copy(
                                dialog = Dialog.RunningTranslationConflict(item, refreshed, requestGeneration),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchTranslateChapter(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
    ) {
        scope.launchNonCancellable {
            translationManager.translateChapter(manga, chapter, expectedRequestGeneration)
        }
    }

    /**
     * Back-drop dismissal of the partial-download decision: the group's
     * acknowledged requests have no scheduled work, so resolve exactly
     * those; queued downloads for the same chapters stay untouched.
     */
    fun dismissPartialDownloadDecision(dialog: Dialog.PartialDownloadTranslation) {
        resolvePartialDownloadRequests(dialog.group, dialog.generations)
        dismissDialog()
    }

    /** Back and the Cancel button both route here: a resolver wins, else plain dismiss. */
    fun dismissInpaintModelMissingDialog(dialog: Dialog.InpaintModelMissing) {
        val resolveDismissal = dialog.onDismiss
        if (resolveDismissal != null) resolveDismissal() else dismissDialog()
    }

    private fun resolvePartialDownloadRequests(group: List<ChapterList.Item>, generations: Map<Long, Long>) {
        group.forEach { item ->
            val chapterId = item.chapter.id ?: return@forEach
            val generation = generations[chapterId] ?: return@forEach
            translationManager.cancelTranslationRequestIfCurrent(chapterId, generation)
        }
        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
    }

    fun finishDownloadBeforeTranslation(dialog: Dialog.PartialDownloadTranslation) {
        val manga = getSuccessState()?.manga
        if (!ensureInpaintModelAvailable(
                onDismiss = {
                    resolvePartialDownloadRequests(dialog.group, dialog.generations)
                    dismissDialog()
                },
            ) { finishDownloadBeforeTranslation(dialog) }
        ) {
            return
        }
        dismissDialog()
        if (manga == null) return
        scope.launchNonCancellable {
            queueAwaitingDownloadTranslations(manga, dialog.group, translationManager::pendingRequestGeneration)
        }
    }

    fun translatePartialDownloadNow(dialog: Dialog.PartialDownloadTranslation) {
        if (!ensureInpaintModelAvailable(
                onDismiss = {
                    resolvePartialDownloadRequests(dialog.group, dialog.generations)
                    dismissDialog()
                },
            ) { translatePartialDownloadNow(dialog) }
        ) {
            return
        }
        dismissDialog()
        val manga = getSuccessState()?.manga ?: return
        scope.launchNonCancellable {
            val generations = mutableMapOf<Long, Long>()
            val contexts = mutableMapOf<Long, BatchAdmissionContext>()
            val chapters = mutableListOf<Chapter>()
            dialog.group.forEach { item ->
                val chapterId = item.chapter.id ?: return@forEach
                val generation = translationManager.pendingRequestGeneration(chapterId) ?: return@forEach
                val decision = dialog.decisions[chapterId] ?: return@forEach
                generations[chapterId] = generation
                contexts[chapterId] = when (decision) {
                    is BatchAdmissionDecision.Partial ->
                        BatchAdmissionContext(
                            probedSourcePageCount = decision.expectedSourcePageCount,
                            sourceCountKnown = true,
                        )
                    BatchAdmissionDecision.UnknownCount ->
                        BatchAdmissionContext(
                            probedSourcePageCount = null,
                            sourceCountKnown = true,
                        )
                    BatchAdmissionDecision.Complete ->
                        BatchAdmissionContext(null, false)
                }
                chapters += item.chapter
            }
            if (chapters.isNotEmpty()) {
                translationManager.translateChaptersIfCurrent(manga, chapters, generations, contexts)
            }
        }
    }

    fun setConfirmPretranslate(show: Boolean) {
        translationPreferences.translationConfirmPretranslate().set(show)
    }

    fun translationConfirmPretranslate(): Boolean =
        translationPreferences.translationConfirmPretranslate().get()

    private fun queueAwaitingDownloadTranslations(
        manga: Manga,
        items: List<ChapterList.Item>,
        getGeneration: (Long) -> Long?,
    ) {
        val admitted = items.mapNotNull { item ->
            val chapterId = item.chapter.id ?: return@mapNotNull null
            val generation = getGeneration(chapterId) ?: return@mapNotNull null
            if (translationManager.queueTranslationAfterDownloadIfCurrent(manga, item.chapter, generation)) {
                item.chapter
            } else {
                null
            }
        }
        if (admitted.isNotEmpty()) {
            enqueueTranslationDownloads(downloadManager, manga, admitted)
            onTranslationsQueuedBehindDownloads()
        }
    }
}
