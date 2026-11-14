package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.persistence.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

/** Pins retention eviction order, selective clearing, and the frozen flow after chapter removal. */
class ReaderOutcomeStoreTest {

    @Test
    fun `publishing past retention evicts the first inserted key in order`() {
        val store = ReaderOutcomeStore()
        (0..32).forEach { index -> store.publish("1", "page$index", SinglePageOutcome.Completed) }

        val flow = store.flowForChapter(1L)
        flow.value.size shouldBe 32
        flow.value.keys shouldBe (1..32).map { "page$it" }.toSet()
    }

    @Test
    fun `clear removes only the matching page`() {
        val store = ReaderOutcomeStore()
        store.publish("1", "page1", SinglePageOutcome.Completed)
        store.publish("1", "page2", SinglePageOutcome.Completed)
        store.publish("2", "page1", SinglePageOutcome.Failed("page1", "boom"))

        store.clear("1", "page1")
        store.clear("1", "missing")

        store.flowForChapter(1L).value shouldBe mapOf("page2" to SinglePageOutcome.Completed)
        store.flowForChapter(2L).value.size shouldBe 1
    }

    @Test
    fun `flow captured before removeChapter stays frozen and a new flow is a fresh instance`() {
        val store = ReaderOutcomeStore()
        store.publish("1", "page1", SinglePageOutcome.Completed)
        val captured = store.flowForChapter(1L)

        store.removeChapter("1")
        val replacement = store.flowForChapter(1L)
        store.publish("1", "page2", SinglePageOutcome.Completed)

        assertNotSame(captured, replacement)
        captured.value shouldBe mapOf("page1" to SinglePageOutcome.Completed)
        replacement.value shouldBe mapOf("page2" to SinglePageOutcome.Completed)
    }

    /** Purge compares the full store identity: a replacement at the same generation number is still stale. */
    @Test
    fun `purge drops rejections from a superseded store identity and keeps live ones`() {
        val store = ReaderOutcomeStore()
        val liveIdentity = ChapterTranslationStore.Identity(instanceId = 1L, generation = 0L)
        store.publish("1", "live", rejectedOutcome(), liveIdentity)
        store.publish("1", "replaced", rejectedOutcome(), ChapterTranslationStore.Identity(instanceId = 0L, generation = 0L))
        store.publish("1", "old-gen", rejectedOutcome(), ChapterTranslationStore.Identity(instanceId = 1L, generation = 7L))

        store.removeStaleRejections("1", liveIdentity) shouldBe true

        store.peek("1", "live") shouldNotBe null
        store.peek("1", "replaced") shouldBe null
        store.peek("1", "old-gen") shouldBe null
    }

    /** Untagged coordinator denials purge on admission; control-flow non-results never block real ones. */
    @Test
    fun `untagged session denials purge while unrelated outcomes stay`() {
        val store = ReaderOutcomeStore()
        val liveIdentity = ChapterTranslationStore.Identity(instanceId = 1L, generation = 0L)
        store.publish("1", "denial", SinglePageOutcome.Rejected(null, PageRejectionKind.SESSION_DENIED, "reader session denied"))
        store.publish("1", "failed", SinglePageOutcome.Failed("failed", "store unavailable"))
        store.publish("1", "completed", SinglePageOutcome.Completed)

        store.removeStaleRejections("1", liveIdentity) shouldBe true

        store.peek("1", "denial") shouldBe null
        store.peek("1", "failed") shouldNotBe null
        store.peek("1", "completed") shouldNotBe null
    }

    private fun rejectedOutcome() =
        SinglePageOutcome.Rejected(null, PageRejectionKind.PERSISTENCE_FAILED, "blocking stale rejection")
}
