package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ReaderLiveActivityAction
import eu.kanade.presentation.reader.components.ReaderLiveActivityCard
import eu.kanade.presentation.reader.components.deriveReaderLiveActivityState
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot

private val animationSpec = tween<IntOffset>(200)
private val fadeSpec = tween<Float>(200)

/**
 * Reader overlay chrome for the UX modernization prototype: a compact floating
 * header up top and a floating navigation dock (with live activity card and
 * batch status line) at the bottom. Both hide together during immersive
 * reading via [visible], exactly like the previous app bars.
 */
@Composable
fun ReaderAppBars(
    visible: Boolean,
    fullscreen: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,

    readingMode: ReadingMode,
    translationState: Translation.State = Translation.State.NOT_TRANSLATED,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    isBatchSession: Boolean = false,
    onClickTranslate: () -> Unit = {},
    onLiveActivityAction: (ReaderLiveActivityAction) -> Unit = {},
) {
    val isWebtoon = viewer is WebtoonViewer || readingMode.type is ReadingMode.ViewerType.Webtoon
    var quickActionsExpanded by remember { mutableStateOf(false) }

    // The live activity card lives and dies with the reader chrome: hiding
    // the controls (page tap) or opening a sheet always collapses it, so it
    // can never linger over the artwork.
    LaunchedEffect(visible) {
        if (!visible) quickActionsExpanded = false
    }

    val modifierWithInsetsPadding = if (fullscreen) {
        Modifier.systemBarsPadding()
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = animationSpec,
                ) + fadeIn(animationSpec = fadeSpec),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = animationSpec,
                ) + fadeOut(animationSpec = fadeSpec),
            ) {
                ReaderTopHeader(
                    modifier = modifierWithInsetsPadding.padding(horizontal = 8.dp, vertical = 4.dp),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    onClickTopAppBar = onClickTopAppBar,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    onOpenInWebView = onOpenInWebView,
                    onOpenInBrowser = onOpenInBrowser,
                    onShare = onShare,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = animationSpec,
                ) + fadeIn(animationSpec = fadeSpec),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = animationSpec,
                ) + fadeOut(animationSpec = fadeSpec),
            ) {
                Column(
                    modifier = modifierWithInsetsPadding.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    val liveActivity = deriveReaderLiveActivityState(
                        translationState,
                        translationBatchProgress,
                        isBatchSession,
                    )
                    ReaderLiveActivityCard(
                        visible = quickActionsExpanded,
                        state = liveActivity,
                        onAction = {
                            quickActionsExpanded = false
                            onLiveActivityAction(it)
                        },
                        onAutoDismiss = { quickActionsExpanded = false },
                    )

                    if (isBatchSession && !quickActionsExpanded) {
                        translationBatchProgress?.let { snapshot ->
                            ReaderTranslationStatusLine(snapshot = snapshot)
                        }
                    }

                    ReaderNavigationDock(
                        isRtl = false,
                        isWebtoon = isWebtoon,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        translationState = translationState,
                        translationBatchProgress = translationBatchProgress,
                        isBatchSession = isBatchSession,
                        onClickTranslate = onClickTranslate,
                        quickActionsExpanded = quickActionsExpanded,
                        onToggleQuickActions = { quickActionsExpanded = !quickActionsExpanded },
                    )
                }
            }
        }

        if (isWebtoon && totalPages > 1) {
            ReaderVerticalScrubber(
                visible = visible,
                currentPage = currentPage,
                totalPages = totalPages,
                onScrollProgress = { fraction ->
                    (viewer as? WebtoonViewer)?.scrollToFraction(fraction)
                },
                modifier = modifierWithInsetsPadding
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            )
        }
    }
}
