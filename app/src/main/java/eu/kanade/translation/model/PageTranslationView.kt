package eu.kanade.translation.model

/** Read-only surface shared by mutable pipeline drafts and immutable store publications. */
interface PageTranslationView {
    val blocks: List<TranslationBlockView>
    val imgWidth: Float
    val imgHeight: Float
    val cleanedImageName: String?
    val cleanedImageContentHash: String?
    val coloredImageName: String?
    val coloredImageContentHash: String?

    /** The colored image was derived from the original page, not from an inpainted cleaned image. */
    val coloredFromOriginal: Boolean
    val coloringStamp: String?
    val coloringFingerprint: String?
    val ocrArtifactId: String?
    val recognitionEngine: String?
    val detectionCount: Int
    val ocrBlockCount: Int
    val decodeSampleSize: Int
    val originalImgWidth: Float
    val originalImgHeight: Float
    val ocrStatus: String
    val translationStatus: String
    val inpaintStatus: String
    val renderStatus: String
    val coloringStatus: String
    val ocrError: String?
    val translationError: String?
    val inpaintError: String?
    val renderError: String?
    val coloringError: String?
    val updatedAt: Long
    val sourceFileName: String?
    val runGeneration: Long
    val pageVersion: Long
    val retryCount: Int
    val inpaintRevision: Int
    val inpaintingModeUsed: String?
    val sourceFingerprint: String?
    val detectionFingerprint: String?
    val ocrFingerprint: String?
    val inpaintFingerprint: String?
    val translationFingerprint: String?
    val layoutFingerprint: String?
    val translationOrigin: String?
    val originalImageFallback: Boolean
    val inpaintMaskBoxes: List<InpaintMaskBox>
    val allTextDetections: List<DetectionView>
    val attemptCount: Int
    val attemptCharged: Boolean
    val errorMessage: String?
    val activeError: String?
}

/** Read-only block surface. Pipeline drafts keep their mutable [TranslationBlock] objects local. */
interface TranslationBlockView {
    val blockId: String?
    val text: String
    val translation: String
    val width: Float
    val height: Float
    val x: Float
    val y: Float
    val symHeight: Float
    val symWidth: Float
    val angle: Float
    val label: Int
    val score: Float
    val parentX: Float
    val parentY: Float
    val parentWidth: Float
    val parentHeight: Float
    val textColor: Long
    val strokeColor: Long
    val strokeWidth: Float
    val direction: String
    val panelIndex: Int?
    val panelAssignment: String
    val panelContainment: Float
    val bubbleIndex: Int?
    val segmentationMask: BubbleMaskRle?
    val userEditedAt: Long?
}

/** Detection geometry without exposing its mutable IntArray backing storage. */
interface DetectionView {
    /** A fresh array for compatibility; mutations can never reach the published detection. */
    val bbox: IntArray
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    val label: Int
    val score: Float
    val className: String
}
