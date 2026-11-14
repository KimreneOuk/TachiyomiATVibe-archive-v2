package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.vision.detection.PanelAssignment
import eu.kanade.translation.engines.vision.webtoon.WebtoonSlidingDetector
import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TranslationBlock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Spatial grouping stage of the ROI recognition pipeline: dedupe, bubble
 * indexing, panel assignment and block shaping. Operates on the engine's
 * state through the receiver; no state is owned here.
 */

internal fun RoiPageRecognitionEngine.appendRecognizedBlock(
    bitmap: Bitmap,
    detection: Detection,
    text: String,
    isVerticalLanguage: Boolean,
    bubbles: List<Detection>,
    bubbleMasks: List<BubbleMaskRle>,
    lockedRecognizedBlocks: MutableList<RecognizedBlock>,
) {
    val bbox = detection.bbox
    val boxWidth = (bbox[2] - bbox[0]).toFloat()
    val boxHeight = (bbox[3] - bbox[1]).toFloat()
    val centerX = (bbox[0] + bbox[2]) / 2.0
    val centerY = (bbox[1] + bbox[3]) / 2.0
    val rawParent = selectParentBubble(detection, bbox, bubbles, centerX, centerY)
    val parentBbox = rawParent?.let { rp ->
        val siblings = bubbles.filter { it !== rp }.map { it.bbox }
        trimParentBbox(rp.bbox, bbox, siblings)
    } ?: rawParent?.bbox
    val renderColors = RenderColorEstimator.estimate(
        bitmap,
        bbox[0],
        bbox[1],
        bbox[2],
        bbox[3],
        parentBbox,
    )
    val direction = if (isVerticalLanguage && boxHeight > boxWidth * 1.2f) "TTB" else "LTR"
    lockedRecognizedBlocks.add(
        RecognizedBlock(
            detection = detection,
            block = TranslationBlock(
                text = text,
                width = boxWidth,
                height = boxHeight,
                x = bbox[0].toFloat(),
                y = bbox[1].toFloat(),
                symWidth = boxWidth * 0.1f,
                symHeight = boxHeight * 0.1f,
                angle = 0f,
                label = detection.label,
                score = detection.score,
                parentX = parentBbox?.get(0)?.toFloat() ?: 0f,
                parentY = parentBbox?.get(1)?.toFloat() ?: 0f,
                parentWidth = parentBbox?.let { (it[2] - it[0]).toFloat() } ?: 0f,
                parentHeight = parentBbox?.let { (it[3] - it[1]).toFloat() } ?: 0f,
                textColor = renderColors.first,
                strokeColor = renderColors.second,
                strokeWidth = renderColors.third,
                direction = direction,
                segmentationMask = bubbleMasks.firstOrNull { rle ->
                    rle.overlapPixels(centerX.toInt(), centerY.toInt(), centerX.toInt() + 1, centerY.toInt() + 1) > 0
                },
            ),
        ),
    )
}

/**
 * run panel detection on the page bitmap and assign each final
 * OCR block to a panel via [PanelAssignment]. Mutates the block list in
 * place: sets [TranslationBlock.panelIndex], [TranslationBlock.panelAssignment],
 * [TranslationBlock.panelContainment]. No-op when the panel detector is not
 * loaded (panel-less translation, the prior behaviour).
 *
 * Conservative policy enforced here, NOT silent fallback:
 *  - owned blocks get a real panelIndex (reading-order).
 *  - spanning / free_floating / orphan / invalid blocks keep panelIndex=null
 *    and are surfaced to the translator as page-level context. They are
 *    NEVER attached to the nearest panel — that would poison speaker and
 *    pronoun inference, the exact failure mode panel context exists to fix.
 *  - every non-OWNED block is logged so a flaky panel model is visible.
 *
 * Panel boxes are sorted into reading order (XY-cut, RTL-aware for manga)
 * BEFORE assignment so panelIndex is the reading-order index the translator
 * will see in the prompt, not the raw detector order.
 */
internal fun RoiPageRecognitionEngine.assignPanels(bitmap: Bitmap, pageTranslation: PageTranslation) {
    val pd = panelDetector ?: return
    if (pageTranslation.blocks.isEmpty()) return
    // derive stable bubble indices from parent geometry BEFORE
    // panel assignment (see assignBubbleIndices). Must run first so the
    // bubbleIndex is present on each block before panel fields are set.
    assignBubbleIndices(pageTranslation)
    val isWebtoonMode = WebtoonSlidingDetector.isTallImage(bitmap.width, bitmap.height) ||
        language == TextRecognizerLanguage.KOREAN ||
        !resolveReadingOrderRtl()
    if (isWebtoonMode) {
        logcat(LogPriority.INFO) { "Panel detection bypassed for webtoon / LTR reading order" }
        return
    }
    val panels: List<FloatArray> = try {
        pd.detect(bitmap)
    } catch (e: Exception) {
        // Detection threw (closed session, native error). Fail loudly but do
        // NOT crash OCR/translation — blocks keep panelAssignment="none" and
        // the prompt layer treats missing panel context as panel-less.
        logcat(LogPriority.ERROR, e) {
            "Panel detection failed; page will be translated without panel context"
        }
        return
    }
    if (panels.isEmpty()) {
        // Text present but 0 panels: the broken/full-bleed page case. Mark
        // every block ORPHAN so the translator sees explicit page-level context.
        pageTranslation.blocks.forEach { block ->
            val res = PanelAssignment.noPanels(boxValid = PanelAssignment.isValidBox(block.x, block.y, block.x + block.width, block.y + block.height))
            applyAssignment(pageTranslation, block, res)
        }
        logcat(LogPriority.INFO) {
            "Panel detection: 0 panels for page with ${pageTranslation.blocks.size} block(s) — marked orphan (broken/full-bleed page)"
        }
        return
    }

    val orderedPanels = ReadingOrderSorter.readingOrderPanels(panels, resolveReadingOrderRtl())
    val counts = HashMap<String, Int>()
    for (block in pageTranslation.blocks) {
        val res = PanelAssignment.assign(
            block.x,
            block.y,
            block.x + block.width,
            block.y + block.height,
            orderedPanels,
        )
        applyAssignment(pageTranslation, block, res)
        counts.merge(res.category.asString(), 1) { a, b -> a + b }
    }
    // Surface non-owned categories so a degraded panel model is visible
    // without enabling full diagnostics.
    val owned = counts["owned"] ?: 0
    val nonOwned = pageTranslation.blocks.size - owned
    if (nonOwned > 0) {
        logcat(LogPriority.INFO) {
            "Panel assignment: ${pageTranslation.blocks.size} blocks, ${orderedPanels.size} panels — " +
                "owned=$owned, non-owned=$nonOwned ($counts)"
        }
    }
}

/**
 * assign a stable per-page bubble index to each block based on
 * its parent-bubble geometry. Blocks inside the same speech bubble share the
 * same parentX/Y/Width/Height (set by the recognition path); they get the
 * same index so the translator can treat them as one utterance. Free-text
 * blocks (no parent bubble, parentWidth/Height == 0) keep index null.
 *
 * Indices are assigned in top-left reading order of the distinct bubbles so
 * they're stable across re-runs on the same page state. Mutates blocks in
 * place via copy()+replace (data class val fields).
 */
internal fun RoiPageRecognitionEngine.assignBubbleIndices(pageTranslation: PageTranslation) {
    // Collect distinct bubble geometries (rounded to int to treat near-
    // identical floats as the same bubble).
    data class BubbleKey(val x: Int, val y: Int, val w: Int, val h: Int)
    val distinctBubbles = LinkedHashMap<BubbleKey, Int>()
    for (block in pageTranslation.blocks) {
        if (block.parentWidth <= 0f || block.parentHeight <= 0f) continue
        val key = BubbleKey(
            block.parentX.toInt(),
            block.parentY.toInt(),
            block.parentWidth.toInt(),
            block.parentHeight.toInt(),
        )
        if (key !in distinctBubbles) distinctBubbles[key] = 0
    }
    if (distinctBubbles.isEmpty()) return
    // Sort by (y, x) for a stable grouping ID. RTL ordering is irrelevant —
    // the prompt layer never orders by bubbleIndex, only groups by it.
    val sortedKeys = distinctBubbles.keys.sortedWith(
        compareBy<BubbleKey>({ it.y }, { it.x }),
    )
    val keyToIndex = HashMap<BubbleKey, Int>(sortedKeys.size)
    sortedKeys.forEachIndexed { i, k -> keyToIndex[k] = i }

    // Apply: replace each block with a copy carrying its bubbleIndex.
    for (i in pageTranslation.blocks.indices) {
        val block = pageTranslation.blocks[i]
        if (block.parentWidth <= 0f || block.parentHeight <= 0f) continue
        val key = BubbleKey(
            block.parentX.toInt(),
            block.parentY.toInt(),
            block.parentWidth.toInt(),
            block.parentHeight.toInt(),
        )
        val bidx = keyToIndex[key]
        if (bidx != null && bidx != block.bubbleIndex) {
            pageTranslation.blocks[i] = block.copy(bubbleIndex = bidx)
        }
    }
}

internal fun RoiPageRecognitionEngine.applyAssignment(
    pageTranslation: PageTranslation,
    block: TranslationBlock,
    res: PanelAssignment.Result,
) {
    // panelIndex is only set for OWNED; other categories keep null so the
    // translator renders page-level context instead of a confidently wrong panel.
    val idx = pageTranslation.blocks.indexOfFirst { it === block }
    if (idx < 0) {
        logcat(LogPriority.WARN) {
            "Panel assignment: could not locate block for category=${res.category.asString()}; assignment dropped"
        }
        return
    }
    pageTranslation.blocks[idx] = block.copy(
        panelIndex = res.panelIndex,
        panelAssignment = res.category.asString(),
        panelContainment = res.bestContainment,
        bubbleIndex = block.bubbleIndex,
    )
}

internal fun RoiPageRecognitionEngine.suppressCrossLabelDuplicates(
    textDetections: List<Detection>,
    bubbles: List<Detection>,
): List<Detection> =
    OcrBlockDeduplication.suppressCrossLabelDuplicates(textDetections, bubbles)

internal fun RoiPageRecognitionEngine.dedupeTextDetections(
    textDetections: List<Detection>,
    bubbles: List<Detection>,
): List<Detection> =
    OcrBlockDeduplication.dedupeTextDetections(textDetections, bubbles)

internal fun RoiPageRecognitionEngine.removePostOcrDuplicateBlocks(blocks: List<RecognizedBlock>): List<RecognizedBlock> =
    OcrBlockDeduplication.removePostOcrDuplicateBlocks(blocks)

internal fun RoiPageRecognitionEngine.trimParentBbox(
    parent: IntArray,
    textBbox: IntArray,
    siblingBubbles: List<IntArray>,
): IntArray =
    OcrBlockDeduplication.trimParentBbox(parent, textBbox, siblingBubbles)

internal fun RoiPageRecognitionEngine.selectParentBubble(
    detection: Detection,
    textBbox: IntArray,
    bubbles: List<Detection>,
    centerX: Double,
    centerY: Double,
): Detection? =
    OcrBlockDeduplication.selectParentBubble(detection, textBbox, bubbles, centerX, centerY)

internal fun RoiPageRecognitionEngine.cropBitmap(source: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap =
    VerticalLineOcr.cropBitmap(source, x1, y1, x2, y2)

internal suspend fun RoiPageRecognitionEngine.recognizeMultiLine(
    engine: RoiOcrEngine,
    crop: Bitmap,
    paddleDet: PaddleOcrV6DetEngine?,
    verticalFallback: Boolean,
): String =
    VerticalLineOcr.recognizeMultiLine(engine, crop, paddleDet, verticalFallback, language) { closed }
