package eu.kanade.translation.coordinator

import android.content.Context
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal data class DurableQueueState(
    val status: Translation.State?,
    val nextEligibleRetryAtEpochMs: Long?,
)

/**
 * Manages translation queue persistence, disk restoration, and durable state inspection.
 */
internal class BatchQueuePersistence(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val queueStore: TranslationQueueStore,
    private val resolveStore: suspend (Translation) -> ChapterTranslationStore?,
    private val resolveTranslation: suspend (Long) -> Translation? = { resolveTranslationFromChapterId(it) },
) {

    fun persist(queue: List<Translation>) {
        queueStore.save(
            queue.mapNotNull { it.chapter.id },
            queue.filter { it.colorizeOnly }.mapNotNullTo(mutableSetOf()) { it.chapter.id },
        )
    }

    fun loadIds(): List<Long> = queueStore.load()

    fun loadColorizeOnlyIds(): Set<Long> = queueStore.loadColorizeOnlyIds()

    suspend fun restoreQueue(
        queueLock: Any,
        currentQueue: () -> List<Translation>,
        applyRestored: (List<Translation>) -> Unit,
    ) {
        val ids = synchronized(queueLock) { queueStore.load() }
        if (ids.isEmpty()) return
        val colorizeOnlyIds = synchronized(queueLock) { queueStore.loadColorizeOnlyIds() }
        val restored = mutableListOf<Translation>()
        for (id in ids) {
            val translation = resolveTranslation(id) ?: continue
            if (id in colorizeOnlyIds) translation.colorizeOnly = true
            val durable = durableQueueState(translation)
            translation.status = when (durable?.status) {
                Translation.State.ERROR -> Translation.State.ERROR
                else -> Translation.State.PAUSED
            }
            restored += translation
        }
        synchronized(queueLock) {
            // Lookups above suspend. Re-read the durable membership and merge
            // any queue additions that landed during that window instead of
            // replacing them with the startup snapshot.
            val durableIds = queueStore.load()
            val merged = mergeRestoredQueueEntries(
                durableIds = durableIds,
                restoredById = restored.mapNotNull { translation ->
                    translation.chapter.id?.let { it to translation }
                }.toMap(),
                liveById = currentQueue().mapNotNull { translation ->
                    translation.chapter.id?.let { it to translation }
                }.toMap(),
            )
            if (merged.isNotEmpty()) {
                applyRestored(merged)
                // Re-save so self-healed stale ids are removed while concurrent
                // additions retained by the merge remain durable.
                persist(merged)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT restored ${merged.size}/${ids.size} queued translations from disk"
                }
            } else {
                applyRestored(emptyList())
                queueStore.clear()
                // All persisted ids were stale (chapters deleted) and no
                // concurrent queue entry survived the merge.
                logcat(LogPriority.INFO) {
                    "TachiyomiAT queue restore: all ${ids.size} persisted ids were stale; cleared store"
                }
            }
        }
    }

    /** Reads only durable status; it never starts work or creates a directory. */
    suspend fun durableQueueState(translation: Translation): DurableQueueState? =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = resolveStore(translation)
                val store = active ?: provider.findMangaDir(translation.manga.title, translation.source)?.let { parent ->
                    val fileName = provider.getTranslationFileName(
                        translation.chapter.name,
                        translation.chapter.scanlator,
                    )
                    ChapterTranslationStore.openArtifactSuspend(
                        parent,
                        fileName,
                    )
                }
                store ?: return@withContext null
                DurableQueueState(
                    status = store.artifactStatus(),
                    nextEligibleRetryAtEpochMs = store.durableFailuresSnapshot().values
                        .firstOrNull { it.stage == ArtifactStage.TRANSLATION }
                        ?.nextEligibleRetryAtEpochMs,
                )
            }.getOrNull()
        }
}
