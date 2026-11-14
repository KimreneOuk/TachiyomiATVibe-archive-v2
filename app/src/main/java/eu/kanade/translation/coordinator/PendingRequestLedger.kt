package eu.kanade.translation.coordinator

import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Live and durable request state; one lock and per-chapter fences protect mutations. */
internal class PendingRequestLedger(
    private val pendingRequestStore: TranslationPendingRequestStore,
    initialPendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>>? = null,
) {

    val mutationLock: Any = Any()

    private val liveRequests = initialPendingRequests ?: MutableStateFlow(
        pendingRequestStore.load().associateWith { chapterId ->
            val record = pendingRequestStore.record(chapterId)
            TranslationRequestState(
                chapterId = chapterId,
                phase = record?.phase ?: TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = pendingRequestStore.reason(chapterId),
                generation = record?.generation ?: 0L,
                failureKind = record?.failureKind ?: TranslationRequestFailureKind.NONE,
            )
        },
    )
    val state: StateFlow<Map<Long, TranslationRequestState>> = liveRequests.asStateFlow()

    private val pendingRequestWriteVersions = ConcurrentHashMap<Long, AtomicLong>()
    private val pendingRequestGenerationCounters = ConcurrentHashMap<Long, AtomicLong>()
    private val downloadAttachGenerations = ConcurrentHashMap<Long, Long>()
    private val pendingGroupIdSequence = AtomicLong(0)

    /** Prefers the live request, falling back to its durable record after a restart. */
    fun current(chapterId: Long): TranslationRequestState? =
        liveRequests.value[chapterId]
            ?: pendingRequestStore.record(chapterId)?.let { record ->
                TranslationRequestState(
                    chapterId = record.chapterId,
                    phase = record.phase,
                    reason = record.reason,
                    generation = record.generation,
                    failureKind = record.failureKind,
                )
            }

    /** Terminal requests cannot satisfy a generation fence; retries allocate a new generation. */
    fun isCurrent(chapterId: Long, generation: Long): Boolean =
        current(chapterId)?.let { request ->
            !request.isTerminal && request.generation == generation
        } == true

    /** Current in-process request generation, or null when no request exists. */
    fun generation(chapterId: Long): Long? =
        liveRequests.value[chapterId]?.generation

    fun durableChapterIds(): Set<Long> = pendingRequestStore.load()

    fun durablePhase(chapterId: Long): TranslationRequestPhase? = pendingRequestStore.phase(chapterId)

    /** Captures only the currently attached, live request for this download. */
    fun downloadAttachGeneration(chapterId: Long): Long? = synchronized(mutationLock) {
        val current = current(chapterId) ?: return@synchronized null
        current.generation.takeIf {
            !current.isTerminal && downloadAttachGenerations[chapterId] == current.generation
        }
    }

    /** Records the live generation the download's completion callback will be fenced against. */
    fun attachDownloadGeneration(chapterId: Long): Long? = synchronized(mutationLock) {
        liveRequests.value[chapterId]?.generation?.also { downloadAttachGenerations[chapterId] = it }
    }

    /** Publishes STARTING with one generation per request and one group ID per batch. */
    fun acknowledge(chapterIds: List<Long>): Map<Long, PendingAcknowledgement> = synchronized(mutationLock) {
        val generations = chapterIds.associateWith(::allocateGeneration)
        val groupId = nextGroupId()
        liveRequests.update { current ->
            current + chapterIds.associateWith { chapterId ->
                TranslationRequestState(
                    chapterId = chapterId,
                    phase = TranslationRequestPhase.STARTING,
                    generation = generations.getValue(chapterId),
                )
            }
        }
        chapterIds.associateWith { chapterId ->
            PendingAcknowledgement(
                version = nextPendingRequestVersion(chapterId),
                generation = generations.getValue(chapterId),
                groupId = groupId,
            )
        }
    }

    /** Stale download callbacks cannot admit or recreate a request. */
    fun beginHandoffIfCurrent(chapterId: Long): Long? = synchronized(mutationLock) {
        val current = current(chapterId) ?: return@synchronized null
        // A request that reached an explicit terminal phase (cancelled or
        // failed by the download side) can never be satisfied by a
        // completion callback.
        if (current.isTerminal) return@synchronized null
        val attachedGeneration = downloadAttachGenerations[chapterId]
        if (attachedGeneration == null || attachedGeneration != current.generation) {
            logcat(LogPriority.INFO) {
                "T911 dropped stale download-completion callback for chapter $chapterId " +
                    "(attachedGeneration=$attachedGeneration currentGeneration=${current.generation})"
            }
            return@synchronized null
        }
        set(chapterId, TranslationRequestPhase.PREPARING)
        current.generation
    }

    /** Checks the request, attachment and admission under the same lock as the retry write. */
    fun beginRekeyRetryIfCurrent(
        chapterId: Long,
        expectedGeneration: Long,
        isAdmissionBlocked: () -> Boolean,
    ): Long? = synchronized(mutationLock) {
        val current = current(chapterId) ?: return@synchronized null
        val retryableFailure =
            current.phase == TranslationRequestPhase.ADMISSION_FAILED &&
                current.failureKind == TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED &&
                current.isDownloadRekeyFailure()
        val waitingForReaderStore =
            current.phase == TranslationRequestPhase.PREPARING &&
                current.reason == REGISTERED_READER_STORE_WAIT_REASON
        if (
            current.generation != expectedGeneration ||
            (!retryableFailure && !waitingForReaderStore) ||
            downloadAttachGenerations[chapterId] != expectedGeneration ||
            isAdmissionBlocked()
        ) {
            return@synchronized null
        }
        set(
            chapterId = chapterId,
            phase = TranslationRequestPhase.PREPARING,
            reason = "Retrying translation after completed-download reconciliation",
        )
        current(chapterId)?.generation?.also { downloadAttachGenerations[chapterId] = it }
    }

    fun set(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    ) {
        synchronized(mutationLock) {
            nextPendingRequestVersion(chapterId)
            val current = liveRequests.value[chapterId]
            val durable = pendingRequestStore.record(chapterId)
            // Live transitions retain the attachment fence; retries and new records
            // allocate a generation that cannot match a cancelled request.
            val wasTerminal = current?.isTerminal == true ||
                (current == null && durable?.phase?.isTerminalPhase() == true)
            val generation = when {
                wasTerminal && !phase.isTerminalPhase() -> allocateGeneration(chapterId)
                current != null -> current.generation
                durable != null -> durable.generation
                else -> allocateGeneration(chapterId)
            }
            val groupId = durable?.groupId
            val kind = if (phase.isTerminalPhase()) failureKind else TranslationRequestFailureKind.NONE
            pendingRequestStore.add(
                TranslationPendingRequestRecord(
                    chapterId = chapterId,
                    phase = phase,
                    reason = reason,
                    generation = generation,
                    groupId = groupId,
                    failureKind = kind,
                    createdAtEpochMs = durable?.createdAtEpochMs ?: System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            liveRequests.update {
                it + (
                    chapterId to TranslationRequestState(
                        chapterId = chapterId,
                        phase = phase,
                        reason = reason,
                        generation = generation,
                        failureKind = kind,
                    )
                    )
            }
            BatchDownloadDiagnostics.requestPhase(
                chapterId = chapterId,
                generation = generation,
                from = current?.phase ?: durable?.phase,
                to = phase,
                failureKind = kind,
            )
        }
    }

    fun clear(chapterId: Long) {
        synchronized(mutationLock) {
            val current = current(chapterId)
            nextPendingRequestVersion(chapterId)
            // Generation bump on cancel/removal: a later request for the same
            // chapter must never reuse the removed request's generation.
            pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
                AtomicLong(pendingRequestStore.generation(chapterId))
            }.incrementAndGet()
            downloadAttachGenerations.remove(chapterId)
            pendingRequestStore.remove(chapterId)
            liveRequests.update { it - chapterId }
            if (current != null) {
                BatchDownloadDiagnostics.requestCleared(chapterId, current.generation, current.phase)
            }
        }
    }

    fun clearAll() {
        synchronized(mutationLock) {
            val requests = (liveRequests.value.keys + pendingRequestStore.load())
                .distinct()
                .mapNotNull(::current)
            requests.forEach { request ->
                val chapterId = request.chapterId
                nextPendingRequestVersion(chapterId)
                pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
                    AtomicLong(pendingRequestStore.generation(chapterId))
                }.incrementAndGet()
                downloadAttachGenerations.remove(chapterId)
            }
            pendingRequestStore.clear()
            liveRequests.value = emptyMap()
            requests.forEach { request ->
                BatchDownloadDiagnostics.requestCleared(request.chapterId, request.generation, request.phase)
            }
        }
    }

    /** The shared lock makes the STARTING version check and durable write atomic. */
    fun persistStartingAcknowledgement(
        chapterId: Long,
        acknowledgement: PendingAcknowledgement,
    ) {
        synchronized(mutationLock) {
            val currentVersion = pendingRequestWriteVersions[chapterId]?.get() ?: return
            val current = liveRequests.value[chapterId]
            if (current == null) {
                // A cancellation may have removed the request before this IO
                // task acquired the lock. Keep the durable store cleared.
                pendingRequestStore.remove(chapterId)
                return
            }
            if (currentVersion != acknowledgement.version ||
                current.phase != TranslationRequestPhase.STARTING
            ) {
                return
            }
            pendingRequestStore.add(
                TranslationPendingRequestRecord(
                    chapterId = chapterId,
                    phase = TranslationRequestPhase.STARTING,
                    reason = null,
                    generation = acknowledgement.generation,
                    groupId = acknowledgement.groupId,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun nextPendingRequestVersion(chapterId: Long): Long =
        pendingRequestWriteVersions.computeIfAbsent(chapterId) { AtomicLong() }.incrementAndGet()

    /** Continues per-chapter generations from durable state so retries cannot reuse an old generation. */
    private fun allocateGeneration(chapterId: Long): Long =
        pendingRequestGenerationCounters.computeIfAbsent(chapterId) {
            AtomicLong(
                maxOf(
                    pendingRequestStore.generation(chapterId),
                    liveRequests.value[chapterId]?.generation ?: 0L,
                ),
            )
        }.incrementAndGet()

    private fun nextGroupId(): String = "batch-${pendingGroupIdSequence.incrementAndGet()}"

    private fun TranslationRequestPhase.isTerminalPhase(): Boolean =
        this == TranslationRequestPhase.DOWNLOAD_FAILED ||
            this == TranslationRequestPhase.CANCELLED ||
            this == TranslationRequestPhase.ADMISSION_FAILED

    private fun TranslationRequestState.isDownloadRekeyFailure(): Boolean =
        reason?.startsWith("Rekey deferred:") == true ||
            reason?.startsWith("Rekey rejected:") == true ||
            reason?.startsWith("Rekey partial:") == true ||
            reason?.startsWith("Download re-key reconciliation ") == true

    internal data class PendingAcknowledgement(
        val version: Long,
        val generation: Long,
        val groupId: String,
    )
}
