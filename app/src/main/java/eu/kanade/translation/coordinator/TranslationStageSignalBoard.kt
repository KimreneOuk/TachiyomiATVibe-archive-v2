package eu.kanade.translation.coordinator

import eu.kanade.translation.processing.TranslationStageSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * TachiyomiAT: in-memory, per-attempt pipeline stage signals for live reader
 * feedback (the status pill). Atomic primitives — never persisted, never part
 * of the durable translation store. Carriers carry the attempt token so a
 * stale reporter callback arriving after a retry started can never overwrite
 * the newer attempt's stage (spec §8 stale-callback rule).
 */
internal data class PageStageSignal(
    val stage: TranslationStageSignal,
    val attemptToken: Long,
)

internal class TranslationStageSignalBoard {
    private val signals = MutableStateFlow<Map<String, PageStageSignal>>(emptyMap())
    val flow: StateFlow<Map<String, PageStageSignal>> = signals.asStateFlow()

    private val attemptCounter = AtomicLong()

    fun nextAttemptToken(): Long = attemptCounter.incrementAndGet()

    fun publish(fullKey: String, stage: TranslationStageSignal, attemptToken: Long) {
        val current = signals.value[fullKey]
        // Stale fence: a callback from an old attempt must never overwrite the
        // newer attempt's stage.
        if (current != null && current.attemptToken > attemptToken) return
        signals.value = signals.value + (fullKey to PageStageSignal(stage, attemptToken))
    }

    fun clear(fullKey: String, attemptToken: Long) {
        val current = signals.value[fullKey] ?: return
        if (current.attemptToken == attemptToken) {
            signals.value = signals.value - fullKey
        }
    }
}
