package eu.kanade.translation.coordinator

import eu.kanade.translation.model.SinglePageOutcome
import io.kotest.matchers.shouldBe
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
}
