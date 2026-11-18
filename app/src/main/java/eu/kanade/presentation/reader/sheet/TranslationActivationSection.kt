package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.designsystem.ActionCard
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SwitchCard
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Pipeline activation toggles of the translation drawer's Basic mode. */
@Composable
internal fun TranslationActivationSection(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_activation))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.Translate,
            title = stringResource(ATMR.strings.pref_translation_enabled),
            subtitle = stringResource(ATMR.strings.pref_translation_enabled_summary),
            checked = state.enabled,
            onCheckedChange = onTranslationEnabledChange,
        )
        SwitchCard(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(ATMR.strings.pref_auto_translate),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
            checked = state.autoTranslate,
            onCheckedChange = { auto ->
                if (auto && !state.enabled) onTranslationEnabledChange(true)
                onAutoTranslateChange(auto)
            },
        )
    }
}

/**
 * Overlay affordances of the Basic mode: floater visibility and placement,
 * translated-text visibility, translate button placement.
 */
@Composable
internal fun TranslationOverlaySection(
    state: TranslationSettingsState,
    onFloaterEnabledChange: (Boolean) -> Unit,
    onFloaterIdleFadeChange: (Boolean) -> Unit,
    onShowTranslatedTextChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onResetFloaterPosition: () -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_overlay))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchCard(
            icon = Icons.Outlined.Layers,
            title = stringResource(ATMR.strings.pref_floater_show),
            subtitle = stringResource(ATMR.strings.reader_translation_floater_summary),
            checked = state.floaterEnabled,
            onCheckedChange = onFloaterEnabledChange,
        )
        if (state.floaterEnabled) {
            SwitchCard(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(ATMR.strings.pref_floater_idle_fade),
                subtitle = stringResource(ATMR.strings.pref_floater_idle_fade_summary),
                checked = state.floaterIdleFade,
                onCheckedChange = onFloaterIdleFadeChange,
            )
            ActionCard(
                icon = Icons.Outlined.Refresh,
                title = stringResource(ATMR.strings.pref_floater_reset_position),
                subtitle = stringResource(ATMR.strings.pref_floater_reset_position_summary),
                onClick = onResetFloaterPosition,
                actionLabel = stringResource(ATMR.strings.action_reset),
            )
        }
        SwitchCard(
            icon = Icons.Outlined.Visibility,
            title = stringResource(ATMR.strings.pref_show_translations),
            subtitle = stringResource(ATMR.strings.pref_show_translations_summary),
            checked = state.showTranslatedText,
            onCheckedChange = onShowTranslatedTextChange,
        )
        SwitchCard(
            icon = Icons.Outlined.Translate,
            title = stringResource(ATMR.strings.pref_reader_translate_button_mode),
            subtitle = stringResource(
                if (state.translateButtonWithControls) {
                    ATMR.strings.pref_reader_translate_button_mode_summary_with_controls
                } else {
                    ATMR.strings.pref_reader_translate_button_mode_summary_default
                },
            ),
            checked = state.translateButtonWithControls,
            onCheckedChange = onTranslateButtonWithControlsChange,
        )
    }
}
