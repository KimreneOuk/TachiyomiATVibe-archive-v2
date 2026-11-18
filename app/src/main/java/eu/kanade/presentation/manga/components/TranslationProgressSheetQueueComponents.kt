package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import eu.kanade.translation.model.Translation
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private data class QueueIcon(val vector: ImageVector, val tint: Color)

@Composable
internal fun QueueRow(chapter: BatchChapterStatus, onRetryChapter: ((Long) -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = when (chapter.state) {
            Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                QueueIcon(Icons.Default.Check, DesignTokens.AccentPrimary)
            Translation.State.TRANSLATING -> null
            Translation.State.ERROR -> QueueIcon(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
            Translation.State.PAUSED -> QueueIcon(Icons.Outlined.PauseCircle, DesignTokens.TextMuted)
            else -> QueueIcon(Icons.Outlined.Schedule, DesignTokens.TextMuted)
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (chapter.state == Translation.State.TRANSLATING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = DesignTokens.AccentPrimary,
                )
            } else {
                icon?.let {
                    Icon(
                        imageVector = it.vector,
                        contentDescription = null,
                        tint = it.tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subText = when (chapter.state) {
                Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                    listOfNotNull(
                        stringResource(ATMR.strings.batch_queue_done),
                        chapter.totalPages?.takeIf { it > 0 }?.let { "$it" },
                    ).joinToString(" · ")
                Translation.State.ERROR -> listOfNotNull(
                    stringResource(ATMR.strings.manga_batch_tier_failed),
                    chapter.failureReason,
                ).joinToString(" · ")
                Translation.State.QUEUE -> stringResource(ATMR.strings.batch_queue_queued)
                Translation.State.PAUSED -> stringResource(ATMR.strings.manga_batch_status_paused)
                else -> null
            }
            subText?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = if (chapter.state == Translation.State.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        DesignTokens.TextMuted
                    },
                )
            }
            if (chapter.state == Translation.State.TRANSLATING) {
                val total = chapter.totalPages?.takeIf { it > 0 }
                val done = chapter.donePages ?: 0
                if (total != null) {
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
                                .fillMaxWidth((done.toFloat() / total).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(DesignTokens.ShapePill)
                                .background(DesignTokens.AccentPrimary),
                        )
                    }
                }
            }
        }

        when (chapter.state) {
            Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                QueueBadge(
                    text = stringResource(ATMR.strings.manga_batch_tier_readable),
                    color = DesignTokens.AccentPrimary,
                )
            Translation.State.TRANSLATING -> {
                val total = chapter.totalPages?.takeIf { it > 0 }
                val done = chapter.donePages ?: 0
                if (total != null) {
                    QueueBadge(text = "${(done.toFloat() / total * 100).toInt()}%", color = DesignTokens.AccentPrimary)
                }
            }
            Translation.State.ERROR, Translation.State.PAUSED -> onRetryChapter?.let { retry ->
                OutlinedButton(
                    onClick = { retry(chapter.chapterId) },
                    shape = DesignTokens.ShapePill,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        text = stringResource(ATMR.strings.batch_queue_retry),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (chapter.state == Translation.State.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            DesignTokens.AccentPrimary
                        },
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
internal fun QueueBadge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(DesignTokens.ShapePill)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}
