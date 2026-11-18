package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.UpsertMangaLastVisited
import java.util.concurrent.atomic.AtomicLong

/**
 * Event-capture-ordered serialized writer for manga last-visited tracking.
 *
 * Guarantees:
 * 1. Immediate event capture: Each visit captures timestamp and an atomically incremented
 *    sequence number at the moment of page selection.
 * 2. Single-consumer FIFO serialized execution: All write commands are queued into an
 *    unbounded Channel and processed strictly one-by-one by a dedicated sequential coroutine.
 * 3. Same-millisecond preservation: If multiple page navigation events occur within the same
 *    millisecond, channel FIFO order ensures the latter transition overwrites the earlier one.
 * 4. Intentional backward navigation: Backward navigation (higher sequence number, lower page index)
 *    is unconditionally persisted to SQLite as the latest user position.
 * 5. Stale event rejection: If an event arrives with sequence <= lastProcessedSequence,
 *    it is discarded as stale.
 * 6. Lifecycle flush: Supports [flush] to wait until all currently queued write operations have
 *    fully committed to the SQLite database.
 *
 * Crash Limitation:
 * In-flight events buffered in memory in the [Channel] prior to SQLite transaction commit will be
 * lost if the process is killed abruptly (e.g. OS low memory kill / SIGKILL), leaving the database
 * in its last committed state. Normal app lifecycle transitions (onPause, onDestroy, activity finish)
 * invoke [flush] to guarantee all pending channel events are committed to disk before transition completes.
 */
class ReaderLastVisitedWriter(
    private val upsertMangaLastVisited: UpsertMangaLastVisited,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val sequenceGenerator = AtomicLong(0L)
    private var lastProcessedSequence = 0L

    internal sealed interface Command {
        data class RecordVisit(
            val mangaId: Long,
            val chapterId: Long,
            val page: Long,
            val visitedAt: Long,
            val sequence: Long,
        ) : Command

        data class Flush(
            val deferred: CompletableDeferred<Unit>,
        ) : Command
    }

    private val channel = Channel<Command>(Channel.UNLIMITED)
    internal val workerJob: Job = scope.launch {
        for (command in channel) {
            when (command) {
                is Command.RecordVisit -> {
                    if (command.sequence <= lastProcessedSequence) {
                        logcat(LogPriority.DEBUG) {
                            "Dropping stale last-visited event for manga ${command.mangaId}: seq ${command.sequence} <= $lastProcessedSequence"
                        }
                    } else {
                        lastProcessedSequence = command.sequence
                        try {
                            upsertMangaLastVisited.await(
                                mangaId = command.mangaId,
                                chapterId = command.chapterId,
                                lastVisitedPage = command.page,
                                visitedAt = command.visitedAt,
                            )
                        } catch (e: Throwable) {
                            logcat(LogPriority.ERROR, e) {
                                "Failed to write last-visited to SQLite for manga ${command.mangaId}"
                            }
                        }
                    }
                }
                is Command.Flush -> {
                    command.deferred.complete(Unit)
                }
            }
        }
    }

    /**
     * Immediately captures the event and enqueues a write operation.
     * Returns the sequence number assigned to this visit.
     */
    fun recordVisit(
        mangaId: Long,
        chapterId: Long,
        page: Long,
        visitedAt: Long = System.currentTimeMillis(),
    ): Long {
        val sequence = sequenceGenerator.incrementAndGet()
        val command = Command.RecordVisit(
            mangaId = mangaId,
            chapterId = chapterId,
            page = page,
            visitedAt = visitedAt,
            sequence = sequence,
        )
        channel.trySend(command)
        return sequence
    }

    /**
     * Enqueues an explicit command (used in tests or manual replay).
     */
    internal fun enqueueCommand(command: Command) {
        channel.trySend(command)
    }

    /**
     * Flushes all currently queued events, suspending until they have been executed against SQLite.
     */
    suspend fun flush() {
        val deferred = CompletableDeferred<Unit>()
        channel.send(Command.Flush(deferred))
        deferred.await()
    }

    fun close() {
        channel.close()
    }
}
