package eu.kanade.presentation.reader.sheet

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.ui.reader.ReaderSettingsDestination
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
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
    onRecolorChapterClick: () -> Unit = {},
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    onResetChapterData: () -> Unit,
    onStoryColorReferenceToggle: (Boolean) -> Unit = {},
    onStoryColorReferenceFromUri: (Uri) -> Unit = {},
    onStoryColorReferenceFromChapterPage: (ReaderPage) -> Unit = {},
    onStoryColorReferenceFromCover: () -> Unit = {},
    onRemoveStoryColorReference: () -> Unit = {},
    chapterPages: List<ReaderPage> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onStoryColorReferenceFromUri(uri)
        }
    }
    var showChapterPageDialog by remember { mutableStateOf(false) }
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

    // The Translation tab pushes a drawer instead of swapping content in
    // place; the lit tab stays as both title and the back control.
    var translationOpen by rememberSaveable(destination) {
        mutableStateOf(destination == ReaderSettingsDestination.TRANSLATION)
    }
    var advancedMode by rememberSaveable { mutableStateOf(false) }
    val readerScroll = rememberScrollState()
    val basicScroll = rememberScrollState()
    val advancedScroll = rememberScrollState()

    val closeSheet = {
        onDismissRequest()
        onShowMenus()
    }
    // Back and scrim taps collapse the drawer first; only a closed drawer
    // dismisses the sheet itself.
    val dismiss = {
        if (translationOpen) translationOpen = false else closeSheet()
    }
    BackHandler(enabled = translationOpen) { translationOpen = false }

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
                    onClose = closeSheet,
                )

                Spacer(modifier = Modifier.height(4.dp))

                SegmentedPillBar(
                    tabs = listOf(
                        stringResource(ATMR.strings.reader_pane_reader),
                        stringResource(ATMR.strings.reader_pane_translation),
                    ),
                    selectedIndex = if (translationOpen) 1 else 0,
                    onTabSelected = { index ->
                        translationOpen = index == 1 && !translationOpen
                    },
                )

                AnimatedContent(
                    targetState = translationOpen,
                    transitionSpec = { stageTransition(targetState) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = "reader-sheet-stage",
                ) { open ->
                    if (open) {
                        TranslationModeFlip(
                            showAdvanced = advancedMode,
                            onAdvancedChange = { advancedMode = it },
                            basicScroll = basicScroll,
                            advancedScroll = advancedScroll,
                            basic = {
                                TranslationActivationSection(
                                    state = translationState,
                                    onTranslationEnabledChange = onTranslationEnabledChange,
                                    onAutoTranslateChange = onAutoTranslateChange,
                                )
                                TranslationColorizeActionsSection(
                                    state = translationState,
                                    onColorizePageClick = onColorizePageClick,
                                    onColorizeChapterClick = onColorizeChapterClick,
                                    onRecolorChapterClick = onRecolorChapterClick,
                                )
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
                                TranslationOverlaySection(
                                    state = translationState,
                                    onFloaterEnabledChange = onFloaterEnabledChange,
                                    onFloaterIdleFadeChange = onFloaterIdleFadeChange,
                                    onShowTranslatedTextChange = onShowTranslatedTextChange,
                                    onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                                    onResetFloaterPosition = onResetFloaterPosition,
                                )
                            },
                            advanced = {
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
                                    onStoryColorReferenceToggle = onStoryColorReferenceToggle,
                                    onChooseFromDevice = { getContent.launch("image/*") },
                                    onChooseChapterPage = { showChapterPageDialog = true },
                                    onUseStoryCover = onStoryColorReferenceFromCover,
                                    onRemoveStoryColorReference = onRemoveStoryColorReference,
                                )
                                TranslationResetSection(onResetChapterData = onResetChapterData)
                            },
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(readerScroll)
                                .padding(bottom = 24.dp),
                        ) {
                            ReaderSetupTab(
                                screenModel = screenModel,
                                onInteractingWithVisuals = { isInteractingWithVisuals = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showChapterPageDialog) {
        AlertDialog(
            onDismissRequest = { showChapterPageDialog = false },
            title = { Text(stringResource(ATMR.strings.story_reference_source_page)) },
            text = {
                if (chapterPages.isEmpty()) {
                    Text(stringResource(ATMR.strings.story_reference_page_picker_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(chapterPages) { page ->
                            SelectionTile(
                                title = stringResource(ATMR.strings.story_reference_page_item, page.index + 1),
                                subtitle = null,
                                selected = false,
                                onClick = {
                                    showChapterPageDialog = false
                                    onStoryColorReferenceFromChapterPage(page)
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChapterPageDialog = false }) {
                    Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel))
                }
            },
        )
    }
}

private fun stageTransition(open: Boolean) =
    if (open) {
        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(200))) togetherWith
            (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(200)))
    } else {
        (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(200))) togetherWith
            (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(200)))
    }

/**
 * Basic ↔ Advanced flip of the unified translation drawer: a segmented pill
 * plus a directional slide, each mode keeping its own scroll position.
 */
@Composable
internal fun TranslationModeFlip(
    showAdvanced: Boolean,
    onAdvancedChange: (Boolean) -> Unit,
    basicScroll: ScrollState,
    advancedScroll: ScrollState,
    basic: @Composable () -> Unit,
    advanced: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SegmentedPillBar(
            tabs = listOf(
                stringResource(ATMR.strings.reader_translation_mode_basic),
                stringResource(ATMR.strings.reader_translation_mode_advanced),
            ),
            selectedIndex = if (showAdvanced) 1 else 0,
            onTabSelected = { index -> onAdvancedChange(index == 1) },
        )
        AnimatedContent(
            targetState = showAdvanced,
            transitionSpec = { stageTransition(targetState) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "translation-mode-flip",
        ) { isAdvanced ->
            if (isAdvanced) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(advancedScroll)
                        .padding(bottom = 24.dp),
                ) { advanced() }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(basicScroll)
                        .padding(bottom = 24.dp),
                ) { basic() }
            }
        }
    }
}
