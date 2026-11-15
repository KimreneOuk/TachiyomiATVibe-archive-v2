package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.ChapterTranslationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** In-memory per-chapter outcomes, capped at 32 pages; a removed chapter's flow stays frozen. */
internal class ReaderOutcomeStore {

    private val outcomeStates = ConcurrentHashMap<String, MutableStateFlow<Map<String, SinglePageOutcome>>>()
    private val outcomeIdentities = ConcurrentHashMap<String, ChapterTranslationStore.Identity>()
    private val outcomeLock = Any()

    fun publish(chapterKey: String, pageKey: String, outcome: SinglePageOutcome, identity: ChapterTranslationStore.Identity? = null) {
        synchronized(outcomeLock) {
            val state = outcomeStates.computeIfAbsent(chapterKey) { MutableStateFlow(emptyMap()) }
            val updated = LinkedHashMap(state.value)
            updated[pageKey] = outcome
            while (updated.size > OUTCOME_RETENTION) {
                val eldest = updated.keys.first()
                updated.remove(eldest)
                outcomeIdentities.remove(identityKey(chapterKey, eldest))
            }
            state.value = updated
            val tagKey = identityKey(chapterKey, pageKey)
            if (identity != null) outcomeIdentities[tagKey] = identity else outcomeIdentities.remove(tagKey)
        }
    }

    fun clear(chapterKey: String, pageKey: String) {
        synchronized(outcomeLock) {
            outcomeStates[chapterKey]?.let { state ->
                if (pageKey in state.value) state.value = state.value - pageKey
            }
            outcomeIdentities.remove(identityKey(chapterKey, pageKey))
        }
    }

    fun peek(chapterKey: String, pageKey: String): SinglePageOutcome? =
        outcomeStates[chapterKey]?.value?.get(pageKey)

    fun flowForChapter(chapterId: Long): StateFlow<Map<String, SinglePageOutcome>> =
        outcomeStates.computeIfAbsent(chapterId.toString()) { MutableStateFlow(emptyMap()) }.asStateFlow()

    fun removeChapter(chapterKey: String) {
        outcomeStates.remove(chapterKey)
        val prefix = "$chapterKey:"
        outcomeIdentities.keys.removeAll { it.startsWith(prefix) }
    }

    fun clearAll() {
        outcomeStates.clear()
        outcomeIdentities.clear()
    }

    /**
     * Drops rejections that can no longer mean anything: non-retryable
     * rejections recorded against a superseded store identity, and the
     * coordinator's own untagged session-denial artifacts from an
     * admission-refused window. Real results for the live identity stay.
     */
    fun removeStaleRejections(chapterKey: String, liveIdentity: ChapterTranslationStore.Identity): Boolean {
        val state = outcomeStates[chapterKey] ?: return false
        var removedAny = false
        synchronized(outcomeLock) {
            val updated = LinkedHashMap(state.value)
            updated.entries.removeAll { (pageKey, outcome) ->
                val recordedIdentity = outcomeIdentities[identityKey(chapterKey, pageKey)]
                val stale = outcome is SinglePageOutcome.Rejected &&
                    (
                        (recordedIdentity != null && recordedIdentity != liveIdentity) ||
                            (
                                recordedIdentity == null &&
                                    outcome.owner == null &&
                                    outcome.kind == PageRejectionKind.SESSION_DENIED
                                )
                        )
                if (stale) {
                    outcomeIdentities.remove(identityKey(chapterKey, pageKey))
                    removedAny = true
                }
                stale
            }
            if (removedAny) state.value = updated
        }
        return removedAny
    }

    private fun identityKey(chapterKey: String, pageKey: String) = "$chapterKey:$pageKey"

    private companion object {
        const val OUTCOME_RETENTION = 32
    }
}

internal fun isReaderOutcomeRetryable(
    outcome: SinglePageOutcome,
    manual: Boolean,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = when (outcome) {
    is SinglePageOutcome.Paused -> (outcome.nextEligibleRetryAtEpochMs ?: 0L) <= nowMs
    is SinglePageOutcome.Stalled, is SinglePageOutcome.Failed -> manual
    else -> false
}
