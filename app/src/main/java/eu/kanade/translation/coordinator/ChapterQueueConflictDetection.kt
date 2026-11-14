package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation

/** Queue fields for same-source admission rules, without constructing a full [Translation] or its source. */
data class QueuedChapterView(
    val chapterId: Long,
    val chapterName: String,
    val sourceId: Long,
    val status: Translation.State,
)

/** Running conflicts may discard native work if cancelled; queued entries can be removed with artifacts intact. */
fun findRunningSameSourceConflict(
    queue: List<QueuedChapterView>,
    requestedChapterId: Long,
    sourceId: Long,
): QueuedChapterView? = queue
    .asSequence()
    .filter { it.chapterId != requestedChapterId }
    .filter { it.sourceId == sourceId }
    .filter { it.status == Translation.State.TRANSLATING }
    .firstOrNull()

/** Queued same-source chapters to remove when admitting [keepChapterId]; eviction keeps each chapter's artifacts. */
fun staleQueuedChaptersToEvict(
    queue: List<QueuedChapterView>,
    keepChapterId: Long,
    sourceId: Long,
): List<QueuedChapterView> = queue
    .asSequence()
    .filter { it.chapterId != keepChapterId }
    .filter { it.sourceId == sourceId }
    .filter { it.status == Translation.State.QUEUE }
    .toList()

/**
 * Maps a [Translation] queue entry to the minimal [QueuedChapterView] used by
 * the pure conflict/eviction helpers. Entries with no chapter id are dropped by
 * the callers (they cannot conflict with a known requested chapter id).
 */
fun Translation.toQueuedChapterView(): QueuedChapterView = QueuedChapterView(
    chapterId = chapter.id ?: -1L,
    chapterName = chapter.name,
    sourceId = source.id,
    status = status,
)
