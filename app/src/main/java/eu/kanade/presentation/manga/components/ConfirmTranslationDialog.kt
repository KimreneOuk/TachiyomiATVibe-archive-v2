package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.ui.manga.ChapterTranslationReadiness
import eu.kanade.translation.presentation.TranslationUiTruth
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ConfirmTranslationDialog(
    chapterNames: List<String>,
    readiness: ChapterTranslationReadiness?,
    showAgain: Boolean,
    onShowAgainChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onFinishDownloadFirst: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
) {
    val midDownload = readiness as? ChapterTranslationReadiness.MidDownload

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.batch_translate_chapters),
                    subtitle = if (chapterNames.size == 1) {
                        chapterNames.first()
                    } else {
                        stringResource(ATMR.strings.batch_translation_chapters_selected, chapterNames.size)
                    },
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(16.dp))

                midDownload?.let { state ->
                    Text(
                        text = TranslationUiTruth.partialDownloadBody(
                            arrayOf(
                                TranslationUiTruth.PartialDecision(
                                    downloaded = state.downloadedPageCount,
                                    expectedSourceTotal = state.expectedSourcePageCount,
                                ),
                            ),
                        ),
                        fontSize = 12.sp,
                        color = DesignTokens.TextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onDismissRequest()
                            onFinishDownloadFirst?.invoke()
                        },
                        shape = DesignTokens.ShapeSquircle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(44.dp),
                    ) {
                        Text(
                            text = stringResource(ATMR.strings.manga_translate_finish_download_first),
                            color = DesignTokens.TextMuted,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = DesignTokens.AccentPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(ATMR.strings.batch_translation_settings_title),
                            color = DesignTokens.AccentPrimary,
                            fontSize = 12.sp,
                        )
                    }
                    LabeledCheckbox(
                        label = stringResource(ATMR.strings.translation_confirm_dont_show),
                        checked = !showAgain,
                        onCheckedChange = { checked -> onShowAgainChange(!checked) },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        shape = DesignTokens.ShapeSquircle,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_cancel),
                            color = DesignTokens.TextMuted,
                        )
                    }
                    Button(
                        onClick = {
                            onDismissRequest()
                            onConfirm()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DesignTokens.AccentPrimary,
                            contentColor = DesignTokens.TextOnAccent,
                        ),
                        shape = DesignTokens.ShapeSquircle,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            text = when {
                                midDownload != null -> stringResource(ATMR.strings.manga_translate_what_exists)
                                readiness is ChapterTranslationReadiness.NeedsDownload ->
                                    stringResource(ATMR.strings.manga_translate_download_first)
                                else -> stringResource(ATMR.strings.batch_start_cta, chapterNames.size)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
