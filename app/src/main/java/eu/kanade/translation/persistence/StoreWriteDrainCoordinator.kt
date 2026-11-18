package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Typed outcome of evicting/marking a translation store defunct. */
sealed interface StoreDefunctOutcome {
    data object Flushed : StoreDefunctOutcome
    data class FlushedWithDeferred(val deferredCount: Int) : StoreDefunctOutcome
    data class IncompleteRetained(val pendingCount: Int) : StoreDefunctOutcome
}

/** Serializes background image writes and coalesced lazy page snapshots. */
internal class StoreWriteDrainCoordinator(
    private val store: ChapterTranslationStore,
    dispatcher: CoroutineDispatcher,
) {
    private val drainScopeJob = SupervisorJob()
    private val drainScope = CoroutineScope(drainScopeJob + dispatcher)

    internal companion object {
        internal const val PERSIST_JOIN_TIMEOUT_MS = 2_000L
        internal const val PERSIST_DEBOUNCE_MS = 250L
    }

    private val mutex get() = store.mutex
    private val flushMutex = Mutex()

    // Queue state is mutated under the store mutex. Every flush shares one
    // mutex, so an explicit flush and the debounced worker cannot overlap.
    internal var drainJob: Job? = null
    internal val pendingLazyMutations = LinkedHashMap<String, PendingLazyMutation>()
    private var pendingUnregisterRequested = false
    private var markGenerationDefunctLockedFn: (() -> Unit)? = null
    var onEventualUnregister: (() -> Unit)? = null

    /** Synchronizes store adoption with worker turns; prevents defuncted store adoption. */
    suspend fun tryAdopt(): Boolean = mutex.withLock {
        if (store.isDefunct) return@withLock false
        pendingUnregisterRequested = false
        markGenerationDefunctLockedFn = null
        store.enableLazyPersistence()
        true
    }

    fun cancelPendingUnregister() {
        pendingUnregisterRequested = false
        markGenerationDefunctLockedFn = null
    }

    suspend fun flush() = flushMutex.withLock {
        flushPendingLazyMutations()
    }

    /** Flushes accepted work and joins the worker before returning. */
    suspend fun closeAndFlush() {
        try {
            flush()
        } finally {
            withContext(NonCancellable) { drainScopeJob.cancelAndJoin() }
        }
    }

    /**
     * Bounded flush on evict. If work remains, retains it and keeps the store
     * non-defunct so background drain can finish, unless forced.
     * Process-death boundary: unpersisted in-memory mutations survive across
     * unregister calls, but will be lost if the OS terminates the process.
     */
    suspend fun markDefunct(
        force: Boolean = false,
        markGenerationDefunctLocked: () -> Unit,
    ): StoreDefunctOutcome = withContext(NonCancellable) {
        val pendingDrain = mutex.withLock { drainJob }
        pendingDrain?.let { pending ->
            if (withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { pending.join() } == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store drain did not finish within ${PERSIST_JOIN_TIMEOUT_MS} ms before eviction"
                }
            }
        }
        withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) {
            flush()
        }
        mutex.withLock {
            val remaining = pendingLazyMutations.size
            if (remaining == 0 || force) {
                if (drainJob === pendingDrain) drainJob = null
                markGenerationDefunctLocked()
                releaseRetainedWorkLocked()
                if (remaining > 0) {
                    StoreDefunctOutcome.FlushedWithDeferred(remaining)
                } else {
                    StoreDefunctOutcome.Flushed
                }
            } else {
                markGenerationDefunctLockedFn = markGenerationDefunctLocked
                pendingUnregisterRequested = true
                scheduleDrain()
                StoreDefunctOutcome.IncompleteRetained(remaining)
            }
        }
    }

    /** Caller holds the store mutex. */
    internal fun scheduleDrain() {
        if (store.isDefunct || !store.hasArtifactPersistenceTarget() || drainJob?.isActive == true) return
        val scheduledJob = drainScope.launch {
            val runningJob = currentCoroutineContext()[Job]
            try {
                delay(PERSIST_DEBOUNCE_MS)
                flush()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (drainJob === runningJob) {
                            drainJob = null
                            if (hasPendingWorkLocked()) {
                                scheduleDrain()
                            } else if (pendingUnregisterRequested) {
                                pendingUnregisterRequested = false
                                val markDefunct = markGenerationDefunctLockedFn
                                markGenerationDefunctLockedFn = null
                                markDefunct?.invoke()
                                onEventualUnregister?.invoke()
                            }
                        }
                    }
                }
            }
        }
        drainJob = scheduledJob
    }

    /** Caller holds the store mutex after defunct/generation transition. */
    internal fun releaseRetainedWorkLocked() {
        pendingLazyMutations.clear()
    }

    private fun hasPendingWorkLocked(): Boolean =
        pendingLazyMutations.isNotEmpty()

    private suspend fun flushPendingLazyMutations(): Boolean = mutex.withLock {
        if (pendingLazyMutations.isEmpty()) return@withLock true
        val keys = pendingLazyMutations.keys.toList()
        var allAccepted = true
        for (pageKey in keys) {
            val mutation = pendingLazyMutations[pageKey] ?: continue
            if (!store.isLazyGenerationCurrent(mutation.generation)) {
                pendingLazyMutations.remove(pageKey)
                continue
            }
            val success = runCatching {
                store.publicationBridge.persistArtifactMutation(
                    pageKey = mutation.pageKey,
                    previous = mutation.previous,
                    updated = mutation.updated,
                    expected = mutation.expected,
                )
            }.getOrDefault(false)
            if (success) {
                if (pendingLazyMutations[pageKey] === mutation) {
                    pendingLazyMutations.remove(pageKey)
                }
            } else {
                allAccepted = false
            }
        }
        allAccepted && pendingLazyMutations.isEmpty()
    }
}

internal data class PendingLazyMutation(
    val pageKey: String,
    val previous: PageTranslation?,
    val updated: PageTranslation,
    val expected: ChapterTranslationStore.PatchPrecondition?,
    val generation: Long,
)
