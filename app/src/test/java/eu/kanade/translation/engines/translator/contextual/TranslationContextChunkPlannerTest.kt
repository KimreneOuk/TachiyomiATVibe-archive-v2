package eu.kanade.translation.engines.translator.contextual

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationContextChunkPlannerTest {

    @Test
    fun `unannotated chunks remain on legacy protocol`() {
        TranslationContextChunk(
            pages = linkedMapOf(),
            blockCount = 0,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
        ).protocol shouldBe ContextualRequestProtocol.LEGACY
    }

    @Test
    fun `cjk heavy text is estimated conservatively`() {
        val cjk = "\u65e5".repeat(100)
        val latin = "a".repeat(100)

        TranslationContextChunkPlanner.estimateTokens(cjk) shouldBe 100
        TranslationContextChunkPlanner.estimateTokens(latin) shouldBe 13
    }
}
