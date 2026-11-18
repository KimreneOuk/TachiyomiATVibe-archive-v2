package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation

/**
 * Queue-admission result returned before replacing or adding chapter work.
 *
 * Stale queued entries can be evicted automatically because no stage work has
 * started. Replacing an actively translating chapter requires confirmation,
 * since cancellation can discard in-flight OCR or inpainting work.
 */
sealed interface ChapterQueuePreflight {
    /** No conflict: queue immediately; stale same-source QUEUE entries are evicted by translateChapter. */
    data object NoConflict : ChapterQueuePreflight

    /** Captures the live queue entry and its run generation for the cancellation fence. */
    data class RunningConflict(
        val chapterId: Long,
        val chapterName: String,
        val run: Translation,
        val runGeneration: Long,
    ) : ChapterQueuePreflight
}
