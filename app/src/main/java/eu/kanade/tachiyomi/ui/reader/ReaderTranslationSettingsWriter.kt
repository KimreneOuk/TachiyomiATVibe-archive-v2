package eu.kanade.tachiyomi.ui.reader

import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.settings.TranslationSettingsPreferenceWriter
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory

internal class ReaderTranslationSettingsWriter(
    private val controller: ReaderTranslationController,
) {
    private val translationPreferences get() = controller.translationPreferences
    private val viewModelScope get() = controller.viewModelScope
    private val aiModelFetchState get() = controller.aiModelFetchState
    private val preferenceWriter by lazy { TranslationSettingsPreferenceWriter(translationPreferences) }

    internal fun setTranslationEnabled(enabled: Boolean) = preferenceWriter.setTranslationEnabled(enabled)

    internal fun setAutoTranslate(auto: Boolean) = preferenceWriter.setAutoTranslate(auto)

    /** TachiyomiAT: idle translate button ghost vs. show-only-with-controls. */
    internal fun setTranslateButtonWithControls(withControls: Boolean) = preferenceWriter.setTranslateButtonWithControls(withControls)

    internal fun setTranslateFromLanguage(language: String) = preferenceWriter.setTranslateFromLanguage(language)

    internal fun setTranslateToLanguage(language: String) = preferenceWriter.setTranslateToLanguage(language)

    internal fun setOcrModel(model: OcrModel) = preferenceWriter.setOcrModel(model)

    internal fun setTranslationInpaintingMode(mode: String) = preferenceWriter.setTranslationInpaintingMode(mode)

    internal fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) = preferenceWriter.setTranslationInpaintingNeuralModel(model)

    internal fun setTranslationColorizationEnabled(enabled: Boolean) = preferenceWriter.setTranslationColorizationEnabled(enabled)

    internal fun setTranslationColorizationModel(model: ColorizationModel) = preferenceWriter.setTranslationColorizationModel(model)

    internal fun setTranslationColorCombineMode(mode: ColorCombineMode) = preferenceWriter.setTranslationColorCombineMode(mode)

    internal fun setTranslationUpscalingEnabled(enabled: Boolean) = preferenceWriter.setTranslationUpscalingEnabled(enabled)

    internal fun setTranslationEngineCategory(category: TranslationEngineCategory) = preferenceWriter.setTranslationEngineCategory(category)

    internal fun setTranslationStandardEngine(engine: StandardEngine) = preferenceWriter.setTranslationStandardEngine(engine)

    internal fun setTranslationDeeplApiKey(key: String) = preferenceWriter.setTranslationDeeplApiKey(key)

    internal fun setTranslationAiEngine(engine: AiEngine) = preferenceWriter.setTranslationAiEngine(engine)

    internal fun setTranslationAiApiKey(key: String) = preferenceWriter.setTranslationAiApiKey(key)

    internal fun setTranslationAiBaseUrl(url: String) = preferenceWriter.setTranslationAiBaseUrl(url)

    internal fun setTranslationAiModel(model: String) = preferenceWriter.setTranslationAiModel(model)

    internal fun fetchAiModels() {
        val aiEngine = translationPreferences.translationAiEngine().get()
        val key = translationPreferences.translationAiApiKey(aiEngine).get()
        val url = translationPreferences.translationAiBaseUrl(aiEngine)?.get().orEmpty()

        aiModelFetchState.value = AiModelListState.Loading(if (aiEngine == AiEngine.LMSTUDIO) url else key)
        viewModelScope.launch {
            try {
                val result = AiModelFetcher.fetch(aiEngine, key, url)
                aiModelFetchState.value = when (result) {
                    is AiModelFetcher.Result.Success -> AiModelListState.Loaded(result.models)
                    is AiModelFetcher.Result.InvalidKey -> AiModelListState.Failed("Invalid or expired API key")
                    is AiModelFetcher.Result.NoModels -> AiModelListState.Loaded(emptyList())
                    is AiModelFetcher.Result.Error -> AiModelListState.Failed(result.message)
                }
            } catch (e: Exception) {
                aiModelFetchState.value = AiModelListState.Failed(e.message ?: "Unknown error")
            }
        }
    }
}
