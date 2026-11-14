package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Reader-facing translation UI preferences: master gates, auto-mode toggles,
 * translate-button presentation, language selection, and batch-sheet depth.
 */
internal class ReaderUiPrefs(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Master gate for reader translation features (per-page translate
     * buttons, auto mode). Defaults to false: the Translation settings sheet
     * exposes a toggle, so translation is opt-in.
     */
    fun translationEnabled() = preferenceStore.getBoolean("translation_enabled", false)

    /**
     * Show a read-only confirmation popup (current source/target language,
     * engine/model, OCR model, output tokens) before the manga-screen batch
     * "Translate chapter" action runs. The reader per-page/on-the-fly path is
     * unaffected. Defaults to true so users review their settings at least
     * once; the popup's "Don't show this again" checkbox and the Translation
     * settings sheet both toggle this off.
     */
    fun translationConfirmPretranslate() = preferenceStore.getBoolean("translation_confirm_pretranslate", true)

    /**
     * Reader auto-translation: when enabled (and [TranslationPreferences.translationEnabled] is on),
     * the reader proactively translates the current page and pre-processes
     * upcoming pages on each page change. Work is additive (never cancelled
     * on scroll) and clamped to the current chapter.
     */
    fun autoTranslate() = preferenceStore.getBoolean("auto_translate", false)

    /**
     * Reader auto-colorization: when enabled (and [TranslationPreferences.translationColorizationEnabled] is on),
     * the reader proactively colorizes the current page N and next page N+1 on viewport changes.
     * Operates without requiring whole-chapter download, coexisting with autoTranslate.
     */
    fun autoColorize() = preferenceStore.getBoolean("auto_colorize", false)

    /**
     * TachiyomiAT: translate button presentation on reader pages.
     *
     * One lightweight boolean encodes the two spec modes:
     * - false (default) = ADAPTIVE: the button ghosts (dims) while reader
     *   controls are hidden, and becomes fully visible when controls show.
     * - true = WITH_CONTROLS: the button is hidden while reading and appears
     *   only together with reader controls. The cancel affordance during an
     *   active translation stays visible in both modes.
     */
    fun translateButtonWithControls() = preferenceStore.getBoolean("reader_translate_button_with_controls", false)

    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")

    fun translationRecentLanguagesFrom() = preferenceStore.getString("translation_recent_languages_from", "")
    fun translationRecentLanguagesTo() = preferenceStore.getString("translation_recent_languages_to", "")

    /**
     * The batch progress sheet's Advanced view (pipeline stage
     * cards, page grid, failure groups, queue detail) behind the simplified
     * default. Persisted so the chosen depth survives sheet reopenings.
     */
    fun translationProgressSheetAdvancedView() =
        preferenceStore.getBoolean("translation_progress_sheet_advanced_view", false)
}
