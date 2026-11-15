package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.model.PageTranslation

/**
 * Token estimation for AI request accounting. Providers and the input
 * accounting contracts use [estimateTokens] as the shared CL100K_BASE
 * estimator for quota-governor input estimates.
 */
object TranslationContextChunkPlanner {
    private val encodingRegistry = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
    private val encoding = encodingRegistry.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE)

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 1
        return encoding.countTokens(text).coerceAtLeast(1)
    }
}

enum class ContextualRequestProtocol {
    /** Strict, versioned envelope used only by chapter-batch AI translation. */
    BATCH_V1,

    /** Existing reader/single-page prompt and response behavior. */
    LEGACY,
}

data class TranslationContextChunk(
    val pages: LinkedHashMap<String, PageTranslation>,
    val blockCount: Int,
    val rollingContext: String,
    val estimatedPromptTokens: Int,
    val maxOutputTokens: Int,
    val protocol: ContextualRequestProtocol = ContextualRequestProtocol.LEGACY,
    val pageIndexes: Map<String, Int> = emptyMap(),
    /** In-memory-only hint for the one unresolved-only semantic repair request. */
    val correctionHint: TranslationCorrectionHint? = null,
)

data class TranslationCorrectionHint(
    val sourceEcho: Boolean = false,
    val wrongTargetLanguage: Boolean = false,
) {
    init {
        require(sourceEcho || wrongTargetLanguage) { "Correction hint requires classified evidence" }
    }
}

interface ContextualTextTranslator : TextTranslator {
    /** Applies the returned batch exactly once and exposes it to the live retry controller. */
    suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch

    /**
     * Mandatory structured-result contract. Every contextual provider returns
     * per-id results keyed by request-local IDs without mutating request blocks.
     */
    suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch

    /** Prompts the underlying model directly for summarization. */
    suspend fun promptText(prompt: String): String
}
