# Architectural Plan: Auto-Translation Sub-Toggles in Reader

**Status:** Proposed / Review  
**Target:** Parent Coordinator & Developers  
**Author:** Tactical Software Engineer & Strategic System Architect  
**Adherence:** AGENTS.md standards (Interface First, Deep Modules, Strict Locality, ≤3-line comments)

---

## 1. Context & Motivation

Currently, when the **Auto-translate** master switch is turned on in the Reader settings, the reader automatically executes the entire monolithic pipeline across visible and look-ahead pages:
`Decode -> OCR -> Translation -> Inpainting -> Colorization (if active)`

Many users want more granular control over what runs automatically while reading:
1. **Text Translation Only**: Automatic OCR, Translation, and Inpainting without running compute-heavy colorization.
2. **Colorization Only**: Automatic AI Colorization (MCV2) directly from original pages, completely skipping text detection, translation, and inpainting.
3. **Both (Full Pipeline)**: Both text translation and colorization when both sub-toggles are enabled.

---

## 2. Preference & Domain Contracts

### 2.1 Preferences Layer
Add two durable boolean preferences in `domain/src/main/java/tachiyomi/domain/translation/ReaderUiPrefs.kt` and expose them via `TranslationPreferences.kt`:

```kotlin
// In ReaderUiPrefs.kt
fun autoTranslateText() = preferenceStore.getBoolean("reader_auto_translate_text", true)
fun autoTranslateColor() = preferenceStore.getBoolean("reader_auto_translate_color", false)
```

Unlike the session-scoped master switch (`autoTranslate`), these sub-toggle preferences persist user preferences across reader sessions.

### 2.2 Domain Pipeline Execution Mode
Define an explicit, typed execution enum in `app/src/main/java/eu/kanade/translation/coordinator/ReaderTranslationContracts.kt`:

```kotlin
enum class ReaderPipelineMode {
    TEXT_ONLY,
    COLOR_ONLY,
    FULL;

    val requiresText: Boolean get() = this == TEXT_ONLY || this == FULL
    val requiresColor: Boolean get() = this == COLOR_ONLY || this == FULL
}
```

---

## 3. UI / UX Design (`TranslationActivationSection.kt`)

In `app/src/main/java/eu/kanade/presentation/reader/sheet/TranslationActivationSection.kt`:
When `state.autoTranslate == true`, conditionally display two indented secondary toggle cards:

```kotlin
if (state.autoTranslate) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.Translate,
            title = stringResource(ATMR.strings.pref_auto_translate_text),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_text_summary),
            checked = state.autoTranslateText,
            onCheckedChange = onAutoTranslateTextChange,
        )
        SwitchCard(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.pref_auto_translate_color),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_color_summary),
            checked = state.autoTranslateColor,
            onCheckedChange = onAutoTranslateColorChange,
        )
    }
}
```

### 3.1 Behavior & State Transition Rules
- **State Guard (Anti-Zombie)**: If the user unchecks the last remaining sub-toggle while `autoTranslate` is on, master `autoTranslate` automatically flips to `false`.
- **Default Restoration**: When the user flips master `autoTranslate` from `false` to `true`, if both sub-toggles are `false`, `autoTranslateText` is automatically defaulted to `true`.
- **Color Model Activation**: Toggling `autoTranslateColor` to `true` automatically verifies that `translationColorizationEnabled().set(true)`.
- **Decoupled Preflight Checks**: `inpaintModelMissing()` checks are enforced only if `mode.requiresText` is true. In `COLOR_ONLY` mode, missing inpaint assets will not block auto-colorization.

---

## 4. Pipeline & Coordinator Execution

### 4.1 Resolving the Infinite Re-Colorization Concurrency Loop
In `ReaderTranslationContracts.kt`, `isReaderPageWorkFinal()` currently treats `isColorizeOnlyComplete` as non-final because it expects translation to follow:
```kotlin
// Updated seam:
fun isReaderPageWorkFinal(
    page: PageTranslationView?,
    force: Boolean,
    mode: ReaderPipelineMode = ReaderPipelineMode.FULL,
): Boolean {
    if (page == null) return false
    return when (mode) {
        ReaderPipelineMode.COLOR_ONLY -> {
            page.coloringStatus == StageStatus.READY && page.coloredImageName != null
        }
        ReaderPipelineMode.TEXT_ONLY -> {
            page.translationStatus == StageStatus.READY || page.isTextlessTerminal
        }
        ReaderPipelineMode.FULL -> {
            val textDone = page.translationStatus == StageStatus.READY || page.isTextlessTerminal
            val colorDone = page.coloringStatus == StageStatus.READY || page.coloringStatus == StageStatus.SKIPPED
            textDone && colorDone
        }
    }
}
```
This prevents infinite background re-processing loops on viewport scrolls.

### 4.2 Single-Page Pipeline Dispatch (`ReaderPagePipeline.kt`)
Update `PageRequest` to include `val mode: ReaderPipelineMode = ReaderPipelineMode.FULL`.

- **When `mode == ReaderPipelineMode.COLOR_ONLY`**:
  Directly invoke `processor.colorizeOriginalPage()`. Acquire `PageStage.Render` lease, emit `COLORING` stage signal, skip OCR/Inpainting, decode original image, and commit colored result to the store.
- **When `mode == ReaderPipelineMode.TEXT_ONLY`**:
  Invoke `processor.processSinglePage(allowColorization = false)`. Decode, OCR, translate, inpaint, but bypass colorization even if a color model is active.
- **When `mode == ReaderPipelineMode.FULL`**:
  Execute full pipeline `processor.processSinglePage(allowColorization = true)`.

---

## 5. Viewer Stream & Display Binding

No viewer layer changes are required. The existing display binding in `ReaderTranslationController.kt` and `ReaderTranslationObservationController.kt` resolves via `translation.displayImageName(colorizationEnabled)`:
- **Text Only**: Returns `cleanedImageName`. Pager/Webtoon renders cleaned image with translated text overlay.
- **Color Only**: Returns `coloredImageName` (with `coloredFromOriginal = true`). Text blocks are empty, so no overlay is shown.
- **Both**: Returns `coloredImageName` (colored inpaint). Text overlay renders translated text on top of the colored image.

---

## 6. Implementation Checklist & Modified Files

- [ ] `domain/src/main/java/tachiyomi/domain/translation/ReaderUiPrefs.kt`
- [ ] `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
- [ ] `app/src/main/java/eu/kanade/translation/coordinator/ReaderTranslationContracts.kt`
- [ ] `app/src/main/java/eu/kanade/translation/coordinator/ReaderTranslationCoordinator.kt`
- [ ] `app/src/main/java/eu/kanade/translation/coordinator/ReaderViewportController.kt`
- [ ] `app/src/main/java/eu/kanade/translation/coordinator/ReaderPagePipeline.kt`
- [ ] `app/src/main/java/eu/kanade/translation/processing/TranslationProcessor.kt`
- [ ] `app/src/main/java/eu/kanade/tachiyomi/ui/reader/TranslationSettingsState.kt`
- [ ] `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- [ ] `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt`
- [ ] `app/src/main/java/eu/kanade/presentation/reader/sheet/TranslationActivationSection.kt`
- [ ] `app/src/main/java/eu/kanade/presentation/reader/sheet/ReaderSettingsSheet.kt`
- [ ] `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- [ ] `i18n-at/src/commonMain/moko-resources/base/strings.xml`
- [ ] `app/src/test/java/eu/kanade/translation/coordinator/ReaderPageWorkFinalityTest.kt`
