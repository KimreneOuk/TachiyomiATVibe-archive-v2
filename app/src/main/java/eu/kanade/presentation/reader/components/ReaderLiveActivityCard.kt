package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.tachiyomi.ui.reader.TranslationPillStage
import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.presentation.BatchStatusLine
import eu.kanade.translation.presentation.BatchStatusLineKind
import eu.kanade.translation.presentation.TranslationUiTruth
import eu.kanade.translation.presentation.UiAction
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** One-glance state of the batch, driving both the dock LED and the activity card. */
enum class ReaderActivityPulse { RUNNING, QUEUED, PAUSED, ATTENTION, DONE, IDLE }

/** Card-level intent; the reader maps it onto translation control APIs. */
sealed interface ReaderLiveActivityAction {
    data object Pause : ReaderLiveActivityAction
    data class Resume(val chapterId: Long) : ReaderLiveActivityAction
    data class Retry(val chapterId: Long) : ReaderLiveActivityAction
    data class CancelQueued(val chapterId: Long) : ReaderLiveActivityAction
}

/** Stage rail chip: per-phase counts straight from the snapshot. */
data class ReaderLiveActivityStageChip(
    val phase: BatchPhase,
    val succeeded: Int,
    val total: Int,
)

data class ReaderLiveActivityFailureGroup(
    val reason: String,
    val pageCount: Int,
)

/**
 * Everything the live activity surfaces render, projected from one
 * [TranslationProgressSnapshot] — no new data plumbing beyond this point.
 */
data class ReaderLiveActivityState(
    val pulse: ReaderActivityPulse,
    val chapterId: Long = 0L,
    val colorizeOnly: Boolean = false,
    val donePages: Int = 0,
    val totalPages: Int = 0,
    val trustedTotal: Boolean = false,
    val titleRes: StringResource? = null,
    val focusStage: TranslationPillStage? = null,
    val focusPageNumber: Int? = null,
    val focusPhase: BatchPhase? = null,
    val pausedForCharging: Boolean = false,
    val queuePosition: Int? = null,
    val queueTotal: Int? = null,
    val failedCount: Int = 0,
    val notSaved: Boolean = false,
    val pagesPerMinute: Float? = null,
    val etaMs: Long? = null,
    val stageChips: List<ReaderLiveActivityStageChip> = emptyList(),
    val pageStages: List<TranslationProgressSnapshot.Page> = emptyList(),
    val failureGroups: List<ReaderLiveActivityFailureGroup> = emptyList(),
    val hiddenFailureGroupCount: Int = 0,
    val attentionCount: Int = 0,
    val statusLine: BatchStatusLine? = null,
    val rebuildProgress: BatchRebuildProgress? = null,
    val canReadTranslated: Boolean = false,
    val readablePages: Int = 0,
    val elapsedMs: Long? = null,
    val action: ReaderLiveActivityAction? = null,
) {
    val pulseColor: Color
        @Composable get() = when (pulse) {
            ReaderActivityPulse.RUNNING -> DesignTokens.AccentPrimary
            ReaderActivityPulse.DONE -> MaterialTheme.colorScheme.tertiary
            ReaderActivityPulse.PAUSED -> DesignTokens.StatusWarning
            ReaderActivityPulse.ATTENTION -> MaterialTheme.colorScheme.error
            ReaderActivityPulse.QUEUED, ReaderActivityPulse.IDLE -> DesignTokens.TextMuted
        }
}

private fun pulseTitleRes(pulse: ReaderActivityPulse): StringResource? = when (pulse) {
    ReaderActivityPulse.QUEUED -> ATMR.strings.reader_translation_pill_queued
    ReaderActivityPulse.PAUSED -> ATMR.strings.reader_activity_paused
    ReaderActivityPulse.ATTENTION -> ATMR.strings.reader_activity_attention
    ReaderActivityPulse.DONE -> ATMR.strings.reader_activity_ready
    else -> null // RUNNING resolves from colorizeOnly, IDLE renders its own body
}

private fun TranslationProgressStage.toPillStage(): TranslationPillStage? = when (this) {
    TranslationProgressStage.QUEUED -> TranslationPillStage.QUEUED
    TranslationProgressStage.OCR -> TranslationPillStage.READING_TEXT
    TranslationProgressStage.TRANSLATE -> TranslationPillStage.TRANSLATING
    TranslationProgressStage.INPAINT, TranslationProgressStage.RENDER -> TranslationPillStage.CLEANING
    TranslationProgressStage.COLORING -> TranslationPillStage.COLORING
    TranslationProgressStage.DONE, TranslationProgressStage.FAILED -> null
}

private fun TranslationProgressStage.toPhase(): BatchPhase? = when (this) {
    TranslationProgressStage.OCR -> BatchPhase.OCR
    TranslationProgressStage.TRANSLATE -> BatchPhase.TRANSLATE
    TranslationProgressStage.INPAINT -> BatchPhase.INPAINT
    TranslationProgressStage.RENDER -> BatchPhase.RENDER
    else -> null
}

/**
 * Single projection of (chapter state + batch snapshot) into the live
 * activity vocabulary. Pure and deterministic so the LED and the card can
 * never disagree.
 */
internal fun deriveReaderLiveActivityState(
    translationState: Translation.State,
    snapshot: TranslationProgressSnapshot?,
    isBatchSession: Boolean,
): ReaderLiveActivityState {
    if (snapshot == null || !isBatchSession) return ReaderLiveActivityState(pulse = ReaderActivityPulse.IDLE)

    val paused = translationState == Translation.State.PAUSED || snapshot.pauseReason != null
    val runningPages = snapshot.pages.filter { it.stage.isRunning }
    val chapterTruth = TranslationUiTruth.forChapterIndicator(translationState, snapshot)
    val statusLine = TranslationUiTruth.readerBarLine(snapshot, isBatchSession = true)
    val requestTerminal = snapshot.requestState?.isTerminal == true
    val rebuilding = statusLine?.kind == BatchStatusLineKind.REBUILDING ||
        statusLine?.kind == BatchStatusLineKind.RESTORING
    val done = chapterTruth.terminalSuccess && TranslationUiTruth.isCompletedOutcome(snapshot)
    val requestPreparing = snapshot.requestState?.phase == TranslationRequestPhase.STARTING ||
        snapshot.requestState?.phase == TranslationRequestPhase.PREPARING ||
        snapshot.requestState?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD
    val hasAttention = TranslationUiTruth.hasPagesNeedingAttention(snapshot)
    val attentionCount = TranslationUiTruth.pagesNeedingAttentionCount(snapshot)
    val retryableUnsaved = snapshot.nonDurableFailure &&
        translationState == Translation.State.READY_WITH_WARNINGS &&
        snapshot.state == Translation.State.READY_WITH_WARNINGS &&
        !requestTerminal &&
        !snapshot.aborted &&
        TranslationUiTruth.forSheetRetryAction(snapshot, restartWired = true)
            ?.actions
            ?.contains(UiAction.RETRY) == true

    val pulse = when {
        requestTerminal -> ReaderActivityPulse.ATTENTION
        snapshot.aborted -> ReaderActivityPulse.ATTENTION
        paused -> ReaderActivityPulse.PAUSED
        done -> ReaderActivityPulse.DONE
        rebuilding -> ReaderActivityPulse.RUNNING
        translationState == Translation.State.QUEUE -> ReaderActivityPulse.QUEUED
        snapshot.nonDurableFailure -> ReaderActivityPulse.ATTENTION
        snapshot.state == Translation.State.ERROR -> ReaderActivityPulse.ATTENTION
        hasAttention && runningPages.isEmpty() -> ReaderActivityPulse.ATTENTION
        translationState == Translation.State.TRANSLATING ||
            runningPages.isNotEmpty() ||
            requestPreparing -> ReaderActivityPulse.RUNNING
        else -> ReaderActivityPulse.IDLE
    }

    // Focus = the first page with live work, else the batch's active page.
    val focusPage = runningPages.minByOrNull { it.index }
        ?: snapshot.pages.firstOrNull { it.index == snapshot.activePage }
    val focusStage = when {
        pulse != ReaderActivityPulse.RUNNING -> null
        rebuilding || requestPreparing -> TranslationPillStage.PREPARING
        focusPage != null -> focusPage.stage.toPillStage()
        else -> null
    }

    val chips = if (snapshot.colorizeOnly || rebuilding) {
        emptyList()
    } else {
        snapshot.perStage
            .filter { it.value.total > 0 }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (phase, count) -> ReaderLiveActivityStageChip(phase, count.succeeded, count.total) }
    }

    val action = when (pulse) {
        ReaderActivityPulse.RUNNING -> ReaderLiveActivityAction.Pause
        ReaderActivityPulse.PAUSED -> ReaderLiveActivityAction.Resume(snapshot.chapterId)
        ReaderActivityPulse.QUEUED -> ReaderLiveActivityAction.CancelQueued(snapshot.chapterId)
        ReaderActivityPulse.ATTENTION -> ReaderLiveActivityAction.Retry(snapshot.chapterId).takeIf { retryableUnsaved }
        else -> null
    }

    val allFailureGroups = snapshot.groupedFailures.entries
        .mapNotNull { (reason, pageKeys) ->
            val cleanReason = reason.trim()
            if (cleanReason.isBlank() || pageKeys.isEmpty()) {
                null
            } else {
                ReaderLiveActivityFailureGroup(cleanReason, pageKeys.size)
            }
        }
        .sortedWith(compareByDescending<ReaderLiveActivityFailureGroup> { it.pageCount }.thenBy { it.reason })
    val visibleFailureGroups = allFailureGroups.take(MAX_VISIBLE_FAILURE_GROUPS)

    return ReaderLiveActivityState(
        pulse = pulse,
        chapterId = snapshot.chapterId,
        colorizeOnly = snapshot.colorizeOnly,
        donePages = snapshot.donePages,
        totalPages = snapshot.totalPages,
        trustedTotal = snapshot.expectedPageCountTrusted && snapshot.totalPages > 0,
        titleRes = when {
            requestTerminal || snapshot.aborted -> pulseTitleRes(pulse)
            snapshot.nonDurableFailure && pulse == ReaderActivityPulse.ATTENTION && snapshot.colorizeOnly ->
                ATMR.strings.reader_activity_coloring_not_saved
            snapshot.nonDurableFailure && pulse == ReaderActivityPulse.ATTENTION ->
                ATMR.strings.reader_activity_not_saved
            done && !snapshot.canReadTranslated -> ATMR.strings.manga_batch_phase_completed
            else -> pulseTitleRes(pulse)
        },
        focusStage = focusStage,
        focusPageNumber = focusPage?.index?.takeIf { pulse == ReaderActivityPulse.RUNNING },
        focusPhase = focusPage?.stage?.toPhase()?.takeIf { pulse == ReaderActivityPulse.RUNNING && !snapshot.colorizeOnly },
        pausedForCharging = snapshot.pausedForCharging,
        queuePosition = snapshot.queuePosition,
        queueTotal = snapshot.queueTotal,
        failedCount = snapshot.failedCount,
        notSaved = snapshot.nonDurableFailure,
        pagesPerMinute = snapshot.pagesPerMinute,
        etaMs = snapshot.estimatedRemainingMs,
        stageChips = chips,
        pageStages = snapshot.pages.sortedBy { it.index },
        failureGroups = visibleFailureGroups,
        hiddenFailureGroupCount = allFailureGroups.size - visibleFailureGroups.size,
        attentionCount = attentionCount,
        statusLine = statusLine,
        rebuildProgress = snapshot.rebuildProgress.takeIf { rebuilding },
        canReadTranslated = snapshot.canReadTranslated,
        readablePages = snapshot.displayReadyPages,
        elapsedMs = snapshot.elapsedMs.takeIf { it > 0L },
        action = action,
    )
}

private const val MAX_VISIBLE_FAILURE_GROUPS = 2

private fun stageRes(stage: TranslationPillStage): StringResource = when (stage) {
    TranslationPillStage.QUEUED -> ATMR.strings.reader_translation_pill_queued
    TranslationPillStage.PREPARING -> ATMR.strings.reader_translation_pill_preparing
    TranslationPillStage.READING_TEXT -> ATMR.strings.reader_translation_pill_reading
    TranslationPillStage.TRANSLATING -> ATMR.strings.reader_translation_pill_translating
    TranslationPillStage.CLEANING -> ATMR.strings.reader_translation_pill_cleaning
    TranslationPillStage.COLORING -> ATMR.strings.reader_translation_pill_coloring
    TranslationPillStage.FINISHING -> ATMR.strings.reader_translation_pill_finishing
}

private fun phaseRes(phase: BatchPhase): StringResource = when (phase) {
    BatchPhase.OCR -> ATMR.strings.reader_activity_stage_read
    BatchPhase.TRANSLATE -> ATMR.strings.reader_activity_stage_translate
    BatchPhase.INPAINT -> ATMR.strings.reader_activity_stage_clean
    BatchPhase.RENDER -> ATMR.strings.reader_activity_stage_render
    BatchPhase.DISPLAY -> ATMR.strings.reader_activity_stage_finish
}

@Composable
private fun etaText(state: ReaderLiveActivityState): String {
    val ppm = state.pagesPerMinute
    val eta = state.etaMs
    if (ppm == null || eta == null) return elapsedText(state).orEmpty()
    val time = if (eta >= 60_000L) {
        stringResource(ATMR.strings.reader_activity_eta_min, (eta / 60_000L).toInt())
    } else {
        stringResource(ATMR.strings.reader_activity_eta_sec, (eta / 1_000L).toInt())
    }
    return stringResource(ATMR.strings.reader_activity_eta, ppm.roundToInt(), time)
}

@Composable
private fun elapsedText(state: ReaderLiveActivityState): String? {
    val elapsedMs = state.elapsedMs?.takeIf { it > 0L } ?: return null
    val elapsed = if (elapsedMs >= 60_000L) {
        stringResource(ATMR.strings.reader_activity_eta_min, (elapsedMs / 60_000L).toInt())
    } else {
        stringResource(ATMR.strings.reader_activity_eta_sec, (elapsedMs / 1_000L).toInt())
    }
    return "${stringResource(ATMR.strings.batch_live_time_elapsed)} · $elapsed"
}

@Composable
private fun statusLineText(state: ReaderLiveActivityState): String? = state.statusLine?.let { line ->
    when (line.kind) {
        BatchStatusLineKind.REBUILDING -> stringResource(ATMR.strings.translation_status_rebuilding)
        BatchStatusLineKind.RESTORING -> stringResource(
            ATMR.strings.translation_bar_restoring,
            *line.formatArgs.toTypedArray(),
        )
        else -> line.fallback
    }
}

/**
 * Live batch activity card anchored above the navigation dock, replacing the
 * old quick-actions menu. Status monitor, not a menu: hero (what & where),
 * stage rail (how far), page strip (which pages), telemetry + at most one
 * contextual action. Auto-dismisses shortly after the batch completes.
 */
@Composable
fun ReaderLiveActivityCard(
    visible: Boolean,
    state: ReaderLiveActivityState,
    onAction: (ReaderLiveActivityAction) -> Unit,
    onAutoDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + expandVertically(tween(150), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(90)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        if (state.pulse == ReaderActivityPulse.DONE) {
            LaunchedEffect(Unit) {
                delay(2400)
                onAutoDismiss()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme
                        .surfaceColorAtElevation(3.dp)
                        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.pulse == ReaderActivityPulse.IDLE) {
                IdleBody()
            } else {
                HeroRow(state)
                if (state.pulse != ReaderActivityPulse.DONE) StageRail(state)
                PageStrip(state)
                if (state.pulse == ReaderActivityPulse.ATTENTION) FailureGroupDetails(state)
                FooterRow(state, onAction)
            }
        }
    }
}

@Composable
private fun IdleBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(ATMR.strings.reader_activity_idle),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = DesignTokens.TextPrimary,
        )
        Text(
            text = stringResource(ATMR.strings.reader_activity_idle_hint),
            style = MaterialTheme.typography.bodySmall,
            color = DesignTokens.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun HeroRow(state: ReaderLiveActivityState) {
    val rebuildProgress = state.rebuildProgress
    val ringFraction = if (rebuildProgress != null && rebuildProgress.totalPages > 0) {
        (rebuildProgress.restoredPages.toFloat() / rebuildProgress.totalPages).coerceIn(0f, 1f)
    } else if (state.trustedTotal) {
        (state.donePages.toFloat() / state.totalPages.coerceAtLeast(1)).coerceIn(0f, 1f)
    } else {
        null
    }
    val showIndeterminateRing = ringFraction == null && state.pulse == ReaderActivityPulse.RUNNING
    val title = when {
        state.pulse == ReaderActivityPulse.RUNNING && state.colorizeOnly ->
            stringResource(ATMR.strings.reader_activity_colorize_chapter)
        state.pulse == ReaderActivityPulse.RUNNING ->
            stringResource(ATMR.strings.reader_activity_translate_chapter)
        state.titleRes != null -> stringResource(state.titleRes)
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            val ringColor = state.pulseColor
            if (!showIndeterminateRing) {
                CircularProgressIndicator(
                    progress = { ringFraction ?: 0f },
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                ringFraction?.let { fraction ->
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = DesignTokens.TextPrimary,
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    color = ringColor,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = DesignTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val focus = focusText(state)
            if (focus != null) {
                Text(
                    text = focus,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.pulse) {
                        ReaderActivityPulse.PAUSED -> DesignTokens.StatusWarning
                        ReaderActivityPulse.ATTENTION -> MaterialTheme.colorScheme.error
                        else -> DesignTokens.TextMuted
                    },
                    fontWeight = if (state.pulse == ReaderActivityPulse.PAUSED) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (state.totalPages > 0 && state.pulse != ReaderActivityPulse.DONE) {
            Text(
                text = "${state.donePages} / ${state.totalPages}",
                style = MaterialTheme.typography.labelMedium,
                color = DesignTokens.TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun focusText(state: ReaderLiveActivityState): String? = when (state.pulse) {
    ReaderActivityPulse.RUNNING -> when (state.statusLine?.kind) {
        BatchStatusLineKind.REBUILDING,
        BatchStatusLineKind.RESTORING,
        BatchStatusLineKind.BUILDING_CONTEXT,
        BatchStatusLineKind.REQUEST_STARTING,
        BatchStatusLineKind.REQUEST_PREPARING,
        BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD,
        -> statusLineText(state)
        else -> state.focusStage?.let { stage ->
            val stageText = stringResource(stageRes(stage))
            state.focusPageNumber?.let {
                stringResource(ATMR.strings.reader_activity_page_focus, it, stageText)
            } ?: stageText
        }
    }
    ReaderActivityPulse.PAUSED -> statusLineText(state)
        ?: if (state.pausedForCharging) {
            stringResource(ATMR.strings.reader_activity_paused_charging)
        } else {
            stringResource(ATMR.strings.reader_activity_paused)
        }
    ReaderActivityPulse.QUEUED -> {
        val status = state.statusLine
        if (status?.kind in setOf(
                BatchStatusLineKind.REQUEST_STARTING,
                BatchStatusLineKind.REQUEST_PREPARING,
                BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD,
            )
        ) {
            statusLineText(state)
        } else {
            val position = state.queuePosition
            if (position != null) {
                stringResource(ATMR.strings.reader_activity_queue_info, position, state.queueTotal ?: position)
            } else {
                stringResource(ATMR.strings.reader_activity_queue_waiting)
            }
        }
    }
    ReaderActivityPulse.ATTENTION -> {
        val typedFailure = when (state.statusLine?.kind) {
            BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED,
            BatchStatusLineKind.REQUEST_CANCELLED,
            BatchStatusLineKind.REQUEST_ADMISSION_FAILED,
            BatchStatusLineKind.ATTENTION_REQUIRED,
            -> true
            else -> false
        }
        if (state.notSaved && !typedFailure) {
            null
        } else {
            statusLineText(state) ?: if (state.attentionCount > 0) {
                stringResource(ATMR.strings.reader_activity_failed_focus, state.attentionCount)
            } else {
                stringResource(ATMR.strings.reader_translation_pill_failed)
            }
        }
    }
    ReaderActivityPulse.DONE -> if (state.canReadTranslated && state.trustedTotal) {
        stringResource(ATMR.strings.reader_activity_pages_ready, state.readablePages, state.totalPages)
    } else {
        null
    }
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageRail(state: ReaderLiveActivityState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.colorizeOnly) {
            StageChip(
                label = stringResource(ATMR.strings.reader_translation_pill_coloring).removeSuffix("…"),
                count = "${state.donePages}/${state.totalPages}",
                active = state.pulse == ReaderActivityPulse.RUNNING,
                check = state.pulse == ReaderActivityPulse.DONE,
            )
        } else {
            state.stageChips.forEach { chip ->
                StageChip(
                    label = stringResource(phaseRes(chip.phase)),
                    count = "${chip.succeeded}/${chip.total}",
                    active = chip.phase == state.focusPhase,
                    check = chip.succeeded >= chip.total,
                )
            }
        }
    }
}

@Composable
private fun StageChip(label: String, count: String, active: Boolean, check: Boolean) {
    Row(
        modifier = Modifier
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (active) DesignTokens.AccentPrimary else DesignTokens.CardBorder,
                ),
                shape = DesignTokens.ShapeBadge,
            )
            .background(
                color = if (active) DesignTokens.AccentGlow else Color.Transparent,
                shape = DesignTokens.ShapeBadge,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (check) {
            Text(text = "✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) DesignTokens.AccentPrimary else DesignTokens.TextMuted,
        )
        Text(
            text = count,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) DesignTokens.AccentPrimary else DesignTokens.TextMuted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageStrip(state: ReaderLiveActivityState) {
    if (state.pageStages.size !in 2..60) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.pageStages.forEach { page ->
            val fill = when {
                page.displayReady && !page.partial -> MaterialTheme.colorScheme.tertiary
                page.stage == TranslationProgressStage.FAILED -> MaterialTheme.colorScheme.error
                page.partial -> DesignTokens.StatusWarning
                page.stage.isRunning -> DesignTokens.AccentPrimary
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(color = fill, shape = RoundedCornerShape(50))
                    .border(
                        border = BorderStroke(1.dp, DesignTokens.CardBorder),
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun FailureGroupDetails(state: ReaderLiveActivityState) {
    if (state.failureGroups.isEmpty() && state.hiddenFailureGroupCount == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        state.failureGroups.forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "×${group.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.hiddenFailureGroupCount > 0) {
            Text(
                text = stringResource(
                    ATMR.strings.reader_activity_more_failure_groups,
                    state.hiddenFailureGroupCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.TextMuted,
            )
        }
    }
}

@Composable
private fun FooterRow(state: ReaderLiveActivityState, onAction: (ReaderLiveActivityAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val telemetry = when (state.pulse) {
            ReaderActivityPulse.RUNNING -> etaText(state)
            ReaderActivityPulse.PAUSED, ReaderActivityPulse.ATTENTION -> elapsedText(state).orEmpty()
            ReaderActivityPulse.QUEUED -> stringResource(ATMR.strings.reader_activity_queue_waiting)
            ReaderActivityPulse.DONE -> if (state.canReadTranslated) {
                stringResource(ATMR.strings.reader_activity_completed, state.readablePages)
            } else {
                ""
            }
            else -> ""
        }
        Text(
            text = telemetry,
            style = MaterialTheme.typography.labelSmall,
            color = DesignTokens.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val action = state.action
        if (action != null) {
            val label = when (action) {
                is ReaderLiveActivityAction.Pause -> stringResource(MR.strings.action_pause)
                is ReaderLiveActivityAction.Resume -> stringResource(MR.strings.action_resume)
                is ReaderLiveActivityAction.Retry -> stringResource(ATMR.strings.batch_queue_retry)
                is ReaderLiveActivityAction.CancelQueued -> stringResource(MR.strings.action_cancel)
            }
            TextButton(
                onClick = { onAction(action) },
                colors = ButtonDefaults.textButtonColors(contentColor = DesignTokens.AccentPrimary),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
