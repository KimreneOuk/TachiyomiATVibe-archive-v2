package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation

enum class BatchQueuePauseState {
    NOT_PAUSED,
    REQUESTED,
    PAUSED,
}

sealed interface BatchQueueResumeResult {
    data class Resumed(
        val chapterIds: Set<Long>,
        val deferredRetryAtEpochMsByChapterId: Map<Long, Long> = emptyMap(),
    ) : BatchQueueResumeResult

    data class RetryDeferred(
        val chapterId: Long,
        val retryAtEpochMs: Long,
    ) : BatchQueueResumeResult

    data class BatchRetryDeferred(
        val retryAtEpochMsByChapterId: Map<Long, Long>,
    ) : BatchQueueResumeResult

    data object AlreadyRunning : BatchQueueResumeResult

    data object PauseUnwinding : BatchQueueResumeResult

    data object NoResumableWork : BatchQueueResumeResult

    data class QueueItemMissing(val chapterId: Long) : BatchQueueResumeResult

    data class StaleQueueItem(val chapterId: Long) : BatchQueueResumeResult

    data class NotResumable(
        val chapterId: Long,
        val status: Translation.State,
    ) : BatchQueueResumeResult

    data class SessionRejected(val reason: SessionRejection) : BatchQueueResumeResult
}
