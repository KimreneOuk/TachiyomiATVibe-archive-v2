package eu.kanade.translation.samvizprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File

/** MCV2 W8A8 NPU pass: mirrors NpuMangaColorizationEngine's LiteRtSession —
 *  single fused model, input [1,5,H,W] NCHW (ch0 gray over paper-white pad,
 *  ch1-4 hint/conditioning channels), output [1,H,W,3] NHWC in [0,1].
 *  Hint encoding under test: ch1-3 = RGB color, ch4 = binary mask. */
internal class McV2Result(
    val rgb: FloatArray,
    val contentWidth: Int,
    val contentHeight: Int,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val packMs: Long,
) {
    val totalMs: Long get() = preprocessMs + inferenceMs + packMs
}

internal class McV2Runner private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val input: TensorBuffer,
    private val output: TensorBuffer,
) : AutoCloseable {

    private var closed = false

    /** [hints] is RGBA bytes at [hintWidth]x[hintHeight] in the work-grid space, or null. */
    @Synchronized
    fun run(source: Bitmap, hints: ByteArray? = null, hintWidth: Int = 0, hintHeight: Int = 0): McV2Result {
        check(!closed) { "Runner is closed" }
        val started = System.nanoTime()
        val letterbox = Letterbox.fit(source.width, source.height, INPUT_WIDTH, INPUT_HEIGHT)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val gray = GrayPreprocess.grayLFromArgb(pixels)
        val contentGray = AreaResampler.resample(gray, source.width, source.height, letterbox.width, letterbox.height)
        val inputTensor = packInput(letterbox, contentGray, hints)
        val preprocessedAt = System.nanoTime()

        input.writeFloat(inputTensor)
        model.run(mapOf(INPUT_NAME to input), mapOf(OUTPUT_NAME to output), SIGNATURE)
        val raw = output.readFloat()
        val inferredAt = System.nanoTime()

        val rgb = cropLetterbox(raw, letterbox)
        check(rgb.size == letterbox.width * letterbox.height * 3 && rgb.all(Float::isFinite)) { "Invalid MCV2 output" }
        val finished = System.nanoTime()
        return McV2Result(
            rgb = rgb,
            contentWidth = letterbox.width,
            contentHeight = letterbox.height,
            preprocessMs = (preprocessedAt - started) / 1_000_000,
            inferenceMs = (inferredAt - preprocessedAt) / 1_000_000,
            packMs = (finished - inferredAt) / 1_000_000,
        )
    }

    private class Letterbox(val width: Int, val height: Int, val left: Int, val top: Int) {
        companion object {
            /** Aspect-preserving fit — mirrors MangaColorizationLetterbox.fit. */
            fun fit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Letterbox {
                val scale = minOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
                val width = (sourceWidth * scale).toInt().coerceIn(1, targetWidth)
                val height = (sourceHeight * scale).toInt().coerceIn(1, targetHeight)
                return Letterbox(width, height, (targetWidth - width) / 2, (targetHeight - height) / 2)
            }
        }
    }

    private fun packInput(letterbox: Letterbox, contentGray: ByteArray, hints: ByteArray?): FloatArray {
        val planeSize = INPUT_WIDTH * INPUT_HEIGHT
        val tensor = FloatArray(5 * planeSize)
        // ch0: paper-white pad, content gray normalized to [0,1] inside the letterbox rect.
        java.util.Arrays.fill(tensor, 0, planeSize, 1.0f)
        for (y in 0 until letterbox.height) {
            val outRow = (y + letterbox.top) * INPUT_WIDTH + letterbox.left
            val srcRow = y * letterbox.width
            for (x in 0 until letterbox.width) {
                tensor[outRow + x] = (contentGray[srcRow + x].toInt() and 0xFF) / 255f
            }
        }
        // ch1-4: hint planes, zeros outside strokes. Encoding under test:
        // ch1-3 = RGB in [0,1], ch4 = binary mask at stroke pixels.
        if (hints != null) {
            val decoded = BitmapFactory.decodeByteArray(hints, 0, hints.size)
                ?: throw IllegalArgumentException("undecodable hint image")
            val hintPixels = IntArray(decoded.width * decoded.height)
            decoded.getPixels(hintPixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            decoded.recycle()
            val r = ByteArray(hintPixels.size)
            val g = ByteArray(hintPixels.size)
            val b = ByteArray(hintPixels.size)
            val a = ByteArray(hintPixels.size)
            for (i in hintPixels.indices) {
                val p = hintPixels[i]
                r[i] = ((p shr 16) and 0xFF).toByte()
                g[i] = ((p shr 8) and 0xFF).toByte()
                b[i] = (p and 0xFF).toByte()
                a[i] = ((p shr 24) and 0xFF).toByte()
            }
            val hintW = decoded.width
            val hintH = decoded.height
            val rPlane = AreaResampler.resample(r, hintW, hintH, letterbox.width, letterbox.height)
            val gPlane = AreaResampler.resample(g, hintW, hintH, letterbox.width, letterbox.height)
            val bPlane = AreaResampler.resample(b, hintW, hintH, letterbox.width, letterbox.height)
            val aPlane = AreaResampler.resample(a, hintW, hintH, letterbox.width, letterbox.height)
            for (c in 0..3) {
                val plane = when (c) { 0 -> rPlane; 1 -> gPlane; 2 -> bPlane; else -> aPlane }
                val channelOffset = (c + 1) * planeSize
                for (y in 0 until letterbox.height) {
                    val srcRow = y * letterbox.width
                    val outRow = (y + letterbox.top) * INPUT_WIDTH + letterbox.left
                    for (x in 0 until letterbox.width) {
                        val value = plane[srcRow + x].toInt() and 0xFF
                        if (value >= 128) {
                            // Mask channel is binary; color channels keep their value.
                            tensor[channelOffset + outRow + x] = if (c == 3) 1.0f else value / 255f
                        }
                    }
                }
            }
        }
        return tensor
    }

    /** Mirrors cropRgb01Letterbox: MCV2 output is already in [0,1]. */
    private fun cropLetterbox(raw: FloatArray, letterbox: Letterbox): FloatArray {
        val cropped = FloatArray(letterbox.width * letterbox.height * 3)
        for (y in 0 until letterbox.height) {
            for (x in 0 until letterbox.width) {
                val sourceOffset = ((y + letterbox.top) * INPUT_WIDTH + x + letterbox.left) * 3
                val destinationOffset = (y * letterbox.width + x) * 3
                cropped[destinationOffset] = raw[sourceOffset].coerceIn(0f, 1f)
                cropped[destinationOffset + 1] = raw[sourceOffset + 1].coerceIn(0f, 1f)
                cropped[destinationOffset + 2] = raw[sourceOffset + 2].coerceIn(0f, 1f)
            }
        }
        return cropped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { model.close() }
        runCatching { environment.close() }
    }

    data class PinnedArtifact(val name: String, val bytes: Long, val sha256: String) {
        fun verify(modelsDir: File): File {
            val file = File(modelsDir, name)
            check(file.isFile) { "Missing $name in ${modelsDir.absolutePath} — run tools/sam_viz/provision_models.py" }
            check(file.length() == bytes) { "Wrong size for $name: ${file.length()} != $bytes" }
            val digest = MangaLightRunner.sha256Of(file)
            check(digest == sha256) { "Wrong sha256 for $name: $digest" }
            return file
        }
    }

    companion object {
        const val INPUT_NAME = "input"
        const val OUTPUT_NAME = "rgb"
        const val INPUT_WIDTH = 576
        const val INPUT_HEIGHT = 832
        const val SIGNATURE = "serving_default"

        /** Same pin as NpuMangaColorizationEngine (bank-1111 recalibrated W8A8 apply_plugin). */
        val ARTIFACT = PinnedArtifact(
            "manga_colorize_npu_sm8650_w8a8.tflite",
            55_628_120L,
            "bd44365fa699122d778e52bcd3c0eb6d61ec4ae0b3fc01079afaf8fc17fb2cc3",
        )

        /** Verifies the pin and opens the fused NPU model; throws descriptively on failure. */
        fun open(context: Context): McV2Runner {
            val modelError = qnnDispatchPrecheck(File(context.applicationInfo.nativeLibraryDir))
            check(modelError == null) { modelError ?: "Qualcomm NPU runtime unavailable" }
            val file = ARTIFACT.verify(MangaLightRunner.modelsDir(context))
            val environment = Environment.create(
                context.applicationContext,
                mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir),
            )
            var model: CompiledModel? = null
            var input: TensorBuffer? = null
            var output: TensorBuffer? = null
            try {
                model = CompiledModel.create(file.absolutePath, CompiledModel.Options(Accelerator.NPU), environment)
                val inputType = model.getInputTensorType(INPUT_NAME, SIGNATURE)
                check(inputType.elementType == TensorType.ElementType.FLOAT && inputType.layout?.dimensions == listOf(1, 5, INPUT_HEIGHT, INPUT_WIDTH)) {
                    "MCV2 input has dimensions ${inputType.layout?.dimensions}"
                }
                val outputType = model.getOutputTensorType(OUTPUT_NAME, SIGNATURE)
                check(outputType.elementType == TensorType.ElementType.FLOAT && outputType.layout?.dimensions == listOf(1, INPUT_HEIGHT, INPUT_WIDTH, 3)) {
                    "MCV2 output has dimensions ${outputType.layout?.dimensions}"
                }
                input = model.createInputBuffer(INPUT_NAME, SIGNATURE)
                output = model.createOutputBuffer(OUTPUT_NAME, SIGNATURE)
                return McV2Runner(environment, model, input!!, output!!)
            } catch (failure: Throwable) {
                input?.let { runCatching { it.close() } }
                output?.let { runCatching { it.close() } }
                model?.let { runCatching { it.close() } }
                runCatching { environment.close() }
                throw failure
            }
        }
    }
}
