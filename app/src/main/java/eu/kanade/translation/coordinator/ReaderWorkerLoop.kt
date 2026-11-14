package eu.kanade.translation.coordinator

import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.ChapterTranslationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/** [claimNext] must claim and register under the coordinator mutex to prevent duplicate admission. */
internal interface ReaderWorkerGateway {
    suspend fun claimNext(): ReaderTranslationCoordinator.PageRequest?
    fun isBatchYielding(chapterKey: String): Boolean
    fun isFinalInStore(request: ReaderTranslationCoordinator.PageRequest): Boolean
    fun publishOutcome(chapterKey: String, pageKey: String, outcome: SinglePageOutcome, identity: ChapterTranslationStore.Identity?)
    fun setActiveKey(pageKey: String)
    fun clearActiveKey(pageKey: String)
    fun isPausedForSwitch(): Boolean
    suspend fun onDrained()
}

/** Serial reader worker and owner of its trigger channel and in-flight registries. */
internal class ReaderWorkerLoop(
    private val scope: CoroutineScope,
    private val pipeline: ReaderPagePipeline,
    private val prepareEngines: suspend () -> Unit,
    private val gateway: ReaderWorkerGateway,
) {

    // Event channel to wake up worker on new work instead of polling delay
    private val triggerChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val requestJobs = ConcurrentHashMap<String, Job>()
    private val requestChapterKeys = ConcurrentHashMap<String, String>()
    private val automaticRequestKeys = ConcurrentHashMap.newKeySet<String>()

    private var workerJob: Job? = null

    fun start() {
        if (workerJob != null) return
        workerJob = scope.launch {
            for (trigger in triggerChannel) {
                while (isActive) {
                    val nextRequest = gateway.claimNext() ?: break

                    val fullKey = "${nextRequest.chapterKey}:${nextRequest.pageKey}"
                    var requestJob: Job? = null
                    try {
                        // Check mutually exclusive batch gate
                        if (gateway.isBatchYielding(nextRequest.chapterKey)) {
                            logcat(LogPriority.INFO) {
                                "ReaderTranslationCoordinator: yielding $fullKey because batch is active on chapter"
                            }
                            continue
                        }

                        // Check if already completed in store
                        if (gateway.isFinalInStore(nextRequest)) {
                            logcat(LogPriority.DEBUG) {
                                "ReaderTranslationCoordinator: page $fullKey already complete, skipping"
                            }
                            gateway.publishOutcome(
                                nextRequest.chapterKey,
                                nextRequest.pageKey,
                                SinglePageOutcome.Completed,
                                nextRequest.session.store.currentIdentity,
                            )
                            continue
                        }

                        gateway.setActiveKey(nextRequest.pageKey)
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            val outcome = try {
                                prepareEngines()
                                pipeline.processRequest(nextRequest)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                logcat(LogPriority.ERROR, t) {
                                    "ReaderTranslationCoordinator: unexpected failure processing $fullKey"
                                }
                                SinglePageOutcome.Failed(nextRequest.pageKey, t.message ?: "Translation failed")
                            }
                            if (isActive) {
                                gateway.publishOutcome(
                                    nextRequest.chapterKey,
                                    nextRequest.pageKey,
                                    outcome,
                                    nextRequest.session.store.currentIdentity,
                                )
                            }
                        }
                        requestJob = job
                        requestJobs[fullKey] = job
                        job.invokeOnCompletion {
                            requestJobs.remove(fullKey, job)
                            requestChapterKeys.remove(fullKey)
                            automaticRequestKeys.remove(fullKey)
                            inFlight.remove(fullKey)
                            gateway.clearActiveKey(nextRequest.pageKey)
                        }
                        if (gateway.isPausedForSwitch()) {
                            job.cancel()
                            break
                        }
                        job.start()
                        job.join()
                    } finally {
                        requestJob?.let { requestJobs.remove(fullKey, it) }
                        requestChapterKeys.remove(fullKey)
                        automaticRequestKeys.remove(fullKey)
                        inFlight.remove(fullKey)
                        gateway.clearActiveKey(nextRequest.pageKey)
                    }
                }
                gateway.onDrained()
            }
        }
    }

    /** Runs while the coordinator still holds its mutex, keeping claim atomic. */
    fun registerClaim(request: ReaderTranslationCoordinator.PageRequest) {
        val fullKey = "${request.chapterKey}:${request.pageKey}"
        inFlight.add(fullKey)
        requestChapterKeys[fullKey] = request.chapterKey
        if (!request.isManual) automaticRequestKeys.add(fullKey)
    }

    fun nudge() {
        triggerChannel.trySend(Unit)
    }

    fun trackedJobs(): Collection<Job> = requestJobs.values

    fun isIdle(): Boolean = inFlight.isEmpty() && requestJobs.isEmpty()

    fun isInFlight(fullKey: String): Boolean = inFlight.contains(fullKey)

    fun isTracked(fullKey: String): Boolean = requestChapterKeys.containsKey(fullKey)

    fun automaticKeysFor(chapterKey: String): List<String> =
        automaticRequestKeys.filter { it.startsWith("$chapterKey:") }

    fun chapterKeysFor(chapterKey: String): Set<String> =
        requestChapterKeys.filterValues { it == chapterKey }.keys

    fun trackedChapterKeys(): Collection<String> = requestChapterKeys.values

    suspend fun cancelAndJoin(keys: Collection<String>) {
        for (key in keys) {
            while (requestChapterKeys.containsKey(key) && requestJobs[key] == null) yield()
            requestJobs[key]?.cancelAndJoin()
        }
    }

    fun stop() {
        triggerChannel.close()
        workerJob?.cancel()
        workerJob = null
    }

    fun clearTracked() {
        requestJobs.values.forEach(Job::cancel)
        requestJobs.clear()
        requestChapterKeys.clear()
        automaticRequestKeys.clear()
    }
}
