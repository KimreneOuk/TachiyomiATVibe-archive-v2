package eu.kanade.translation.engines.inpainting.aot

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.FreeTextRoute
import eu.kanade.translation.engines.inpainting.InpaintBoxGeometry
import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.RegionBackend
import eu.kanade.translation.engines.inpainting.RegionDispatch
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.PADDLE_BOX_THRESH
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.PADDLE_CROP_PAD
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.PADDLE_THRESH
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.PADDLE_UNCLIP_RATIO
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_AOT_CONTEXT
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_FREE_TEXT_DILATE
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_FREE_TEXT_FEATHER
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_FREE_TEXT_PAD
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.REPORT_INPAINT_CONTEXT
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.engines.inpainting.opencv.OpenCvInpaintEngine
import eu.kanade.translation.engines.inpainting.resolveDispatch
import eu.kanade.translation.engines.inpainting.resolveFreeTextRoute
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Region routing stage: bubble vs free-text split, Paddle line refinement,
 * and the free-text execution routes (LiteRT delegate / ONNX / classical).
 */

internal fun NeuralInpaintingCoordinator.runInpaintRegions(
    image: Bitmap,
    boxes: List<IntArray>,
    labels: List<Int>? = null,
    dispatch: RegionDispatch = InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true),
    blocks: List<eu.kanade.translation.model.TranslationBlock>? = null,
): Bitmap {
    lastRunDegraded = false
    lastBubbleRoute = null
    if (boxes.isEmpty()) return image.copy(Bitmap.Config.ARGB_8888, true)
    val fixedSess = fixedSession
    val dynamicSess = dynamicSession
    var result = image.copy(Bitmap.Config.ARGB_8888, true)

    val bubbleBoxes = mutableListOf<IntArray>()
    val textBoxes = mutableListOf<IntArray>()
    val textLabels = mutableListOf<Int>()

    if (labels != null && labels.size == boxes.size) {
        for (i in boxes.indices) {
            val lbl = labels[i]
            if (lbl == 0) {
                bubbleBoxes.add(boxes[i])
            } else {
                textBoxes.add(boxes[i])
                textLabels.add(lbl)
            }
        }
    } else {
        for (box in boxes) {
            textBoxes.add(box)
            textLabels.add(2)
        }
    }

    val bubbleTextBoxes = mutableListOf<IntArray>()
    val freeTextDetectorBoxes = mutableListOf<IntArray>()

    for (i in textBoxes.indices) {
        val box = textBoxes[i]
        val lbl = textLabels[i]
        val parent = InpaintBoxGeometry.findParentBubble(box, bubbleBoxes)
        if (parent != null || InpaintBoxGeometry.overlapsAnyBubble(box, bubbleBoxes)) {
            bubbleTextBoxes.add(box)
        } else if (lbl == 2) {
            freeTextDetectorBoxes.add(box)
        } else {
            bubbleTextBoxes.add(box)
        }
    }

    var paddleLinesTotal = 0
    var paddleFallback = 0
    val rawFreeTextGroups: List<List<IntArray>> = if (paddleDetector != null && freeTextDetectorBoxes.isNotEmpty()) {
        val refined = refineFreeTextGroups(image, freeTextDetectorBoxes)
        paddleLinesTotal = refined.paddleLineCount
        paddleFallback = refined.fallbackCount
        refined.groups
    } else {
        freeTextDetectorBoxes.map { listOf(it.copyOf()) }
    }

    val freeTextGroups = InpaintBoxGeometry.clusterFreeTextGroups(rawFreeTextGroups, REPORT_AOT_CONTEXT)

    logcat(LogPriority.INFO) {
        "[inpaint] pipeline=investigation_report bubbleText=${bubbleTextBoxes.size} " +
            "freeDets=${freeTextDetectorBoxes.size} rawGroups=${rawFreeTextGroups.size} clusteredGroups=${freeTextGroups.size} " +
            "dispatch=$dispatch fixed=${fixedSess != null} qnn=${fixedQnnSession != null} nnapi=${fixedNnapiSession != null} " +
            "dynamic=${dynamicSess != null} model=$neuralModel bubbleDelegate=${bubbleNeuralFill != null} " +
            "freeTextDelegate=${freeTextNeuralFill != null}"
    }
    if (paddleDetector != null && freeTextDetectorBoxes.isNotEmpty()) {
        logcat(LogPriority.INFO) {
            "[inpaint] report_paddle detectorText=${freeTextDetectorBoxes.size} paddleLines=$paddleLinesTotal " +
                "fallback=$paddleFallback linePad=$REPORT_FREE_TEXT_PAD lineDilate=$REPORT_FREE_TEXT_DILATE " +
                "aotContext=$REPORT_AOT_CONTEXT thresh=$PADDLE_THRESH boxThresh=$PADDLE_BOX_THRESH unclip=$PADDLE_UNCLIP_RATIO cropPad=$PADDLE_CROP_PAD"
        }
    }

    result = inpaintReportBubbles(result, bubbleTextBoxes, blocks, dispatch)

    val dispatchNeuralFreeText = dispatch.freeText == RegionBackend.NEURAL
    val freeTextRoute = resolveFreeTextRoute(
        dispatchNeural = dispatchNeuralFreeText,
        delegateAvailable = freeTextNeuralFill != null,
        onnxInitialized = isInitialized(),
    )

    if (freeTextRoute == FreeTextRoute.NEURAL_DELEGATE) {
        // Batch all free-text boxes across groups so LiteRT Manga LaMa clusters
        // nearby text into a single 512x512 patch and executes in one forward pass.
        val delegate = requireNotNull(freeTextNeuralFill)
        val allBoxes = freeTextGroups.flatten().distinctBy { "${it[0]},${it[1]},${it[2]},${it[3]}" }
        if (allBoxes.isNotEmpty()) {
            val filled = try {
                delegate(result, allBoxes)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "[inpaint] free-text neural delegate failed; classical fallback" }
                null
            }
            if (filled != null) {
                result = filled
                lastAcceptedRoute = "free_text_delegate"
            } else {
                for (group in freeTextGroups) {
                    result = inpaintReportFreeTextFast(result, group, reason = "neural_delegate_failed")
                }
            }
        }
    } else {
        for (group in freeTextGroups) {
            if (group.isEmpty()) continue
            when (freeTextRoute) {
                FreeTextRoute.NEURAL_ONNX -> result = inpaintReportFreeTextNeural(result, group)
                FreeTextRoute.CLASSICAL -> result = inpaintReportFreeTextFast(
                    result,
                    group,
                    reason = if (!dispatchNeuralFreeText) "fast_mode" else "no_neural_session",
                )
                else -> Unit
            }
        }
    }

    return result
}

private data class RefinedFreeTextGroups(
    val groups: List<List<IntArray>>,
    val paddleLineCount: Int,
    val fallbackCount: Int,
)

private fun NeuralInpaintingCoordinator.refineFreeTextGroups(
    image: Bitmap,
    detectorBoxes: List<IntArray>,
): RefinedFreeTextGroups {
    val w = image.width
    val h = image.height
    val paddleDetector = this.paddleDetector ?: return RefinedFreeTextGroups(detectorBoxes.map { listOf(it.copyOf()) }, 0, detectorBoxes.size)
    val groups = ArrayList<List<IntArray>>(detectorBoxes.size)
    var paddleLineCount = 0
    var fallbackCount = 0
    for (det in detectorBoxes) {
        val detW = det[2] - det[0]
        val detH = det[3] - det[1]
        // Skip giant candidate boxes: manga free text is never a massive 2D square
        if (detW > 400 && detH > 400) {
            logcat(LogPriority.WARN) {
                "[inpaint] skipping oversized 2D detector box: [${det[0]},${det[1]},${det[2]},${det[3]}] (${detW}x$detH)"
            }
            continue
        }
        val cx1 = (det[0] - PADDLE_CROP_PAD).coerceIn(0, w)
        val cy1 = (det[1] - PADDLE_CROP_PAD).coerceIn(0, h)
        val cx2 = (det[2] + PADDLE_CROP_PAD).coerceIn(0, w)
        val cy2 = (det[3] + PADDLE_CROP_PAD).coerceIn(0, h)
        if (cx2 <= cx1 || cy2 <= cy1) continue

        val crop = Bitmap.createBitmap(image, cx1, cy1, cx2 - cx1, cy2 - cy1)
        val lines = try {
            paddleDetector.detectLines(crop, thresh = PADDLE_THRESH, boxThresh = PADDLE_BOX_THRESH, unclipRatio = PADDLE_UNCLIP_RATIO)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[inpaint] report Paddle DET failed on crop" }
            emptyList()
        } finally {
            crop.recycle()
        }

        if (lines.isNotEmpty()) {
            val group = ArrayList<IntArray>(lines.size)
            for (line in lines) {
                val b = line.bbox
                if (b.size < 4) continue
                val px1 = (cx1 + b[0]).coerceIn(0, w)
                val py1 = (cy1 + b[1]).coerceIn(0, h)
                val px2 = (cx1 + b[2]).coerceIn(0, w)
                val py2 = (cy1 + b[3]).coerceIn(0, h)
                if (px2 > px1 && py2 > py1) {
                    group.add(intArrayOf(px1, py1, px2, py2))
                    paddleLineCount++
                }
            }
            if (group.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "[inpaint] freeText refined det=[${det[0]},${det[1]},${det[2]},${det[3]}] into ${group.size} lines: " +
                        group.joinToString { b -> "[${b[0]},${b[1]},${b[2]},${b[3]}]" }
                }
                groups.add(group)
                continue
            }
        }

        // Fallback when Paddle detected no text lines in this crop:
        // Only accept fallback if the candidate box is realistic text size
        if (detW <= 300 && detH <= 300 && (detW.toLong() * detH.toLong() <= (w.toLong() * h.toLong() * 0.04).toLong())) {
            groups.add(listOf(det.copyOf()))
            fallbackCount++
        } else {
            logcat(LogPriority.INFO) {
                "[inpaint] skipping oversized fallback detector box without Paddle text lines: [${det[0]}, ${det[1]}, ${det[2]}, ${det[3]}] (${detW}x$detH)"
            }
        }
    }
    return RefinedFreeTextGroups(groups, paddleLineCount, fallbackCount)
}

internal fun NeuralInpaintingCoordinator.inpaintReportFreeTextFast(
    image: Bitmap,
    boxes: List<IntArray>,
    reason: String = "neural_exhausted",
): Bitmap {
    val bounds = InpaintBoxGeometry.paddedUnionBounds(boxes, image.width, image.height, REPORT_INPAINT_CONTEXT) ?: return image
    val cropW = bounds[2] - bounds[0]
    val cropH = bounds[3] - bounds[1]
    if (cropW <= 0 || cropH <= 0) return image
    val localBoxes = boxes.mapNotNull { InpaintBoxGeometry.localizeBox(it, bounds[0], bounds[1], cropW, cropH) }
    val mask = BubbleMaskBuilder.buildFixedPillMask(localBoxes, cropW, cropH, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
    if (mask.none { it != 0.toByte() }) return image
    val original = IntArray(cropW * cropH)
    image.getPixels(original, 0, cropW, bounds[0], bounds[1], cropW, cropH)
    val work = original.copyOf()
    val classicalBackend = OpenCvInpaintEngine.inpaintPixelsWithBackend(work, mask, cropW, cropH)
    if (reason != "fast_mode" || classicalBackend == OpenCvInpaintEngine.Backend.PUSH_PULL_EMERGENCY) {
        lastRunDegraded = true
    }
    val alpha = BubbleMaskBuilder.featherAlphaField(mask, cropW, cropH, REPORT_FREE_TEXT_FEATHER)
    InpaintPixelOps.compositeInto(original, work, alpha, work)
    image.setPixels(work, 0, cropW, bounds[0], bounds[1], cropW, cropH)
    logcat(LogPriority.INFO) {
        "[inpaint] route=${classicalBackend.routeLabel} reason=$reason crop=${cropW}x$cropH boxes=${boxes.size}"
    }
    return image
}

internal fun NeuralInpaintingCoordinator.inpaintReportFreeTextNeural(image: Bitmap, boxes: List<IntArray>): Bitmap {
    val crop = InpaintBoxGeometry.centeredReportCrop(boxes, image.width, image.height, REPORT_AOT_CONTEXT)
        ?: return image
    val side = crop[2] - crop[0]
    if (side <= 0 || crop[3] - crop[1] != side) return image
    val localBoxes = boxes.mapNotNull { InpaintBoxGeometry.localizeBox(it, crop[0], crop[1], side, side) }
    val maskBytes = BubbleMaskBuilder.buildFixedPillMask(localBoxes, side, side, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
    if (maskBytes.none { it != 0.toByte() }) return image

    var memoryFallbackReason: String? = null
    val candidate = neuralFillSquare(
        image = image,
        localMaskBytes = maskBytes,
        crop = crop,
        side = side,
        logTag = "report",
        logExtra = "boxes=${boxes.size}",
        onMemoryRejected = { reason -> memoryFallbackReason = reason },
    )
    if (candidate == null) {
        return inpaintReportFreeTextFast(image, boxes, reason = memoryFallbackReason ?: "neural_exhausted")
    }
    compositeNeuralTile(image, candidate, maskBytes, crop, side)
    return image
}
