package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.MaskGeometry
import eu.kanade.translation.model.TranslationBlockView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object TextLayoutMaskRegions {

    internal class MaskGrouping(
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
    internal fun buildMaskRegions(blocks: List<TranslationBlockView>): MaskGrouping {
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
    internal fun resolveComponentId(geometry: MaskGeometry, block: TranslationBlockView): Int? {
        val left = floor(block.x).toInt().coerceAtLeast(0)
        val top = floor(block.y).toInt().coerceAtLeast(0)
        val right = ceil(block.x + block.width).toInt().coerceAtMost(geometry.width)
        val bottom = ceil(block.y + block.height).toInt().coerceAtMost(geometry.height)
        return geometry.componentForRectangleDeterministic(left, top, right, bottom)
    }
}
