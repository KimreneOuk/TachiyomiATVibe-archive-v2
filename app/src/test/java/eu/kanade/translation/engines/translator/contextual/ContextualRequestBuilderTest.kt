package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ContextualRequestBuilderTest {
    @Test
    fun requestIdsFollowFrozenPlanOrderAndRebuildAfterPartialAcceptance() = runTest {
        // The chunk reflects the executor's accepted PLAN sequence. Stable block IDs deliberately
        // sort differently, so request numbering must preserve this order rather than re-sort it.
        val laterPage = PageTranslation(
            blocks = mutableListOf(
                block("ten", "p2_b10"),
                block("two", "p2_b2"),
            ),
        )
        val earlierPage = PageTranslation(
            blocks = mutableListOf(block("zero", "p1_b0")),
        )
        val liveChunk = TranslationContextChunk(
            pages = linkedMapOf("002.jpg" to laterPage, "001.jpg" to earlierPage),
            blockCount = 3,
            rollingContext = "prior => context",
            estimatedPromptTokens = 128,
            maxOutputTokens = 256,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = linkedMapOf("001.jpg" to 1, "002.jpg" to 2),
        )

        val request = ContextualRequestBuilder.build(
            liveChunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )

        request.orderedIds shouldContainExactly listOf("1", "2", "3")
        request.promptLines shouldContainExactly listOf(
            "1|zero",
            "2|ten",
            "3|two",
        )
        request.idMap["1"] shouldBe AnchoredTargetKey(pageIndex = 1, blockIndex = 0)
        request.idMap["2"] shouldBe AnchoredTargetKey(pageIndex = 2, blockIndex = 10)
        request.idMap["3"] shouldBe AnchoredTargetKey(pageIndex = 2, blockIndex = 2)
        request.locations["1"] shouldBe TargetLocation("001.jpg", 0)
        request.locations["2"] shouldBe TargetLocation("002.jpg", 0)
        request.locations["3"] shouldBe TargetLocation("002.jpg", 1)
        request.orderedIds.none { it.startsWith("p") || it.startsWith("b") } shouldBe true
        ContextualRequestBuilder.renderPrompt(request, liveChunk.rollingContext) shouldBe
            """
            BACKGROUND:
            prior => context

            SOURCE ITEMS:
            1|zero
            2|ten
            3|two
            """.trimIndent()

        // LEGACY enumerates in supplied page-map order and resets bN within each page.
        // Keep the deliberately multi-page fixture: it pins the existing reader path as
        // [b0,b1,b0] and prevents this compatibility protocol from being mistaken for BATCH_V1.
        val legacy = ContextualRequestBuilder.buildLegacy(
            liveChunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        legacy.orderedIds shouldContainExactly listOf("b0", "b1", "b0")
        legacy.promptLines shouldContainExactly listOf("b0|ten", "b1|two", "b0|zero")

        // A follow-up is a new request-local namespace over only its unresolved durable item.
        val followUp = ContextualRequestBuilder.build(
            liveChunk.copy(
                pages = linkedMapOf(
                    "002.jpg" to PageTranslation(blocks = mutableListOf(block("ten", "p2_b10"))),
                ),
                blockCount = 1,
                pageIndexes = mapOf("002.jpg" to 2),
            ),
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        followUp.orderedIds shouldBe listOf("1")
        followUp.idMap["1"] shouldBe AnchoredTargetKey(pageIndex = 2, blockIndex = 10)
        followUp.locations["1"] shouldBe TargetLocation("002.jpg", 0)
    }

    private fun block(text: String, id: String) = TranslationBlock(
        blockId = id,
        text = text,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
