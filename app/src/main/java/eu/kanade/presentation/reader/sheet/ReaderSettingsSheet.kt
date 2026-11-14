package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.ui.reader.ReaderSettingsDestination
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Unified reader and translation settings, opened at the requested pane. */
@Composable
fun ReaderSettingsSheet(
    destination: ReaderSettingsDestination,
    screenModel: ReaderSettingsScreenModel,
    translationState: TranslationSettingsState,
    onPaneSelected: (ReaderSettingsDestination) -> Unit,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onFloaterEnabledChange: (Boolean) -> Unit,
    onFloaterIdleFadeChange: (Boolean) -> Unit,
    onShowTranslatedTextChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onResetFloaterPosition: () -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onColorizationEnabledChange: (Boolean) -> Unit,
    onColorizationModelChange: (ColorizationModel) -> Unit,
    onColorCombineModeChange: (ColorCombineMode) -> Unit,
    onUpscalingEnabledChange: (Boolean) -> Unit,
    onColorizeChapterClick: () -> Unit,
    onColorizePageClick: () -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    onResetChapterData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isInteractingWithVisuals by remember { mutableStateOf(false) }

    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(isInteractingWithVisuals) {
        if (isInteractingWithVisuals) {
            window?.setDimAmount(0f)
            onHideMenus()
        } else {
            window?.setDimAmount(0.6f)
            onShowMenus()
        }
    }

    var selectedPane by rememberSaveable(destination) {
        mutableIntStateOf(destination.paneIndex)
    }
    val paneScrollStates = listOf(
        rememberScrollState(),
        rememberScrollState(),
        rememberScrollState(),
        rememberScrollState(),
    )
    LaunchedEffect(selectedPane) {
        onPaneSelected(ReaderSettingsDestination.entries[selectedPane])
    }
    val dismiss = {
        onDismissRequest()
        onShowMenus()
    }

    AdaptiveSheet(
        forceSheetMode = true,
        onDismissRequest = dismiss,
    ) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(ATMR.strings.reader_settings_title),
                    subtitle = stringResource(ATMR.strings.reader_unified_subtitle),
                    onClose = dismiss,
                )

                Spacer(modifier = Modifier.height(4.dp))

                ReaderSettingsTabs(
                    tabs = listOf(
                        stringResource(ATMR.strings.reader_pane_reader),
                        stringResource(ATMR.strings.reader_subtab_language_engines),
                        stringResource(ATMR.strings.reader_subtab_vision_color),
                        stringResource(ATMR.strings.reader_pane_session_data),
                    ),
                    selectedIndex = selectedPane,
                    onTabSelected = {
                        selectedPane = it
                        onPaneSelected(ReaderSettingsDestination.entries[it])
                    },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(paneScrollStates[selectedPane])
                        .padding(bottom = 24.dp),
                ) {
                    when (selectedPane) {
                        ReaderSettingsDestination.READER.paneIndex -> ReaderSetupTab(
                            screenModel = screenModel,
                            onInteractingWithVisuals = { isInteractingWithVisuals = it },
                        )
                        ReaderSettingsDestination.ENGINES.paneIndex -> {
                            TranslationLanguageSection(
                                state = translationState,
                                onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                                onTranslateToLanguageChange = onTranslateToLanguageChange,
                            )
                            TranslationEngineSection(
                                state = translationState,
                                onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                                onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                                onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
                                onTranslationAiEngineChange = onTranslationAiEngineChange,
                                onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                                onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                                onTranslationAiModelChange = onTranslationAiModelChange,
                                onFetchAiModels = onFetchAiModels,
                            )
                        }
                        ReaderSettingsDestination.VISION.paneIndex -> {
                            TranslationOcrInpaintSection(
                                state = translationState,
                                onOcrModelChange = onOcrModelChange,
                                onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                                onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                            )
                            TranslationColorizationSection(
                                state = translationState,
                                onColorizationEnabledChange = onColorizationEnabledChange,
                                onColorizationModelChange = onColorizationModelChange,
                                onColorCombineModeChange = onColorCombineModeChange,
                                onUpscalingEnabledChange = onUpscalingEnabledChange,
                                onColorizeChapterClick = onColorizeChapterClick,
                                onColorizePageClick = onColorizePageClick,
                            )
                        }
                        else -> {
                            TranslationActivationSection(
                                state = translationState,
                                onTranslationEnabledChange = onTranslationEnabledChange,
                                onAutoTranslateChange = onAutoTranslateChange,
                                onFloaterEnabledChange = onFloaterEnabledChange,
                                onFloaterIdleFadeChange = onFloaterIdleFadeChange,
                                onShowTranslatedTextChange = onShowTranslatedTextChange,
                                onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                                onResetFloaterPosition = onResetFloaterPosition,
                            )
                            TranslationResetSection(onResetChapterData = onResetChapterData)
                        }
                    }
                }
            }
        }
    }
}
