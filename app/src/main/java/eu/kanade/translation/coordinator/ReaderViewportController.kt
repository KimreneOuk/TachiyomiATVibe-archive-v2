package eu.kanade.translation.coordinator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns viewport windows, revision fences, and per-chapter update locks. */
internal class ReaderViewportController(
    private val scope: CoroutineScope,
    private val resolveSession: (ReaderSessionToken) -> TranslationSession?,
    private val isPausedForSwitch: () -> Boolean,
    private val isBatchActive: (String) -> Boolean,
    private val isAutoTranslateEnabled: () -> Boolean,
    private val isAutoEnabled: (String) -> Boolean,
    private val setAutoEnabled: (String, Boolean) -> Unit,
    private val enqueueAuto: suspend (ReaderTranslationCoordinator.PageRequest) -> Unit,
    private val trimWindow: suspend (String, Set<String>) -> Unit,
    private val releaseIfIdle: suspend () -> Unit,
    private val forEachBoundChapter: ((Long, ReaderSessionToken) -> Unit) -> Unit,
) {

    private val viewportLocks = ConcurrentHashMap<String, Mutex>()
    private val viewportRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val viewportStates = ConcurrentHashMap<String, ViewportState>()

    fun updateViewport(
        token: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        val session = resolveSession(token) ?: return
        val chapterId = session.chapter.id ?: return
        val chapterKey = chapterId.toString()
        if (isPausedForSwitch() || isBatchActive(chapterKey) || !isAutoTranslateEnabled()) return
        viewportStates[chapterKey] = ViewportState(token, pageIndex, pageCount, resolve)
        val revision = viewportRevisions.computeIfAbsent(chapterKey) { AtomicLong() }.incrementAndGet()
        setAutoEnabled(chapterKey, true)
        if (isPausedForSwitch() || !isAutoTranslateEnabled()) {
            setAutoEnabled(chapterKey, false)
            return
        }
        scope.launch {
            var releaseNeeded = false
            viewportLocks.computeIfAbsent(chapterKey) { Mutex() }.withLock {
                if (isPausedForSwitch() ||
                    viewportRevisions[chapterKey]?.get() != revision ||
                    resolveSession(token) == null
                ) {
                    return@withLock
                }
                if (!isAutoTranslateEnabled() || !isAutoEnabled(chapterKey)) {
                    releaseNeeded = true
                    return@withLock
                }
                val visibleOrNear = (pageIndex..minOf(pageIndex + 1, pageCount - 1))
                    .mapNotNull(resolve)
                    .distinctBy { it.pageKey }
                if (isPausedForSwitch() ||
                    viewportRevisions[chapterKey]?.get() != revision ||
                    resolveSession(token) == null
                ) {
                    return@withLock
                }
                if (!isAutoTranslateEnabled() || !isAutoEnabled(chapterKey)) {
                    releaseNeeded = true
                    return@withLock
                }
                trimWindow(chapterKey, visibleOrNear.mapTo(linkedSetOf()) { it.pageKey })
                visibleOrNear.forEach { work ->
                    enqueueAuto(
                        ReaderTranslationCoordinator.PageRequest(
                            session = session,
                            chapterKey = chapterKey,
                            pageKey = work.pageKey,
                            isManual = false,
                            streamFn = work.streamFn,
                        ),
                    )
                }
            }
            if (releaseNeeded) {
                releaseIfIdle()
            }
        }
    }

    fun reconcile(token: ReaderSessionToken, resolve: (Int) -> ReaderPageWork?) {
        val chapterKey = resolveSession(token)?.chapter?.id?.toString() ?: return
        val state = viewportStates[chapterKey]?.takeIf { it.token == token } ?: return
        updateViewport(token, state.pageIndex, state.pageCount, resolve)
    }

    fun reconcileChapter(chapterKey: String) {
        val state = viewportStates[chapterKey] ?: return
        reconcile(state.token, state.resolve)
    }

    fun reconcileAll(admissionGuard: (Long) -> Boolean = { true }) {
        forEachBoundChapter { chapterId, token ->
            if (!admissionGuard(chapterId)) return@forEachBoundChapter
            val state = viewportStates[chapterId.toString()] ?: return@forEachBoundChapter
            reconcile(token, state.resolve)
        }
    }

    /** Removes a chapter's window and revision fencing; its lock is retained. */
    fun drop(chapterKey: String) {
        viewportStates.remove(chapterKey)
        viewportRevisions.remove(chapterKey)
    }

    /** Clears windows and revision fencing; per-chapter locks are retained. */
    fun reset() {
        viewportStates.clear()
        viewportRevisions.clear()
    }

    /** Full teardown, including the per-chapter update locks. */
    fun clear() {
        viewportStates.clear()
        viewportRevisions.clear()
        viewportLocks.clear()
    }

    private data class ViewportState(
        val token: ReaderSessionToken,
        val pageIndex: Int,
        val pageCount: Int,
        val resolve: (Int) -> ReaderPageWork?,
    )
}
