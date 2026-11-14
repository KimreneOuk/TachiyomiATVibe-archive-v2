package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation

/** Policies that protect translation work from chapter deletion. */
internal object ChapterDeletionProtection {

    /** Reader auto-delete must not evict files needed by any batch intent. */
    fun protects(
        queuedState: Translation.State?,
        hasPendingRequest: Boolean,
    ): Boolean = hasPendingRequest ||
        queuedState == Translation.State.QUEUE ||
        queuedState == Translation.State.TRANSLATING ||
        queuedState == Translation.State.PAUSED

    /** Protected ids are passed to the pending chapter deleter at reader finish. */
    fun protectedIds(
        queued: List<Translation>,
        pendingChapterIds: Set<Long>,
        persistedChapterIds: Set<Long>,
    ): Set<Long> = buildSet {
        queued
            .filter { translation ->
                translation.status == Translation.State.QUEUE ||
                    translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.PAUSED
            }
            .mapNotNullTo(this) { it.chapter.id }
        addAll(pendingChapterIds)
        addAll(persistedChapterIds)
    }
}
