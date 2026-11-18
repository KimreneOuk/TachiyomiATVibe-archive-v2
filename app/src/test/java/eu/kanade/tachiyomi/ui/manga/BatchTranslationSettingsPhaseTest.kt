package eu.kanade.tachiyomi.ui.manga

import eu.kanade.translation.coordinator.BatchQueuePauseState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchTranslationSettingsPhaseTest {

    @Test
    fun `pre start settings do not depend on queue state`() {
        assertEquals(
            BatchTranslationSettingsPhase.PRE_START,
            resolveBatchTranslationSettingsPhase(
                isPreStart = true,
                pauseState = BatchQueuePauseState.NOT_PAUSED,
                hasResumableBatchWork = false,
            ),
        )
    }

    @Test
    fun `requested pause is distinct from confirmed pause`() {
        assertEquals(
            BatchTranslationSettingsPhase.PAUSE_REQUESTED,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.REQUESTED,
                hasResumableBatchWork = true,
            ),
        )
        assertEquals(
            BatchTranslationSettingsPhase.PAUSE_REQUESTED,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.REQUESTED,
                hasResumableBatchWork = false,
            ),
        )
    }

    @Test
    fun `confirmed pause requires resumable queue work`() {
        assertEquals(
            BatchTranslationSettingsPhase.PAUSED,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.PAUSED,
                hasResumableBatchWork = true,
            ),
        )
        assertEquals(
            BatchTranslationSettingsPhase.BATCH_ENDED,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.PAUSED,
                hasResumableBatchWork = false,
            ),
        )
    }

    @Test
    fun `resumable siblings keep running batch phase active`() {
        assertEquals(
            BatchTranslationSettingsPhase.BATCH_RUNNING,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.NOT_PAUSED,
                hasResumableBatchWork = true,
            ),
        )
    }

    @Test
    fun `empty queue ends settings phase`() {
        assertEquals(
            BatchTranslationSettingsPhase.BATCH_ENDED,
            resolveBatchTranslationSettingsPhase(
                isPreStart = false,
                pauseState = BatchQueuePauseState.NOT_PAUSED,
                hasResumableBatchWork = false,
            ),
        )
    }
}
