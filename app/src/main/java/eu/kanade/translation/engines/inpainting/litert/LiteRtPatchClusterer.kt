package eu.kanade.translation.engines.inpainting.litert

import android.graphics.Rect
import eu.kanade.translation.engines.inpainting.InpaintBoxGeometry
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

internal data class InpaintPatch(
    val cropRect: Rect,
    val containedBoxes: List<IntArray>,
)

internal object LiteRtPatchClusterer {

    private const val MAX_CLUSTER_BOX_SIZE = 448

    internal fun clusterIntoPatches(
        boxes: List<IntArray>,
        pageW: Int,
        pageH: Int,
        patchSize: Int,
        pad: Int,
    ): List<InpaintPatch> {
        if (boxes.isEmpty()) return emptyList()

        // Decompose any tall or wide boxes exceeding MAX_CLUSTER_BOX_SIZE into overlapping tiles
        val inputBoxes = mutableListOf<IntArray>()
        for (box in boxes) {
            val bw = box[2] - box[0]
            val bh = box[3] - box[1]
            if (bw > MAX_CLUSTER_BOX_SIZE && bh > MAX_CLUSTER_BOX_SIZE) {
                // A box that is BOTH wider and taller than MAX_CLUSTER_BOX_SIZE is not a text line;
                // tiling it into 2D grid of 448x448 tiles would mask out and erase massive artwork regions.
                logcat(LogPriority.WARN) {
                    "[LiteRTInpaint] Skipping 2D oversized non-text box [${box[0]},${box[1]},${box[2]},${box[3]}] (${bw}x$bh)"
                }
                continue
            }
            if (bw > MAX_CLUSTER_BOX_SIZE || bh > MAX_CLUSTER_BOX_SIZE) {
                inputBoxes.addAll(InpaintBoxGeometry.tileBounds(box[0], box[1], box[2], box[3], tile = MAX_CLUSTER_BOX_SIZE, overlap = 64))
            } else {
                inputBoxes.add(box)
            }
        }

        val maxClusterSpan = patchSize - (2 * pad)
        val patches = mutableListOf<InpaintPatch>()
        val unassigned = inputBoxes.toMutableList()

        while (unassigned.isNotEmpty()) {
            val seed = unassigned.removeAt(0)
            val patchBoxes = mutableListOf(seed)

            var minX = seed[0]
            var minY = seed[1]
            var maxX = seed[2]
            var maxY = seed[3]

            // Iteratively absorb all nearby boxes that fit within maxClusterSpan so they run in one pass
            var mergedAny = true
            while (mergedAny) {
                mergedAny = false
                val iter = unassigned.iterator()
                while (iter.hasNext()) {
                    val candidate = iter.next()
                    val nMinX = min(minX, candidate[0])
                    val nMinY = min(minY, candidate[1])
                    val nMaxX = max(maxX, candidate[2])
                    val nMaxY = max(maxY, candidate[3])

                    if ((nMaxX - nMinX) <= maxClusterSpan && (nMaxY - nMinY) <= maxClusterSpan) {
                        minX = nMinX
                        minY = nMinY
                        maxX = nMaxX
                        maxY = nMaxY
                        patchBoxes.add(candidate)
                        iter.remove()
                        mergedAny = true
                    }
                }
            }

            val side = min(patchSize, min(pageW, pageH))
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2

            val left = (cx - side / 2).coerceIn(0, max(0, pageW - side))
            val top = (cy - side / 2).coerceIn(0, max(0, pageH - side))
            val right = min(pageW, left + side)
            val bottom = min(pageH, top + side)

            patches.add(InpaintPatch(Rect(left, top, right, bottom), patchBoxes))
        }

        return patches
    }
}
