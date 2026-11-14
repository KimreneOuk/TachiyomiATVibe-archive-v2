package eu.kanade.tachiyomi.ui.reader

import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.TextRecognizerLanguage
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences

internal class ReaderTranslationSettingsWriter(
    private val controller: ReaderTranslationController,
) {
    private val translationPreferences get() = controller.translationPreferences
    private val viewModelScope get() = controller.viewModelScope
    private val aiModelFetchState get() = controller.aiModelFetchState

    internal fun setTranslationEnabled(enabled: Boolean) {
        translationPreferences.translationEnabled().set(enabled)
    }

    internal fun setAutoTranslate(auto: Boolean) {
        translationPreferences.autoTranslate().set(auto)
    }

    internal fun setAutoColorize(auto: Boolean) {
        translationPreferences.autoColorize().set(auto)
        if (auto && translationPreferences.translationColorizationEnabled().get()) {
            controller.colorizeCurrentPageForAuto()
        }
    }

    /** TachiyomiAT: idle translate button ghost vs. show-only-with-controls. */
    internal fun setTranslateButtonWithControls(withControls: Boolean) {
        translationPreferences.translateButtonWithControls().set(withControls)
    }

    internal fun setTranslateFromLanguage(language: String) {
        translationPreferences.translateFromLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesFrom().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesFrom().set(updated)
    }

    internal fun setTranslateToLanguage(language: String) {
        translationPreferences.translateToLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesTo().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesTo().set(updated)
    }

    internal fun setOcrModel(model: OcrModel) {
        val fromValue = translationPreferences.translateFromLanguage().get()
        val language = TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue } ?: TextRecognizerLanguage.CHINESE
        OcrModelCatalog.preferenceFor(translationPreferences, language).set(model)
    }

    internal fun setTranslationInpaintingMode(mode: String) {
        translationPreferences.translationInpaintingMode().set(mode)
    }

    internal fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) {
        translationPreferences.translationInpaintingNeuralModel().set(model)
    }

    internal fun setTranslationColorizationEnabled(enabled: Boolean) {
        translationPreferences.translationColorizationEnabled().set(enabled)
    }

    internal fun setTranslationColorizationModel(model: ColorizationModel) {
        translationPreferences.translationColorizationModel().set(model)
    }

    internal fun setTranslationColorCombineMode(mode: ColorCombineMode) {
        translationPreferences.colorCombineMode().set(mode)
    }

    internal fun setTranslationUpscalingEnabled(enabled: Boolean) {
        translationPreferences.translationUpscalingEnabled().set(enabled)
    }

    internal fun setTranslationEngineCategory(category: TranslationEngineCategory) {
        translationPreferences.translationEngineCategory().set(category)
    }

    internal fun setTranslationStandardEngine(engine: StandardEngine) {
        translationPreferences.translationStandardEngine().set(engine)
    }

    internal fun setTranslationDeeplApiKey(key: String) {
        translationPreferences.translationDeeplApiKey().set(key)
    }

    internal fun setTranslationAiEngine(engine: AiEngine) {
        translationPreferences.translationAiEngine().set(engine)
    }

    internal fun setTranslationAiApiKey(key: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiApiKey(engine).set(key)
    }

    internal fun setTranslationAiBaseUrl(url: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiBaseUrl(engine)?.set(url)
    }

    internal fun setTranslationAiModel(model: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiModel(engine).set(model)
        val recentPref = translationPreferences.translationAiRecentModels(engine)
        val recentModels = TranslationPreferences.decodeRecentModels(recentPref.get())
        recentPref.set(TranslationPreferences.encodeRecentModels(listOf(model) + recentModels))
    }

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
