package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.processing.MemoryPressurePolicy
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.TranslationStageSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Reader translation: manual priority, one in-flight page to bound memory, dedupe, batch yield, store commit. */
internal class ReaderTranslationCoordinator(
    private val processor: TranslationProcessor,
    private val isBatchActiveForChapter: (String) -> Boolean = { false },
    private val resolveSessionStore: suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore? = { _, _, _ -> null },
    private val admitReaderSession: (Long) -> Boolean = { true },
    private val beforeOpenSession: suspend (Long) -> Unit = { },
    private val manualStreamProvider: (Manga, Chapter, HttpSource, String) -> (() -> java.io.InputStream)? = { _, _, _, _ -> null },
    private val isAutoTranslateEnabled: () -> Boolean = { true },
    private val prepareEngines: suspend () -> Unit = { },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry? = { null },
    private val batchCoordinator: BatchTranslationCoordinator? = null,
    private val sessionCoordinator: TranslationSessionCoordinator? = null,
    private val clearPendingRequests: () -> Unit = { },
    private val disposeBatchTracker: (Long) -> Unit = { },
    private val unregisterActiveStore: suspend (Long) -> Unit = { },
    private val retryPendingRekeysAfterReaderLeave: suspend () -> Unit = { },
    autoStart: Boolean = true,
) : Closeable {

    data class PageRequest(
        val session: TranslationSession,
        val chapterKey: String,
        val pageKey: String,
        val isManual: Boolean,
        val streamFn: (() -> java.io.InputStream)?,
        val force: Boolean = false,
    )

    private val mutex = Mutex()
    private val readerTeardownMutex = Mutex()
    private val activeStores get() = activeStoresProvider()
    private val manualQueue = LinkedHashMap<String, PageRequest>()
    private val autoQueue = LinkedHashMap<String, PageRequest>()
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val automaticQueuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val autoEnabledChapters = ConcurrentHashMap.newKeySet<String>()
    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    private val sessionBindingMutex = Mutex()
    private val readerGeneration = AtomicLong()
    private val readerStopEpoch = AtomicLong()
    private val chapterCancellations = ConcurrentHashMap.newKeySet<String>()
    private val chapterReplacementCancellations = ConcurrentHashMap.newKeySet<String>()
    private val chapterCancellationJobs = ConcurrentHashMap<String, Job>()
    private val chapterCancellationLock = Any()
    private val outcomes = ReaderOutcomeStore()
    private val staleOutcomes = MutableStateFlow<Map<String, SinglePageOutcome>>(emptyMap())
    private val pendingSessionPreparations = AtomicInteger(0)
    private val sessionSwitchProtocol = SessionSwitchJoinProtocol()

    @Volatile
    private var lastReaderStop: ReaderStopTicket? = null

    @Volatile
    private var activeSessionChapterKey: String? = null

    @Volatile
    private var readerStopInFlight = false

    @Volatile
    private var batchSwitchPauseRequested = false

    private val _activePageKey = MutableStateFlow<String?>(null)
    val activePageKey: StateFlow<String?> = _activePageKey.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** Full chapter:page keys isolate stage signals; attempt tokens prevent stale updates. */
    private val stageBoard = TranslationStageSignalBoard()
    val pageStageSignalFlow = stageBoard.flow

    private val pagePipeline = ReaderPagePipeline(
        processor = processor,
        stageBoard = stageBoard,
    )

    // Queue state and admission fences stay on the coordinator; the loop owns
    // the channel and in-flight registries and reaches back via this gateway.
    private val worker: ReaderWorkerLoop = ReaderWorkerLoop(
        scope = scope,
        pipeline = pagePipeline,
        prepareEngines = prepareEngines,
        gateway = object : ReaderWorkerGateway {
            override suspend fun claimNext(): PageRequest? = mutex.withLock {
                if (batchSwitchPauseRequested) return@withLock null
                val manualKey = manualQueue.keys.firstOrNull()
                val req = if (manualKey != null) {
                    manualQueue.remove(manualKey)
                } else {
                    val autoKey = autoQueue.keys.firstOrNull()
                    if (autoKey != null) autoQueue.remove(autoKey) else null
                }
                req?.let { request ->
                    val fullKey = "${request.chapterKey}:${request.pageKey}"
                    queuedKeys.remove(fullKey)
                    if (!request.isManual) automaticQueuedKeys.remove(fullKey)
                    worker.registerClaim(request)
                }
                updatePendingCountLocked()
                req
            }

            override fun isBatchYielding(chapterKey: String): Boolean = isBatchActiveForChapter(chapterKey)

            override fun isFinalInStore(request: PageRequest): Boolean =
                isReaderPageWorkFinal(request.session.store.state.value[request.pageKey], request.force)

            override fun publishOutcome(chapterKey: String, pageKey: String, outcome: SinglePageOutcome, identity: ChapterTranslationStore.Identity?) {
                // A tagged outcome from any store state other than the bound one
                // can never commit; publishing it would poison the replacement
                // session's retry gate.
                if (identity != null && sessionBindings[chapterKey]?.session?.store?.currentIdentity != identity) return
                outcomes.publish(chapterKey, pageKey, outcome, identity)
            }

            override fun setActiveKey(pageKey: String) {
                _activePageKey.value = pageKey
            }

            override fun clearActiveKey(pageKey: String) {
                if (_activePageKey.value == pageKey) _activePageKey.value = null
            }

            override fun isPausedForSwitch(): Boolean = batchSwitchPauseRequested

            override suspend fun onDrained() {
                releaseSessionIfIdle()
            }
        },
    )

    // Viewport demand and revision fencing live in the controller; queue,
    // session, and worker state stay here behind these gates.
    private val viewport = ReaderViewportController(
        scope = scope,
        resolveSession = ::sessionFor,
        isPausedForSwitch = { batchSwitchPauseRequested },
        isBatchActive = isBatchActiveForChapter,
        isAutoTranslateEnabled = isAutoTranslateEnabled,
        isAutoEnabled = { it in autoEnabledChapters },
        setAutoEnabled = { chapterKey, enabled -> setAutoEnabled(chapterKey, enabled) },
        enqueueAuto = { request -> enqueuePage(request) },
        trimWindow = { chapterKey, keys -> updateViewportWindow(chapterKey, keys) },
        releaseIfIdle = { releaseSessionIfIdle() },
        forEachBoundChapter = { action ->
            sessionBindings.values.forEach { binding ->
                binding.session.chapter.id?.let { action(it, binding.token) }
            }
        },
    )

    private data class SessionBinding(
        val token: ReaderSessionToken,
        val session: TranslationSession,
        val computeClass: TranslatorComputeClass,
        val boundStopEpoch: Long,
    )

    init {
        if (autoStart) {
            startWorker()
        }
    }

    fun startWorker() = worker.start()

    fun beginSessionPreparation() {
        pendingSessionPreparations.incrementAndGet()
    }

    suspend fun endSessionPreparationAndReleaseIfIdle() {
        val remaining = pendingSessionPreparations.decrementAndGet()
        if (remaining <= 0) {
            pendingSessionPreparations.compareAndSet(remaining, 0)
            releaseSessionIfIdle()
        }
    }

    fun endSessionPreparationAndReleaseIfIdleAsync() {
        val remaining = pendingSessionPreparations.decrementAndGet()
        if (remaining <= 0) {
            pendingSessionPreparations.compareAndSet(remaining, 0)
            scope.launch { releaseSessionIfIdle() }
        }
    }

    /** Releases READER_SESSION -> IDLE once no queued, active, or preparing work remains. */
    suspend fun releaseSessionIfIdle() {
        val sessions = sessionCoordinator ?: return
        if (sessions.state.value != TranslationSessionState.READER_SESSION) return
        sessionBindingMutex.withLock {
            if (sessions.state.value != TranslationSessionState.READER_SESSION) return@withLock
            val shouldRelease = mutex.withLock {
                manualQueue.isEmpty() &&
                    autoQueue.isEmpty() &&
                    worker.isIdle() &&
                    autoEnabledChapters.isEmpty() &&
                    pendingSessionPreparations.get() == 0
            }
            if (shouldRelease) {
                sessionBindings.clear()
                activeSessionChapterKey = null
                viewport.reset()
                sessions.finishSession(TranslationSessionState.READER_SESSION)
            }
        }
    }

    fun pauseForSessionSwitch() {
        batchSwitchPauseRequested = true
        worker.trackedJobs().forEach { job ->
            sessionSwitchProtocol.retain(job)
            job.cancel()
        }
    }

    suspend fun joinForSessionSwitch(timeoutMs: Long): Boolean {
        sessionBindingMutex.withLock {
            mutex.withLock {
                manualQueue.clear()
                autoQueue.clear()
                queuedKeys.clear()
                automaticQueuedKeys.clear()
                autoEnabledChapters.clear()
                viewport.reset()
                sessionBindings.clear()
                activeSessionChapterKey = null
                updatePendingCountLocked()
            }
            worker.trackedJobs().forEach { job ->
                sessionSwitchProtocol.retain(job)
                job.cancel()
            }
        }
        chapterCancellationJobs.values.forEach(sessionSwitchProtocol::retain)
        val joined = sessionSwitchProtocol.joinAll(timeoutMs)
        return joined && worker.isIdle()
    }

    suspend fun switchToBatchSession(chapterIds: Set<Long>): SessionAdmission {
        return try {
            val sessions = sessionCoordinator
            if (sessions == null) {
                pauseForSessionSwitch()
                joinForSessionSwitch(timeoutMs = 3_000L)
                SessionAdmission.Admitted(TranslationSessionState.BATCH_SESSION)
            } else {
                withContext(Dispatchers.IO) {
                    sessions.switchReaderToBatch(
                        intent = BatchSessionIntent(chapterIds),
                        pauseReader = ::pauseForSessionSwitch,
                        joinReader = ::joinForSessionSwitch,
                        onJoinTimeout = { timeoutMs ->
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT batch admission waiting for reader unwind after $timeoutMs ms"
                            }
                        },
                    )
                }
            }
        } finally {
            batchSwitchPauseRequested = false
        }
    }

    suspend fun switchReaderSession(chapterId: Long?): SessionAdmission {
        val sessions = checkNotNull(sessionCoordinator) { "Reader session admission is not wired" }
        val batches = checkNotNull(batchCoordinator) { "Batch coordinator is not wired" }
        return withContext(Dispatchers.IO) {
            sessions.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = chapterId, confirmBatchSwitch = true),
                pauseBatch = batches::pauseForSessionSwitch,
                joinBatch = batches::cancelTranslatorJobAndJoinForSession,
                onJoinTimeout = { timeoutMs ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT reader admission waiting for batch unwind after $timeoutMs ms"
                    }
                },
            )
        }
    }

    fun onMemoryPressure(level: Int) {
        val batches = batchCoordinator
        batches?.onMemoryPressure(level, MemoryPressurePolicy.classify(level))
        reconcileAll(admissionGuard = { chapterId ->
            batches?.isChapterBatchActive(chapterId, includePaused = true) != true
        })
    }

    /**
     * Opens a session for direct (untransactional) use. Store resolution runs
     * under the chapter's admission lock via the lock-held [resolveSessionStore]
     * variant — the kotlinx Mutex is non-reentrant.
     */
    suspend fun openSession(manga: Manga, chapter: Chapter, source: HttpSource): TranslationSession? {
        val chapterId = chapter.id ?: return null
        val stores = activeStores ?: return openSessionAdmitted(manga, chapter, source)
        return stores.withChapterAdmissionLock(chapterId) { openSessionAdmitted(manga, chapter, source) }
    }

    /**
     * Binds a reader token to its live store, invalidating an older chapter
     * binding. A bind arriving while a reader stop enumerates waits on the
     * binding mutex and records the epoch, so the stop skips this newer session.
     */
    suspend fun bindSession(
        session: TranslationSession,
        computeClass: TranslatorComputeClass,
    ): ReaderSessionToken? {
        val chapterId = session.chapter.id ?: return null
        val chapterKey = chapterId.toString()
        chapterCancellationJobs[chapterKey]?.join()
        return sessionBindingMutex.withLock {
            if (batchSwitchPauseRequested ||
                readerStopInFlight ||
                chapterKey in chapterCancellations ||
                chapterKey in chapterReplacementCancellations ||
                isBatchActiveForChapter(chapterKey)
            ) {
                return@withLock null
            }
            val previousChapterKey = activeSessionChapterKey
            if (previousChapterKey != null && previousChapterKey != chapterKey) {
                chapterCancellationJobs[previousChapterKey]?.join()
                cancelChapter(previousChapterKey)
            }
            if (batchSwitchPauseRequested ||
                readerStopInFlight ||
                chapterKey in chapterCancellations ||
                chapterKey in chapterReplacementCancellations ||
                isBatchActiveForChapter(chapterKey) ||
                !admitReaderSession(chapterId)
            ) {
                return@withLock null
            }
            val existing = sessionBindings[chapterKey]
            if (existing != null &&
                existing.session.key == session.key &&
                existing.session.store === session.store &&
                existing.computeClass == computeClass
            ) {
                activeSessionChapterKey = chapterKey
                return@withLock existing.token
            }
            if (existing != null) cancelChapter(chapterKey)
            val token = ReaderSessionToken(session.key, readerGeneration.incrementAndGet())
            sessionBindings[chapterKey] = SessionBinding(token, session, computeClass, readerStopEpoch.get())
            activeSessionChapterKey = chapterKey
            token
        }
    }

    /**
     * Atomically resolves the chapter's active store and binds the session
     * under the same admission lock eviction takes, so a bind never straddles
     * an eviction. [resolveSessionStore] must not re-take the non-reentrant lock.
     */
    suspend fun admitReaderSessionUse(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        computeClass: TranslatorComputeClass,
    ): ReaderAdmission {
        val chapterId = chapter.id ?: return ReaderAdmission.StoreUnavailable
        val expectedKey = "${source.id}:${manga.id}:$chapterId"
        val bind: suspend () -> ReaderAdmission = {
            val chapterKey = chapterId.toString()
            val existing = sessionBindings[chapterKey]?.session
            val session = if (existing?.key == expectedKey) existing else openSessionAdmitted(manga, chapter, source)
            when {
                session == null -> ReaderAdmission.StoreUnavailable
                else -> when (val token = bindSession(session, computeClass)) {
                    null -> ReaderAdmission.SessionDenied
                    else -> {
                        // Outcomes tagged with a superseded store identity can never
                        // commit; dropping those blocking rejections lets the freshly
                        // bound session retry the affected pages.
                        outcomes.removeStaleRejections(chapterKey, session.store.currentIdentity)
                        ReaderAdmission.Admitted(token)
                    }
                }
            }
        }
        // Store resolution and binding are atomic against store eviction under
        // the chapter's admission lock; without a registry there is no eviction
        // to guard against.
        val stores = activeStores ?: return bind()
        return stores.withChapterAdmissionLock(chapterId, bind)
    }

    private suspend fun openSessionAdmitted(manga: Manga, chapter: Chapter, source: HttpSource): TranslationSession? {
        val chapterId = chapter.id ?: return null
        beforeOpenSession(chapterId)
        val store = resolveSessionStore(manga, chapter, source) ?: return null
        return TranslationSession("${source.id}:${manga.id}:$chapterId", manga, chapter, source, store)
    }

    fun sessionFor(token: ReaderSessionToken): TranslationSession? =
        sessionBindings.values.firstOrNull { it.token == token }?.session

    fun sessionForChapter(chapterKey: String): TranslationSession? = sessionBindings[chapterKey]?.session

    fun sessionTokenForChapter(chapterKey: String): ReaderSessionToken? = sessionBindings[chapterKey]?.token

    /** Invalidates a session synchronously while the caller holds a chapter admission fence. */
    fun invalidateSession(chapterKey: String) {
        sessionBindings.remove(chapterKey)
        if (activeSessionChapterKey == chapterKey) activeSessionChapterKey = null
    }

    /** Cancels page work without waiting for non-cooperative provider calls to unwind. */
    fun cancelChapterAsync(chapterKey: String) {
        chapterCancellations.add(chapterKey)
        invalidateSession(chapterKey)
        synchronized(chapterCancellationLock) {
            if (chapterCancellationJobs[chapterKey]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                cancelChapter(chapterKey)
                releaseSessionIfIdle()
            }
            chapterCancellationJobs[chapterKey] = job
            job.invokeOnCompletion {
                synchronized(chapterCancellationLock) {
                    if (chapterCancellationJobs[chapterKey] === job) chapterCancellationJobs.remove(chapterKey)
                }
            }
            job.start()
        }
    }

    suspend fun submitPage(
        token: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission {
        val session = sessionFor(token) ?: return rejectedSubmission(trigger, PageRejectionKind.SESSION_DENIED, "reader session is no longer active")
        val chapterId = session.chapter.id ?: return rejectedSubmission(trigger, PageRejectionKind.OTHER, "chapter id missing")
        val chapterKey = chapterId.toString()
        if (batchSwitchPauseRequested || isBatchActiveForChapter(chapterKey)) {
            return rejectedSubmission(trigger, PageRejectionKind.SESSION_DENIED, "batch owns chapter")
        }
        val isManual = trigger == ReaderTrigger.MANUAL_TAP
        val previousOutcome = outcomes.peek(chapterId.toString(), work.pageKey)
        if (previousOutcome != null && !force && !isReaderOutcomeRetryable(previousOutcome, isManual)) {
            return ReaderSubmission.Existing(previousOutcome)
        }
        val accepted = enqueuePage(
            PageRequest(
                session = session,
                chapterKey = chapterKey,
                pageKey = work.pageKey,
                isManual = isManual,
                streamFn = work.streamFn,
                force = force,
            ),
        )
        return if (accepted) ReaderSubmission.Queued(work.pageKey) else ReaderSubmission.Existing(outcomes.peek(chapterId.toString(), work.pageKey))
    }

    fun updateViewport(
        token: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> ReaderPageWork?,
    ) = viewport.updateViewport(token, pageIndex, pageCount, resolve)

    fun setAutoEnabled(token: ReaderSessionToken, enabled: Boolean): Boolean {
        val chapterKey = sessionFor(token)?.chapter?.id?.toString() ?: return false
        return setAutoEnabled(chapterKey, enabled)
    }

    fun reconcile(token: ReaderSessionToken, resolve: (Int) -> ReaderPageWork?) =
        viewport.reconcile(token, resolve)

    fun reconcileChapter(chapterKey: String) = viewport.reconcileChapter(chapterKey)

    fun reconcileAll(admissionGuard: (Long) -> Boolean = { true }) = viewport.reconcileAll(admissionGuard)

    /** Reader-direct colorization bypasses queue reporting; attempt tokens prevent stale signal clears. */
    suspend fun <T> withColorizeStageSignal(
        chapterId: Long,
        pageKey: String,
        block: suspend () -> T,
    ): T {
        val fullStageKey = "$chapterId:$pageKey"
        val stageAttempt = stageBoard.nextAttemptToken()
        stageBoard.publish(fullStageKey, TranslationStageSignal.COLORING, stageAttempt)
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                stageBoard.clear(fullStageKey, stageAttempt)
            }
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String, force: Boolean = false) {
        beginSessionPreparation()
        scope.launch {
            try {
                when (val admission = admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)) {
                    ReaderAdmission.StoreUnavailable -> {
                        chapter.id?.let { outcomes.publish(it.toString(), pageKey, SinglePageOutcome.Failed(pageKey, "store unavailable")) }
                    }
                    ReaderAdmission.SessionDenied -> {
                        chapter.id?.let {
                            outcomes.publish(
                                it.toString(),
                                pageKey,
                                SinglePageOutcome.Rejected(null, PageRejectionKind.SESSION_DENIED, "reader session denied"),
                            )
                        }
                    }
                    is ReaderAdmission.Admitted -> {
                        val work = ReaderPageWork(pageKey, -1, manualStreamProvider(manga, chapter, source, pageKey))
                        submitPage(admission.token, work, ReaderTrigger.MANUAL_TAP, force)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    endSessionPreparationAndReleaseIfIdle()
                }
            }
        }
    }

    fun outcomeFor(chapterId: Long, pageKey: String): SinglePageOutcome? =
        outcomes.peek(chapterId.toString(), pageKey)

    fun outcomes(token: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>> {
        val chapterKey = sessionFor(token)?.chapter?.id?.toString() ?: return staleOutcomes.asStateFlow()
        return outcomesForChapter(chapterKey.toLong())
    }

    fun outcomesForChapter(chapterId: Long): StateFlow<Map<String, SinglePageOutcome>> =
        outcomes.flowForChapter(chapterId)

    private fun rejectedSubmission(
        trigger: ReaderTrigger,
        kind: PageRejectionKind,
        detail: String,
    ) = ReaderSubmission.Rejected(
        SinglePageOutcome.Rejected(
            owner = if (trigger == ReaderTrigger.MANUAL_TAP) PageWriteOrigin.MANUAL else PageWriteOrigin.AUTO,
            kind = kind,
            diagnosticDetail = detail,
        ),
    )

    /** Enqueues a page for translation; manual requests preempt all auto requests. */
    suspend fun enqueuePage(request: PageRequest): Boolean = mutex.withLock {
        if (batchSwitchPauseRequested) return false
        val fullKey = "${request.chapterKey}:${request.pageKey}"

        if (!request.isManual && request.chapterKey !in autoEnabledChapters) return false

        // Deduplication against in-flight
        if (worker.isInFlight(fullKey)) {
            return false
        }

        if (request.isManual) {
            if (fullKey in manualQueue) return false
            // Remove from auto if present and add to manual
            if (autoQueue.remove(fullKey) != null) {
                queuedKeys.remove(fullKey)
                automaticQueuedKeys.remove(fullKey)
            }
            outcomes.clear(request.chapterKey, request.pageKey)
            manualQueue[fullKey] = request
            queuedKeys.add(fullKey)
        } else {
            // Auto requests are ignored if already queued as manual
            if (!manualQueue.containsKey(fullKey) && !autoQueue.containsKey(fullKey)) {
                outcomes.clear(request.chapterKey, request.pageKey)
                autoQueue[fullKey] = request
                queuedKeys.add(fullKey)
                automaticQueuedKeys.add(fullKey)
            } else {
                return false
            }
        }

        updatePendingCountLocked()
        worker.nudge()
        true
    }

    /** Enables automatic demand for a chapter, or removes/cancels its automatic work. */
    fun setAutoEnabled(chapterKey: String, enabled: Boolean): Boolean {
        if (enabled) {
            autoEnabledChapters.add(chapterKey)
            return false
        }
        autoEnabledChapters.remove(chapterKey)
        val hadAutomaticDemand = worker.automaticKeysFor(chapterKey).isNotEmpty() ||
            automaticQueuedKeys.any { it.startsWith("$chapterKey:") }
        scope.launch {
            updateViewportWindow(chapterKey, emptySet())
            cancelRequestsAndJoin(worker.automaticKeysFor(chapterKey))
            releaseSessionIfIdle()
        }
        return hadAutomaticDemand
    }

    suspend fun updateViewportWindow(chapterKey: String, visibleOrNearPageKeys: Set<String>) = mutex.withLock {
        val removed = autoQueue.keys.filter { key ->
            key.startsWith("$chapterKey:") && key.substringAfter("$chapterKey:") !in visibleOrNearPageKeys
        }
        removed.forEach { key ->
            autoQueue.remove(key)
            queuedKeys.remove(key)
            automaticQueuedKeys.remove(key)
        }
        updatePendingCountLocked()
    }

    fun cancelPageAsync(chapterKey: String, pageKey: String): Boolean {
        val fullKey = "$chapterKey:$pageKey"
        val wasQueuedOrRunning = fullKey in queuedKeys || worker.isInFlight(fullKey)
        scope.launch { cancelPage(chapterKey, pageKey) }
        return wasQueuedOrRunning
    }

    suspend fun cancelPage(chapterKey: String, pageKey: String): Boolean {
        val fullKey = "$chapterKey:$pageKey"
        val wasQueued = mutex.withLock {
            val manualRemoved = manualQueue.remove(fullKey) != null
            val autoRemoved = autoQueue.remove(fullKey) != null
            queuedKeys.remove(fullKey)
            automaticQueuedKeys.remove(fullKey)
            updatePendingCountLocked()
            manualRemoved || autoRemoved
        }
        val active = worker.isTracked(fullKey)
        cancelRequestsAndJoin(listOf(fullKey))
        worker.nudge()
        releaseSessionIfIdle()
        return wasQueued || active
    }

    /** Cancels all queued and executing work for a chapter before its store is evicted. */
    suspend fun cancelChapter(chapterKey: String) {
        chapterCancellations.add(chapterKey)
        try {
            invalidateSession(chapterKey)
            viewport.drop(chapterKey)
            mutex.withLock {
                val queued = (manualQueue.keys + autoQueue.keys).filter { key ->
                    key.startsWith("$chapterKey:")
                }
                queued.forEach { key ->
                    manualQueue.remove(key)
                    autoQueue.remove(key)
                    queuedKeys.remove(key)
                    automaticQueuedKeys.remove(key)
                }
                autoEnabledChapters.remove(chapterKey)
                updatePendingCountLocked()
            }
            cancelRequestsAndJoin(worker.chapterKeysFor(chapterKey))
            outcomes.removeChapter(chapterKey)
        } finally {
            chapterCancellations.remove(chapterKey)
        }
    }

    private suspend fun cancelRequestsAndJoin(keys: Collection<String>) {
        worker.cancelAndJoin(keys)
    }

    /** Drains every reader request before a global reader teardown. Chapters bound at or after [skipChaptersBoundAtOrAfter] belong to a newer session and are left untouched. */
    suspend fun cancelAllAndJoin(skipChaptersBoundAtOrAfter: Long? = null) {
        readerStopInFlight = true
        try {
            sessionBindingMutex.withLock {
                val protectedChapters = skipChaptersBoundAtOrAfter?.let { epoch ->
                    sessionBindings.keys.filterTo(mutableSetOf()) { key ->
                        (sessionBindings[key]?.boundStopEpoch ?: Long.MIN_VALUE) >= epoch
                    }
                } ?: emptySet()
                val chapters = mutex.withLock {
                    (manualQueue.values + autoQueue.values).mapTo(linkedSetOf()) { it.chapterKey }
                }
                chapters += worker.trackedChapterKeys()
                chapters += autoEnabledChapters
                chapters += sessionBindings.keys
                chapters.removeAll(protectedChapters)
                chapters.forEach { cancelChapter(it) }
                // Cleared under the same mutex that bindSession re-checks, so a
                // queued bind never observes a stale in-flight flag.
                readerStopInFlight = false
            }
        } finally {
            readerStopInFlight = false
        }
    }

    /** Schedules joined reader teardown on the coordinator lifetime. */
    fun stopReaderTranslations(reason: String) {
        beginReaderStop(reason, terminal = false)
    }

    /**
     * Schedules a joined reader teardown and assigns its stop epoch synchronously,
     * so any session bound after this call is newer than the stop. Background stops
     * skip chapters bound at or after the epoch; terminal stops tear down everything.
     */
    fun beginReaderStop(reason: String, terminal: Boolean): ReaderStopTicket {
        val epoch = readerStopEpoch.incrementAndGet()
        val completion = scope.async(start = CoroutineStart.DEFAULT) {
            if (terminal) runTerminalReaderStop(reason) else runBackgroundReaderStop(epoch, reason)
        }
        val ticket = ReaderStopTicket(epoch, terminal, completion)
        lastReaderStop = ticket
        return ticket
    }

    /** Resolves once the latest reader stop — background or terminal — finished; false if it is still running after [timeoutMs]. */
    suspend fun awaitPendingReaderStop(timeoutMs: Long = 10_000L): Boolean {
        val stop = lastReaderStop ?: return true
        return withTimeoutOrNull(timeoutMs) {
            stop.await()
            true
        } ?: false
    }

    private suspend fun runTerminalReaderStop(reason: String) {
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                sessionCoordinator?.abortPausingToBatch()
                cancelAllAndJoin(skipChaptersBoundAtOrAfter = null)
                evictRegisteredStores(reason, skipChaptersBoundAtOrAfter = null, clearTransientQueues = false)
                sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
            }
            retryPendingRekeysAfterReaderLeave()
        }
    }

    private suspend fun runBackgroundReaderStop(stopEpoch: Long, reason: String) {
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                sessionCoordinator?.abortPausingToBatch()
                cancelAllAndJoin(skipChaptersBoundAtOrAfter = stopEpoch)
                evictRegisteredStores(reason, skipChaptersBoundAtOrAfter = stopEpoch, clearTransientQueues = true)
                // The batch side flips TRANSLATING->QUEUE and releases native
                // buffers. A session that survived this stop still owns the
                // engines, and active batch work keeps them too.
                if (!hasChapterBoundAtOrAfter(stopEpoch) && !isAnyBatchTranslationActive()) {
                    batchCoordinator?.stop(reason, closeEngines = false)
                }
                // A newer session survived this stop; it owns READER_SESSION.
                if (!hasChapterBoundAtOrAfter(stopEpoch)) {
                    sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
                }
            }
        }
    }

    fun requestReaderStop(reason: String): Deferred<Unit> =
        scope.async(start = CoroutineStart.DEFAULT) { beginReaderStop(reason, terminal = true).await() }

    suspend fun awaitReaderStop(reason: String) {
        beginReaderStop(reason, terminal = true).await()
    }

    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        cancelPageAsync(chapterId.toString(), pageKey)

    suspend fun cancelRunningChapterForReplace(expectedRun: Translation, expectedRunGeneration: Long): Boolean {
        val chapterId = expectedRun.chapter.id ?: return false
        val chapterKey = chapterId.toString()
        val reserved = sessionBindingMutex.withLock {
            val queue = batchCoordinator ?: return@withLock false
            if (!queue.reserveRunningChapterForReplace(expectedRun, expectedRunGeneration)) return@withLock false
            chapterReplacementCancellations.add(chapterKey)
            chapterCancellations.add(chapterKey)
            invalidateSession(chapterKey)
            true
        }
        if (!reserved) return false

        try {
            cancelChapter(chapterKey)
            activeStores?.get(chapterId)?.clearTransientQueuePages("Replaced by another chapter's batch")
            return batchCoordinator?.removeReplacedChapterIfCurrent(expectedRun, expectedRunGeneration) == true
        } finally {
            batchCoordinator?.releaseChapterReplacementReservation(expectedRun, expectedRunGeneration)
            chapterReplacementCancellations.remove(chapterKey)
            chapterCancellations.remove(chapterKey)
        }
    }

    suspend fun cancelPageTranslations(chapterId: Long, reason: String = "Translation cancelled") {
        cancelChapter(chapterId.toString())
        releaseSessionIfIdle()
        if (isChapterBatchActive(chapterId, includePaused = true)) return
        disposeBatchTracker(chapterId)
        withContext(Dispatchers.IO) {
            val stores = activeStores ?: return@withContext
            stores.withChapterAdmissionLock(chapterId) {
                stores.get(chapterId)?.clearTransientQueuePages(reason)
                unregisterActiveStore(chapterId)
            }
        }
    }

    suspend fun cancelAllPageTranslations(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) {
        cancelAllAndJoin(skipChaptersBoundAtOrAfter = null)
        evictRegisteredStores(
            reason = reason,
            skipChaptersBoundAtOrAfter = null,
            clearTransientQueues = true,
            includeBatchStores = cancelBatchQueue,
        )
        if (cancelBatchQueue) {
            batchCoordinator?.clearQueue()
            clearPendingRequests()
            sessionCoordinator?.finishSession()
        } else {
            sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
        }
    }

    suspend fun cancelAllPageTranslationsOffMain(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = withContext(Dispatchers.IO) {
        cancelAllPageTranslations(cancelBatchQueue, reason)
    }

    /**
     * Evicts registered stores under each chapter's admission lock, skipping
     * chapters bound at or after [skipChaptersBoundAtOrAfter]; batch-owned
     * stores are untouched unless [includeBatchStores] clears the queue with them.
     */
    private suspend fun evictRegisteredStores(
        reason: String,
        skipChaptersBoundAtOrAfter: Long?,
        clearTransientQueues: Boolean,
        includeBatchStores: Boolean = false,
    ) {
        val stores = activeStores ?: return
        val chapterIdsToEvict = stores.chapterIds()
            .filter { includeBatchStores || !isChapterBatchActive(it, includePaused = true) }
        chapterIdsToEvict.forEach { chapterId ->
            stores.withChapterAdmissionLock(chapterId) {
                if (skipChaptersBoundAtOrAfter != null && isChapterBoundAtOrAfter(chapterId, skipChaptersBoundAtOrAfter)) {
                    return@withChapterAdmissionLock
                }
                if (clearTransientQueues) stores.get(chapterId)?.clearTransientQueuePages(reason)
                unregisterActiveStore(chapterId)
            }
        }
    }

    private fun isChapterBoundAtOrAfter(chapterId: Long, stopEpoch: Long): Boolean =
        (sessionBindings[chapterId.toString()]?.boundStopEpoch ?: Long.MIN_VALUE) >= stopEpoch

    private fun hasChapterBoundAtOrAfter(stopEpoch: Long): Boolean =
        sessionBindings.values.any { it.boundStopEpoch >= stopEpoch }

    private fun isAnyBatchTranslationActive(): Boolean = batchCoordinator?.queueState?.value?.any {
        it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
    } == true

    private fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean =
        batchCoordinator?.queueState?.value?.any { translation ->
            translation.chapter.id == chapterId &&
                (
                    translation.status == Translation.State.QUEUE ||
                        translation.status == Translation.State.TRANSLATING ||
                        (includePaused && translation.status == Translation.State.PAUSED)
                    )
        } == true

    private fun updatePendingCountLocked() {
        _pendingCount.value = manualQueue.size + autoQueue.size
    }

    override fun close() {
        worker.stop()
        manualQueue.clear()
        autoQueue.clear()
        queuedKeys.clear()
        worker.clearTracked()
        automaticQueuedKeys.clear()
        autoEnabledChapters.clear()
        sessionBindings.clear()
        chapterCancellations.clear()
        chapterReplacementCancellations.clear()
        outcomes.clearAll()
        viewport.clear()
        sessionSwitchProtocol.clear()
        pendingSessionPreparations.set(0)
        batchSwitchPauseRequested = false
        activeSessionChapterKey = null
        updatePendingCountLocked()
    }
}
