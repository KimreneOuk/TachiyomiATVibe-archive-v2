package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.presentation.TranslationUiTruth
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource

internal fun chapterOverflowTranslationAction(
    state: Translation.State,
    request: TranslationRequestState?,
): ChapterTranslationAction = when {
    state == Translation.State.QUEUE || state == Translation.State.TRANSLATING ||
        state == Translation.State.PAUSED -> ChapterTranslationAction.DETAILS
    state == Translation.State.NOT_TRANSLATED && request != null && !request.isTerminal ->
        ChapterTranslationAction.DETAILS
    else -> ChapterTranslationAction.START
}

internal fun chapterOverflowDownloadEnabled(state: Download.State): Boolean =
    state == Download.State.NOT_DOWNLOADED || state == Download.State.ERROR

/**
 * The chapter row's single action entry point: a three-dot button opening a
 * menu with the translation and download functions that are valid for the
 * chapter's current state.
 */
@Composable
fun ChapterRowOverflowMenu(
    enabled: Boolean,
    translationStateProvider: () -> Translation.State,
    translationRequestProvider: () -> TranslationRequestState?,
    downloadStateProvider: () -> Download.State,
    onTranslationAction: ((ChapterTranslationAction) -> Unit)?,
    onDownloadAction: ((ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(ATMR.strings.manga_translate)) },
                enabled = onTranslationAction != null,
                onClick = {
                    isMenuExpanded = false
                    onTranslationAction?.invoke(
                        chapterOverflowTranslationAction(translationStateProvider(), translationRequestProvider()),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.manga_download)) },
                enabled = onDownloadAction != null && chapterOverflowDownloadEnabled(downloadStateProvider()),
                onClick = {
                    isMenuExpanded = false
                    onDownloadAction?.invoke(ChapterDownloadAction.START)
                },
            )
        }
        Box(
            modifier = Modifier
                .size(IconButtonTokens.StateLayerSize)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(
                    interactionSource = null,
                    indication = ripple(
                        bounded = false,
                        radius = IconButtonTokens.StateLayerSize / 2,
                    ),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { isMenuExpanded = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(MR.strings.action_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KebabIconSize),
            )
        }
    }
}

/**
 * Compact passive status for the chapter row: replaces the two always-visible
 * action buttons with glyphs that only appear while work is in flight, a
 * result needs attention, or a download is active.
 */
@Composable
fun ChapterRowStatus(
    translationStateProvider: () -> Translation.State,
    translationRequestProvider: () -> TranslationRequestState?,
    translationProgressProvider: () -> TranslationProgressSnapshot?,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(StatusSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TranslationStatusGlyph(
            state = translationStateProvider(),
            request = translationRequestProvider(),
            progress = translationProgressProvider(),
        )
        DownloadStatusGlyph(
            state = downloadStateProvider(),
            progress = downloadProgressProvider(),
        )
    }
}

/** Terminal phase shown without motion; failures get the error glyph. */
internal fun translationRowRequestGlyph(request: TranslationRequestState?): TranslationRowRequestGlyph = when {
    request == null -> TranslationRowRequestGlyph.NONE
    request.phase == TranslationRequestPhase.DOWNLOAD_FAILED ||
        request.phase == TranslationRequestPhase.ADMISSION_FAILED -> TranslationRowRequestGlyph.ERROR
    request.phase == TranslationRequestPhase.CANCELLED -> TranslationRowRequestGlyph.NONE
    else -> TranslationRowRequestGlyph.PROGRESS
}

internal enum class TranslationRowRequestGlyph { NONE, PROGRESS, ERROR }

@Composable
private fun TranslationStatusGlyph(
    state: Translation.State,
    request: TranslationRequestState?,
    progress: TranslationProgressSnapshot?,
) {
    when (state) {
        Translation.State.NOT_TRANSLATED -> when (translationRowRequestGlyph(request)) {
            TranslationRowRequestGlyph.NONE -> {}
            TranslationRowRequestGlyph.PROGRESS -> ProgressGlyph(color = MaterialTheme.colorScheme.primary)
            TranslationRowRequestGlyph.ERROR -> ErrorGlyph()
        }
        Translation.State.QUEUE, Translation.State.TRANSLATING -> ProgressGlyph(
            color = MaterialTheme.colorScheme.primary,
            progress = progress?.takeIf { it.totalStages > 0 }?.fraction,
        )
        Translation.State.PAUSED -> Icon(
            imageVector = Icons.Outlined.Pause,
            contentDescription = null,
            modifier = Modifier.size(StatusGlyphSize),
            tint = WarningColor,
        )
        Translation.State.TRANSLATED -> DotGlyph(
            color = MaterialTheme.colorScheme.tertiary,
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
        Translation.State.READY_WITH_WARNINGS -> DotGlyph(
            color = WarningColor,
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
        Translation.State.ERROR -> ErrorGlyph(
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
    }
}

@Composable
private fun DownloadStatusGlyph(
    state: Download.State,
    progress: Int,
) {
    when (state) {
        Download.State.QUEUE, Download.State.DOWNLOADING -> ProgressGlyph(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            progress = progress.takeIf { state == Download.State.DOWNLOADING && it > 0 }?.div(100f),
        )
        Download.State.ERROR -> ErrorGlyph()
        Download.State.NOT_DOWNLOADED, Download.State.DOWNLOADED -> {}
    }
}

@Composable
private fun ProgressGlyph(color: Color, progress: Float? = null) {
    if (progress == null) {
        CircularProgressIndicator(
            modifier = Modifier.size(StatusGlyphSize),
            color = color,
            strokeWidth = StatusStrokeWidth,
        )
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(StatusGlyphSize),
            color = color,
            strokeWidth = StatusStrokeWidth,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun DotGlyph(color: Color, description: String?) {
    Icon(
        imageVector = Icons.Filled.Circle,
        contentDescription = description,
        modifier = Modifier.size(StatusDotSize),
        tint = color,
    )
}

@Composable
private fun ErrorGlyph(description: String? = null) {
    Icon(
        imageVector = Icons.Outlined.ErrorOutline,
        contentDescription = description,
        modifier = Modifier.size(StatusGlyphSize),
        tint = MaterialTheme.colorScheme.error,
    )
}

private val WarningColor = Color(0xFFFFA000)

private val KebabIconSize = 24.dp
private val StatusGlyphSize = 16.dp
private val StatusDotSize = 10.dp
private val StatusStrokeWidth = 2.dp
private val StatusSpacing = 2.dp
