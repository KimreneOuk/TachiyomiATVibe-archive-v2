package eu.kanade.presentation.reader.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.designsystem.ActionCard
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.components.designsystem.SwitchCard
import eu.kanade.presentation.translation.colorizationModelOptions
import eu.kanade.presentation.translation.neuralInpaintOptions
import eu.kanade.presentation.translation.ocrModelOptions
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun TranslationOcrInpaintSection(
    state: TranslationSettingsState,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    TranslationOcrModelSection(state, onOcrModelChange)
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InpaintingModeTiles(state, onTranslationInpaintingModeChange)
        NeuralInpaintModelSelector(state, onTranslationInpaintingNeuralModelChange)
    }
}

@Composable
internal fun TranslationOcrModelSection(
    state: TranslationSettingsState,
    onOcrModelChange: (OcrModel) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_vision))

    val ocrOptions = ocrModelOptions()

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
    ) {
        SearchableConfigSelectorBox(
            selectedLabel = ocrOptions.firstOrNull { it.value == state.ocrModel }?.label
                ?: state.ocrModel.name,
            options = ocrOptions,
            onSelect = onOcrModelChange,
            placeholder = stringResource(ATMR.strings.pref_ocr_model),
        )
    }
}

@Composable
internal fun TranslationInpaintingModeSection(
    state: TranslationSettingsState,
    onTranslationInpaintingModeChange: (String) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
    ) {
        InpaintingModeTiles(state, onTranslationInpaintingModeChange)
    }
}

@Composable
internal fun TranslationNeuralInpaintModelSection(
    state: TranslationSettingsState,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    if (!state.inpaintingMode.equals("FAST", ignoreCase = true)) {
        SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(DesignTokens.ShapeCard)
                .background(DesignTokens.CardFill)
                .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
                .padding(14.dp),
        ) {
            NeuralInpaintModelSelector(state, onTranslationInpaintingNeuralModelChange)
        }
    }
}

@Composable
private fun InpaintingModeTiles(
    state: TranslationSettingsState,
    onTranslationInpaintingModeChange: (String) -> Unit,
) {
    val currentInpaint = state.inpaintingMode.ifEmpty { "QUALITY" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionTile(
            selected = currentInpaint.equals("FAST", ignoreCase = true),
            onClick = { onTranslationInpaintingModeChange("FAST") },
            title = stringResource(ATMR.strings.pref_inpainting_mode_fast),
            modifier = Modifier.weight(1f),
        )
        SelectionTile(
            selected = currentInpaint.equals("BALANCE", ignoreCase = true),
            onClick = { onTranslationInpaintingModeChange("BALANCE") },
            title = stringResource(ATMR.strings.pref_inpainting_mode_balance),
            modifier = Modifier.weight(1f),
        )
        SelectionTile(
            selected = currentInpaint.equals("QUALITY", ignoreCase = true),
            onClick = { onTranslationInpaintingModeChange("QUALITY") },
            title = stringResource(ATMR.strings.pref_inpainting_mode_quality),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NeuralInpaintModelSelector(
    state: TranslationSettingsState,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    val currentInpaint = state.inpaintingMode.ifEmpty { "QUALITY" }
    AnimatedVisibility(
        visible = !currentInpaint.equals("FAST", ignoreCase = true),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val neuralOptions = neuralInpaintOptions()
        SearchableConfigSelectorBox(
            selectedLabel = neuralOptions.firstOrNull { it.value == state.inpaintingNeuralModel }?.label
                ?: state.inpaintingNeuralModel.name,
            options = neuralOptions,
            onSelect = onTranslationInpaintingNeuralModelChange,
            placeholder = stringResource(ATMR.strings.pref_inpainting_neural_model),
        )
    }
}

@Composable
internal fun TranslationColorizationSection(
    state: TranslationSettingsState,
    onColorizationEnabledChange: (Boolean) -> Unit,
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onColorCombineModeChange: (ColorCombineMode) -> Unit,
    onUpscalingEnabledChange: (Boolean) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.pref_colorization_title))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorizationEnabledControl(state, onColorizationEnabledChange)
        ColorizationModelAndCombineControls(state, onColorizationModelChange, onColorCombineModeChange)
    }

    UpscalingSection(state, onUpscalingEnabledChange)
}

@Composable
internal fun TranslationColorizationToggleSection(
    state: TranslationSettingsState,
    onColorizationEnabledChange: (Boolean) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.pref_colorization_title))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
    ) {
        ColorizationEnabledControl(state, onColorizationEnabledChange)
    }
}

@Composable
internal fun TranslationColorizationOptionsSection(
    state: TranslationSettingsState,
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onColorCombineModeChange: (ColorCombineMode) -> Unit,
    onUpscalingEnabledChange: (Boolean) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.pref_colorization_title))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorizationModelAndCombineControls(state, onColorizationModelChange, onColorCombineModeChange)
    }
    UpscalingSection(state, onUpscalingEnabledChange)
}

@Composable
private fun ColorizationEnabledControl(
    state: TranslationSettingsState,
    onColorizationEnabledChange: (Boolean) -> Unit,
) {
    SwitchCard(
        icon = Icons.Outlined.Palette,
        title = stringResource(ATMR.strings.pref_colorization_enabled),
        subtitle = stringResource(ATMR.strings.pref_colorization_enabled_summary),
        checked = state.colorizationEnabled,
        onCheckedChange = onColorizationEnabledChange,
    )
}

@Composable
private fun ColorizationModelAndCombineControls(
    state: TranslationSettingsState,
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onColorCombineModeChange: (ColorCombineMode) -> Unit,
) {
    AnimatedVisibility(
        visible = state.colorizationEnabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val colorizationOptions = colorizationModelOptions()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchableConfigSelectorBox(
                selectedLabel = colorizationOptions.firstOrNull { it.value == state.colorizationModel }?.label
                    ?: state.colorizationModel.name,
                options = colorizationOptions,
                onSelect = onColorizationModelChange,
                placeholder = stringResource(ATMR.strings.pref_colorization_model),
            )
            SectionHeading(title = stringResource(ATMR.strings.pref_color_combine_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionTile(
                    selected = state.colorCombineMode == ColorCombineMode.ORIGINAL,
                    onClick = { onColorCombineModeChange(ColorCombineMode.ORIGINAL) },
                    title = stringResource(ATMR.strings.pref_color_combine_original),
                    subtitle = stringResource(ATMR.strings.pref_color_combine_original_summary),
                    modifier = Modifier.weight(1f),
                )
                SelectionTile(
                    selected = state.colorCombineMode == ColorCombineMode.VIBRANT,
                    onClick = { onColorCombineModeChange(ColorCombineMode.VIBRANT) },
                    title = stringResource(ATMR.strings.pref_color_combine_vibrant),
                    subtitle = stringResource(ATMR.strings.pref_color_combine_vibrant_summary),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UpscalingSection(
    state: TranslationSettingsState,
    onUpscalingEnabledChange: (Boolean) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.pref_upscaling_title))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(ATMR.strings.pref_upscaling_enabled),
            subtitle = stringResource(ATMR.strings.pref_upscaling_enabled_summary),
            checked = state.upscalingEnabled,
            onCheckedChange = onUpscalingEnabledChange,
        )
    }
}

/** Quick colorize actions of the translation drawer's Basic mode. */
@Composable
internal fun TranslationColorizeActionsSection(
    onColorizePageClick: () -> Unit,
    onColorizeChapterClick: () -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.pref_colorization_title))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionCard(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.reader_colorize_page),
            subtitle = stringResource(ATMR.strings.reader_colorize_page_summary),
            onClick = onColorizePageClick,
        )
        ActionCard(
            icon = Icons.Outlined.Palette,
            title = stringResource(ATMR.strings.reader_colorize_chapter),
            subtitle = stringResource(ATMR.strings.reader_colorize_summary),
            onClick = onColorizeChapterClick,
        )
    }
}
