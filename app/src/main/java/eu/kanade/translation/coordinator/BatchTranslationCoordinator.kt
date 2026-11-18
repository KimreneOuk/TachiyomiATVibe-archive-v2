package eu.kanade.translation.coordinator

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.engines.translator.TranslationEngineBuilder
import eu.kanade.translation.model.BatchExecutionMode
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.ChapterBatchExecutor
import eu.kanade.translation.processing.MemoryPressureClass
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.batch.progress.ReconciliationResult
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.processing.memory.BitmapPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

internal class BatchTranslationCoordinator(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val queueStore: TranslationQueueStore = TranslationQueueStore(context),
    private val processor: TranslationProcessor,
    private val pageOperation: ChapterBatchExecutor,
    private val resolveStore: suspend (Translation) -> ChapterTranslationStore? = { null },
    private val batchTrackerFactory: (Long, ChapterTranslationStore, List<String>) -> TranslationBatchProgressTracker? = { _, _, _ -> null },
    private val onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit = { _, _, _, _ -> },
    private val sessionCoordinator: TranslationSessionCoordinator? = null,
    // DI-resolved lazily on first use, so test constructions stay Injekt-free.
    // Kept last: positional test constructions above stay source-compatible.
    private val onlineColorizeStreams: OnlineColorizePageStreamResolver? = null,
    /** Terminal snapshot for a chapter; gates the not-saved retry rearm.
     *  Session-scoped in-memory LRU: absent after process death, 20-entry
     *  eviction, or a newer run replacing the chapter. */
    private val terminalSnapshotProvider: (Long) -> TranslationProgressSnapshot? = { null },
) : Closeable {

    private val colorizeStreams: OnlineColorizePageStreamResolver
        get() = onlineColorizeStreams ?: OnlineColorizePageStreamResolver(Injekt.get())

    companion object {
        // Bound the delete-path join: the worker may be mid-uncancellable native ONNX (OrtSession.run);
        // coroutine cancellation lands only at the next suspension point, so an unbounded join could hang
        // deletion. On timeout the delete proceeds; the defunct-store guard neutralizes any late write.
        const val BATCH_JOIN_TIMEOUT_MS = 2_000L

        // Retry the chapter claim while the previous job finishes unwinding.
        const val IN_FLIGHT_CLAIM_RETRY_MS = 100L
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    fun statusFlow(): Flow<Translation> = translationQueueStatusFlow(queueState)

    private val activeChapters = ConcurrentHashMap.newKeySet<String>()

    fun isChapterActive(chapterKey: String): Boolean = activeChapters.contains(chapterKey)

    fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean =
        queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (
                    translation.status == Translation.State.QUEUE ||
                        translation.status == Translation.State.TRANSLATING ||
                        (includePaused && translation.status == Translation.State.PAUSED)
                    )
        }

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any {
            it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
        }

    fun queuedTranslationOrNull(chapterId: Long): Translation? =
        queueState.value.firstOrNull { it.chapter.id == chapterId }

    /** True when the chapter's outstanding queue entry is a colorize-only run still waiting or executing. */
    fun colorizeOnlyQueuedOrRunning(chapterId: Long): Boolean =
        queueState.value.any {
            it.chapter.id == chapterId &&
                it.colorizeOnly &&
                (it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING)
        }

    fun rearmQueuedChapter(chapterId: Long): Boolean {
        synchronized(queueMutationLock) {
            val translation = _queueState.value.firstOrNull { it.chapter.id == chapterId } ?: return false
            if (replacementReservations.containsKey(chapterId)) return false
            // A crash can persist any status into the queue; leaving a stale
            // terminal entry here wedges it out of selectChapterWave forever.
            if (translation.status == Translation.State.QUEUE ||
                translation.status == Translation.State.TRANSLATING
            ) {
                return true
            }
            translation.status = Translation.State.QUEUE
            markQueueUnpaused()
            _queueState.update { it.toList() }
            persistQueue()
            return true
        }
    }

    fun queuePreflight(chapterId: Long?, sourceId: Long): ChapterQueuePreflight {
        if (chapterId == null) return ChapterQueuePreflight.NoConflict
        return synchronized(queueMutationLock) {
            val queue = _queueState.value
            val conflict = findRunningSameSourceConflict(
                queue.map { it.toQueuedChapterView() },
                chapterId,
                sourceId,
            ) ?: return@synchronized ChapterQueuePreflight.NoConflict
            val run = queue.firstOrNull { it.chapter.id == conflict.chapterId }
                ?: return@synchronized ChapterQueuePreflight.NoConflict
            val runGeneration = run.activeRunGenerationOrNull()
                ?: return@synchronized ChapterQueuePreflight.NoConflict
            ChapterQueuePreflight.RunningConflict(conflict.chapterId, conflict.chapterName, run, runGeneration)
        }
    }

    /** Reserves the exact run observed by preflight until its cancellation completes. */
    fun reserveRunningChapterForReplace(expectedRun: Translation, expectedRunGeneration: Long): Boolean =
        synchronized(queueMutationLock) {
            val chapterId = expectedRun.chapter.id ?: return@synchronized false
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
            if (current !== expectedRun) return@synchronized false
            if (expectedRun.activeRunGenerationOrNull() != expectedRunGeneration) return@synchronized false
            if (replacementReservations.containsKey(chapterId)) return@synchronized false
            replacementReservations[chapterId] = ReplacementReservation(expectedRun, expectedRunGeneration)
            replacementReservationChanges.tryEmit(Unit)
            true
        }

    /** Removes only the reserved run; a changed queue slot makes replacement unsafe. */
    fun removeReplacedChapterIfCurrent(expectedRun: Translation, expectedRunGeneration: Long): Boolean =
        synchronized(queueMutationLock) {
            val chapterId = expectedRun.chapter.id ?: return@synchronized false
            val reservation = replacementReservations[chapterId]
            if (reservation?.run !== expectedRun || reservation.runGeneration != expectedRunGeneration) {
                return@synchronized false
            }
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
            if (current != null && (current !== expectedRun || current.runGeneration != expectedRunGeneration)) {
                replacementReservations.remove(chapterId)
                replacementReservationChanges.tryEmit(Unit)
                return@synchronized false
            }
            if (current === expectedRun) {
                if (expectedRun.status == Translation.State.TRANSLATING || expectedRun.status == Translation.State.QUEUE) {
                    expectedRun.status = Translation.State.NOT_TRANSLATED
                }
                _queueState.update { it - expectedRun }
                persistQueue()
            }
            replacementReservations.remove(chapterId)
            replacementReservationChanges.tryEmit(Unit)
            true
        }

    fun releaseChapterReplacementReservation(expectedRun: Translation, expectedRunGeneration: Long) {
        synchronized(queueMutationLock) {
            val chapterId = expectedRun.chapter.id ?: return
            val reservation = replacementReservations[chapterId]
            if (reservation?.run === expectedRun && reservation.runGeneration == expectedRunGeneration) {
                replacementReservations.remove(chapterId)
                replacementReservationChanges.tryEmit(Unit)
            }
        }
    }

    fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        queueState.value.asSequence()
            .filter { it.chapter.id in staleIds }
            .map { it.chapter }
            .forEach(::removeFromQueue)
    }

    private val queueMutationLock = Any()
    private data class ReplacementReservation(val run: Translation, val runGeneration: Long)
    private val replacementReservations = mutableMapOf<Long, ReplacementReservation>()
    private val replacementReservationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    internal fun beginTranslationRun(translation: Translation): Boolean = synchronized(queueMutationLock) {
        val chapterId = translation.chapter.id ?: return@synchronized false
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return@synchronized false
        if (replacementReservations.containsKey(chapterId)) return@synchronized false
        translation.status = Translation.State.TRANSLATING
        true
    }

    private fun isChapterReplacementReserved(chapterId: Long): Boolean = synchronized(queueMutationLock) {
        replacementReservations.containsKey(chapterId)
    }

    private fun selectAvailableChapterWave(queue: List<Translation>): List<Translation> =
        selectChapterWave(queue).filterNot { translation ->
            translation.chapter.id?.let(::isChapterReplacementReserved) == true
        }

    private val queuePersistence = BatchQueuePersistence(
        context = context,
        provider = provider,
        queueStore = queueStore,
        resolveStore = resolveStore,
    )

    private val chapterPipeline = BatchChapterPipeline(
        context = context,
        provider = provider,
        downloadProvider = downloadProvider,
        colorizeStreams = { colorizeStreams },
        pageOperation = pageOperation,
        resolveStore = resolveStore,
        batchTrackerFactory = batchTrackerFactory,
        onBatchClosed = onBatchClosed,
        activeChapters = activeChapters,
        beginTranslationRun = ::beginTranslationRun,
    )

    fun persistedQueueChapterIds(): Set<Long> = synchronized(queueMutationLock) {
        queuePersistence.loadIds().toSet()
    }

    fun prioritizeChapter(chapterId: Long) {
        synchronized(queueMutationLock) {
            val current = _queueState.value
            val target = current.find { it.chapter.id == chapterId } ?: return
            if (target.status == Translation.State.TRANSLATING) return
            val activeIndex = current.indexOfFirst { it.status == Translation.State.TRANSLATING }
            val insertIndex = if (activeIndex >= 0) activeIndex + 1 else 0
            val targetIndex = current.indexOf(target)
            if (targetIndex <= insertIndex) return

            val reordered = current.toMutableList().apply {
                removeAt(targetIndex)
                add(insertIndex, target)
            }
            _queueState.value = reordered
            persistQueue()
            logcat(LogPriority.INFO) {
                "TachiyomiAT S7: steered chapter $chapterId to queue index $insertIndex"
            }
        }
    }

    private fun persistQueue() {
        synchronized(queueMutationLock) {
            queuePersistence.persist(_queueState.value)
        }
    }

    suspend fun restoreQueue() {
        queuePersistence.restoreQueue(
            queueLock = queueMutationLock,
            currentQueue = { _queueState.value },
            applyRestored = { _queueState.value = it },
        )
        synchronized(queueLifecycleLock) {
            if (queueState.value.isEmpty()) {
                markQueueUnpaused()
            } else {
                _batchQueuePauseState.value = BatchQueuePauseState.PAUSED
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hasResumableBatchWork = MutableStateFlow(false)
    val hasResumableBatchWork: StateFlow<Boolean> = _hasResumableBatchWork.asStateFlow()

    private val _batchQueuePauseState = MutableStateFlow(BatchQueuePauseState.NOT_PAUSED)
    val batchQueuePauseState: StateFlow<BatchQueuePauseState> = _batchQueuePauseState.asStateFlow()
    val isPausedFlow: StateFlow<Boolean> = batchQueuePauseState
        .map { it == BatchQueuePauseState.PAUSED }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val queueLifecycleLock = Any()
    private val resumeMutex = Mutex()

    init {
        scope.launchIO {
            queueState.flatMapLatest { queue ->
                if (queue.isEmpty()) {
                    flowOf(false)
                } else {
                    merge(*queue.map(Translation::statusFlow).toTypedArray())
                        .map { queue.any(::isResumableQueueEntry) }
                        .onStart { emit(queue.any(::isResumableQueueEntry)) }
                }
            }.collect { _hasResumableBatchWork.value = it }
        }
    }

    private fun isResumableQueueEntry(translation: Translation): Boolean =
        translation.status == Translation.State.QUEUE || translation.status == Translation.State.PAUSED

    private fun markQueueUnpaused() {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return
        _batchQueuePauseState.value = BatchQueuePauseState.NOT_PAUSED
    }

    private fun markQueuePauseRequested() {
        _batchQueuePauseState.value = BatchQueuePauseState.REQUESTED
    }

    private fun finishQueuePauseAfterDrain() {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
            _batchQueuePauseState.value = BatchQueuePauseState.PAUSED
        }
    }

    @Volatile
    private var translationJob: Job? = null

    private val sessionSwitchProtocol = SessionSwitchJoinProtocol()

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    // Serialize job admission so concurrent requests cannot both observe an
    // idle translator and launch duplicate work for the queue head.
    private val translatorLaunchLock = Any()

    // Claim held while a chapter job runs or unwinds native work after cancellation. New admissions
    // wait for the claim to release instead of starting a second run that would advance the store
    // generation and fence the first run.
    private val inFlightChapterIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val inFlightClaimReleasedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun start(): Boolean = synchronized(queueLifecycleLock) lifecycle@{
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
            return@lifecycle false
        }
        if (queueState.value.isEmpty()) {
            return@lifecycle false
        }

        synchronized(translatorLaunchLock) {
            if (isRunning) {
                return@lifecycle false
            }

            // A generic queue start does not resurrect failed entries after a
            // restart. An ERROR chapter re-enters work only through an explicit
            // per-chapter request or requeueExisting.
            val pending = queueState.value.filter {
                it.status != Translation.State.TRANSLATED &&
                    it.status != Translation.State.PAUSED &&
                    it.status != Translation.State.ERROR &&
                    it.chapter.id?.let { chapterId -> !isChapterReplacementReserved(chapterId) } ?: true
            }
            if (pending.isEmpty()) {
                return@lifecycle false
            }
            pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
            markQueueUnpaused()
            launchTranslatorJob()
            pending.isNotEmpty()
        }
    }

    fun stop(reason: String? = null, closeEngines: Boolean = false) {
        if (reason == null) {
            cancelTranslatorJob()
        } else {
            requestQueuePause(retainForSession = false)
        }
        // Interrupted chapters stay resumable: their per-page artifacts are
        // durable, so a later Start continues from the planner's reuse scan.
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }

        if (reason == "reader backgrounded") {
            try {
                processor.forceReleaseNativeBuffers()
            } catch (_: Exception) {}
        }

        // Background and memory-pressure stops keep engines open. Callers that
        // request engine closure tear them down so the next run can rebuild
        // after a provider, model, language, or OCR configuration change.
        if (reason != null && !closeEngines) return
        synchronized(queueLifecycleLock) {
            if (_batchQueuePauseState.value != BatchQueuePauseState.REQUESTED) {
                markQueueUnpaused()
            }
        }
        finishSessionIfQueueIdle()
        processor.closeEngines()
    }

    fun onMemoryPressure(level: Int, pressureClass: MemoryPressureClass) {
        BitmapPool.releaseAll()
        try {
            processor.forceReleaseNativeBuffers()
        } catch (_: Exception) {}
        // Keep the translator job alive under memory pressure. Cancelling it
        // can drop an active provider request and publish an aborted state while
        // the model is still generating; process death resumes through restoreQueue().
    }

    fun pause() {
        requestQueuePause(retainForSession = false)
    }

    /** Non-blocking pause; [cancelTranslatorJobAndJoinForSession] retains the job if unwinding exceeds one join. */
    fun pauseForSessionSwitch() {
        requestQueuePause(retainForSession = true)
    }

    private fun requestQueuePause(retainForSession: Boolean) {
        val cancelledJob: Job?
        synchronized(queueLifecycleLock) {
            markQueuePauseRequested()
            cancelledJob = translationJob
            translationJob = null
            cancelledJob?.cancel()
            if (retainForSession) cancelledJob?.let(sessionSwitchProtocol::retain)
            synchronized(queueMutationLock) {
                _queueState.value
                    .filter { it.status == Translation.State.TRANSLATING }
                    .forEach { it.status = Translation.State.QUEUE }
                _queueState.update { it.toList() }
                persistQueue()
            }
            if (cancelledJob == null && inFlightChapterIds.isEmpty()) {
                finishQueuePauseAfterDrain()
            }
        }
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
            scope.launchIO {
                cancelledJob?.join()
                while (inFlightChapterIds.isNotEmpty()) {
                    withTimeoutOrNull(IN_FLIGHT_CLAIM_RETRY_MS) {
                        inFlightClaimReleasedSignal.first()
                    }
                }
                synchronized(queueLifecycleLock) { finishQueuePauseAfterDrain() }
            }
        }
    }

    private data class ResumeCandidate(
        val translation: Translation,
        val runGeneration: Long,
    )

    suspend fun resumeBatchQueue(force: Boolean = false): BatchQueueResumeResult = resumeMutex.withLock {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
            return@withLock BatchQueueResumeResult.PauseUnwinding
        }
        if (isRunning) return@withLock BatchQueueResumeResult.AlreadyRunning

        val candidates = synchronized(queueMutationLock) {
            _queueState.value.filter(::isResumableQueueEntry)
                .map { ResumeCandidate(it, it.runGeneration) }
        }
        if (candidates.isEmpty()) return@withLock BatchQueueResumeResult.NoResumableWork

        val retryTimes = candidates.associate { candidate ->
            val chapterId = candidate.translation.chapter.id ?: return@withLock BatchQueueResumeResult.NoResumableWork
            chapterId to queuePersistence.durableQueueState(candidate.translation)?.nextEligibleRetryAtEpochMs
        }
        val now = System.currentTimeMillis()
        val deferred = retryTimes.mapNotNull { (chapterId, retryAt) ->
            if (!force && retryAt != null && retryAt > now) chapterId to retryAt else null
        }.toMap()
        val eligible = candidates.filter { candidate ->
            candidate.translation.chapter.id !in deferred
        }

        synchronized(queueLifecycleLock) lifecycle@{
            if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
                return@lifecycle BatchQueueResumeResult.PauseUnwinding
            }
            if (isRunning) return@lifecycle BatchQueueResumeResult.AlreadyRunning
            val eligibleIds = eligible.mapNotNullTo(mutableSetOf()) { it.translation.chapter.id }
            if (eligibleIds.isEmpty()) {
                val staleId = updateResumeCandidates(candidates, emptySet(), deferred.keys)
                if (staleId != null) return@lifecycle BatchQueueResumeResult.StaleQueueItem(staleId)
                return@lifecycle BatchQueueResumeResult.BatchRetryDeferred(deferred)
            }

            val update = admitAndUpdateResumeCandidates(candidates, eligibleIds, deferred.keys)
            update.staleChapterId?.let { return@lifecycle BatchQueueResumeResult.StaleQueueItem(it) }
            update.rejection?.let { return@lifecycle BatchQueueResumeResult.SessionRejected(it) }
            val eligibleRuns = eligible.mapTo(mutableSetOf()) { it.translation }
            if (!startSelectedRunsWhileLocked(eligibleRuns)) {
                return@lifecycle BatchQueueResumeResult.NoResumableWork
            }
            BatchQueueResumeResult.Resumed(eligibleIds, deferred)
        }
    }

    suspend fun resumeBatchChapter(
        chapterId: Long,
        force: Boolean = false,
        expectedRun: Translation? = null,
    ): BatchQueueResumeResult = resumeMutex.withLock {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
            return@withLock BatchQueueResumeResult.PauseUnwinding
        }
        if (expectedRun != null && expectedRun.chapter.id != chapterId) {
            return@withLock BatchQueueResumeResult.StaleQueueItem(chapterId)
        }
        val candidate = synchronized(queueMutationLock) {
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
                ?: return@synchronized null
            if (expectedRun != null && current !== expectedRun) return@withLock BatchQueueResumeResult.StaleQueueItem(chapterId)
            if (current.status == Translation.State.TRANSLATING || isChapterTranslationInFlight(chapterId)) {
                return@withLock BatchQueueResumeResult.AlreadyRunning
            }
            if (!isResumableQueueEntry(current)) {
                return@withLock BatchQueueResumeResult.NotResumable(chapterId, current.status)
            }
            ResumeCandidate(current, current.runGeneration)
        } ?: return@withLock BatchQueueResumeResult.QueueItemMissing(chapterId)

        val retryAt = queuePersistence.durableQueueState(candidate.translation)?.nextEligibleRetryAtEpochMs
        if (!force && retryAt != null && retryAt > System.currentTimeMillis()) {
            return@withLock BatchQueueResumeResult.RetryDeferred(chapterId, retryAt)
        }

        synchronized(queueLifecycleLock) lifecycle@{
            if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) {
                return@lifecycle BatchQueueResumeResult.PauseUnwinding
            }
            val update = admitAndUpdateResumeCandidates(
                listOf(candidate),
                setOf(chapterId),
                emptySet(),
            )
            update.staleChapterId?.let { return@lifecycle BatchQueueResumeResult.StaleQueueItem(it) }
            update.rejection?.let { return@lifecycle BatchQueueResumeResult.SessionRejected(it) }
            if (update.alreadyRunning) return@lifecycle BatchQueueResumeResult.AlreadyRunning
            if (!startSelectedRunsWhileLocked(setOf(candidate.translation))) {
                return@lifecycle BatchQueueResumeResult.NoResumableWork
            }
            BatchQueueResumeResult.Resumed(setOf(chapterId))
        }
    }

    private fun updateResumeCandidates(
        candidates: List<ResumeCandidate>,
        eligibleChapterIds: Set<Long>,
        deferredChapterIds: Set<Long>,
    ): Long? = synchronized(queueMutationLock) {
        val stale = staleResumeCandidate(candidates)
        if (stale != null) return@synchronized stale.translation.chapter.id
        applyResumeCandidateStates(candidates, eligibleChapterIds, deferredChapterIds)
        null
    }

    private data class ResumeAdmissionUpdate(
        val staleChapterId: Long? = null,
        val rejection: SessionRejection? = null,
        val alreadyRunning: Boolean = false,
    )

    private fun admitAndUpdateResumeCandidates(
        candidates: List<ResumeCandidate>,
        eligibleChapterIds: Set<Long>,
        deferredChapterIds: Set<Long>,
    ): ResumeAdmissionUpdate = synchronized(queueMutationLock) {
        val active = candidates.firstOrNull { candidate ->
            val chapterId = candidate.translation.chapter.id
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
            current === candidate.translation &&
                current.runGeneration == candidate.runGeneration &&
                (current.status == Translation.State.TRANSLATING || isChapterTranslationInFlight(chapterId))
        }
        if (active != null) return@synchronized ResumeAdmissionUpdate(alreadyRunning = true)
        val stale = staleResumeCandidate(candidates)
        if (stale != null) {
            return@synchronized ResumeAdmissionUpdate(staleChapterId = stale.translation.chapter.id)
        }
        val admission = sessionCoordinator?.requestBatchSession(eligibleChapterIds)
        if (admission is SessionAdmission.Rejected) {
            return@synchronized ResumeAdmissionUpdate(rejection = admission.reason)
        }

        applyResumeCandidateStates(candidates, eligibleChapterIds, deferredChapterIds)
        ResumeAdmissionUpdate()
    }

    private fun staleResumeCandidate(candidates: List<ResumeCandidate>): ResumeCandidate? =
        candidates.firstOrNull { candidate ->
            val chapterId = candidate.translation.chapter.id
            val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
            current == null ||
                current !== candidate.translation ||
                current.runGeneration != candidate.runGeneration ||
                !isResumableQueueEntry(current)
        }

    private fun applyResumeCandidateStates(
        candidates: List<ResumeCandidate>,
        eligibleChapterIds: Set<Long>,
        deferredChapterIds: Set<Long>,
    ) {
        var changed = false
        candidates.forEach { candidate ->
            val chapterId = candidate.translation.chapter.id ?: return@forEach
            val translation = candidate.translation
            if (chapterId in eligibleChapterIds && translation.status == Translation.State.PAUSED) {
                translation.status = Translation.State.QUEUE
                changed = true
            } else if (chapterId in deferredChapterIds && translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.PAUSED
                changed = true
            }
        }
        if (changed) {
            _queueState.update { it.toList() }
            persistQueue()
        }
    }

    private fun startSelectedRunsWhileLocked(runs: Set<Translation>): Boolean {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return false
        val runnable = queueState.value.filter { queued ->
            runs.any { it === queued } &&
                (queued.status == Translation.State.QUEUE || queued.status == Translation.State.PAUSED)
        }
        if (runnable.isEmpty()) return false
        runnable.filter { it.status == Translation.State.PAUSED }
            .forEach { it.status = Translation.State.QUEUE }
        markQueueUnpaused()
        if (!isRunning) launchTranslatorJob(runnable.toSet())
        return true
    }

    suspend fun requeueExisting(chapterId: Long, force: Boolean = false): Boolean = resumeMutex.withLock {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return@withLock false
        val translation = queueState.value.firstOrNull { it.chapter.id == chapterId }
        if (translation == null) {
            logcat(LogPriority.INFO) { "TachiyomiAT requeue: chapter $chapterId is not in the queue" }
            return@withLock false
        }
        val durable = queuePersistence.durableQueueState(translation)
        val next = durable?.nextEligibleRetryAtEpochMs
        if (!force && next != null && next > System.currentTimeMillis()) {
            logcat(LogPriority.INFO) { "TachiyomiAT requeue: chapter $chapterId backoff until $next" }
            return@withLock false
        }
        synchronized(queueLifecycleLock) {
            if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return@synchronized false
            val rearmed = synchronized(queueMutationLock) {
                if (replacementReservations.containsKey(chapterId)) return@synchronized false
                val current = _queueState.value.firstOrNull { it.chapter.id == chapterId }
                    ?: return@synchronized false
                if (current !== translation) return@synchronized false
                val durablePaused = durable?.status == Translation.State.PAUSED
                val nonDurableWarning = current.status == Translation.State.READY_WITH_WARNINGS &&
                    terminalSnapshotProvider(chapterId)?.nonDurableFailure == true
                if (current.status != Translation.State.PAUSED && !durablePaused && !nonDurableWarning) {
                    return@synchronized current.status == Translation.State.QUEUE
                }
                current.status = Translation.State.QUEUE
                markQueueUnpaused()
                _queueState.update { it.toList() }
                persistQueue()
                true
            }
            if (!rearmed) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT requeue: chapter $chapterId not rearmed (status=${translation.status})"
                }
                return@synchronized false
            }
            if (!isRunning) launchTranslatorJob()
            true
        }
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
        synchronized(queueLifecycleLock) {
            if (_batchQueuePauseState.value != BatchQueuePauseState.REQUESTED) {
                markQueueUnpaused()
            }
        }
        finishSessionIfQueueIdle()
    }

    private fun launchTranslatorJob(onlyRuns: Set<Translation>? = null) {
        synchronized(translatorLaunchLock) {
            if (isRunning) {
                return
            }

            translationJob = scope.launchIO {
                try {
                    if (onlyRuns != null) {
                        while (true) {
                            val wave = selectAvailableChapterWave(
                                queueState.value.filter { queued -> onlyRuns.any { it === queued } },
                            )
                            if (wave.isEmpty()) break
                            supervisorScope {
                                wave.map { launchTranslationJob(it) }.joinAll()
                            }
                        }
                        return@launchIO
                    }

                    val activeTranslationFlow = queueState.transformLatest { queue ->
                        emit(selectAvailableChapterWave(queue))
                        if (queue.isEmpty()) return@transformLatest
                        // Wake the wave on any entry status flip (e.g. a raw pause)
                        // or in-flight claim release; selection always recomputes live.
                        val statusWakes: Flow<List<Translation>> =
                            queue.map(Translation::statusFlow).merge().map { selectAvailableChapterWave(queue) }
                        val claimWakes: Flow<List<Translation>> =
                            inFlightClaimReleasedSignal.map { selectAvailableChapterWave(queue) }
                        val reservationWakes: Flow<List<Translation>> =
                            replacementReservationChanges.map { selectAvailableChapterWave(queue) }
                        emitAll(merge(statusWakes, claimWakes, reservationWakes))
                    }

                    supervisorScope {
                        val translationJobs = mutableMapOf<Translation, Job>()

                        activeTranslationFlow.collectLatest { activeTranslations ->
                            val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                            translationJobsToStop.forEach { (translation, job) ->
                                job.cancel()
                                translationJobs.remove(translation)
                            }

                            val translationsToStart = activeTranslations.filter {
                                it !in translationJobs && it.status != Translation.State.TRANSLATED
                            }
                            translationsToStart.forEach { translation ->
                                val job = launchTranslationJob(translation)
                                translationJobs[translation] = job
                            }
                        }
                    }
                } finally {
                    // If the whole translation job was cancelled, make sure paused
                    // items return to QUEUE for the next start.
                    queueState.value.filter { it.status == Translation.State.TRANSLATING }
                        .forEach { it.status = Translation.State.QUEUE }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        val chapterId = translation.chapter.id
        var claimed = false
        try {
            if (chapterId != null) {
                // Admit one live schedule per chapter; releases on coroutine unwind.
                var retries = 0
                while (!inFlightChapterIds.add(chapterId)) {
                    val waitResult = withTimeoutOrNull(IN_FLIGHT_CLAIM_RETRY_MS) {
                        inFlightClaimReleasedSignal.first()
                    }
                    if (waitResult == null && ++retries >= 3) {
                        logcat(LogPriority.WARN) { "Breaking stale inFlightChapterId claim for chapter $chapterId" }
                        inFlightChapterIds.remove(chapterId)
                    }
                }
                claimed = true
            }
            val reconciliation = translateChapterInternal(translation)
            if (translation.status == Translation.State.TRANSLATED ||
                (
                    translation.status == Translation.State.READY_WITH_WARNINGS &&
                        reconciliation?.nonDurableFailure != true
                    )
            ) {
                removeFromQueue(translation)
            }
            if (translation.status != Translation.State.PAUSED &&
                reconciliation?.nonDurableFailure != true &&
                areAllTranslationsFinished()
            ) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // One chapter's unexpected failure must not kill the rest of the
            // queue: mark the chapter failed so the queue loop advances.
            logcat(LogPriority.ERROR, e)
            translation.status = Translation.State.ERROR
            if (areAllTranslationsFinished()) {
                stop()
            }
        } finally {
            // Release the claim only after the batch coroutine fully unwinds,
            // so another admission cannot overlap its remaining work.
            if (claimed) {
                inFlightChapterIds.remove(chapterId)
                inFlightClaimReleasedSignal.tryEmit(Unit)
            }
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    internal fun isChapterTranslationInFlight(chapterId: Long?): Boolean =
        chapterId != null && inFlightChapterIds.contains(chapterId)

    /**
     * Cancels the batch translator job AND waits for it to unwind, bounded by
     * [BATCH_JOIN_TIMEOUT_MS]. Used only by the delete path: a chapter's on-disk
     * translation file + images must not be deleted while the batch coroutine
     * still holds the old [ChapterTranslationStore] reference and may write
     * RUNNING/rendered results into it. Joining guarantees the job reaches a
     * suspension point (finally blocks run, permit released) before
     * [TranslationManager.deleteTranslation] deletes files. Plain
     * [cancelTranslatorJob] stays non-blocking so pause/stop/clearQueue keep
     * their behaviour. On timeout this returns anyway; the defunct-store guard
     * rejects subsequent writes and NativeRunQuarantine invalidates the timed-out
     * native generation.
     */
    suspend fun cancelTranslatorJobAndJoin(timeoutMs: Long = BATCH_JOIN_TIMEOUT_MS) {
        cancelTranslatorJobAndJoinResult(timeoutMs)
    }

    suspend fun cancelTranslatorJobAndJoinResult(timeoutMs: Long = BATCH_JOIN_TIMEOUT_MS): Boolean {
        val job = translationJob ?: return true
        translationJob = null
        val joined = withTimeoutOrNull(timeoutMs) { job.cancelAndJoin() } != null
        if (!joined) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch translator job did not unwind within " +
                    "$timeoutMs ms; proceeding (defunct guard + " +
                    "native-run quarantine will neutralize any late write)"
            }
        }
        return joined
    }

    /**
     * Join the job captured by [pauseForSessionSwitch], retrying the same
     * parent after a timeout. This is intentionally separate from the delete
     * path above: existing delete/reset callers retain their historical
     * timeout behavior, while a session switch must never lose the old job
     * between attempts.
     */
    suspend fun cancelTranslatorJobAndJoinForSession(timeoutMs: Long): Boolean {
        val job = translationJob
        if (job != null) {
            translationJob = null
            job.cancel()
            sessionSwitchProtocol.retain(job)
        }
        return sessionSwitchProtocol.joinAll(timeoutMs)
    }

    fun isQueueConfigValid(): Boolean = isTranslationQueueConfigValid(translationPreferences)

    fun queueChapter(
        manga: Manga,
        chapter: Chapter,
        // Optional source-count evidence from the trigger's admission probe.
        // Defaults keep callers without that probe on the same path.
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
        colorizeOnly: Boolean = false,
    ): Boolean {
        val chapterId = chapter.id ?: return false
        val source = sourceManager.get(manga.source) as? HttpSource ?: return false
        val existingResult = synchronized(queueMutationLock) {
            if (replacementReservations.containsKey(chapterId)) return@synchronized false
            val existing = _queueState.value.firstOrNull { it.chapter.id == chapterId }
                ?: return@synchronized null
            if (existing.status != Translation.State.TRANSLATING) {
                // A re-arm adopts the caller's latest intent: re-clicking
                // "Colorize chapter" over a stopped TRANSLATION entry (or
                // vice versa) must not resume the previous run's mode.
                val modeChanged = existing.colorizeOnly != colorizeOnly
                existing.colorizeOnly = colorizeOnly
                rearmQueuedChapter(chapterId)
                if (modeChanged && existing.status == Translation.State.QUEUE) persistQueue()
            }
            true
        }
        if (existingResult != null) return existingResult
        // A colorize-only run never translates, so an invalid or unsupported
        // language configuration is irrelevant and must not block queueing.
        val fromLang: TextRecognizerLanguage
        val toLang: TextTranslatorLanguage
        if (colorizeOnly) {
            fromLang = TextRecognizerLanguage.CHINESE
            toLang = TextTranslatorLanguage.ENGLISH
        } else {
            // Invalid persisted configuration must not crash this UI action or
            // queue a batch that would fail every page with the same error. Catch
            // conversion failures and surface them as a toast.
            try {
                fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
                toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
            } catch (e: IllegalArgumentException) {
                logcat(LogPriority.ERROR, e) { "TachiyomiAT queueChapter aborted: invalid translation config" }
                scope.launchUI {
                    context.toast(e.message ?: "Invalid translation configuration")
                }
                return false
            }
            if (TranslationEngineBuilder.isMlKitActive(translationPreferences) &&
                !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)
            ) {
                scope.launchUI {
                    context.toast(ATMR.strings.error_mlkit_language_unsupported)
                }
                return false
            }
        }
        val executionMode = if (translationPreferences.translationRequireCharging().get()) {
            BatchExecutionMode.ONLY_WHILE_CHARGING
        } else {
            BatchExecutionMode.STANDARD
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang, executionMode)
        if (sourceCountKnown) {
            translation.probedSourcePageCount = probedSourcePageCount
            translation.sourceCountKnown = true
        }
        translation.colorizeOnly = colorizeOnly
        return addToQueue(translation)
    }

    // Internal so focused tests can drive the exact
    // exceptional exits (missing files, unexpected exceptions) without the
    // async queue worker.
    internal suspend fun translateChapterInternal(translation: Translation): ReconciliationResult? {
        logcat(LogPriority.INFO) {
            "TachiyomiAT: translateChapterInternal starting for ${translation.chapter.name} " +
                "(colorizeOnly=${translation.colorizeOnly})"
        }
        return chapterPipeline.execute(
            translation = translation,
            isJobActive = { translationJob?.isActive == true },
            isTranslationQueued = { queueState.value.contains(it) },
        )
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none {
            it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
        }
    }

    private fun addToQueue(translation: Translation): Boolean = synchronized(queueMutationLock) {
        val chapterId = translation.chapter.id ?: return@synchronized false
        if (replacementReservations.containsKey(chapterId)) return@synchronized false
        val existing = _queueState.value.firstOrNull { it.chapter.id == translation.chapter.id }
        if (existing != null) {
            if (existing.status != Translation.State.TRANSLATING) {
                existing.status = Translation.State.QUEUE
                markQueueUnpaused()
                _queueState.update { it.toList() }
                persistQueue()
            }
            return@synchronized true
        }
        translation.status = Translation.State.QUEUE
        _queueState.update {
            it + translation
        }
        persistQueue()
        true
    }

    private fun removeFromQueue(translation: Translation) {
        synchronized(queueMutationLock) {
            _queueState.update {
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
                it - translation
            }
            persistQueue()
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        synchronized(queueMutationLock) {
            _queueState.update { queue ->
                val translations = queue.filter { predicate(it) }
                translations.forEach { translation ->
                    if (translation.status == Translation.State.TRANSLATING ||
                        translation.status == Translation.State.QUEUE
                    ) {
                        translation.status = Translation.State.NOT_TRANSLATED
                    }
                }
                queue - translations
            }
            persistQueue()
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    /** Public batch entry: acquire session ownership, start queued work, and expose foreground work. */
    fun startWithSessionAdmission(): Boolean {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return false
        val chapterIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        if (sessionCoordinator?.requestBatchSession(chapterIds) == false) {
            logcat(LogPriority.WARN) { "BatchTranslationCoordinator: batch session rejected for $chapterIds" }
            return false
        }
        if (!isRunning) start()
        if (isAnyBatchTranslationActive) TranslationForegroundService.start(context)
        return isAnyBatchTranslationActive
    }

    /** Requeue a chapter only after the batch lane has acquired its session. */
    suspend fun requeueWithSessionAdmission(chapterId: Long, force: Boolean = false): Boolean {
        if (_batchQueuePauseState.value == BatchQueuePauseState.REQUESTED) return false
        if (sessionCoordinator?.requestBatchSession(setOf(chapterId)) == false) {
            logcat(LogPriority.WARN) { "TachiyomiAT requeue: batch session rejected for chapter $chapterId" }
            return false
        }
        return requeueExisting(chapterId, force)
    }

    /** Removes one queued chapter and resumes remaining work under this queue's ownership. */
    fun removeChapterAndResume(chapter: Chapter) {
        val wasRunning = isRunning
        if (wasRunning) pause()
        removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) stop() else start()
        }
        finishSessionIfQueueIdle()
    }

    private fun finishSessionIfQueueIdle() {
        if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
            sessionCoordinator?.finishSession(TranslationSessionState.BATCH_SESSION)
        }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        synchronized(queueMutationLock) {
            _queueState.update {
                it.forEach { translation ->
                    if (translation.status == Translation.State.TRANSLATING ||
                        translation.status == Translation.State.QUEUE
                    ) {
                        translation.status = Translation.State.NOT_TRANSLATED
                    }
                }
                emptyList()
            }
            persistQueue()
        }
    }

    override fun close() {
        cancelTranslatorJob()
        sessionSwitchProtocol.clear()
        activeChapters.clear()
    }
}

/** Replays each queued translation's current status, then follows its live state flow. */
internal fun translationQueueStatusFlow(queue: StateFlow<List<Translation>>): Flow<Translation> =
    queue.flatMapLatest { translations ->
        translations
            .map { translation ->
                translation.statusFlow
                    .drop(1)
                    .map { translation }
                    .onStart { emit(translation) }
            }
            .merge()
    }
