package eu.kanade.translation.coordinator

import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal const val REGISTERED_READER_STORE_WAIT_REASON =
    "Waiting for registered reader translation store to close before download re-key"

/**
 * Coordinates pending translation requests from acknowledgement through download handoff.
 * Every asynchronous durable write is fenced by per-chapter version and request generation,
 * so a later phase change, cancel, or retry cannot be overwritten by a stale write.
 */
internal class TranslationRequestCoordinator(
    internal val pendingRequestStore: TranslationPendingRequestStore,
    initialPendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>>? = null,
    private val storeScopeProvider: () -> CoroutineScope,
    private val queueStateProvider: (() -> StateFlow<List<Translation>>)? = null,
    private val batchCoordinatorProvider: () -> BatchTranslationCoordinator,
    private val getQueuedTranslationOverride: ((Long) -> Translation?)? = null,
    private val translateChapterOverride: ((Manga, Chapter, Long?, Boolean) -> Unit)? = null,
    private val startBatchTranslation: () -> Unit = {},
    private val sessionCoordinator: TranslationSessionCoordinator = TranslationSessionCoordinator(),
    private val cancelReaderChapterAsync: (Long) -> Unit = {},
    private val sourceIsHttp: (Manga) -> Boolean = { false },
    private val resolveTranslation: suspend (Long) -> Translation? = { resolveTranslationFromChapterId(it) },
    private val hasDownloadedFiles: (Translation) -> Boolean = { false },
    private val hasPendingDownloadRekeyFn: (Long) -> Boolean = { false },
    private val reconcilePendingDownloadRekeyFn: suspend (Long) -> Boolean = { true },
) {

    enum class AdmissionResult {
        STALE_GENERATION,
        QUEUED,
        QUEUE_FAILED,
    }

    private val ledger = PendingRequestLedger(pendingRequestStore, initialPendingRequests)
    val pendingTranslationRequests: StateFlow<Map<Long, TranslationRequestState>>
        get() = ledger.state
    internal val pendingRequestMutationLock: Any
        get() = ledger.mutationLock
    private val downloadRekeyingChapterIds = ConcurrentHashMap.newKeySet<Long>()
    private val storeScope get() = storeScopeProvider()
    private val batchCoordinator get() = batchCoordinatorProvider()
    private val queueState get() = queueStateProvider?.invoke() ?: batchCoordinator.queueState
    private fun queuedTranslationOrNull(chapterId: Long): Translation? =
        getQueuedTranslationOverride?.invoke(chapterId) ?: batchCoordinator.queuedTranslationOrNull(chapterId)

    private fun submitTranslation(manga: Manga, chapter: Chapter, generation: Long?, autoStart: Boolean) {
        val override = translateChapterOverride
        if (override != null) {
            override(manga, chapter, generation, autoStart)
            return
        }
        val result = translateChapter(manga, chapter, generation, autoStart = autoStart)
        if (autoStart && result != AdmissionResult.STALE_GENERATION) startBatchTranslation()
    }
    private val restoreAdmittedChapterIds = ConcurrentHashMap.newKeySet<Long>()
    private val startupReconciliationOnce = AtomicBoolean(false)

    @Volatile private var downloadQueueRestoredChapterIds: Set<Long>? = null

    @Volatile private var translationQueueRestoreJob: Job? = null

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        chapter.id?.let { chapterId ->
            restoreAdmittedChapterIds.remove(chapterId)
            synchronized(pendingRequestMutationLock) {
                ledger.set(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
                // Attach: capture the request generation the download will be
                // fenced against when its completion callback arrives.
                ledger.attachDownloadGeneration(chapterId)
            }
        }
    }

    /** Writes WAITING only while the request still has [expectedGeneration]; cancellation wins the lock race. */
    fun queueTranslationAfterDownloadIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
    ): Boolean {
        val chapterId = chapter.id ?: return false
        synchronized(pendingRequestMutationLock) {
            if (!ledger.isCurrent(chapterId, expectedGeneration)) return false
            queueTranslationAfterDownload(manga, chapter)
            return true
        }
    }

    /** Writes PREPARING only while the request still has [expectedGeneration]. */
    fun markTranslationRequestPreparingIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean {
        synchronized(pendingRequestMutationLock) {
            if (!ledger.isCurrent(chapterId, expectedGeneration)) return false
            markTranslationRequestPreparing(chapterId)
            return true
        }
    }

    /** Keeps this attached download request live while a reader owns its chapter store. */
    fun markRegisteredReaderStoreWaitIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean {
        synchronized(pendingRequestMutationLock) {
            if (!ledger.isCurrent(chapterId, expectedGeneration)) return false
            ledger.set(
                chapterId = chapterId,
                phase = TranslationRequestPhase.PREPARING,
                reason = REGISTERED_READER_STORE_WAIT_REASON,
            )
            return true
        }
    }

    /** True when the live request is non-terminal and carries [generation]; terminal phases never satisfy fences. */
    fun isTranslationRequestCurrent(chapterId: Long, generation: Long): Boolean =
        ledger.isCurrent(chapterId, generation)

    /** Current in-process request generation, or null when no request exists. */
    fun pendingRequestGeneration(chapterId: Long): Long? =
        ledger.generation(chapterId)

    /** Reserve a completed-download page-key migration against request and batch admission. */
    fun tryBeginDownloadRekey(chapterId: Long, isBatchActive: () -> Boolean): Boolean =
        synchronized(pendingRequestMutationLock) {
            if (isBatchActive()) return@synchronized false
            downloadRekeyingChapterIds.add(chapterId)
        }

    fun finishDownloadRekey(chapterId: Long) {
        downloadRekeyingChapterIds.remove(chapterId)
    }

    fun isChapterDownloadRekeying(chapterId: Long): Boolean =
        chapterId in downloadRekeyingChapterIds

    /** Captures only the currently attached, live request for this download. */
    fun downloadAttachGeneration(chapterId: Long): Long? =
        ledger.downloadAttachGeneration(chapterId)

    /**
     * Retries only the request that failed because its completed-download
     * re-key was deferred/rejected. The original generation and download
     * attachment must still match; cancel/replacement/pause or existing queue
     * ownership leaves the request visible for explicit user action.
     */
    fun retryRekeyFailedAdmissionIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
        autoStart: Boolean,
    ): Boolean {
        val chapterId = chapter.id ?: return false
        val retryGeneration =
            ledger.beginRekeyRetryIfCurrent(chapterId, expectedGeneration) {
                batchCoordinator.batchQueuePauseState.value != BatchQueuePauseState.NOT_PAUSED ||
                    queueState.value.any { it.chapter.id == chapterId }
            } ?: return false
        submitTranslation(manga, chapter, retryGeneration, autoStart)
        if (queueState.value.any { it.chapter.id == chapterId }) return true

        // translateChapter can decline before queue admission (for example a
        // reader session won a race after the full leave). Keep the request
        // visible and retryable instead of leaving a false PREPARING state.
        synchronized(pendingRequestMutationLock) {
            val current = ledger.current(chapterId)
            if (current?.generation == retryGeneration && current.phase == TranslationRequestPhase.PREPARING) {
                ledger.set(
                    chapterId = chapterId,
                    phase = TranslationRequestPhase.ADMISSION_FAILED,
                    reason = "Download re-key reconciliation completed but batch admission was deferred",
                    failureKind = TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED,
                )
            }
        }
        return false
    }

    /** Publishes the acknowledgement shown while the live download probe runs. */
    fun acknowledgeTranslationRequests(chapters: List<Chapter>) {
        val chapterIds = chapters.mapNotNull { it.id }.distinct()
        if (chapterIds.isEmpty()) return
        chapterIds.forEach(restoreAdmittedChapterIds::remove)
        val versions = ledger.acknowledge(chapterIds)
        versions.forEach { (chapterId, acknowledgement) ->
            BatchDownloadDiagnostics.requestPhase(
                chapterId = chapterId,
                generation = acknowledgement.generation,
                from = null,
                to = TranslationRequestPhase.STARTING,
                failureKind = TranslationRequestFailureKind.NONE,
            )
        }
        storeScope.launch(Dispatchers.IO) {
            versions.forEach { (chapterId, acknowledgement) ->
                ledger.persistStartingAcknowledgement(chapterId, acknowledgement)
            }
        }
    }

    /** Advances an acknowledged request into archive/store/engine preparation. */
    fun markTranslationRequestPreparing(chapterId: Long) {
        if (ledger.state.value.containsKey(chapterId)) {
            ledger.set(chapterId, TranslationRequestPhase.PREPARING)
        }
    }

    fun admitChapterThroughQueue(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long?,
        admissionContext: BatchAdmissionContext?,
        evictStaleQueuedEntry: Boolean,
        colorizeOnly: Boolean = false,
    ): AdmissionResult {
        val chapterId = chapter.id ?: return AdmissionResult.QUEUE_FAILED
        if (expectedGeneration != null) {
            val current = ledger.state.value[chapterId]
            if (current == null || current.generation != expectedGeneration) {
                logcat(LogPriority.INFO) {
                    "T911 dropped stale translate admission for chapter $chapterId " +
                        "(expectedGeneration=$expectedGeneration currentGeneration=${current?.generation})"
                }
                return AdmissionResult.STALE_GENERATION
            }
        }
        cancelReaderChapterAsync(chapterId)
        if (evictStaleQueuedEntry) batchCoordinator.evictStaleQueuedChapters(chapterId, manga.source)
        markTranslationRequestPreparing(chapterId)
        val queued = batchCoordinator.queueChapter(
            manga,
            chapter,
            admissionContext?.probedSourcePageCount,
            admissionContext?.sourceCountKnown ?: false,
            colorizeOnly,
        )
        return if (queued && batchCoordinator.queuedTranslationOrNull(chapterId) != null) {
            ledger.clear(chapterId)
            AdmissionResult.QUEUED
        } else {
            markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            AdmissionResult.QUEUE_FAILED
        }
    }

    fun translateChapter(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
        admissionContext: BatchAdmissionContext? = null,
        autoStart: Boolean = true,
        colorizeOnly: Boolean = false,
    ): AdmissionResult {
        val chapterId = chapter.id ?: return AdmissionResult.QUEUE_FAILED
        val admission = sessionCoordinator.requestBatchSession(BatchSessionIntent(setOf(chapterId)))
        if (admission is SessionAdmission.Rejected) {
            markSessionAdmissionFailed(
                chapterIds = setOf(chapterId),
                expectedGenerations = expectedRequestGeneration?.let { mapOf(chapterId to it) },
                rejection = admission.reason,
            )
            return AdmissionResult.QUEUE_FAILED
        }
        val result = synchronized(pendingRequestMutationLock) {
            admitChapterThroughQueue(
                manga,
                chapter,
                expectedRequestGeneration,
                admissionContext,
                evictStaleQueuedEntry = true,
                colorizeOnly,
            )
        }
        if (result != AdmissionResult.QUEUED) {
            finishBatchSessionIfQueueIdle()
            return result
        }
        val queued = batchCoordinator.queuedTranslationOrNull(chapterId)
        if (autoStart) {
            batchCoordinator.rearmQueuedChapter(chapterId)
        } else if (queued?.status == Translation.State.QUEUE) {
            queued.status = Translation.State.PAUSED
        }
        return result
    }

    fun translateChapterAndStart(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
        admissionContext: BatchAdmissionContext? = null,
        autoStart: Boolean = true,
        colorizeOnly: Boolean = false,
    ): AdmissionResult {
        val result = translateChapter(manga, chapter, expectedRequestGeneration, admissionContext, autoStart, colorizeOnly)
        if (autoStart && result == AdmissionResult.QUEUED) startBatchTranslation()
        return result
    }

    fun translateChaptersInternal(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>?,
        admissionContexts: Map<Long, BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()
        val intentChapterIds = chapters.mapNotNull { it.id }.toSet()
        val admission = sessionCoordinator.requestBatchSession(BatchSessionIntent(intentChapterIds))
        if (admission is SessionAdmission.Rejected) {
            markSessionAdmissionFailed(intentChapterIds, expectedGenerations, admission.reason)
            return emptyList()
        }
        val admitted = mutableListOf<Chapter>()
        synchronized(pendingRequestMutationLock) {
            chapters.forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                val expectedGeneration = expectedGenerations?.get(chapterId)
                if (expectedGenerations != null && expectedGeneration == null) return@forEach
                val result = admitChapterThroughQueue(
                    manga,
                    chapter,
                    expectedGeneration,
                    admissionContexts[chapterId],
                    evictStaleQueuedEntry = false,
                )
                if (result == AdmissionResult.QUEUED) {
                    batchCoordinator.rearmQueuedChapter(chapterId)
                    admitted += chapter
                }
            }
        }
        if (admitted.isEmpty()) finishBatchSessionIfQueueIdle()
        return admitted
    }

    internal fun finishBatchSessionIfQueueIdle() {
        val hasActiveOrPaused = batchCoordinator.queueState.value.any {
            it.status == Translation.State.QUEUE ||
                it.status == Translation.State.TRANSLATING ||
                it.status == Translation.State.PAUSED
        }
        if (!hasActiveOrPaused) {
            sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
        }
    }

    fun translateChaptersAndStart(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>? = null,
        admissionContexts: Map<Long, BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        val admitted = translateChaptersInternal(manga, chapters, expectedGenerations, admissionContexts)
        if (admitted.isNotEmpty()) startBatchTranslation()
        return admitted
    }

    fun markTranslationQueueFailureIfAcknowledged(manga: Manga, chapterId: Long) {
        if (ledger.state.value.containsKey(chapterId)) {
            val failureKind = translationQueueAdmissionFailureKind(
                sourceIsHttp = sourceIsHttp(manga),
                configValid = batchCoordinator.isQueueConfigValid(),
            )
            ledger.set(
                chapterId,
                TranslationRequestPhase.ADMISSION_FAILED,
                "Translation could not be queued",
                failureKind,
            )
        }
    }

    /**
     * Session admission rejected live request(s): publish an explicit
     * retryable failure instead of stranding the acknowledgement in
     * STARTING/PREPARING with no scheduled work.
     */
    private fun markSessionAdmissionFailed(
        chapterIds: Set<Long>,
        expectedGenerations: Map<Long, Long>?,
        rejection: SessionRejection,
    ) {
        val reason = when (rejection) {
            SessionRejection.READER_ACTIVE -> "Reader translation session is active"
            SessionRejection.PAUSING_IN_PROGRESS -> "A translation session switch is in progress"
            SessionRejection.BATCH_ACTIVE -> "Another batch session is active"
        }
        synchronized(pendingRequestMutationLock) {
            chapterIds.forEach { chapterId ->
                val current = ledger.current(chapterId)
                if (current == null || current.isTerminal) return@forEach
                if (expectedGenerations != null) {
                    val expected = expectedGenerations[chapterId] ?: return@forEach
                    if (current.generation != expected) return@forEach
                }
                ledger.set(
                    chapterId,
                    TranslationRequestPhase.ADMISSION_FAILED,
                    reason,
                    TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED,
                )
            }
        }
    }

    fun onDownloadQueueRestored(chapterIds: Set<Long>) {
        downloadQueueRestoredChapterIds = chapterIds
        storeScope.launch { runStartupReconciliationIfReady() }
    }

    fun isRestoreAdmitted(chapterId: Long): Boolean = chapterId in restoreAdmittedChapterIds

    fun consumeRestoreAdmission(chapterId: Long): Boolean = restoreAdmittedChapterIds.remove(chapterId)

    suspend fun startTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id
        val autoStart = chapterId == null || !restoreAdmittedChapterIds.remove(chapterId)
        startTranslationAfterDownloadIfRequested(manga, chapter, autoStart)
    }

    fun translationQueueRestoreStarted(job: Job) {
        translationQueueRestoreJob = job
    }

    fun runStartupReconciliationIfReady() {
        val downloadSnapshot = downloadQueueRestoredChapterIds ?: return
        if (!startupReconciliationOnce.compareAndSet(false, true)) return
        storeScope.launch {
            translationQueueRestoreJob?.join()
            reconcilePendingRequestsForStartup(downloadQueueChapterIds = downloadSnapshot)
        }
    }

    suspend fun reconcilePendingRequestsForStartup(
        downloadQueueChapterIds: Set<Long>,
        resolveTranslation: suspend (Long) -> Translation? = this.resolveTranslation,
        hasDownloadedFiles: (Translation) -> Boolean = this.hasDownloadedFiles,
    ) {
        val pendingIds = (ledger.durableChapterIds() + ledger.state.value.keys).distinct()
        if (pendingIds.isEmpty()) return
        val translationQueueIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        pendingIds.forEach { chapterId ->
            val current = ledger.current(chapterId)
            if (current == null || current.isTerminal) return@forEach
            val translation = resolveTranslation(chapterId)
            when {
                translation == null -> ledger.clear(chapterId)
                chapterId in translationQueueIds -> ledger.clear(chapterId)
                chapterId in downloadQueueChapterIds -> {
                    restoreAdmittedChapterIds.add(chapterId)
                    if (current.phase != TranslationRequestPhase.WAITING_FOR_DOWNLOAD) {
                        ledger.set(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD)
                    }
                }
                hasDownloadedFiles(translation) -> admitRestoredPendingTranslation(translation, current.generation)
                else -> markTranslationDownloadFailed(
                    chapterId,
                    "Interrupted before the chapter download or translation could run",
                    TranslationRequestFailureKind.INTERRUPTED,
                )
            }
        }
    }

    fun admitRestoredPendingTranslation(translation: Translation, expectedGeneration: Long) {
        val chapterId = translation.chapter.id ?: return
        if (!sessionCoordinator.requestBatchSession(setOf(chapterId))) return
        synchronized(pendingRequestMutationLock) {
            val current = ledger.state.value[chapterId]
            val currentGeneration = current?.generation ?: pendingRequestStore.record(chapterId)?.generation
            if (currentGeneration != expectedGeneration) return
            cancelReaderChapterAsync(chapterId)
            markTranslationRequestPreparing(chapterId)
            val queued = batchCoordinator.queueChapter(translation.manga, translation.chapter)
            if (queued && batchCoordinator.queuedTranslationOrNull(chapterId) != null) {
                restoreAdmittedChapterIds.add(chapterId)
                ledger.clear(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(translation.manga, chapterId)
            }
        }
    }

    /** Keeps a failed download request visible and retryable from the batch details UI. */
    fun markTranslationDownloadFailed(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) {
        if (ledger.state.value.containsKey(chapterId) ||
            ledger.durableChapterIds().contains(chapterId)
        ) {
            ledger.set(
                chapterId,
                TranslationRequestPhase.DOWNLOAD_FAILED,
                reason,
                failureKind,
            )
        }
    }

    /**
     * Fenced handoff failure: writes only while the request is still the
     * one this download was attached to, so a stale completion callback can
     * never fail a newer request.
     */
    fun markAttachedDownloadHandoffFailed(
        chapterId: Long,
        reason: String?,
        failureKind: TranslationRequestFailureKind,
    ): Boolean = synchronized(pendingRequestMutationLock) {
        val attached = ledger.downloadAttachGeneration(chapterId) ?: return@synchronized false
        ledger.set(
            chapterId,
            TranslationRequestPhase.ADMISSION_FAILED,
            reason ?: "Translation could not start after the chapter download",
            failureKind,
        )
        true
    }

    // Download lifecycle notifications are no-ops when a chapter has no pending request.

    /** The chapter's download was cancelled or removed from the queue. */
    fun onDownloadCancelled(chapterId: Long) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.CANCELLED, null) {
            TranslationRequestFailureKind.CANCELLED
        }
    }

    /** The whole download queue was cleared. */
    fun onDownloadQueueCleared(chapterId: Long) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.CANCELLED, null) {
            TranslationRequestFailureKind.QUEUE_CLEARED
        }
    }

    /** The downloader stopped (offline, Wi-Fi policy, generic stop) with the chapter unfinished. */
    fun onDownloadStopped(chapterId: Long, reason: String?) {
        transitionAttachedRequest(chapterId, TranslationRequestPhase.DOWNLOAD_FAILED, reason) {
            TranslationRequestFailureKind.DOWNLOADER_STOPPED
        }
    }

    /**
     * Transitions a still-attached request into an explicit terminal phase.
     * Existence-checked: without a pending request this is a no-op, and a
     * cancelled/re-requested chapter is only touched when a request actually
     * exists (its generation changes again, so fences stay correct).
     */
    private fun transitionAttachedRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String?,
        kind: () -> TranslationRequestFailureKind,
    ) {
        if (ledger.state.value.containsKey(chapterId) ||
            ledger.durableChapterIds().contains(chapterId)
        ) {
            ledger.set(chapterId, phase, reason, kind())
        }
    }

    /** Removes only the pending request; downloaded files and queue membership are untouched. */
    fun cancelTranslationRequest(chapterId: Long): Boolean {
        val existed = ledger.state.value.containsKey(chapterId) ||
            ledger.durableChapterIds().contains(chapterId)
        ledger.clear(chapterId)
        return existed
    }

    /** Fenced cancel: clears the request only while it still carries [expectedGeneration]. */
    fun cancelTranslationRequestIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean =
        synchronized(pendingRequestMutationLock) {
            val current = ledger.current(chapterId)
            if (current?.generation != expectedGeneration) return@synchronized false
            ledger.clear(chapterId)
            true
        }

    /** Atomically returns a generation that may carry this start: fresh ack,
     *  re-arm of the displayed terminal request, or adoption of the displayed
     *  live generation; null-displayed never adopts, mismatch is stale → null. */
    fun beginTranslationRequestIfCurrent(chapterId: Long, expectedGeneration: Long?): Long? =
        synchronized(pendingRequestMutationLock) {
            val current = ledger.current(chapterId)
            when {
                current == null && expectedGeneration == null -> {
                    ledger.acknowledge(listOf(chapterId))
                    ledger.generation(chapterId)
                }
                current == null || expectedGeneration == null -> null
                current.generation != expectedGeneration -> null
                current.isTerminal -> {
                    ledger.set(chapterId, TranslationRequestPhase.STARTING)
                    ledger.generation(chapterId)
                }
                else -> current.generation
            }
        }

    /**
     * Drops a stale terminal request at manual/auto reader entry so a failed,
     * cancelled, or admission-failed projection cannot outlive the batch that
     * produced it and a later download completion cannot fire an unrequested
     * batch. Live phases are left untouched.
     */
    fun clearStaleDownloadFailedRequest(chapterId: Long) {
        val phase = ledger.state.value[chapterId]?.phase
            ?: ledger.durablePhase(chapterId)
        if (phase == TranslationRequestPhase.DOWNLOAD_FAILED ||
            phase == TranslationRequestPhase.CANCELLED ||
            phase == TranslationRequestPhase.ADMISSION_FAILED
        ) {
            ledger.clear(chapterId)
        }
    }

    /** Rearms a failed download request so an explicit download retry can complete it. */
    fun rearmDownloadFailedRequest(chapterId: Long): Boolean {
        val currentPhase = ledger.state.value[chapterId]?.phase
            ?: ledger.durablePhase(chapterId)
            ?: return false
        if (currentPhase == TranslationRequestPhase.DOWNLOAD_FAILED) {
            ledger.set(
                chapterId = chapterId,
                phase = TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = "re-armed after download failure",
                failureKind = TranslationRequestFailureKind.NONE,
            )
            return true
        }
        return false
    }

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        ledger.state.value.containsKey(chapterId) ||
            ledger.durableChapterIds().contains(chapterId)

    /** Protected from reader auto-delete while its queued batch or durable post-download intent may need files. */
    fun isChapterTranslationProtected(chapterId: Long): Boolean {
        val queuedState = queuedTranslationOrNull(chapterId)?.status
        return ChapterDeletionProtection.protects(
            queuedState = queuedState,
            hasPendingRequest = ledger.state.value.containsKey(chapterId) ||
                ledger.durableChapterIds().contains(chapterId),
        ) ||
            chapterId in batchCoordinator.persistedQueueChapterIds()
    }

    /** Protected ids are passed to the pending chapter deleter at reader finish. */
    fun protectedChapterIds(): Set<Long> = ChapterDeletionProtection.protectedIds(
        queueState.value,
        ledger.state.value.keys + ledger.durableChapterIds(),
        batchCoordinator.persistedQueueChapterIds(),
    )

    fun clearAllPendingTranslationRequests() = ledger.clearAll()

    /** Clears all queued work and request acknowledgements as one batch lifecycle operation. */
    fun clearTranslationQueue() {
        batchCoordinator.clearQueue()
        batchCoordinator.stop()
        ledger.clearAll()
        sessionCoordinator.finishSession()
    }

    /** Fences download completion with the attached request generation; stale callbacks cannot admit or recreate it. */
    suspend fun startTranslationAfterDownloadIfRequested(
        manga: Manga,
        chapter: Chapter,
        // Requests admitted during startup recovery remain queued until explicitly resumed.
        autoStart: Boolean = true,
    ) {
        val chapterId = chapter.id ?: return
        // Reconcile any retained re-key before PREPARING or admission, outside the mutation lock to avoid deadlock.
        // Store synchronization protects reconciliation; the fence below rechecks the request so cancel or pause wins.
        if (hasPendingDownloadRekeyFn(chapterId) && !reconcilePendingDownloadRekeyFn(chapterId)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT download re-key reconciliation not clean for chapter $chapterId; " +
                    "admission refused without a PREPARING request"
            }
            markAttachedDownloadHandoffFailed(
                chapterId,
                "Download re-key reconciliation deferred or rejected; retry translation after the reader fully closes",
                TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED,
            )
            return
        }
        // The generation check and PREPARING write share a lock: cancellation either drops the callback
        // first or removes its write afterward, so the request cannot be resurrected.
        // Admission revalidates the captured generation under that same lock.
        val generation = ledger.beginHandoffIfCurrent(chapterId) ?: return
        submitTranslation(manga, chapter, generation, autoStart)
    }
}
