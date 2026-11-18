package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.presentation.reader.sheet.TranslationColorizationOptionsSection
import eu.kanade.presentation.reader.sheet.TranslationColorizationToggleSection
import eu.kanade.presentation.reader.sheet.TranslationEngineSection
import eu.kanade.presentation.reader.sheet.TranslationInpaintingModeSection
import eu.kanade.presentation.reader.sheet.TranslationLanguageSection
import eu.kanade.presentation.reader.sheet.TranslationModeFlip
import eu.kanade.presentation.reader.sheet.TranslationNeuralInpaintModelSection
import eu.kanade.presentation.reader.sheet.TranslationOcrModelSection
import eu.kanade.tachiyomi.ui.manga.BatchTranslationSettingsActions
import eu.kanade.tachiyomi.ui.manga.BatchTranslationSettingsPhase
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun BatchTranslationSettingsSheet(
    state: TranslationSettingsState,
    phase: BatchTranslationSettingsPhase,
    actions: BatchTranslationSettingsActions,
    onDismissRequest: () -> Unit,
    onResume: (() -> Unit)?,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val basicScroll = rememberScrollState()
    val advancedScroll = rememberScrollState()

    AdaptiveSheet(
        forceSheetMode = true,
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.batch_translation_settings_title),
                    subtitle = stringResource(ATMR.strings.batch_translation_settings_subtitle),
                    onClose = onDismissRequest,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    TranslationModeFlip(
                        showAdvanced = advanced,
                        onAdvancedChange = { advanced = it },
                        basicScroll = basicScroll,
                        advancedScroll = advancedScroll,
                        basic = {
                            SettingsScopeBanner(phase)
                            TranslationLanguageSection(
                                state = state,
                                onTranslateFromLanguageChange = actions::setTranslateFromLanguage,
                                onTranslateToLanguageChange = actions::setTranslateToLanguage,
                            )
                            TranslationEngineSection(
                                state = state,
                                onTranslationEngineCategoryChange = actions::setTranslationEngineCategory,
                                onTranslationStandardEngineChange = actions::setTranslationStandardEngine,
                                onTranslationDeeplApiKeyChange = actions::setTranslationDeeplApiKey,
                                onTranslationAiEngineChange = actions::setTranslationAiEngine,
                                onTranslationAiApiKeyChange = actions::setTranslationAiApiKey,
                                onTranslationAiBaseUrlChange = actions::setTranslationAiBaseUrl,
                                onTranslationAiModelChange = actions::setTranslationAiModel,
                                onFetchAiModels = actions::fetchAiModels,
                            )
                            TranslationColorizationToggleSection(
                                state = state,
                                onColorizationEnabledChange = actions::setTranslationColorizationEnabled,
                            )
                            TranslationInpaintingModeSection(
                                state = state,
                                onTranslationInpaintingModeChange = actions::setTranslationInpaintingMode,
                            )
                        },
                        advanced = {
                            SettingsScopeBanner(phase)
                            TranslationOcrModelSection(
                                state = state,
                                onOcrModelChange = actions::setOcrModel,
                            )
                            TranslationNeuralInpaintModelSection(
                                state = state,
                                onTranslationInpaintingNeuralModelChange = actions::setTranslationInpaintingNeuralModel,
                            )
                            TranslationColorizationOptionsSection(
                                state = state,
                                onColorizationModelChange = actions::setTranslationColorizationModel,
                                onColorCombineModeChange = actions::setTranslationColorCombineMode,
                                onUpscalingEnabledChange = actions::setTranslationUpscalingEnabled,
                            )
                        },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(ATMR.strings.batch_translation_settings_done))
                    }
                    if (phase == BatchTranslationSettingsPhase.PAUSED && onResume != null) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(ATMR.strings.batch_translation_settings_resume))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScopeBanner(phase: BatchTranslationSettingsPhase) {
    val message = when (phase) {
        BatchTranslationSettingsPhase.PRE_START -> return
        BatchTranslationSettingsPhase.PAUSE_REQUESTED ->
            stringResource(ATMR.strings.batch_translation_settings_pause_requested)
        BatchTranslationSettingsPhase.PAUSED ->
            stringResource(ATMR.strings.batch_translation_settings_paused)
        BatchTranslationSettingsPhase.BATCH_RUNNING ->
            stringResource(ATMR.strings.batch_translation_settings_batch_running)
        BatchTranslationSettingsPhase.BATCH_ENDED ->
            stringResource(ATMR.strings.batch_translation_settings_batch_ended)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = DesignTokens.ShapeCard,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}
