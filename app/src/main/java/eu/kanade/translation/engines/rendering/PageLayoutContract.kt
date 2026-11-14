package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.MaskGeometry
import eu.kanade.translation.model.TranslationBlockView
import kotlin.math.max
import kotlin.math.min

/**
 * Pure text-measurement abstraction.
 *
 * Layout math needs font metrics that only a real [android.graphics.Paint] can
 * provide, which is Android-bound and not unit-testable (the project avoids
 * Robolectric). Injecting measurement behind this interface keeps
 * [TextLayoutPlanner] JVM-testable: tests pass a deterministic fake, the renderer
 * passes [PaintTextMeasurer]. Same pure-decision / Android-I/O split as
 * [RenderColorEstimator].
 */
interface TextMeasurer {
    /** Visual width of [text] rendered at [fontSizePx], in the planner's pixel units. */
    fun measureTextWidth(text: String, fontSizePx: Float): Float

    /**
     * Full line height (`fontMetrics.descent - fontMetrics.ascent`) at [fontSizePx],
     * matching the renderer's legacy stacking height.
     */
    fun lineHeight(fontSizePx: Float): Float
}

/**
 * Pure axis-aligned float rectangle.
 *
 * Deliberately NOT `android.graphics.RectF` — keeps the planner JVM-testable with
 * no `android.graphics` dependency. The renderer converts to `RectF` for clipping.
 */
data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top

    /** Positive-area intersection test. Touching edges (zero area) is NOT an overlap. */
    fun overlaps(other: FloatRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun intersection(other: FloatRect): FloatRect = FloatRect(
        max(left, other.left),
        max(top, other.top),
        min(right, other.right),
        min(bottom, other.bottom),
    )
}

/**
 * Horizontal text anchoring for a [BlockLayout].
 *  - [CENTER]: centred on [BlockLayout.originX] — default for non-grown boxes.
 *  - [LEFT]: left-aligned at [BlockLayout.originX] — used when a box grew RIGHT
 *    into free space (original left edge stays the anchor) and by the clip net.
 *  - [RIGHT]: right-aligned at [BlockLayout.originX] — used when a box grew LEFT,
 *    so parentless edge SFX do not slide toward the page centre.
 */
enum class TextAlign { CENTER, LEFT, RIGHT }

/**
 * One pre-positioned, prewrapped horizontal line.
 * `leftPx`/`topPx` are integer `floor()` placement results;
 * `layoutWidthPx = max(1, ceil(advance + 2*SHAPING_GUARD))` is the EXACT width
 * the renderer must shape the line at (one `StaticLayout` line, left-aligned
 * inside that width); [conservativeOccupancy] is the un-clipped planning
 * envelope (advance/line-height rect inflated by stroke, AA guard, and half
 * collision gap).
 */
data class PositionedLine(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
    val layoutWidthPx: Int,
    val layoutHeightPx: Int,
    val conservativeOccupancy: FloatRect,
)

/**
 * Structural render boundary of a layout —
 * component path (via the `(planGeometryId, componentId)` pair) then the
 * cell/safety rectangle. Only these clips justify pixel-containment claims;
 * planning occupancies stay conservative and un-clipped.
 */
data class HardClip(
    val planGeometryId: Int?,
    val componentId: Int?,
    val cellRect: FloatRect?,
)

/**
 * Resolved placement for a single translated block, produced by
 * [TextLayoutPlanner.plan]. The renderer only DRAWS these — it does no further layout.
 *
 * @property originX horizontal draw anchor whose meaning depends on [drawAlign]:
 *   CENTER -> box centre; LEFT -> (clip or growth) left edge; RIGHT -> growth right edge.
 * @property originY vertical center the renderer stacks lines around.
 * @property drawAlign how the text is anchored horizontally at [originX].
 * @property clipRect non-null ONLY when, after growing + shrinking, the block still
 *   cannot avoid a neighbour. The renderer clips to this rect so extents can never
 *   overlap — the safety net making non-overlap structural, not best-effort.
 */
data class BlockLayout(
    val block: TranslationBlockView,
    val text: String,
    val isVertical: Boolean,
    val originX: Float,
    val originY: Float,
    val safeW: Float,
    val safeH: Float,
    val fontSizePx: Float,
    val strokeWidth: Float,
    val drawAlign: TextAlign,
    val clipRect: FloatRect?,
    val lines: List<String>,
    val maskGeometry: MaskGeometry? = null,
    /**
     * Compact per-page group id of this block's segmentation mask
     * (see [SharedMaskSession]). Null when the block has no mask or its mask is
     * groupless. Valid ONLY within the page plan it was produced for.
     */
    val planGeometryId: Int? = null,
    val maskComponentId: Int? = null,
    /**
     * This block's mask region rect (the same region used for
     * placement). The renderer intersects it with the component path so shared
     * components clip structurally instead of only by planning.
     */
    val cellRect: FloatRect? = null,
    /**
     * Pre-positioned adaptive lines. Null selects the
     * EXACT legacy renderer (stacked lines / vertical columns); non-null
     * selects the positioned-line shaping path (one StaticLayout line per
     * entry at its integer placement).
     */
    val positionedLines: List<PositionedLine>? = null,
    /**
     * Per-line conservative planning envelopes (un-clipped,
     * stroke/AA/half-gap inflated). Empty unless [positionedLines] != null.
     */
    val conservativeOccupancy: List<FloatRect> = emptyList(),
    /**
     * Structural render boundary mirrored from
     * [maskGeometry]/[planGeometryId]/[maskComponentId]/[cellRect]; the
     * renderer composes component path then cell rectangle from it.
     */
    val hardClip: HardClip = HardClip(null, null, null),
    /**
     *  contained-fit: false ONLY for the mask-unusable fallback — no
     * fully contained fit exists at the render floor, so the planner fails
     * open to the unshifted OCR-region draw and metadata wiring must not
     * attach the exact component clip (visibility wins over the mask).
     */
    val maskUsable: Boolean = true,
)

/**
 * Identity of one planner input — the original list index
 * plus the block id. `blockId` alone is insufficient (nullable and possibly
 * duplicated), so the identity is the pair.
 */
data class InputIdentity(val inputIndex: Int, val blockId: String?)

/**
 * Reason a nonblank input produced no drawable layout.
 */
enum class NonDrawReason {
    INVALID_OR_SUBPIXEL_SOURCE_RECT,
    EMPTY_SHARED_CELL,
    MASK_OR_WORK_BUDGET_EXHAUSTED,
    POSITIONED_LINE_BUDGET_EXHAUSTED,
    STATIC_LAYOUT_BUDGET_EXHAUSTED,
    NO_DISJOINT_POST_ANCHOR_PLACEMENT,
    INVALID_RENDER_METADATA,
}

/** Per-input planner outcome — exactly one per nonblank input. */
sealed interface LayoutOutcome {
    data class Draw(val layout: BlockLayout) : LayoutOutcome
    data class NonDraw(val reason: NonDrawReason) : LayoutOutcome
}

/**
 * One explicit planner result per nonblank input identity.
 * [chosenText] derives ONLY from [block] (never merged, concatenated, or
 * substituted). [planningOrdinal] is the 0-based rank among nonblank inputs in
 * placement (score-descending, then input index) order; [renderOrdinal] is
 * non-null only for [LayoutOutcome.Draw] and consecutive `0..k-1` in that
 * placement order — the order of [PageLayoutPlan.drawableInRenderOrder].
 */
data class LayoutResult(
    val identity: InputIdentity,
    val block: TranslationBlockView,
    val chosenText: String,
    val planningOrdinal: Int,
    val renderOrdinal: Int?,
    val outcome: LayoutOutcome,
)

/**
 * Full page plan. [resultsInInputOrder] holds exactly one result
 * per nonblank input (blank inputs are the only intentional absence);
 * [drawableInRenderOrder] holds the Draw layouts in placement order and is the
 * only list the renderer consumes.
 */
data class PageLayoutPlan(
    val resultsInInputOrder: List<LayoutResult>,
    val drawableInRenderOrder: List<BlockLayout>,
)

/**
 * [PageLayoutPlan] plus the deterministic count of
 * evaluated final post-anchor placement candidates, summed over every colliding
 * block. Internal test seam — [TextLayoutPlanner.planPage] wraps this and
 * returns only the plan.
 */
internal data class PagePlanWithAttempts(val plan: PageLayoutPlan, val finalPlacementAttempts: Int)

enum class VerticalOrientation { UPRIGHT, ROTATED }
