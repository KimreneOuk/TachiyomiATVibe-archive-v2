package tachiyomi.domain.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Translation pipeline processing preferences: engine family selection, the
 * OCR/inpaint/colorize/upscale stage options, batch execution policy, and
 * diagnostics.
 */
internal class PipelinePrefs(
    private val preferenceStore: PreferenceStore,
) {

    fun translationRequireCharging() = preferenceStore.getBoolean("translation_require_charging", false)
    fun translationContinueInBackground() = preferenceStore.getBoolean("translation_continue_in_background", true)

    fun translationInpaintingMode() = preferenceStore.getString("translation_inpainting_mode", "FAST")

    fun translationInpaintingNeuralModel(): Preference<NeuralInpaintModel> =
        preferenceStore.getEnum("translation_inpainting_neural_model", NeuralInpaintModel.DEFAULT)

    /**
     * Master gate for on-device colorization: when on, every translated page
     * that produced a cleaned image is also colorized (post-inpaint stage).
     * Default OFF — colorization is a multi-second model pass.
     */
    fun translationColorizationEnabled() = preferenceStore.getBoolean("translation_colorization_enabled", false)

    /** Which colorization engine runs when colorization is enabled. */
    fun translationColorizationModel(): Preference<ColorizationModel> =
        preferenceStore.getEnum("translation_colorization_model", ColorizationModel.DEFAULT)

    /** Chroma-combine look applied after colorization: faithful model render or vibrant prototype. */
    fun colorCombineMode(): Preference<ColorCombineMode> =
        preferenceStore.getEnum("color_combine_mode", ColorCombineMode.DEFAULT)

    /** Denoising pre-processor applied when ORIGINAL color combine mode is active. */
    fun originalDenoisingMode(): Preference<OriginalDenoisingMode> =
        preferenceStore.getEnum("original_denoising_mode", OriginalDenoisingMode.DEFAULT)

    /**
     * Master gate for on-device QuickSRNetLarge page upscaling. Default OFF.
     * While UpscalingModelCatalog.operational is false, enabling yields an
     * explicit unsupported state: no upscaling runs and nothing claims success.
     */
    fun translationUpscalingEnabled() = preferenceStore.getBoolean("translation_upscaling_enabled", false)

    fun translationOcrModel(languageName: String): Preference<OcrModel> {
        val normalized = languageName.lowercase()
        return preferenceStore.getEnum(
            "translation_ocr_model_$normalized",
            TranslationPreferences.defaultOcrModel(languageName),
        )
    }

    fun translationEngineCategory() = preferenceStore.getEnum(
        "translation_engine_category",
        TranslationEngineCategory.STANDARD,
    )
    fun translationStandardEngine() = preferenceStore.getEnum("translation_standard_engine", StandardEngine.MLKIT)
    fun translationAiEngine() = preferenceStore.getEnum("translation_ai_engine", AiEngine.GEMINI)

    /**
     * DeepL (Standard engine) API key. DeepL is the only Standard engine that
     * requires credentials. Stored private (excluded from backups / masked in
     * the UI) via the same __PRIVATE_ convention as the AI keys.
     */
    fun translationDeeplApiKey() =
        preferenceStore.getString("__PRIVATE_translation_deepl_api_key", "")

    /**
     * TachiyomiAT: opt-in verbose logging for the translation pipeline. When on,
     * the OCR/inpaint/translate/render stages emit per-stage and per-ROI INFO
     * logs to logcat, plus heap snapshots. When off (default), only ERROR-level
     * diagnostics are emitted, so the hot path isn't doing string interpolation
     * + log dispatch on every stage of every page. Useful for debugging a flaky
     * translation run without rebuilding.
     */
    fun translationDiagnostics() = preferenceStore.getBoolean("translation_diagnostics", false)

    /**
     * TachiyomiAT: source-page reading order (RTL manga / LTR comic / auto from
     * language). The recognition engine caches its resolved RTL/LTR decision
     * once per instance, so changing this at runtime forces a recognition
     * rebuild (see the engine-rebuild gate in TranslationPipeline).
     */
    fun translationReadingOrder() = preferenceStore.getEnum(
        "translation_reading_order",
        TranslationReadingOrder.RTL_MANGA,
    )
}
