package eu.kanade.translation.settings

import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.OriginalDenoisingMode
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences

/** Writes the shared translation preferences used by reader and batch settings. */
internal class TranslationSettingsPreferenceWriter(
    private val preferences: TranslationPreferences,
) {
    fun setTranslationEnabled(enabled: Boolean) {
        preferences.translationEnabled().set(enabled)
    }

    fun setAutoTranslate(auto: Boolean) {
        preferences.autoTranslate().set(auto)
    }

    fun setTranslateButtonWithControls(withControls: Boolean) {
        preferences.translateButtonWithControls().set(withControls)
    }

    fun setTranslateFromLanguage(language: String) {
        preferences.translateFromLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(preferences.translationRecentLanguagesFrom().get())
        preferences.translationRecentLanguagesFrom().set(
            TranslationPreferences.encodeRecentLanguages(listOf(language) + recent),
        )
    }

    fun setTranslateToLanguage(language: String) {
        preferences.translateToLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(preferences.translationRecentLanguagesTo().get())
        preferences.translationRecentLanguagesTo().set(
            TranslationPreferences.encodeRecentLanguages(listOf(language) + recent),
        )
    }

    fun setOcrModel(model: OcrModel) {
        val source = preferences.translateFromLanguage().get()
        val language = OcrModelCatalog.languageFromPreference(source)
        OcrModelCatalog.preferenceFor(preferences, language).set(model)
    }

    fun setTranslationInpaintingMode(mode: String) {
        preferences.translationInpaintingMode().set(mode)
    }

    fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) {
        preferences.translationInpaintingNeuralModel().set(model)
    }

    fun setTranslationColorizationEnabled(enabled: Boolean) {
        preferences.translationColorizationEnabled().set(enabled)
    }

    fun setTranslationColorizationModel(model: ColorizationModel) {
        preferences.translationColorizationModel().set(model)
    }

    fun setTranslationColorCombineMode(mode: ColorCombineMode) {
        preferences.colorCombineMode().set(mode)
    }

    fun setOriginalDenoisingMode(mode: OriginalDenoisingMode) {
        preferences.originalDenoisingMode().set(mode)
    }

    fun setTranslationUpscalingEnabled(enabled: Boolean) {
        preferences.translationUpscalingEnabled().set(enabled)
    }

    fun setTranslationEngineCategory(category: TranslationEngineCategory) {
        preferences.translationEngineCategory().set(category)
    }

    fun setTranslationStandardEngine(engine: StandardEngine) {
        preferences.translationStandardEngine().set(engine)
    }

    fun setTranslationDeeplApiKey(key: String) {
        preferences.translationDeeplApiKey().set(key)
    }

    fun setTranslationAiEngine(engine: AiEngine) {
        preferences.translationAiEngine().set(engine)
    }

    fun setTranslationAiApiKey(key: String) {
        val engine = preferences.translationAiEngine().get()
        preferences.translationAiApiKey(engine).set(key)
    }

    fun setTranslationAiBaseUrl(url: String) {
        val engine = preferences.translationAiEngine().get()
        preferences.translationAiBaseUrl(engine)?.set(url)
    }

    fun setTranslationAiModel(model: String) {
        val engine = preferences.translationAiEngine().get()
        preferences.translationAiModel(engine).set(model)
        val recentPref = preferences.translationAiRecentModels(engine)
        val recentModels = TranslationPreferences.decodeRecentModels(recentPref.get())
        recentPref.set(TranslationPreferences.encodeRecentModels(listOf(model) + recentModels))
    }
}
