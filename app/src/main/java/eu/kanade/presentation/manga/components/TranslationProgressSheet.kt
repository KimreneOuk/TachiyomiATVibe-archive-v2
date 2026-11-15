package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.translation.coordinator.BatchQueuePauseState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.presentation.TranslationUiTruth
import kotlinx.coroutines.delay
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val RESUME_PENDING_TIMEOUT_MS = 10_000L

data class BatchChapterStatus(
    val chapterId: Long,
    val title: String,
    val state: Translation.State,
    val totalPages: Int? = null,
    val donePages: Int? = null,
    val failureReason: String? = null,
)

/**
 * Single-chapter Focus/Details progress sheet following approved flow proposal
 * and canvas boards 04, 05, and 06.
 */
@Composable
fun TranslationProgressSheet(
    snapshot: TranslationProgressSnapshot,
    queue: List<BatchChapterStatus>,
    onDismissRequest: () -> Unit,
    onReadNow: () -> Unit,
    onCancel: () -> Unit,
    onResume: (() -> Unit)? = null,
    onPauseResume: ((paused: Boolean) -> Unit)? = null,
    onRetryChapter: ((Long) -> Unit)? = null,
    onPauseChangeSettings: (() -> Unit)? = null,
    queuePaused: Boolean? = null,
    queuePauseState: BatchQueuePauseState? = null,
    hasResumableBatchWork: Boolean? = null,
) {
    var localPaused by remember(snapshot.chapterId) { mutableStateOf(false) }
    var isResuming by remember(snapshot.chapterId) { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
    val context = LocalContext.current
    val wifiRequired by downloadPreferences.downloadOnlyOverWifi().changes()
        .collectAsState(initial = downloadPreferences.downloadOnlyOverWifi().get())
    val networkState by remember(context) { context.networkStateFlow() }
        .collectAsState(initial = context.activeNetworkState())

    val reqPhase = snapshot.requestState?.phase
    val isDownloaderStopped = reqPhase == TranslationRequestPhase.DOWNLOAD_FAILED &&
        snapshot.requestState?.failureKind == TranslationRequestFailureKind.DOWNLOADER_STOPPED
    val isDownloadPending = reqPhase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD ||
        reqPhase == TranslationRequestPhase.STARTING ||
        isDownloaderStopped
    val isWifiWaiting = !snapshot.aborted &&
        (
            (wifiRequired && !networkState.isWifi && isDownloadPending) ||
                snapshot.requestState?.reason?.contains("Wi-Fi", ignoreCase = true) == true
            )

    val phaseActive = snapshot.batchPhase != TranslationBatchPhase.IDLE ||
        snapshot.state == Translation.State.TRANSLATING
    LaunchedEffect(snapshot.chapterId, snapshot.batchPhase, snapshot.state, queuePauseState) {
        if (isResuming && (phaseActive || queuePauseState == BatchQueuePauseState.NOT_PAUSED)) {
            isResuming = false
        }
    }
    LaunchedEffect(snapshot.chapterId, isResuming) {
        if (isResuming) {
            delay(RESUME_PENDING_TIMEOUT_MS)
            isResuming = false
        }
    }

    val isSnapshotPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
    val isPauseRequested = queuePauseState == BatchQueuePauseState.REQUESTED
    val isConfirmedPaused = if (queuePauseState != null) {
        queuePauseState == BatchQueuePauseState.PAUSED
    } else {
        queuePaused ?: (isSnapshotPaused || localPaused)
    }

    val queueRunning = queue.any { it.state == Translation.State.TRANSLATING } ||
        (queuePauseState == BatchQueuePauseState.NOT_PAUSED && hasResumableBatchWork == true)
    val isBatchRunning = (phaseActive || queueRunning) && !isConfirmedPaused && !isPauseRequested
    val isTerminal = TranslationProgressSheetProjection.isTerminalState(snapshot)
    val canResume = isConfirmedPaused && (hasResumableBatchWork ?: true)

    val ring = TranslationProgressSheetProjection.computeRingProjection(snapshot)
    val animatedFraction by animateFloatAsState(
        targetValue = ring.fraction,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "hero_progress",
    )

    val telemetry = TranslationProgressSheetProjection.computeTelemetry(snapshot)
    val chipText = TranslationProgressSheetProjection.computePhaseChipText(
        snapshot = snapshot,
        isResuming = isResuming,
        isPauseRequested = isPauseRequested,
    )
    val banner = TranslationProgressSheetProjection.computeBanner(snapshot, isWifiWaiting)
    val focusedChapterTitle = queue.firstOrNull { it.chapterId == snapshot.chapterId }?.title

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
                    title = stringResource(ATMR.strings.batch_translation_title),
                    subtitle = batchStatusHeaderSubtitle(snapshot, isResuming),
                    onClose = onDismissRequest,
                )

                SegmentedPillBar(
                    tabs = listOf(
                        stringResource(ATMR.strings.batch_tab_focus),
                        stringResource(ATMR.strings.batch_tab_details),
                    ),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                )

                if (selectedTab == 0) {
                    FocusPanel(
                        snapshot = snapshot,
                        banner = banner,
                        isBatchRunning = isBatchRunning,
                        isPauseRequested = isPauseRequested,
                        canResume = canResume,
                        hasResumableBatchWork = hasResumableBatchWork,
                        isTerminal = isTerminal,
                        chipText = chipText,
                        ring = ring,
                        animatedFraction = animatedFraction,
                        telemetry = telemetry,
                        isResuming = isResuming,
                        canRetry = onRetryChapter != null,
                        onRetry = { onRetryChapter?.invoke(snapshot.chapterId) },
                        onReadNow = onReadNow,
                        onPause = {
                            if (queuePauseState == null) {
                                localPaused = true
                            }
                            onPauseResume?.invoke(true)
                        },
                        onResume = {
                            localPaused = false
                            isResuming = true
                            onResume?.invoke() ?: onPauseResume?.invoke(false)
                        },
                        onPauseChangeSettings = onPauseChangeSettings,
                        onCancel = onCancel,
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    DetailsPanel(
                        snapshot = snapshot,
                        focusedChapterTitle = focusedChapterTitle,
                        queue = queue,
                        chipText = chipText,
                        telemetry = telemetry,
                        onRetryChapter = onRetryChapter,
                    )
                }
            }
        }
    }
}

internal fun batchStatusHeaderSubtitle(
    snapshot: TranslationProgressSnapshot,
    isResuming: Boolean = false,
): String = TranslationUiTruth.batchStatusLine(snapshot, isResuming).fallback
