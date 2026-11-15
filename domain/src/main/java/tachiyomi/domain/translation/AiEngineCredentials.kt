package tachiyomi.domain.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * AI-model translator credentials and per-provider configuration. Keys and
 * recent-model lists use the __PRIVATE_ prefix: excluded from backups and
 * masked in the UI by convention.
 */
internal class AiEngineCredentials(
    private val preferenceStore: PreferenceStore,
) {

    // API keys are private: the __PRIVATE_ prefix excludes them from backups
    // and from any non-masked UI subtitle by convention.
    fun translationAiApiKeyGemini() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_gemini", "")
    fun translationAiApiKeyOpenrouter() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_openrouter", "")
    fun translationAiApiKeyDeepseek() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_deepseek", "")

    fun translationAiApiKey(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiApiKeyGemini()
        AiEngine.OPENROUTER -> translationAiApiKeyOpenrouter()
        AiEngine.DEEPSEEK -> translationAiApiKeyDeepseek()
        AiEngine.LMSTUDIO -> preferenceStore.getString("__PRIVATE_translation_ai_api_key_lmstudio", "")
    }

    fun translationAiBaseUrlLmStudio() =
        preferenceStore.getString("translation_ai_base_url_lmstudio", "")

    fun translationAiModelGemini() = preferenceStore.getString("translation_ai_model_gemini", "gemini-1.5-pro")
    fun translationAiModelOpenrouter() = preferenceStore.getString("translation_ai_model_openrouter", "")
    fun translationAiModelDeepseek() = preferenceStore.getString("translation_ai_model_deepseek", "deepseek-chat")
    fun translationAiModelLmStudio() = preferenceStore.getString("translation_ai_model_lmstudio", "")

    fun translationAiModel(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiModelGemini()
        AiEngine.OPENROUTER -> translationAiModelOpenrouter()
        AiEngine.DEEPSEEK -> translationAiModelDeepseek()
        AiEngine.LMSTUDIO -> translationAiModelLmStudio()
    }

    // Recent models are kept private as well to avoid leaking usage hints
    // into backups. Stored as an ordered set (most-recent first within the
    // serialised string).
    fun translationAiRecentModelsGemini() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_gemini", "")
    fun translationAiRecentModelsOpenrouter() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_openrouter", "")
    fun translationAiRecentModelsDeepseek() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_deepseek", "")
    fun translationAiRecentModelsLmStudio() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_lmstudio", "")

    fun translationAiRecentModels(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiRecentModelsGemini()
        AiEngine.OPENROUTER -> translationAiRecentModelsOpenrouter()
        AiEngine.DEEPSEEK -> translationAiRecentModelsDeepseek()
        AiEngine.LMSTUDIO -> translationAiRecentModelsLmStudio()
    }

    fun translationAiBaseUrl(engine: AiEngine): Preference<String>? = when (engine) {
        AiEngine.LMSTUDIO -> translationAiBaseUrlLmStudio()
        else -> null
    }

    fun translationAiTemperature() = preferenceStore.getString("translation_ai_temperature", "0.3")
    fun translationAiOutputTokens() = preferenceStore.getString("translation_ai_output_tokens", "8192")
    fun translationGeminiThinkingMode() = preferenceStore.getEnum(
        "translation_gemini_thinking_mode",
        GeminiThinkingMode.DISABLED,
    )
}
