package eu.kanade.translation.model

/** Structural semantics shared by mutable drafts and immutable publications. */
internal fun pageTranslationValueEquals(page: PageTranslationView, other: Any?): Boolean {
    if (page === other) return true
    if (other !is PageTranslationView) return false
    return page.blocks == other.blocks &&
        sameValue(page.imgWidth, other.imgWidth) &&
        sameValue(page.imgHeight, other.imgHeight) &&
        page.cleanedImageName == other.cleanedImageName &&
        page.cleanedImageContentHash == other.cleanedImageContentHash &&
        page.coloredImageName == other.coloredImageName &&
        page.coloredImageContentHash == other.coloredImageContentHash &&
        page.coloredFromOriginal == other.coloredFromOriginal &&
        page.coloringStamp == other.coloringStamp &&
        page.coloringFingerprint == other.coloringFingerprint &&
        page.ocrArtifactId == other.ocrArtifactId &&
        page.recognitionEngine == other.recognitionEngine &&
        page.detectionCount == other.detectionCount &&
        page.ocrBlockCount == other.ocrBlockCount &&
        page.decodeSampleSize == other.decodeSampleSize &&
        sameValue(page.originalImgWidth, other.originalImgWidth) &&
        sameValue(page.originalImgHeight, other.originalImgHeight) &&
        page.ocrStatus == other.ocrStatus &&
        page.translationStatus == other.translationStatus &&
        page.inpaintStatus == other.inpaintStatus &&
        page.renderStatus == other.renderStatus &&
        page.coloringStatus == other.coloringStatus &&
        page.ocrError == other.ocrError &&
        page.translationError == other.translationError &&
        page.inpaintError == other.inpaintError &&
        page.renderError == other.renderError &&
        page.coloringError == other.coloringError &&
        page.updatedAt == other.updatedAt &&
        page.sourceFileName == other.sourceFileName &&
        page.runGeneration == other.runGeneration &&
        page.pageVersion == other.pageVersion &&
        page.retryCount == other.retryCount &&
        page.inpaintRevision == other.inpaintRevision &&
        page.inpaintingModeUsed == other.inpaintingModeUsed &&
        page.sourceFingerprint == other.sourceFingerprint &&
        page.detectionFingerprint == other.detectionFingerprint &&
        page.ocrFingerprint == other.ocrFingerprint &&
        page.inpaintFingerprint == other.inpaintFingerprint &&
        page.translationFingerprint == other.translationFingerprint &&
        page.layoutFingerprint == other.layoutFingerprint &&
        page.translationOrigin == other.translationOrigin &&
        page.originalImageFallback == other.originalImageFallback &&
        page.inpaintMaskBoxes == other.inpaintMaskBoxes
}

internal fun pageTranslationValueHashCode(page: PageTranslationView): Int {
    var result = page.blocks.hashCode()
    result = 31 * result + page.imgWidth.toBits()
    result = 31 * result + page.imgHeight.toBits()
    result = 31 * result + (page.cleanedImageName?.hashCode() ?: 0)
    result = 31 * result + (page.cleanedImageContentHash?.hashCode() ?: 0)
    result = 31 * result + (page.coloredImageName?.hashCode() ?: 0)
    result = 31 * result + (page.coloredImageContentHash?.hashCode() ?: 0)
    result = 31 * result + page.coloredFromOriginal.hashCode()
    result = 31 * result + (page.coloringStamp?.hashCode() ?: 0)
    result = 31 * result + (page.coloringFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.ocrArtifactId?.hashCode() ?: 0)
    result = 31 * result + (page.recognitionEngine?.hashCode() ?: 0)
    result = 31 * result + page.detectionCount
    result = 31 * result + page.ocrBlockCount
    result = 31 * result + page.decodeSampleSize
    result = 31 * result + page.originalImgWidth.toBits()
    result = 31 * result + page.originalImgHeight.toBits()
    result = 31 * result + page.ocrStatus.hashCode()
    result = 31 * result + page.translationStatus.hashCode()
    result = 31 * result + page.inpaintStatus.hashCode()
    result = 31 * result + page.renderStatus.hashCode()
    result = 31 * result + page.coloringStatus.hashCode()
    result = 31 * result + (page.ocrError?.hashCode() ?: 0)
    result = 31 * result + (page.translationError?.hashCode() ?: 0)
    result = 31 * result + (page.inpaintError?.hashCode() ?: 0)
    result = 31 * result + (page.renderError?.hashCode() ?: 0)
    result = 31 * result + (page.coloringError?.hashCode() ?: 0)
    result = 31 * result + page.updatedAt.hashCode()
    result = 31 * result + (page.sourceFileName?.hashCode() ?: 0)
    result = 31 * result + page.runGeneration.hashCode()
    result = 31 * result + page.pageVersion.hashCode()
    result = 31 * result + page.retryCount
    result = 31 * result + page.inpaintRevision
    result = 31 * result + (page.inpaintingModeUsed?.hashCode() ?: 0)
    result = 31 * result + (page.sourceFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.detectionFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.ocrFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.inpaintFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.translationFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.layoutFingerprint?.hashCode() ?: 0)
    result = 31 * result + (page.translationOrigin?.hashCode() ?: 0)
    result = 31 * result + page.originalImageFallback.hashCode()
    result = 31 * result + page.inpaintMaskBoxes.hashCode()
    return result
}

internal fun pageTranslationValueToString(page: PageTranslationView): String =
    "PageTranslation(" +
        "blocks=${page.blocks}, " +
        "imgWidth=${page.imgWidth}, " +
        "imgHeight=${page.imgHeight}, " +
        "cleanedImageName=${page.cleanedImageName}, " +
        "cleanedImageContentHash=${page.cleanedImageContentHash}, " +
        "coloredImageName=${page.coloredImageName}, " +
        "coloredImageContentHash=${page.coloredImageContentHash}, " +
        "coloredFromOriginal=${page.coloredFromOriginal}, " +
        "coloringStamp=${page.coloringStamp}, " +
        "coloringFingerprint=${page.coloringFingerprint}, " +
        "ocrArtifactId=${page.ocrArtifactId}, " +
        "recognitionEngine=${page.recognitionEngine}, " +
        "detectionCount=${page.detectionCount}, " +
        "ocrBlockCount=${page.ocrBlockCount}, " +
        "decodeSampleSize=${page.decodeSampleSize}, " +
        "originalImgWidth=${page.originalImgWidth}, " +
        "originalImgHeight=${page.originalImgHeight}, " +
        "ocrStatus=${page.ocrStatus}, " +
        "translationStatus=${page.translationStatus}, " +
        "inpaintStatus=${page.inpaintStatus}, " +
        "renderStatus=${page.renderStatus}, " +
        "coloringStatus=${page.coloringStatus}, " +
        "ocrError=${page.ocrError}, " +
        "translationError=${page.translationError}, " +
        "inpaintError=${page.inpaintError}, " +
        "renderError=${page.renderError}, " +
        "coloringError=${page.coloringError}, " +
        "updatedAt=${page.updatedAt}, " +
        "sourceFileName=${page.sourceFileName}, " +
        "runGeneration=${page.runGeneration}, " +
        "pageVersion=${page.pageVersion}, " +
        "retryCount=${page.retryCount}, " +
        "inpaintRevision=${page.inpaintRevision}, " +
        "inpaintingModeUsed=${page.inpaintingModeUsed}, " +
        "sourceFingerprint=${page.sourceFingerprint}, " +
        "detectionFingerprint=${page.detectionFingerprint}, " +
        "ocrFingerprint=${page.ocrFingerprint}, " +
        "inpaintFingerprint=${page.inpaintFingerprint}, " +
        "translationFingerprint=${page.translationFingerprint}, " +
        "layoutFingerprint=${page.layoutFingerprint}, " +
        "translationOrigin=${page.translationOrigin}, " +
        "originalImageFallback=${page.originalImageFallback}, " +
        "inpaintMaskBoxes=${page.inpaintMaskBoxes})"

internal fun translationBlockValueEquals(block: TranslationBlockView, other: Any?): Boolean {
    if (block === other) return true
    if (other !is TranslationBlockView) return false
    return block.blockId == other.blockId &&
        block.text == other.text &&
        block.translation == other.translation &&
        sameValue(block.width, other.width) &&
        sameValue(block.height, other.height) &&
        sameValue(block.x, other.x) &&
        sameValue(block.y, other.y) &&
        sameValue(block.symHeight, other.symHeight) &&
        sameValue(block.symWidth, other.symWidth) &&
        sameValue(block.angle, other.angle) &&
        block.label == other.label &&
        sameValue(block.score, other.score) &&
        sameValue(block.parentX, other.parentX) &&
        sameValue(block.parentY, other.parentY) &&
        sameValue(block.parentWidth, other.parentWidth) &&
        sameValue(block.parentHeight, other.parentHeight) &&
        block.textColor == other.textColor &&
        block.strokeColor == other.strokeColor &&
        sameValue(block.strokeWidth, other.strokeWidth) &&
        block.direction == other.direction &&
        block.panelIndex == other.panelIndex &&
        block.panelAssignment == other.panelAssignment &&
        sameValue(block.panelContainment, other.panelContainment) &&
        block.bubbleIndex == other.bubbleIndex &&
        block.segmentationMask == other.segmentationMask &&
        block.userEditedAt == other.userEditedAt
}

internal fun translationBlockValueHashCode(block: TranslationBlockView): Int {
    var result = block.blockId?.hashCode() ?: 0
    result = 31 * result + block.text.hashCode()
    result = 31 * result + block.translation.hashCode()
    result = 31 * result + block.width.toBits()
    result = 31 * result + block.height.toBits()
    result = 31 * result + block.x.toBits()
    result = 31 * result + block.y.toBits()
    result = 31 * result + block.symHeight.toBits()
    result = 31 * result + block.symWidth.toBits()
    result = 31 * result + block.angle.toBits()
    result = 31 * result + block.label
    result = 31 * result + block.score.toBits()
    result = 31 * result + block.parentX.toBits()
    result = 31 * result + block.parentY.toBits()
    result = 31 * result + block.parentWidth.toBits()
    result = 31 * result + block.parentHeight.toBits()
    result = 31 * result + block.textColor.hashCode()
    result = 31 * result + block.strokeColor.hashCode()
    result = 31 * result + block.strokeWidth.toBits()
    result = 31 * result + block.direction.hashCode()
    result = 31 * result + (block.panelIndex?.hashCode() ?: 0)
    result = 31 * result + block.panelAssignment.hashCode()
    result = 31 * result + block.panelContainment.toBits()
    result = 31 * result + (block.bubbleIndex?.hashCode() ?: 0)
    result = 31 * result + (block.segmentationMask?.hashCode() ?: 0)
    result = 31 * result + (block.userEditedAt?.hashCode() ?: 0)
    return result
}

internal fun translationBlockValueToString(block: TranslationBlockView): String =
    "TranslationBlock(" +
        "blockId=${block.blockId}, " +
        "text=${block.text}, " +
        "translation=${block.translation}, " +
        "width=${block.width}, " +
        "height=${block.height}, " +
        "x=${block.x}, " +
        "y=${block.y}, " +
        "symHeight=${block.symHeight}, " +
        "symWidth=${block.symWidth}, " +
        "angle=${block.angle}, " +
        "label=${block.label}, " +
        "score=${block.score}, " +
        "parentX=${block.parentX}, " +
        "parentY=${block.parentY}, " +
        "parentWidth=${block.parentWidth}, " +
        "parentHeight=${block.parentHeight}, " +
        "textColor=${block.textColor}, " +
        "strokeColor=${block.strokeColor}, " +
        "strokeWidth=${block.strokeWidth}, " +
        "direction=${block.direction}, " +
        "panelIndex=${block.panelIndex}, " +
        "panelAssignment=${block.panelAssignment}, " +
        "panelContainment=${block.panelContainment}, " +
        "bubbleIndex=${block.bubbleIndex}, " +
        "segmentationMask=${block.segmentationMask}, " +
        "userEditedAt=${block.userEditedAt})"

internal fun detectionValueEquals(detection: DetectionView, other: Any?): Boolean {
    if (detection === other) return true
    if (other !is DetectionView) return false
    return detection.left == other.left &&
        detection.top == other.top &&
        detection.right == other.right &&
        detection.bottom == other.bottom &&
        detection.label == other.label &&
        sameValue(detection.score, other.score) &&
        detection.className == other.className
}

internal fun detectionValueHashCode(detection: DetectionView): Int {
    var result = detection.left
    result = 31 * result + detection.top
    result = 31 * result + detection.right
    result = 31 * result + detection.bottom
    result = 31 * result + detection.label
    result = 31 * result + detection.score.toBits()
    result = 31 * result + detection.className.hashCode()
    return result
}

internal fun detectionValueToString(detection: DetectionView): String =
    "Detection(bbox=${listOf(detection.left, detection.top, detection.right, detection.bottom)}, " +
        "label=${detection.label}, score=${detection.score}, className=${detection.className})"

private fun sameValue(first: Float, second: Float): Boolean = first.toBits() == second.toBits()
