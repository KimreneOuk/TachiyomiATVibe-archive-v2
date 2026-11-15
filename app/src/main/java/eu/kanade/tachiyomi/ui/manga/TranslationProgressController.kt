package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.presentation.TranslationUiProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the manga screen's per-chapter batch-progress collectors.
 *
 * Per-chapter collectors are registered synchronously in [jobs], so test probes
 * and quiescence checks observe registered jobs immediately without relying on an
 * unbounded background daemon coroutine. A collector's teardown removes only its
 * own registered job (two-arg [ConcurrentHashMap.remove]), so a late cleanup can
 * never cancel a replacement collector for the same chapter.
 */
internal class TranslationProgressController(
    private val translationManager: TranslationManager,
    private val snapshots: ChapterTranslationSnapshotRegistry,
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val getSuccessState: () -> MangaScreenModel.State.Success?,
    private val updateSuccessState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
    val jobs: ConcurrentHashMap<Long, Job> = ConcurrentHashMap(),
) {

    private val lock = Any()

    /** Starts a collector for the chapter if not already running. */
    fun start(chapterId: Long, durableStateHint: Translation.State? = null) {
        synchronized(lock) {
            val existing = jobs[chapterId]
            if (existing?.isActive == true) return
            val job = scope.launchIO {
                try {
                    val state = getSuccessState()
                    val chapterItem = state?.chapters?.firstOrNull { it.id == chapterId }
                    val manga = state?.manga
                    val currentSource = state?.source
                    if (chapterItem != null && manga != null && currentSource != null) {
                        translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                            chapterId = chapterId,
                            chapterName = chapterItem.chapter.name,
                            scanlator = chapterItem.chapter.scanlator,
                            mangaTitle = manga.title,
                            source = currentSource,
                            mangaId = manga.id,
                        )
                    }
                    translationManager.observeBatchProgress(chapterId, durableStateHint)
                        .distinctUntilChanged()
                        .catch { error -> logcat(LogPriority.ERROR, error) }
                        .flowWithLifecycle(lifecycle)
                        .collect { progress ->
                            withUIContext { onProgress(chapterId, progress) }
                        }
                } finally {
                    val self = coroutineContext[Job]
                    if (self != null) {
                        jobs.remove(chapterId, self)
                    }
                }
            }
            jobs[chapterId] = job
        }
    }

    /** Cancels and removes the collector for the chapter. */
    fun stop(chapterId: Long) {
        synchronized(lock) {
            jobs.remove(chapterId)?.cancel()
        }
    }

    /** Routes a live-or-terminal status to the serialized start/stop, then patches the item. */
    fun onStatusChanged(translation: Translation) {
        when (translation.status) {
            Translation.State.QUEUE,
            Translation.State.TRANSLATING,
            Translation.State.PAUSED,
            -> start(translation.chapter.id)
            else -> stop(translation.chapter.id)
        }
        updateSuccessState { state ->
            state.updateChapterItem(translation.chapter.id) { item ->
                item.copy(translationState = translation.status)
            }
        }
    }

    fun onPendingRequestsChanged(requests: Map<Long, TranslationRequestState>) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map { item ->
                item.copy(translationRequest = item.id?.let(requests::get))
            }
            if (newChapters == successState.chapters) successState else successState.copy(chapters = newChapters)
        }
    }

    fun onProgress(chapterId: Long, progress: TranslationProgressSnapshot?) {
        snapshots.remember(chapterId, progress)
        reconcileAbortedBatch(chapterId, progress)
        updateSuccessState { state ->
            state.updateChapterItem(chapterId) { item ->
                if (item.translationProgress == progress) item else item.copy(translationProgress = progress)
            }
        }
    }

    // Reconciles mid-run cancelled batch stranded in active states back to
    // a restartable state when its queue entry is gone.
    private fun reconcileAbortedBatch(chapterId: Long, progress: TranslationProgressSnapshot?) {
        if (progress?.aborted != true) return
        if (translationManager.getQueuedTranslationOrNull(chapterId) != null) return
        updateSuccessState { state ->
            state.updateChapterItem(chapterId) { item ->
                val reconciled = TranslationUiProjection.reconcileAbortedBatchState(item.translationState)
                    ?: return@updateChapterItem item
                item.copy(translationState = reconciled)
            }
        }
    }
}
