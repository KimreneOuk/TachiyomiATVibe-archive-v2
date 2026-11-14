package eu.kanade.translation.coordinator

import eu.kanade.translation.model.SinglePageOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** In-memory per-chapter outcomes, capped at 32 pages; a removed chapter's flow stays frozen. */
internal class ReaderOutcomeStore {

    private val outcomeStates = ConcurrentHashMap<String, MutableStateFlow<Map<String, SinglePageOutcome>>>()
    private val outcomeLock = Any()

    fun publish(chapterKey: String, pageKey: String, outcome: SinglePageOutcome) {
        synchronized(outcomeLock) {
            val state = outcomeStates.computeIfAbsent(chapterKey) { MutableStateFlow(emptyMap()) }
            val updated = LinkedHashMap(state.value)
            updated[pageKey] = outcome
            while (updated.size > OUTCOME_RETENTION) updated.remove(updated.keys.first())
            state.value = updated
        }
    }

    fun clear(chapterKey: String, pageKey: String) {
        synchronized(outcomeLock) {
            outcomeStates[chapterKey]?.let { state ->
                if (pageKey in state.value) state.value = state.value - pageKey
            }
        }
    }

    fun peek(chapterKey: String, pageKey: String): SinglePageOutcome? =
        outcomeStates[chapterKey]?.value?.get(pageKey)

    fun flowForChapter(chapterId: Long): StateFlow<Map<String, SinglePageOutcome>> =
        outcomeStates.computeIfAbsent(chapterId.toString()) { MutableStateFlow(emptyMap()) }.asStateFlow()

    fun removeChapter(chapterKey: String) {
        outcomeStates.remove(chapterKey)
    }

    fun clearAll() {
        outcomeStates.clear()
    }

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
