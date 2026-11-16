package eu.kanade.translation.models

/**
 * Embedded prototype catalog. The catalog lives in code for this prototype:
 * model updates therefore ride app updates until an authenticated remote
 * catalog with signed manifests ships (documented restriction).
 */
object ModelPackCatalog {

    const val MANGAOCR_PACK_ID = "mangaocr"

    fun defaultEmbedded(): List<ModelPackDescriptor> = listOf(mangaocr())

    /**
     * First real runtime pack: overrides the bundled MangaOCR ONNX files at the
     * OCR initialize call site. Sizes are the manifest's real byte counts
     * (scripts/models.manifest); digests are computed at install from the
     * build-time verified asset mirror and pinned then — no invented hashes.
     * The CPU route exists in code but ships UNQUALIFIED until device-validated.
     */
    fun mangaocr(): ModelPackDescriptor = ModelPackDescriptor(
        id = MANGAOCR_PACK_ID,
        version = "1.0.0",
        role = PackRole.ESSENTIAL,
        title = "MangaOCR (runtime pack)",
        licenseNote = "Apache-2.0 lineage (kha-white/manga-ocr; ogkalu/manga-ocr-mobile export) — see docs/MODEL_SOURCES.md",
        sourceKind = PackSourceKind.ASSET_MIRROR,
        portability = Portability(ModelFormat.ONNX, Portability.Qualification.Unqualified),
        featureSeam = "OCR_MANGAOCR",
        variants = listOf(
            ModelVariantDescriptor(
                target = ExecutionTarget.CPU,
                format = ModelFormat.ONNX,
                deviceConstraint = null,
                files = listOf(
                    ModelFileSpec("encoder.onnx", 17_070_003L, null, "models/ocr/encoder.onnx"),
                    ModelFileSpec("decoder_init.onnx", 24_875_052L, null, "models/ocr/decoder_init.onnx"),
                    ModelFileSpec("decoder_step.onnx", 22_776_797L, null, "models/ocr/decoder_step.onnx"),
                    ModelFileSpec("vocab.txt", 37_193L, null, "models/ocr/vocab.txt"),
                ),
            ),
        ),
    )
}
