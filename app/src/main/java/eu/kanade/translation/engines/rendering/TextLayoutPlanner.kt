package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.MaskGeometry
import eu.kanade.translation.model.TranslationBlockView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, neighbour-aware text-layout solver for the render stage.
 *
 * **Why this exists.** The previous per-block renderer laid each block out independently
 * with no global view, causing three defects: (1) collision — close boxes' extents
 * overlapped; (2) forced horizontal-long — a tall parentless box was widened up to
 * 3.5× and symmetrically re-centred, ignoring text length and neighbours; (3) too
 * small — the font-fit bottomed at ~8 px with no legibility floor.
 *
 * This object plans the WHOLE page at once: it reuses the tuned per-block math
 * ([computeRects], [binarySearchFontSize], [cjkWrap]) as the initial fit, then a
 * free-space-aware pass re-anchors reshaped boxes away from their nearest neighbour
 * and grows overflowing/undersized boxes toward the page edge so overflow never
 * points at a neighbour. A legibility floor ([minLegibleFont]) prevents the ~8 px
 * collapse, and a clip safety-net guarantees the structural invariant that no two
 * rendered extents overlap.
 *
 * Pure & side-effect-free (no `Bitmap`/`Canvas`/ONNX); all font-dependent
 * measurement goes through the injected [TextMeasurer], so it is unit-testable
 * with a deterministic fake.
 *
 * **Ordering.** Blocks are placed highest-[TranslationBlockView.score] first: the most
 * confident box takes its preferred placement and lower-confidence boxes treat it
 * as a fixed obstacle. Ties keep reading order (stable sort).
 */
object TextLayoutPlanner {

    internal const val VERTICAL_CHAR_STEP = 1.05f
    internal const val VERTICAL_COL_STEP = 1.25f

    /**
     * Legibility floor. The legacy fit could collapse to ~8 px (unreadable). The
     * floor is the larger of an absolute minimum and a fraction of the smaller
     * page dimension, so it scales with resolution. Invisible for normal text
     * (fits at 36–72 px); only rescues genuinely-oversized-text bubbles.
     */
    private const val LEGIBLE_FONT_FRACTION = 0.014f
    private const val LEGIBLE_FONT_ABS_PX = 14f

    /** Min visible gap kept between two placed extents, in planner px. */
    private const val MIN_GAP_PX = 2f

    /**
     * Resolve a render plan for every block on the page. Thin wrapper over
     * [planPage] returning only the drawable layouts in render order, so
     * existing callers ([TranslationOverlayView], tests) keep working unchanged.
     */
    fun plan(
        blocks: List<TranslationBlockView>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): List<BlockLayout> = planPage(blocks, pageWidth, pageHeight, sampleSize, renderSourceText, measurer)
        .drawableInRenderOrder

    /**
     * Full planner contract: exactly one explicit [LayoutResult] for
     * every nonblank input — [LayoutOutcome.Draw] with its finalized layout, or
     * [LayoutOutcome.NonDraw] with a reason. Blank chosen texts remain the only
     * intentional absence. Blocks are placed highest-[TranslationBlockView.score]
     * first (ties keep reading order); the most confident box takes its
     * preferred placement and lower-confidence boxes treat it as a fixed
     * obstacle. [PageLayoutPlan.drawableInRenderOrder] is the placement-ordered
     * Draw list the renderer consumes.
     */
    fun planPage(
        blocks: List<TranslationBlockView>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): PageLayoutPlan {
        return planPageInternal(
            blocks,
            pageWidth,
            pageHeight,
            sampleSize,
            renderSourceText,
            measurer,
        ).plan
    }

    /**
     * [planPage] plus the deterministic count of
     * EVALUATED final post-anchor candidates, summed over every colliding
     * block. A plan with a single colliding block reports that block's exact
     * evaluated candidate count (e.g. 8 when every finite candidate failed,
     * or k when the k-th evaluated candidate won).
     */
    data class FitResult(
        val fontSize: Float,
        val lines: List<String>,
        val lineHeight: Float,
        val strokeWidth: Float,
    )

    fun fitTextInMask(
        text: String,
        box: FloatRect,
        geometry: MaskGeometry,
        componentId: Int,
        scale: Float,
        measurer: TextMeasurer,
    ): FitResult {
        val margin = 6f * scale
        val maxW = max(10f * scale, box.width() - 2f * margin)
        val maxH = max(10f * scale, box.height() - 2f * margin)
        val startSize = max(8f * scale, min(maxH / 1.15f, 72f * scale))
        var size = startSize
        val step = 1f * scale
        val minSize = 8f * scale
        val fractions = floatArrayOf(1.0f, 0.92f, 0.85f, 0.78f, 0.70f, 0.62f, 0.55f)
        val boxCx = box.left + box.width() / 2f
        val boxCy = box.top + box.height() / 2f

        while (size >= minSize) {
            val lineH = measurer.lineHeight(size)
            val sw = computeStrokeWidth(size, scale)
            for (frac in fractions) {
                val wrapW = maxW * frac
                val lines = TextLineBreaker.prewrap(text, size, wrapW, measurer)
                val totalH = lines.size * lineH + 2f * sw
                if (totalH > maxH) {
                    break
                }
                val maxTextW = lines.maxOfOrNull { measurer.measureTextWidth(it, size) } ?: 0f
                val blockW = maxTextW + 2f * sw
                val rx1 = floor(boxCx - blockW / 2f).toInt()
                val ry1 = floor(boxCy - totalH / 2f).toInt()
                val rx2 = ceil(boxCx + blockW / 2f).toInt()
                val ry2 = ceil(boxCy + totalH / 2f).toInt()
                if (geometry.containsRectangleInComponent(rx1, ry1, rx2, ry2, componentId)) {
                    return FitResult(size, lines, lineH, sw)
                }
            }
            size -= step
        }
        val fallbackSize = minSize
        val fallbackLines = TextLineBreaker.prewrap(text, fallbackSize, maxW, measurer)
        val fallbackLineH = measurer.lineHeight(fallbackSize)
        val fallbackSw = computeStrokeWidth(fallbackSize, scale)
        return FitResult(fallbackSize, fallbackLines, fallbackLineH, fallbackSw)
    }

    fun fitTextVertical(
        text: String,
        box: FloatRect,
        scale: Float,
        measurer: TextMeasurer,
    ): FitResult {
        val margin = 6f * scale
        val maxW = max(10f * scale, box.width() - 2f * margin)
        val maxH = max(10f * scale, box.height() - 2f * margin)
        val startSize = max(8f * scale, min(maxH / 1.15f, 72f * scale))
        var size = startSize
        val step = 1f * scale
        val minSize = 8f * scale

        val chars = text.filterNot { it == '\r' || it == '\n' || it == ' ' }
        while (size >= minSize) {
            val charStep = size * VERTICAL_CHAR_STEP
            val colStep = size * VERTICAL_COL_STEP
            val charsPerCol = max(1, (maxH / charStep).toInt())
            val cols = (chars.length + charsPerCol - 1) / charsPerCol
            val totalW = cols * colStep
            if (totalW <= maxW) {
                return FitResult(size, listOf(text), charStep, computeStrokeWidth(size, scale))
            }
            size -= step
        }
        val fallbackSize = minSize
        return FitResult(fallbackSize, listOf(text), fallbackSize * VERTICAL_CHAR_STEP, computeStrokeWidth(fallbackSize, scale))
    }

    /**
     * Desktop 1:1 text layout engine:
     * - Symmetrical mask expansion for single-bubble regions
     * - Natural cluster intersection for multi-region conjoined bubbles
     * - Progressive aspect-ratio contraction fitting into bubble contours
     */
    internal fun planPageInternal(
        blocks: List<TranslationBlockView>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): PagePlanWithAttempts {
        if (blocks.isEmpty()) return PagePlanWithAttempts(PageLayoutPlan(emptyList(), emptyList()), 0)
        val scale = 1f / sampleSize

        val ordered = blocks.withIndex().sortedWith(
            compareByDescending<IndexedValue<TranslationBlockView>> { it.value.score }
                .thenBy { it.index },
        )

        val maskGrouping = buildMaskRegions(blocks)
        val resultsByInput = arrayOfNulls<LayoutResult>(blocks.size)
        val drawable = ArrayList<BlockLayout>(blocks.size)
        var planningOrdinal = 0

        // Step 1: Assign blocks to components (1:1 with desktop assignment loop)
        val assignments = HashMap<Int, Pair<Int, Int>>()
        for ((groupId, members) in maskGrouping.groups) {
            if (groupId < 0) continue
            val geometry = maskGrouping.session.geometryFor(groupId) ?: continue
            for (item in members) {
                val text = chosenText(item.value, renderSourceText)
                if (text.isBlank()) continue
                val componentId = resolveComponentId(geometry, item.value) ?: continue
                assignments[item.index] = Pair(groupId, componentId)
            }
        }

        // Step 2: Count regions per component (1:1 with desktop comp_counts)
        val compCounts = HashMap<Pair<Int, Int>, Int>()
        for (indexed in ordered) {
            val text = chosenText(indexed.value, renderSourceText)
            if (text.isBlank()) continue
            val a = assignments[indexed.index]
            if (a != null) {
                compCounts[a] = (compCounts[a] ?: 0) + 1
            }
        }

        // Step 3: Layout each block
        for (indexed in ordered) {
            val inputIndex = indexed.index
            val block = indexed.value
            val text = chosenText(block, renderSourceText)
            if (text.isBlank()) continue
            val ordinal = planningOrdinal++
            val identity = InputIdentity(inputIndex, block.blockId)

            if (block.width <= 0f || block.height <= 0f || block.width.isNaN() || block.height.isNaN()) {
                resultsByInput[inputIndex] = LayoutResult(
                    identity,
                    block,
                    text,
                    ordinal,
                    null,
                    LayoutOutcome.NonDraw(NonDrawReason.INVALID_OR_SUBPIXEL_SOURCE_RECT),
                )
                continue
            }

            val effectiveW = max(1f, block.width)
            val effectiveH = max(1f, block.height)
            val rbox = FloatRect(block.x, block.y, block.x + effectiveW, block.y + effectiveH)

            val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
            val pbox = if (hasParent) {
                FloatRect(block.parentX, block.parentY, block.parentX + block.parentWidth, block.parentY + block.parentHeight)
            } else {
                null
            }
            val anchorCx = if (pbox != null) pbox.left + pbox.width() / 2f else rbox.left + rbox.width() / 2f
            val anchorCy = if (pbox != null) pbox.top + pbox.height() / 2f else rbox.top + rbox.height() / 2f

            val a = assignments[inputIndex]
            val fitBox: FloatRect
            val geometry: MaskGeometry?
            val componentId: Int?
            val groupId: Int?

            if (a != null) {
                groupId = a.first
                componentId = a.second
                geometry = maskGrouping.session.geometryFor(groupId)
                val cb = geometry?.componentBoundingBox(componentId)
                if (geometry != null && cb != null) {
                    val count = compCounts[a] ?: 1
                    if (count == 1) {
                        // Symmetrical expansion from anchor center within component bounds
                        val halfW = max(rbox.width() / 2f, min(anchorCx - cb[0], cb[2] - anchorCx))
                        val halfH = max(rbox.height() / 2f, min(anchorCy - cb[1], cb[3] - anchorCy))
                        fitBox = FloatRect(
                            max(cb[0].toFloat(), anchorCx - halfW),
                            max(cb[1].toFloat(), anchorCy - halfH),
                            min(cb[2].toFloat(), anchorCx + halfW),
                            min(cb[3].toFloat(), anchorCy + halfH),
                        )
                    } else {
                        // Conjoined / multi-region inside one component: intersect rbox with component
                        val inter = geometry.intersectComponentWithRect(
                            componentId,
                            floor(rbox.left).toInt(),
                            floor(rbox.top).toInt(),
                            ceil(rbox.right).toInt(),
                            ceil(rbox.bottom).toInt(),
                        )
                        fitBox = if (inter != null) {
                            FloatRect(inter[0].toFloat(), inter[1].toFloat(), inter[2].toFloat(), inter[3].toFloat())
                        } else {
                            rbox
                        }
                    }
                } else {
                    fitBox = pbox ?: rbox
                }
            } else {
                groupId = null
                componentId = null
                geometry = null
                fitBox = pbox ?: rbox
            }

            val isVertical = block.direction == "TTB" && shouldRenderVertical(text)
            val isMasked = a != null && geometry != null && componentId != null
            val layout: BlockLayout
            if (isMasked) {
                val fitResult = if (isVertical) {
                    fitTextVertical(text, fitBox, scale, measurer)
                } else {
                    fitTextInMask(text, fitBox, geometry!!, componentId!!, scale, measurer)
                }
                val originX = fitBox.left + fitBox.width() / 2f
                val originY = fitBox.top + fitBox.height() / 2f
                layout = BlockLayout(
                    block = block,
                    text = text,
                    isVertical = isVertical,
                    originX = originX,
                    originY = originY,
                    safeW = fitBox.width(),
                    safeH = fitBox.height(),
                    fontSizePx = fitResult.fontSize,
                    strokeWidth = fitResult.strokeWidth,
                    drawAlign = TextAlign.CENTER,
                    clipRect = null,
                    lines = fitResult.lines,
                    maskGeometry = geometry,
                    planGeometryId = groupId,
                    maskComponentId = componentId,
                    cellRect = null,
                    positionedLines = null,
                    conservativeOccupancy = emptyList(),
                    hardClip = HardClip(groupId, componentId, null),
                    maskUsable = true,
                )
            } else {
                val placedObstacles = drawable.map { extentOf(it, measurer) }
                val minLegible = minLegibleFont(pageWidth, pageHeight, scale)
                val rect = computeRects(block, sampleSize, pbox)
                layout = placeBlock(
                    block = block,
                    text = text,
                    isVertical = isVertical,
                    rect = rect,
                    obstacles = placedObstacles,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    minLegible = minLegible,
                    scale = scale,
                    measurer = measurer,
                    regionOverride = pbox,
                )
            }

            drawable.add(layout)
            resultsByInput[inputIndex] = LayoutResult(
                identity = identity,
                block = block,
                chosenText = text,
                planningOrdinal = ordinal,
                renderOrdinal = drawable.size - 1,
                outcome = LayoutOutcome.Draw(layout),
            )
        }

        val results = ArrayList<LayoutResult>(blocks.size)
        for (i in blocks.indices) resultsByInput[i]?.let { results.add(it) }
        return PagePlanWithAttempts(PageLayoutPlan(results, drawable), 0)
    }

    /** Legibility floor for a page of [pageWidth]×[pageHeight] at decode [scale]. */
    internal fun minLegibleFont(pageWidth: Float, pageHeight: Float, scale: Float): Float =
        max(LEGIBLE_FONT_ABS_PX * scale, min(pageWidth, pageHeight) * LEGIBLE_FONT_FRACTION)

    private fun chosenText(block: TranslationBlockView, renderSourceText: Boolean): String =
        if (renderSourceText) block.translation.ifBlank { block.text } else block.translation

    /**
     * Place one block, treating already-finalised higher-score [obstacles] as fixed.
     * Steps: (1) re-anchor a reshaped tall box away from the nearest obstacle /
     * toward the larger free side; (2) fit font, then if undersized or
     * overflowing grow the box into the larger free side (never
     * toward an obstacle) and re-fit; (3) if a residual overlap remains, clip this
     * block to its own side of the obstacle boundary (the no-overlap guarantee).
     *
     * [allowGrowth] = false suppresses only step 2's growth (the
     * baseline candidate re-places the pre-growth base rect through the exact
     * same fit/anchor/clip path). Every existing call defaults to true.
     */
    internal fun placeBlock(
        block: TranslationBlockView,
        text: String,
        isVertical: Boolean,
        rect: RectResult,
        obstacles: List<FloatRect>,
        pageWidth: Float,
        pageHeight: Float,
        minLegible: Float,
        scale: Float,
        measurer: TextMeasurer,
        regionOverride: FloatRect?,
        allowGrowth: Boolean = true,
    ): BlockLayout {
        var baseX = rect.baseX
        var baseY = rect.baseY
        var baseW = rect.baseW
        var baseH = rect.baseH
        val safePad = max(0f, (baseW - rect.safeW) / 2f)

        val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
        val parentBox = if (hasParent) {
            FloatRect(
                block.parentX,
                block.parentY,
                block.parentX + block.parentWidth,
                block.parentY + block.parentHeight,
            )
        } else {
            null
        }

        val anchorToOcrCenter = regionOverride != null ||
            hasParent ||
            block.label == 2 ||
            (block.direction == "TTB" && !isVertical)
        val region = if (parentBox != null) {
            parentBox
        } else if (regionOverride != null) {
            regionOverride
        } else if (rect.reshaped) {
            FloatRect(0f, 0f, pageWidth, pageHeight)
        } else {
            FloatRect(block.x, block.y, block.x + block.width, block.y + block.height)
        }

        // (1) Re-anchor a reshaped tall box by MINIMAL DISPLACEMENT: start from the
        // legacy symmetric placement; if it collides with an obstacle or page edge,
        // shift the smallest distance that resolves it. Isolated boxes keep the
        // legacy position (no regression); only nudges on a real collision.
        if (rect.reshaped) {
            val origCenterX = rect.origLeft + (rect.origRight - rect.origLeft) / 2f
            baseX = CollisionRelaxation.resolveMinimalDisplacementX(
                preferredX = origCenterX - baseW / 2f,
                width = baseW,
                pageWidth = pageWidth,
                obstacles = obstacles,
                origCenterX = origCenterX,
            )
        }

        var safeW = max(1f, baseW - safePad * 2f)
        var safeH = max(1f, baseH - safePad * 2f)
        var fontSize = binarySearchFontSize(text, safeW, safeH, baseW, isVertical, scale, measurer)

        val grown = if (allowGrowth) {
            CollisionRelaxation.growIntoFreeSpaceIfNeeded(
                text = text,
                isVertical = isVertical,
                baseX = baseX,
                baseY = baseY,
                baseW = baseW,
                baseH = baseH,
                safePad = safePad,
                obstacles = obstacles,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                minLegible = minLegible,
                currentFont = fontSize,
                measurer = measurer,
            )
        } else {
            GrownBox(baseX, baseY, baseW, baseH, safeW, safeH, fontSize, grewRight = false, grewLeft = false)
        }
        baseW = min(grown.baseW, region.width())
        baseH = min(grown.baseH, region.height())
        baseX = grown.baseX.coerceIn(region.left, region.right - baseW)
        baseY = grown.baseY.coerceIn(region.top, region.bottom - baseH)
        safeW = max(1f, baseW - safePad * 2f)
        safeH = max(1f, baseH - safePad * 2f)
        fontSize = binarySearchFontSize(text, safeW, safeH, baseW, isVertical, scale, measurer)

        var strokeWidth = computeStrokeWidth(fontSize, scale)
        var originY = baseY + baseH / 2f
        // Pin the original edge of a box that grew horizontally so parentless edge
        // text (e.g. SFX) does not drift toward the page centre: grew-right ⇒ LEFT
        // at the left edge; grew-left ⇒ RIGHT at the right edge. A required clip
        // (below) overrides this to LEFT at its own left edge.
        var originX: Float
        var drawAlign: TextAlign
        val centeredOriginX = baseX + baseW / 2f
        val edgeAnchorWouldAvoidOverlap = !isVertical &&
            centeredExtentOverlapsObstacle(
                text = text,
                fontSize = fontSize,
                safeW = safeW,
                originX = centeredOriginX,
                originY = originY,
                obstacles = obstacles,
                measurer = measurer,
            )
        when {
            grown.grewRight && edgeAnchorWouldAvoidOverlap -> {
                originX = baseX
                drawAlign = TextAlign.LEFT
            }
            grown.grewLeft && edgeAnchorWouldAvoidOverlap -> {
                originX = baseX + baseW
                drawAlign = TextAlign.RIGHT
            }
            else -> {
                originX = centeredOriginX
                drawAlign = TextAlign.CENTER
            }
        }
        var clipRect: FloatRect? = null

        // (3) Clip safety-net: if the final extent still overlaps an obstacle, clip
        // to this block's own side of the obstacle. Reached only when free space is
        // exhausted; guarantees the no-overlap invariant.
        val safeLeft = baseX + safePad
        val safeTop = baseY + safePad
        val safeRight = baseX + baseW - safePad
        val safeBottom = baseY + baseH - safePad
        var clip = FloatRect(safeLeft, safeTop, safeRight, safeBottom)
        var neededClip = false
        for (obs in obstacles) {
            if (!clip.overlaps(obs)) continue
            val inter = clip.intersection(obs)
            // Keep the half of `clip` on this block's centre side, removing exactly
            // the overlapping sliver.
            val centerX = baseX + baseW / 2f
            clip = if (centerX < (obs.left + obs.right) / 2f) {
                // Block is LEFT of the obstacle → drop the right sliver.
                FloatRect(clip.left, clip.top, min(clip.right, inter.left), clip.bottom)
            } else {
                FloatRect(max(clip.left, inter.right), clip.top, clip.right, clip.bottom)
            }
            neededClip = true
        }
        if (regionOverride == null && neededClip && clip.width() > MIN_GAP_PX && clip.height() > MIN_GAP_PX) {
            clipRect = clip
            if (isVertical) {
                originX = clip.left + clip.width() / 2f
            } else {
                originX = clip.left
                drawAlign = TextAlign.LEFT
            }
            originY = clip.top + clip.height() / 2f
            safeW = clip.width()
            safeH = clip.height()

            val fitFont = binarySearchFontSize(
                text,
                safeW,
                safeH,
                safeW,
                isVertical,
                scale,
                measurer,
            )
            // Skip the legibility floor so text fits the clipped bounds instead of
            // being cut off.
            fontSize = fitFont
        }

        // (C) Containment clip: fall back to clipping at the region boundary when
        // text still overflows it.
        if (CollisionRelaxation.overflows(text, fontSize, isVertical, region.width(), region.height(), measurer)) {
            val r = if (clipRect != null) clipRect.intersection(region) else region
            if (r.width() > MIN_GAP_PX && r.height() > MIN_GAP_PX) {
                clipRect = r
                if (isVertical) {
                    originX = r.left + r.width() / 2f
                } else {
                    originX = r.left
                    drawAlign = TextAlign.LEFT
                }
                originY = r.top + r.height() / 2f
                safeW = r.width()
                safeH = r.height()

                val fitFont = binarySearchFontSize(
                    text,
                    safeW,
                    safeH,
                    safeW,
                    isVertical,
                    scale,
                    measurer,
                )
                fontSize = fitFont
            }
        }

        if (hasParent && parentBox != null) {
            val targetRegion = regionOverride ?: parentBox
            val centerX = targetRegion.left + targetRegion.width() / 2f
            val centerY = targetRegion.top + targetRegion.height() / 2f
            originX = centerX
            originY = centerY
            drawAlign = TextAlign.CENTER

            safeW = max(1f, targetRegion.width() - 8f)
            safeH = max(1f, targetRegion.height() - 8f)
            fontSize = binarySearchFontSize(
                text,
                safeW,
                safeH,
                safeW,
                isVertical,
                scale,
                measurer,
            )
            strokeWidth = computeStrokeWidth(fontSize, scale)
            clipRect = null
        } else if (regionOverride != null) {
            val centerX = regionOverride.left + regionOverride.width() / 2f
            val centerY = regionOverride.top + regionOverride.height() / 2f
            originX = centerX
            originY = centerY
            drawAlign = TextAlign.CENTER

            safeW = max(1f, regionOverride.width() - 8f)
            safeH = max(1f, regionOverride.height() - 8f)
            fontSize = binarySearchFontSize(
                text,
                safeW,
                safeH,
                safeW,
                isVertical,
                scale,
                measurer,
            )
            strokeWidth = computeStrokeWidth(fontSize, scale)
            clipRect = null
        } else if (anchorToOcrCenter) {
            originX = block.x + block.width / 2f
            originY = block.y + block.height / 2f
            drawAlign = TextAlign.CENTER
        }

        return BlockLayout(
            block = block,
            text = text,
            isVertical = isVertical,
            originX = originX,
            originY = originY,
            safeW = safeW,
            safeH = safeH,
            fontSizePx = fontSize,
            strokeWidth = strokeWidth,
            drawAlign = drawAlign,
            clipRect = clipRect,
            lines = if (isVertical) emptyList() else cjkWrap(text, fontSize, safeW, measurer),
        )
    }

    internal data class GrownBox(
        val baseX: Float,
        val baseY: Float,
        val baseW: Float,
        val baseH: Float,
        val safeW: Float,
        val safeH: Float,
        val fontSize: Float,
        // Which horizontal side the box grew into (false when it did not grow
        // horizontally). Drives the placeBlock re-anchor so a grown box stays
        // pinned at its original edge instead of drifting toward page centre.
        val grewRight: Boolean,
        val grewLeft: Boolean,
    )

    /** True when the text fits the safe rect at [font] exactly (no overflow). */
    internal fun fitsAt(
        text: String,
        font: Float,
        isVertical: Boolean,
        safeW: Float,
        safeH: Float,
        measurer: TextMeasurer,
    ): Boolean = CollisionRelaxation.fitsAt(text, font, isVertical, safeW, safeH, measurer)

    /** Number of vertical columns the stripped text occupies at [charStep] in [safeH]. */
    internal fun columnsFor(text: String, safeH: Float, charStep: Float): Int =
        CollisionRelaxation.columnsFor(text, safeH, charStep)

    internal fun strippedLength(text: String): Int =
        CollisionRelaxation.strippedLength(text)

    /**
     * Smallest wrap width at [font] that fits [text] into at most [maxLines] lines,
     * by binary search over [widestAtomicToken, singleLineWidth]. CJK glyphs break
     * anywhere so the floor is small; Latin words are atomic so the floor is the
     * widest single word. Returns the single-line width when [maxLines] == 1.
     */
    internal fun minWidthForLines(
        text: String,
        font: Float,
        maxLines: Int,
        measurer: TextMeasurer,
    ): Float = CollisionRelaxation.minWidthForLines(text, font, maxLines, measurer)
    /**
     * The rendered pixel extent of a finalized [layout], centered on its origin
     * (or within its clip rect when clipped). Used as the obstacle footprint for
     * subsequently-placed blocks and for the final overlap check.
     *
     * Adaptive layouts use the union bounding box of the per-line
     * conservative occupancy rects, so every block kind is measured with the
     * same conservative convention.
     */
    private fun extentOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
        if (layout.positionedLines != null && layout.conservativeOccupancy.isNotEmpty()) {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = Float.MIN_VALUE
            var bottom = Float.MIN_VALUE
            for (rect in layout.conservativeOccupancy) {
                if (rect.left < left) left = rect.left
                if (rect.top < top) top = rect.top
                if (rect.right > right) right = rect.right
                if (rect.bottom > bottom) bottom = rect.bottom
            }
            return FloatRect(left, top, right, bottom)
        }
        val cx = layout.originX
        val cy = layout.originY
        return if (layout.isVertical) {
            val charStep = layout.fontSizePx * VERTICAL_CHAR_STEP
            val colStep = layout.fontSizePx * VERTICAL_COL_STEP
            val cols = columnsFor(layout.text, layout.safeH, charStep)
            val totalW = cols * colStep
            val colH = min(layout.safeH, strippedLength(layout.text) * charStep)
            FloatRect(cx - totalW / 2f, cy - colH / 2f, cx + totalW / 2f, cy + colH / 2f)
        } else {
            val lines = cjkWrap(layout.text, layout.fontSizePx, layout.safeW, measurer)
            val lineH = measurer.lineHeight(layout.fontSizePx)
            val totalH = lines.size * lineH
            val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
                .coerceAtLeast(0f)
            when (layout.drawAlign) {
                TextAlign.LEFT -> FloatRect(cx, cy - totalH / 2f, cx + maxLineW, cy + totalH / 2f)
                TextAlign.RIGHT -> FloatRect(cx - maxLineW, cy - totalH / 2f, cx, cy + totalH / 2f)
                TextAlign.CENTER -> FloatRect(
                    cx - maxLineW / 2f,
                    cy - totalH / 2f,
                    cx + maxLineW / 2f,
                    cy + totalH / 2f,
                )
            }
        }
    }

    private class MaskGrouping(
        val regions: Map<Int, FloatRect>,
        val groupByIndex: Map<Int, Int>,
        val session: SharedMaskSession,
        /** Input-ordered members per group id (grouped and groupless). */
        val groups: Map<Int, List<IndexedValue<TranslationBlockView>>>,
    )

    /**
     * Build strict layout regions from persisted segmentation masks. A fused mask
     * may contain multiple OCR children; partition its bounds at the midpoints of
     * the child centers so one child cannot consume the other's space.
     *
     * Grouping walks blocks in INPUT order through a [SharedMaskSession] instead
     * of hashing whole [BubbleMaskRle] data classes. Grouped masks share their
     * per-page group id (fingerprint-bucketed, full-equality verified); groupless
     * masks keep a deterministic reference-identity group, so the partition math
     * below produces the SAME outputs as before for the production case of one
     * shared mask instance per bubble.
     */
    private fun buildMaskRegions(blocks: List<TranslationBlockView>): MaskGrouping {
        val session = SharedMaskSession()
        val regions = HashMap<Int, FloatRect>()
        val groups = LinkedHashMap<Int, MutableList<IndexedValue<TranslationBlockView>>>()
        val groupByIndex = HashMap<Int, Int>()
        for (item in blocks.withIndex()) {
            val mask = item.value.segmentationMask ?: continue
            val groupId = session.groupIdOf(mask)
            groupByIndex[item.index] = groupId
            groups.getOrPut(groupId) { mutableListOf() }.add(item)
        }

        for ((_, indexedBlocks) in groups) {
            val mask = indexedBlocks.first().value.segmentationMask!!
            val bounds = mask.bounds
            val maskRect = FloatRect(
                bounds[0].toFloat(),
                bounds[1].toFloat(),
                bounds[2].toFloat(),
                bounds[3].toFloat(),
            )
            if (indexedBlocks.size == 1) {
                val single = indexedBlocks.single()
                val block = single.value
                if (block.parentWidth > 0f && block.parentHeight > 0f) {
                    val anchorX = block.parentX + block.parentWidth / 2f
                    val halfW = max(block.width / 2f, min(anchorX - maskRect.left, maskRect.right - anchorX))
                    regions[single.index] = FloatRect(
                        max(maskRect.left, anchorX - halfW),
                        maskRect.top,
                        min(maskRect.right, anchorX + halfW),
                        maskRect.bottom,
                    )
                } else {
                    regions[single.index] = maskRect
                }
                continue
            }

            // If blocks have distinct detected text_bubble boxes, use each block's own bubble box
            val distinctParentBoxes = indexedBlocks.all { it.value.parentWidth > 0f && it.value.parentHeight > 0f }
            val uniqueParents = indexedBlocks.map { "${it.value.parentX}_${it.value.parentY}_${it.value.parentWidth}_${it.value.parentHeight}" }.distinct()

            if (distinctParentBoxes && uniqueParents.size == indexedBlocks.size) {
                for (item in indexedBlocks) {
                    regions[item.index] = FloatRect(
                        item.value.parentX,
                        item.value.parentY,
                        item.value.parentX + item.value.parentWidth,
                        item.value.parentY + item.value.parentHeight,
                    )
                }
                continue
            }

            val centers = indexedBlocks.associate { item ->
                item.index to Pair(
                    item.value.x + item.value.width / 2f,
                    item.value.y + item.value.height / 2f,
                )
            }
            val spanX = centers.values.maxOf { it.first } - centers.values.minOf { it.first }
            val spanY = centers.values.maxOf { it.second } - centers.values.minOf { it.second }
            val splitX = spanX >= spanY
            val ordered = indexedBlocks.sortedBy { centers[it.index]!!.let { c -> if (splitX) c.first else c.second } }
            val cuts = ordered.zipWithNext().map { (left, right) ->
                val a = centers[left.index]!!
                val b = centers[right.index]!!
                if (splitX) (a.first + b.first) / 2f else (a.second + b.second) / 2f
            }

            ordered.forEachIndexed { position, item ->
                val pBox = if (item.value.parentWidth > 0f && item.value.parentHeight > 0f) {
                    FloatRect(
                        item.value.parentX,
                        item.value.parentY,
                        item.value.parentX + item.value.parentWidth,
                        item.value.parentY + item.value.parentHeight,
                    )
                } else {
                    maskRect
                }

                val region = if (splitX) {
                    FloatRect(
                        if (position == 0) pBox.left else cuts[position - 1],
                        pBox.top,
                        if (position == ordered.lastIndex) pBox.right else cuts[position],
                        pBox.bottom,
                    )
                } else {
                    FloatRect(
                        pBox.left,
                        if (position == 0) pBox.top else cuts[position - 1],
                        pBox.right,
                        if (position == ordered.lastIndex) pBox.bottom else cuts[position],
                    )
                }
                regions[item.index] = region
            }
        }
        session.convertAll()
        return MaskGrouping(regions, groupByIndex, session, groups)
    }

    /**
     * Deterministic component assignment for a block's OCR rectangle using
     * [MaskGeometry.componentForRectangleDeterministic]. A tie across
     * components or a zero-overlap rectangle no longer leaves the block
     * cell-less: it resolves to the max-overlap component, else the tied (or,
     * when all overlaps are zero, any) component with the bounds center
     * nearest the OCR center, then the lower component id.
     */
    private fun resolveComponentId(geometry: MaskGeometry, block: TranslationBlockView): Int? {
        val left = floor(block.x).toInt().coerceAtLeast(0)
        val top = floor(block.y).toInt().coerceAtLeast(0)
        val right = ceil(block.x + block.width).toInt().coerceAtMost(geometry.width)
        val bottom = ceil(block.y + block.height).toInt().coerceAtMost(geometry.height)
        return geometry.componentForRectangleDeterministic(left, top, right, bottom)
    }

    private fun centeredExtentOverlapsObstacle(
        text: String,
        fontSize: Float,
        safeW: Float,
        originX: Float,
        originY: Float,
        obstacles: List<FloatRect>,
        measurer: TextMeasurer,
    ): Boolean {
        if (obstacles.isEmpty()) return false
        val lines = cjkWrap(text, fontSize, safeW, measurer)
        val lineH = measurer.lineHeight(fontSize)
        val totalH = lines.size * lineH
        val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, fontSize) } ?: 0f)
            .coerceAtLeast(0f)
        val centered = FloatRect(
            originX - maxLineW / 2f,
            originY - totalH / 2f,
            originX + maxLineW / 2f,
            originY + totalH / 2f,
        )
        return obstacles.any { obs ->
            val inter = centered.intersection(obs)
            inter.width() > MIN_GAP_PX && inter.height() > MIN_GAP_PX
        }
    }

    // Pure per-block font and direction kernels live in FontFittingAlgorithms;
    // these wrappers preserve the planner's long-standing internal seams.
    internal fun isCJK(ch: Char): Boolean = FontFittingAlgorithms.isCJK(ch)

    internal fun cjkRatio(text: String): Float = FontFittingAlgorithms.cjkRatio(text)

    internal fun shouldRenderVertical(text: String): Boolean =
        FontFittingAlgorithms.shouldRenderVertical(text)

    internal fun computeRects(
        block: TranslationBlockView,
        sampleSize: Int = 1,
        regionOverride: FloatRect? = null,
    ): RectResult = FontFittingAlgorithms.computeRects(block, sampleSize, regionOverride)

    internal fun cjkWrap(
        text: String,
        fontSizePx: Float,
        maxWidthPx: Float,
        measurer: TextMeasurer,
    ): List<String> = FontFittingAlgorithms.cjkWrap(text, fontSizePx, maxWidthPx, measurer)

    internal fun binarySearchFontSize(
        text: String,
        safeW: Float,
        safeH: Float,
        containerW: Float,
        isVertical: Boolean,
        scale: Float,
        measurer: TextMeasurer,
    ): Float = FontFittingAlgorithms.binarySearchFontSize(
        text,
        safeW,
        safeH,
        containerW,
        isVertical,
        scale,
        measurer,
    )

    internal fun computeStrokeWidth(fontSizePx: Float, scale: Float): Float =
        FontFittingAlgorithms.computeStrokeWidth(fontSizePx, scale)

    fun graphemeClusters(text: String): List<String> = FontFittingAlgorithms.graphemeClusters(text)

    fun verticalGlyph(cluster: String): String = FontFittingAlgorithms.verticalGlyph(cluster)

    fun verticalOrientation(cluster: String): VerticalOrientation =
        FontFittingAlgorithms.verticalOrientation(cluster)
}

