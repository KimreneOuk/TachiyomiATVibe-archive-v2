package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private const val PAGE_GRID_COLUMNS = 17

@Composable
internal fun DetailsPanel(
    snapshot: TranslationProgressSnapshot,
    focusedChapterTitle: String?,
    queue: List<BatchChapterStatus>,
    chipText: String,
    telemetry: TelemetryProjection,
    onRetryChapter: ((Long) -> Unit)?,
) {
    val totalPages = snapshot.totalPages.coerceAtLeast(1)
    val thisSummary = remember(snapshot, focusedChapterTitle) {
        TranslationProgressSheetProjection.computeThisChapterSummary(snapshot, focusedChapterTitle)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(title = stringResource(ATMR.strings.batch_tab_focus))
        DetailCard {
            ThisChapterRow(summary = thisSummary)
        }

        SectionHeading(title = stringResource(ATMR.strings.batch_section_pipeline))
        DetailCard {
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_ocr_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.OCR]?.processed ?: 0,
                    totalPages = totalPages,
                    extra = snapshot.ocrDurationMs?.takeIf { it > 0 }?.let { formatSeconds(it / 1000) },
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.OCR]?.processed ?: 0, totalPages),
            )
            HorizontalRule()
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_ai_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.TRANSLATE]?.processed ?: 0,
                    totalPages = totalPages,
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.TRANSLATE]?.processed ?: 0, totalPages),
            )
            HorizontalRule()
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_inpaint_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.INPAINT]?.processed ?: 0,
                    totalPages = totalPages,
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.INPAINT]?.processed ?: 0, totalPages),
            )
            val coloringStage = snapshot.pages.firstOrNull { it.stage == TranslationProgressStage.COLORING }
            if (snapshot.colorizeOnly || coloringStage != null) {
                val processedColoring = snapshot.pages.count { it.stage == TranslationProgressStage.DONE || it.displayReady }
                HorizontalRule()
                PipelineStage(
                    label = stringResource(ATMR.strings.manga_batch_stage_coloring_name),
                    valueText = stageValueText(
                        processed = processedColoring,
                        totalPages = totalPages,
                    ),
                    fraction = stageFraction(processedColoring, totalPages),
                )
            }
        }

        val pages = snapshot.pages
        if (pages.isNotEmpty()) {
            SectionHeading(title = stringResource(ATMR.strings.batch_section_pages))
            DetailCard {
                val doneCount = pages.count { it.stage == TranslationProgressStage.DONE || it.displayReady }
                val failedCount = pages.count { it.stage == TranslationProgressStage.FAILED }
                val queuedCount = (pages.size - doneCount - failedCount).coerceAtLeast(0)
                PageGrid(pages = pages)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LegendEntry(
                        color = DesignTokens.AccentPrimary,
                        label = stringResource(ATMR.strings.manga_batch_stage_done) + " $doneCount",
                    )
                    if (failedCount > 0) {
                        LegendEntry(
                            color = MaterialTheme.colorScheme.error,
                            label = stringResource(ATMR.strings.manga_batch_stage_failed) + " $failedCount",
                        )
                    }
                    LegendEntry(
                        color = DesignTokens.SheetBackground,
                        label = stringResource(ATMR.strings.manga_batch_stage_queued) + " $queuedCount",
                    )
                }
            }
        }

        val otherChapters = queue.filter { it.chapterId != snapshot.chapterId }
        if (otherChapters.isNotEmpty()) {
            SectionHeading(title = stringResource(ATMR.strings.batch_section_queue))
            DetailCard {
                otherChapters.forEachIndexed { index, chapter ->
                    QueueRow(chapter = chapter, onRetryChapter = onRetryChapter)
                    if (index < otherChapters.lastIndex) {
                        HorizontalRule()
                    }
                }
            }
        }

        SectionHeading(title = stringResource(ATMR.strings.batch_live_status))
        DetailCard {
            LiveStatusRow(
                icon = Icons.Outlined.Description,
                label = stringResource(ATMR.strings.batch_live_current_step),
                value = chipText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.Schedule,
                label = stringResource(ATMR.strings.batch_live_time_elapsed),
                value = telemetry.elapsedText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.HourglassEmpty,
                label = stringResource(ATMR.strings.batch_live_estimated_remaining),
                value = telemetry.remainingText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.Speed,
                label = stringResource(ATMR.strings.batch_live_processing_speed),
                value = telemetry.speedText,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun ThisChapterRow(summary: SingleChapterDetailsSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            when {
                summary.isTranslating -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = DesignTokens.AccentPrimary,
                )
                summary.state == Translation.State.TRANSLATED || summary.state == Translation.State.READY_WITH_WARNINGS ->
                    Icon(Icons.Default.Check, contentDescription = null, tint = DesignTokens.AccentPrimary, modifier = Modifier.size(18.dp))
                summary.state == Translation.State.ERROR ->
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                summary.state == Translation.State.PAUSED ->
                    Icon(Icons.Outlined.PauseCircle, contentDescription = null, tint = DesignTokens.TextMuted, modifier = Modifier.size(18.dp))
                else ->
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = DesignTokens.TextMuted, modifier = Modifier.size(18.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.statusLabel,
                fontSize = 12.sp,
                color = if (summary.state == Translation.State.ERROR) MaterialTheme.colorScheme.error else DesignTokens.TextMuted,
            )
            if (summary.progressFraction != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(DesignTokens.ShapePill)
                        .background(DesignTokens.SheetBackground),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(summary.progressFraction)
                            .fillMaxHeight()
                            .clip(DesignTokens.ShapePill)
                            .background(DesignTokens.AccentPrimary),
                    )
                }
            }
        }

        summary.percentText?.let { pct ->
            QueueBadge(text = pct, color = DesignTokens.AccentPrimary)
        }
    }
}

@Composable
private fun PipelineStage(label: String, valueText: String, fraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                fontSize = 11.sp,
                color = DesignTokens.TextMuted,
            )
        }
        Spacer(Modifier.height(9.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(DesignTokens.ShapePill)
                .background(DesignTokens.SheetBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(DesignTokens.ShapePill)
                    .background(
                        if (fraction >= 1f) MaterialTheme.colorScheme.tertiary else DesignTokens.AccentPrimary,
                    ),
            )
        }
    }
}

@Composable
private fun PageGrid(pages: List<TranslationProgressSnapshot.Page>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        pages.chunked(PAGE_GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { page ->
                    val color = when {
                        page.stage == TranslationProgressStage.DONE || page.displayReady -> DesignTokens.AccentPrimary
                        page.stage == TranslationProgressStage.FAILED -> MaterialTheme.colorScheme.error
                        page.stage.isRunning -> DesignTokens.AccentPrimary.copy(alpha = 0.45f)
                        else -> DesignTokens.SheetBackground
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 11.sp, color = DesignTokens.TextMuted)
    }
}

@Composable
private fun LiveStatusRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DesignTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DesignTokens.TextMuted,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard),
    ) {
        content()
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(DesignTokens.CardBorder.copy(alpha = 0.45f)),
    )
}

private fun stageFraction(processed: Int, totalPages: Int): Float =
    if (totalPages <= 0) 0f else (processed.toFloat() / totalPages).coerceIn(0f, 1f)

private fun stageValueText(processed: Int, totalPages: Int, extra: String? = null): String =
    listOfNotNull("$processed / $totalPages", extra).joinToString(" · ")

private fun formatSeconds(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
