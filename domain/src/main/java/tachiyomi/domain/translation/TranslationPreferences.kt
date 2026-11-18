package tachiyomi.domain.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Top-level translation engine family. Standard translators do not use
 * dynamic LLM model selection; AI model translators require a provider
 * API key and a selectable model.
 */
enum class TranslationEngineCategory { STANDARD, AI_MODEL }

/**
 * Standard translators. These do not use dynamic LLM model selection. Most
 * require no credentials; DeepL is the exception (it needs an API key, stored
 * via [translationDeeplApiKey]), and FUMETO runs an on-device ONNX model
 * (Japanese→English only). Keeps the list scalable for future providers
 * such as Microsoft or Yandex.
 */
enum class StandardEngine { MLKIT, GOOGLE, DEEPL, FUMETO }

/**
 * AI model translators. Each provider has its own API key and model
 * selection so switching providers does not overwrite credentials.
 */
enum class AiEngine { GEMINI, OPENROUTER, DEEPSEEK, LMSTUDIO }

enum class GeminiThinkingMode { DISABLED, AUTO, LOW }

/**
 * OCR backends used before translation. Stored separately from translator
 * engines because OCR reads the source page language, not the target language.
 */
enum class OcrModel { MLKIT, MANGAOCR, PADDLEOCR_V6_SMALL }

/**
 * Wire-route names for the PaddleOCR v6 ONNX fallback path (used only when the
 * LiteRT runtime is unavailable). Auto-routing requests [QUALCOMM_QNN_GPU]
 * strictly; a failed probe falls back to a labelled CPU session. [QUALCOMM_QNN_HTP]
 * remains for internal benchmark machinery, not user selection.
 */
enum class PaddleOcrExecutionProvider { CPU, QUALCOMM_QNN_GPU, QUALCOMM_QNN_HTP }

/**
 * TachiyomiAT: source reading order for a manga/comic page. Determines how the
 * recognition engine orders detected blocks and which direction the inpainter
 * assumes for text flow.
 *
 * - [AUTO]: derive from the source language (CJK languages -> RTL manga flow,
 *   everything else -> LTR comic flow). The historical default.
 * - [RTL_MANGA]: force right-to-left manga ordering regardless of language.
 * - [LTR_COMIC]: force left-to-right western-comic ordering regardless of language.
 */
enum class TranslationReadingOrder { AUTO, RTL_MANGA, LTR_COMIC }

/**
 * Facade over the translation preference groups. Every member keeps its
 * original name, key, default, and semantics; the definitions live in
 * [AiEngineCredentials], [PipelinePrefs], and [ReaderUiPrefs].
 */
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    private val aiEngineCredentials = AiEngineCredentials(preferenceStore)
    private val pipelinePrefs = PipelinePrefs(preferenceStore)
    private val readerUiPrefs = ReaderUiPrefs(preferenceStore)

    fun translationEnabled() = readerUiPrefs.translationEnabled()

    fun translationConfirmPretranslate() = readerUiPrefs.translationConfirmPretranslate()

    fun autoTranslate() = readerUiPrefs.autoTranslate()

    fun translateButtonWithControls() = readerUiPrefs.translateButtonWithControls()

    fun translateFromLanguage() = readerUiPrefs.translateFromLanguage()
    fun translateToLanguage() = readerUiPrefs.translateToLanguage()

    fun translationRequireCharging() = pipelinePrefs.translationRequireCharging()
    fun translationContinueInBackground() = pipelinePrefs.translationContinueInBackground()

    fun translationInpaintingMode() = pipelinePrefs.translationInpaintingMode()

    fun translationInpaintingNeuralModel(): Preference<NeuralInpaintModel> =
        pipelinePrefs.translationInpaintingNeuralModel()

    fun translationColorizationEnabled() = pipelinePrefs.translationColorizationEnabled()

    fun translationColorizationModel(): Preference<ColorizationModel> =
        pipelinePrefs.translationColorizationModel()

    fun colorCombineMode(): Preference<ColorCombineMode> =
        pipelinePrefs.colorCombineMode()

    fun translationUpscalingEnabled() = pipelinePrefs.translationUpscalingEnabled()

    fun translationRecentLanguagesFrom() = readerUiPrefs.translationRecentLanguagesFrom()
    fun translationRecentLanguagesTo() = readerUiPrefs.translationRecentLanguagesTo()

    fun translationOcrModel(languageName: String): Preference<OcrModel> =
        pipelinePrefs.translationOcrModel(languageName)

    //region Category and engine selection
    fun translationEngineCategory() = pipelinePrefs.translationEngineCategory()
    fun translationStandardEngine() = pipelinePrefs.translationStandardEngine()
    fun translationAiEngine() = pipelinePrefs.translationAiEngine()

    fun translationDeeplApiKey() =
        pipelinePrefs.translationDeeplApiKey()
    //endregion

    //region AI-only preferences
    fun translationAiApiKeyGemini() =
        aiEngineCredentials.translationAiApiKeyGemini()
    fun translationAiApiKeyOpenrouter() =
        aiEngineCredentials.translationAiApiKeyOpenrouter()
    fun translationAiApiKeyDeepseek() =
        aiEngineCredentials.translationAiApiKeyDeepseek()

    fun translationAiApiKey(engine: AiEngine): Preference<String> =
        aiEngineCredentials.translationAiApiKey(engine)

    fun translationAiBaseUrlLmStudio() =
        aiEngineCredentials.translationAiBaseUrlLmStudio()

    fun translationAiModelGemini() = aiEngineCredentials.translationAiModelGemini()
    fun translationAiModelOpenrouter() = aiEngineCredentials.translationAiModelOpenrouter()
    fun translationAiModelDeepseek() = aiEngineCredentials.translationAiModelDeepseek()
    fun translationAiModelLmStudio() = aiEngineCredentials.translationAiModelLmStudio()

    fun translationAiModel(engine: AiEngine): Preference<String> =
        aiEngineCredentials.translationAiModel(engine)

    fun translationAiRecentModelsGemini() =
        aiEngineCredentials.translationAiRecentModelsGemini()
    fun translationAiRecentModelsOpenrouter() =
        aiEngineCredentials.translationAiRecentModelsOpenrouter()
    fun translationAiRecentModelsDeepseek() =
        aiEngineCredentials.translationAiRecentModelsDeepseek()
    fun translationAiRecentModelsLmStudio() =
        aiEngineCredentials.translationAiRecentModelsLmStudio()

    fun translationAiRecentModels(engine: AiEngine): Preference<String> =
        aiEngineCredentials.translationAiRecentModels(engine)

    fun translationAiBaseUrl(engine: AiEngine): Preference<String>? =
        aiEngineCredentials.translationAiBaseUrl(engine)

    fun translationAiTemperature() = aiEngineCredentials.translationAiTemperature()
    fun translationAiOutputTokens() = aiEngineCredentials.translationAiOutputTokens()
    fun translationGeminiThinkingMode() = aiEngineCredentials.translationGeminiThinkingMode()

    fun translationDiagnostics() = pipelinePrefs.translationDiagnostics()

    fun translationReadingOrder() = pipelinePrefs.translationReadingOrder()

    fun translationProgressSheetAdvancedView() =
        readerUiPrefs.translationProgressSheetAdvancedView()

    //endregion

    companion object {
        /** Maximum number of entries kept in the per-provider recent models list. */
        const val RECENT_MODELS_LIMIT = 10
        const val RECENT_LANGUAGES_LIMIT = 3

        /**
         * Serialises a model id list to the stored string form. The first item
         * is the most recently selected model. Duplicate ids are collapsed
         * while preserving order.
         */
        fun encodeRecentModels(models: List<String>): String {
            val seen = LinkedHashSet<String>()
            models.forEach { id -> if (id.isNotBlank()) seen.add(id.trim()) }
            return seen.toList().take(RECENT_MODELS_LIMIT).joinToString("\n")
        }

        /** Deserialises the stored recent models string back into an ordered list. */
        fun decodeRecentModels(value: String): List<String> =
            value.split('\n').map { it.trim() }.filter { it.isNotBlank() }

        fun encodeRecentLanguages(languages: List<String>): String {
            val seen = LinkedHashSet<String>()
            languages.forEach { id -> if (id.isNotBlank()) seen.add(id.trim()) }
            return seen.toList().take(RECENT_LANGUAGES_LIMIT).joinToString("\n")
        }

        fun decodeRecentLanguages(value: String): List<String> =
            value.split('\n').map { it.trim() }.filter { it.isNotBlank() }

        fun defaultOcrModel(languageName: String): OcrModel {
            return when (languageName.uppercase()) {
                "JAPANESE" -> OcrModel.MANGAOCR
                else -> OcrModel.MLKIT
            }
        }
    }
}
