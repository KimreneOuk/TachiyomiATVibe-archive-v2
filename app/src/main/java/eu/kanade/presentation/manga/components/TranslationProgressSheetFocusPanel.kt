package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.presentation.TranslationUiTruth
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val ReadNowGreen = Color(0xFF10B981)

@Composable
internal fun FocusPanel(
    snapshot: TranslationProgressSnapshot,
    banner: SheetBannerProjection?,
    isBatchRunning: Boolean,
    isPauseRequested: Boolean = false,
    canResume: Boolean = false,
    hasResumableBatchWork: Boolean? = null,
    isTerminal: Boolean,
    chipText: String,
    ring: ProgressRingProjection,
    animatedFraction: Float,
    telemetry: TelemetryProjection,
    isResuming: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onReadNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPauseChangeSettings: (() -> Unit)?,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val heroAnnouncement = if (ring.isIndeterminate) {
            "${ring.pagesText}, $chipText"
        } else {
            "${ring.percentText ?: ""}, ${ring.pagesText}, $chipText"
        }

        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(160.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = heroAnnouncement
                },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier
                    .size(160.dp)
                    .clearAndSetSemantics {},
                strokeWidth = 14.dp,
                color = DesignTokens.CardBorder.copy(alpha = 0.35f),
                strokeCap = StrokeCap.Round,
            )
            if (ring.isIndeterminate) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(160.dp)
                        .clearAndSetSemantics {},
                    strokeWidth = 14.dp,
                    color = DesignTokens.AccentPrimary,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier
                        .size(160.dp)
                        .clearAndSetSemantics {},
                    strokeWidth = 14.dp,
                    color = DesignTokens.AccentPrimary,
                    strokeCap = StrokeCap.Round,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics {},
            ) {
                Text(
                    text = ring.percentText ?: "—",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = ring.pagesText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DesignTokens.TextMuted,
                )
            }
        }

        PhaseChip(text = chipText, pulsing = isBatchRunning)

        banner?.let { b ->
            val container = when {
                b.isError -> MaterialTheme.colorScheme.errorContainer
                b.isWarning -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            val content = when {
                b.isError -> MaterialTheme.colorScheme.onErrorContainer
                b.isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            val bannerText = b.resource?.let { stringResource(it) } ?: b.message
            FocusBanner(text = bannerText, container = container, content = content)
        }

        MetricsCard(
            elapsedText = telemetry.elapsedText,
            remainingText = telemetry.remainingText,
            speedText = telemetry.speedText,
        )

        val sheetRetryTruth = TranslationUiTruth.forSheetRetryAction(snapshot, canRetry)
        if (sheetRetryTruth != null && canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(44.dp)
                    .semantics { contentDescription = sheetRetryTruth.contentDescription },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignTokens.AccentPrimary,
                    contentColor = DesignTokens.TextOnAccent,
                ),
                shape = DesignTokens.ShapePill,
            ) {
                Text(
                    text = stringResource(ATMR.strings.manga_batch_retry),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (onPauseChangeSettings != null) {
            val settingsText = if (isBatchRunning || isPauseRequested) {
                stringResource(ATMR.strings.batch_translation_pause_and_change_settings)
            } else {
                stringResource(ATMR.strings.batch_translation_change_settings)
            }
            OutlinedButton(
                onClick = onPauseChangeSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .height(40.dp),
                shape = DesignTokens.ShapePill,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = settingsText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot.canReadTranslated) {
                Button(
                    onClick = onReadNow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ReadNowGreen),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(
                        text = stringResource(ATMR.strings.manga_batch_read_now),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val showActionControls = !isTerminal || hasResumableBatchWork == true
            if (showActionControls) {
                if (isBatchRunning) {
                    Button(
                        onClick = onPause,
                        modifier = Modifier.weight(1.3f),
                        shape = DesignTokens.ShapePill,
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(MR.strings.action_pause), fontWeight = FontWeight.Bold)
                    }
                } else if (isPauseRequested) {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1.3f),
                        shape = DesignTokens.ShapePill,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(MR.strings.action_pause), fontWeight = FontWeight.Bold)
                    }
                } else if (canResume) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.weight(1.3f),
                        shape = DesignTokens.ShapePill,
                    ) {
                        if (isResuming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(MR.strings.action_resume), fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(stringResource(MR.strings.action_cancel), fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(stringResource(MR.strings.action_close), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PhaseChip(text: String, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "phase_dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "phase_dot_alpha",
    )
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(DesignTokens.ShapePill)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapePill)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(DesignTokens.AccentPrimary.copy(alpha = if (pulsing) dotAlpha else 1f)),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FocusBanner(text: String, container: Color, content: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MetricsCard(elapsedText: String, remainingText: String, speedText: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = DesignTokens.ShapeCard,
        color = DesignTokens.CardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.CardBorder),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCell(stringResource(ATMR.strings.batch_stat_elapsed), elapsedText, Modifier.weight(1f))
            FocusVerticalRule()
            MetricCell(stringResource(ATMR.strings.batch_stat_remaining), remainingText, Modifier.weight(1f))
            FocusVerticalRule()
            MetricCell(stringResource(ATMR.strings.batch_stat_speed), speedText, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = DesignTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FocusVerticalRule() {
    Box(
        modifier = Modifier
            .height(44.dp)
            .width(1.dp)
            .background(DesignTokens.CardBorder),
    )
}
