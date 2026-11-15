package eu.kanade.translation.engines.inpainting.aot

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.InpaintBoxGeometry
import eu.kanade.translation.engines.inpainting.InpaintPadOps
import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.RegionBackend
import eu.kanade.translation.engines.inpainting.RegionDispatch
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.BUBBLE_SEG_MASK_EROSION
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.FEATHER_RAMP_PX
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.NEURAL_TILE_WORKING_BYTES_PER_PIXEL
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_BUBBLE_SMOOTH_PASSES
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_BUBBLE_TILE_OVERLAP
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_BUBBLE_TILE_SIZE
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_INPAINT_CONTEXT
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.engines.inpainting.bubble.BubbleMedianDiffusionFill
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Bubble inpainting stage: segmentation-mask rasterization + erosion, tiling,
 * the neural tile loop with heap memory gate, and the median-fill fallback.
 */

/**
 * Bubble inpainting with segmentation-only masks.
 *
 * The erase mask is EXCLUSIVELY the union of the supplied segmentation
 * masks ([eu.kanade.translation.model.TranslationBlock.segmentationMask],
 * the YOLO11-seg whole-balloon interior), contour-eroded by
 * [BUBBLE_SEG_MASK_EROSION] to protect the hand-drawn bubble stroke border.
 * A supplied mask is passed unchanged in shape through crop / pad /
 * composite â€” it is never replaced by a bounding-box or pill approximation.
 *
 * Text inside bubbles WITHOUT a supplied segmentation is preserved: the
 * pixels stay untouched and the skip is surfaced via [lastBubbleRoute] and
 * [lastRunDegraded] instead of inventing a rectangle/pill mask. Free text
 * (detections outside every bubble) keeps its own line-mask batching.
 *
 * Erosion reduces the border risk but cannot guarantee contour
 * preservation for imperfect, thin (< erosion radius), or jagged
 * segmentations.
 */
internal fun NeuralInpaintingCoordinator.inpaintReportBubbles(
    image: Bitmap,
    boxes: List<IntArray>,
    blocks: List<eu.kanade.translation.model.TranslationBlock>?,
    dispatch: RegionDispatch,
): Bitmap {
    if (boxes.isEmpty() && (blocks == null || blocks.none { it.segmentationMask != null })) return image
    val w = image.width
    val h = image.height
    val mask = ByteArray(w * h)

    // Rasterize the supplied segmentation masks and erode them within
    // component bounds so the bubble contour stroke is never erased.
    val blocksWithSegMask = blocks?.filter { it.segmentationMask != null } ?: emptyList()
    if (blocksWithSegMask.isNotEmpty()) {
        val rawSegMask = ByteArray(w * h)
        val boundsList = ArrayList<List<Int>>(blocksWithSegMask.size)
        for (block in blocksWithSegMask) {
            val seg = block.segmentationMask ?: continue
            seg.rasterizeOnto(rawSegMask)
            boundsList.add(seg.bounds)
        }
        val erodedSegMask = erodeBinaryMask(rawSegMask, w, h, boundsList, BUBBLE_SEG_MASK_EROSION)
        for (i in mask.indices) {
            if (erodedSegMask[i] != 0.toByte()) {
                mask[i] = 1
            }
        }
    }

    if (mask.none { it != 0.toByte() }) {
        if (boxes.isNotEmpty()) {
            // Bubble text existed without any supplied segmentation mask:
            // preserve pixels (never invent a pill/rectangle) and surface
            // the skip for the page stamp.
            lastBubbleRoute = "skipped_no_segmentation"
            lastRunDegraded = true
            logcat(LogPriority.WARN) {
                "[inpaint] bubble_route=skipped_no_segmentation boxes=${boxes.size} " +
                    "dispatch=$dispatch (no segmentation masks supplied; pixels preserved)"
            }
        }
        return image
    }

    // Mixed page: bubble text boxes whose area carries no segmentation
    // coverage keep their pixels untouched and downgrade the page to
    // degraded, so a partially-masked page is never reported as clean.
    // [boxes] here are the bubble text boxes split by the caller.
    var uncoveredBubbleBoxes = 0
    for (box in boxes) {
        val x1 = box[0].coerceIn(0, w)
        val y1 = box[1].coerceIn(0, h)
        val x2 = box[2].coerceIn(0, w)
        val y2 = box[3].coerceIn(0, h)
        var covered = false
        outer@ for (y in y1 until y2) {
            val rowOffset = y * w
            for (x in x1 until x2) {
                if (mask[rowOffset + x] != 0.toByte()) {
                    covered = true
                    break@outer
                }
            }
        }
        if (!covered) uncoveredBubbleBoxes++
    }
    val partialMasks = uncoveredBubbleBoxes > 0

    if (dispatch.bubble == RegionBackend.NEURAL) {
        val clusters = InpaintBoxGeometry.componentClusters(mask, w, h)
        if (bubbleNeuralMemoryGateTripped(clusters)) {
            inpaintBubbleMedian(image, mask, w, h)
            lastBubbleRoute = if (partialMasks) "neural_median_memgate_partial_masks" else "neural_median_memgate"
            lastRunDegraded = true
            logPartialMaskSkip(uncoveredBubbleBoxes)
            return image
        }

        var anyNeuralFailure = false
        var neuralTileCount = 0
        val maxCropSide = minOf(InpaintPadOps.SIZE, w, h)
        val tileSide = minOf(REPORT_BUBBLE_TILE_SIZE, maxCropSide)
        val tileOverlap = minOf(REPORT_BUBBLE_TILE_OVERLAP, tileSide - 1).coerceAtLeast(0)

        for (cluster in clusters) {
            val tiles = InpaintBoxGeometry.tileBounds(
                cluster[0],
                cluster[1],
                cluster[2],
                cluster[3],
                tile = tileSide,
                overlap = tileOverlap,
            )
            for (tile in tiles) {
                val tileWidth = tile[2] - tile[0]
                val tileHeight = tile[3] - tile[1]
                val side = (maxOf(tileWidth, tileHeight) + 2 * REPORT_INPAINT_CONTEXT)
                    .coerceAtMost(maxCropSide)
                val cropX = (tile[0] - (side - tileWidth) / 2).coerceIn(0, w - side)
                val cropY = (tile[1] - (side - tileHeight) / 2).coerceIn(0, h - side)
                val crop = intArrayOf(cropX, cropY, cropX + side, cropY + side)
                val localMask = cropMask(mask, w, h, crop, side, tile)
                if (localMask.none { it != 0.toByte() }) continue

                // Prefer the LiteRT delegate for the selected model. A null
                // result can use a legacy ONNX session only when one was
                // explicitly initialized. The mask is the eroded segmentation
                // crop, never the bubble bounding box.
                val candidate = bubbleNeuralFill?.invoke(image, localMask, crop, side)
                    ?: neuralFillSquare(
                        image = image,
                        localMaskBytes = localMask,
                        crop = crop,
                        side = side,
                        logTag = "bubble",
                    )
                if (candidate == null) {
                    anyNeuralFailure = true
                    continue
                }
                compositeNeuralTile(image, candidate, localMask, crop, side)
                neuralTileCount++
            }
        }

        if (anyNeuralFailure) {
            // Neural failed (delegate unavailable, session error, or
            // memory gate): the specified fallback is the solid median
            // blend â€” never an OpenCV NS catch-up.
            inpaintBubbleMedian(image, mask, w, h)
            lastRunDegraded = true
            lastBubbleRoute = if (partialMasks) "neural_median_fallback_partial_masks" else "neural_median_fallback"
            logcat(LogPriority.WARN) {
                "[inpaint] bubble_route=$lastBubbleRoute clusters=${clusters.size} " +
                    "tiles=$neuralTileCount dispatch=$dispatch"
            }
        } else {
            lastBubbleRoute = if (partialMasks) "neural_partial_masks" else "neural"
            lastRunDegraded = lastRunDegraded || partialMasks
            logcat(LogPriority.INFO) {
                "[inpaint] bubble_route=$lastBubbleRoute clusters=${clusters.size} tiles=$neuralTileCount dispatch=$dispatch"
            }
        }
        logPartialMaskSkip(uncoveredBubbleBoxes)
        return image
    }

    // Classical bubble path (FAST/BALANCE and the QUALITY fallback): solid
    // median diffusion fill with feathered edge blending.
    inpaintBubbleMedian(image, mask, w, h)
    lastBubbleRoute = if (partialMasks) "median_fill_partial_masks" else "median_fill"
    lastRunDegraded = lastRunDegraded || partialMasks
    logPartialMaskSkip(uncoveredBubbleBoxes)
    return image
}

internal fun NeuralInpaintingCoordinator.logPartialMaskSkip(uncoveredBubbleBoxes: Int) {
    if (uncoveredBubbleBoxes <= 0) return
    logcat(LogPriority.WARN) {
        "[inpaint] bubble_partial_masks=$uncoveredBubbleBoxes (no segmentation coverage; pixels preserved)"
    }
}

/**
 * Heap gate for the neural bubble path: the working set of one neural tile
 * (â‰¤ [InpaintPadOps.SIZE]Â² pixels across the mask, source, padded tensors,
 * decoded candidate, alpha field, and blend buffers) must fit a quarter of
 * the available heap, otherwise the solid median blend runs instead of
 * risking an OOM mid-fill.
 */
internal fun NeuralInpaintingCoordinator.bubbleNeuralMemoryGateTripped(clusters: List<IntArray>): Boolean {
    val maxTileSide = minOf(InpaintPadOps.SIZE, REPORT_BUBBLE_TILE_SIZE + 2 * REPORT_INPAINT_CONTEXT)
    val tileWorkingBytes = maxTileSide.toLong() * maxTileSide * NEURAL_TILE_WORKING_BYTES_PER_PIXEL
    val availableHeap = EngineMemoryBudget.heapSnapshot().availableHeapBytes
    val tripped = tileWorkingBytes > availableHeap / 4L
    if (tripped) {
        val largestCluster = clusters.maxOfOrNull { (it[2] - it[0]) * (it[3] - it[1]) } ?: 0
        logcat(LogPriority.WARN) {
            "[inpaint] bubble neural memory gate tripped (tileWorkSet=" +
                "${tileWorkingBytes / (1L shl 20)}MiB largestClusterPx=$largestCluster " +
                "heapAvailable=${availableHeap / (1L shl 20)}MiB); using median fill"
        }
    }
    return tripped
}

internal fun NeuralInpaintingCoordinator.cropMask(
    fullMask: ByteArray,
    pageWidth: Int,
    pageHeight: Int,
    crop: IntArray,
    side: Int,
    tile: IntArray,
): ByteArray {
    require(fullMask.size.toLong() == pageWidth.toLong() * pageHeight)
    require(crop.size >= 4 && crop[2] - crop[0] == side && crop[3] - crop[1] == side)
    require(crop[0] >= 0 && crop[1] >= 0 && crop[2] <= pageWidth && crop[3] <= pageHeight)
    require(tile.size >= 4)

    val localMask = ByteArray(side * side)
    val startX = maxOf(crop[0], tile[0])
    val startY = maxOf(crop[1], tile[1])
    val endX = minOf(crop[2], tile[2])
    val endY = minOf(crop[3], tile[3])
    if (startX >= endX || startY >= endY) return localMask
    for (y in startY until endY) {
        val sourceOffset = y * pageWidth + startX
        val destinationOffset = (y - crop[1]) * side + startX - crop[0]
        fullMask.copyInto(
            destination = localMask,
            destinationOffset = destinationOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + endX - startX,
        )
    }
    return localMask
}

internal fun NeuralInpaintingCoordinator.inpaintBubbleMedian(image: Bitmap, mask: ByteArray, width: Int, height: Int) {
    val pixels = IntArray(width * height)
    image.getPixels(pixels, 0, width, 0, 0, width, height)
    BubbleMedianDiffusionFill.fillAndBlend(pixels, mask, width, height, REPORT_BUBBLE_SMOOTH_PASSES, FEATHER_RAMP_PX)
    image.setPixels(pixels, 0, width, 0, 0, width, height)
}

internal fun NeuralInpaintingCoordinator.compositeNeuralTile(
    image: Bitmap,
    candidate: IntArray,
    localMask: ByteArray,
    crop: IntArray,
    side: Int,
) {
    val currentPixels = IntArray(side * side)
    image.getPixels(currentPixels, 0, side, crop[0], crop[1], side, side)
    // INWARD-only feather: alpha is exactly 0 outside the eroded
    // segmentation, so every pixel outside the supplied bubble
    // segmentation (including the hand-drawn stroke border) stays
    // pixel-identical. The blend ramps up inside the erosion margin,
    // which is interior paper — never the stroke.
    val alpha = BubbleMaskBuilder.featherAlphaFieldInward(
        mask = localMask,
        width = side,
        height = side,
        rampWidth = BUBBLE_SEG_MASK_EROSION,
    )
    InpaintPixelOps.compositeInto(currentPixels, candidate, alpha, currentPixels)
    image.setPixels(currentPixels, 0, side, crop[0], crop[1], side, side)
}
