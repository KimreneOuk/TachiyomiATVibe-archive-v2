package eu.kanade.translation.coordinator

import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterDeletionProtectionTest {

    @Test
    fun `queued states and pending requests protect reader files`() {
        ChapterDeletionProtection.protects(
            queuedState = Translation.State.QUEUE,
            hasPendingRequest = false,
        ) shouldBe true
        ChapterDeletionProtection.protects(
            queuedState = Translation.State.TRANSLATING,
            hasPendingRequest = false,
        ) shouldBe true
        ChapterDeletionProtection.protects(
            queuedState = Translation.State.PAUSED,
            hasPendingRequest = false,
        ) shouldBe true
        ChapterDeletionProtection.protects(
            queuedState = null,
            hasPendingRequest = true,
        ) shouldBe true
        ChapterDeletionProtection.protects(
            queuedState = Translation.State.NOT_TRANSLATED,
            hasPendingRequest = false,
        ) shouldBe false
        ChapterDeletionProtection.protects(
            queuedState = Translation.State.TRANSLATED,
            hasPendingRequest = false,
        ) shouldBe false
    }
}
