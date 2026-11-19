package eu.kanade.translation.samvizprobe

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.security.MessageDigest

/** All intermediate tensors of one MangaLight pass, for laptop-side visualization. */
internal class VizResult(
    val luma: FloatArray,
    val level0: FloatArray,
    val level1: FloatArray,
    val rgbPred: FloatArray,
    val preprocessMs: Long,
    val samMs: Long,
    val packMs: Long,
    val generatorMs: Long,
) {
    val totalMs: Long get() = preprocessMs + samMs + packMs + generatorMs
}

/** Faithful port of MangaLightHybridColorizationEngine's Session/Stage: same stage
 *  split, accelerators, shapes, and normalization. Loads the five pinned .tflite
 *  artifacts from filesDir/models, provisioned by tools/sam_viz/provision_models.py. */
internal class MangaLightRunner private constructor(
    private val environments: List<Environment>,
    private val stages: List<Stage>,
    private val generator: Stage,
) : AutoCloseable {

    private var closed = false

    @Synchronized
    fun run(source: Bitmap): VizResult {
        check(!closed) { "Runner is closed" }
        val started = System.nanoTime()
        val luma = GrayPreprocess.lumaPlane(source, WORK_SIZE)
        val preprocessedAt = System.nanoTime()
        val rgb = samRgb(luma)
        var input = rgb
        for (stage in stages.take(3)) input = stage.run(mapOf(stage.inputNames.single() to input)).getValue(stage.outputNames.single())
        val features = stages.last().run(mapOf("enc_stage3_in" to input))
        val samFinished = System.nanoTime()
        val level0 = samLevel0ToNhwc(features.getValue("sam_level0"))
        val level1 = features.getValue("sam_level1")
        check(level1.size == 256 * 32 * 32) { "Invalid SAM level 1" }
        val packed = System.nanoTime()
        val rgbPred = generator.run(
            mapOf(
                "L_bw" to FloatArray(luma.size) { luma[it] * 2f - 1f },
                "sam_level0" to level0,
                "sam_level1" to level1,
            ),
        ).getValue("rgb_pred")
        val finished = System.nanoTime()
        check(rgbPred.size == WORK_SIZE * WORK_SIZE * 3 && rgbPred.all(Float::isFinite)) { "Invalid generator output" }
        return VizResult(
            luma = luma,
            level0 = level0,
            level1 = level1,
            rgbPred = rgbPred,
            preprocessMs = (preprocessedAt - started) / 1_000_000,
            samMs = (samFinished - preprocessedAt) / 1_000_000,
            packMs = (packed - samFinished) / 1_000_000,
            generatorMs = (finished - packed) / 1_000_000,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        (stages + generator).asReversed().forEach { runCatching { it.close() } }
        environments.asReversed().forEach { runCatching { it.close() } }
    }

    private class Stage(val model: CompiledModel, val inputs: Map<String, TensorBuffer>, val outputs: Map<String, TensorBuffer>) : AutoCloseable {
        val inputNames get() = inputs.keys
        val outputNames get() = outputs.keys

        fun run(values: Map<String, FloatArray>): Map<String, FloatArray> {
            check(values.keys == inputs.keys) { "Unexpected model inputs" }
            values.forEach { (name, value) -> inputs.getValue(name).writeFloat(value) }
            model.run(inputs, outputs, SIGNATURE)
            return outputs.mapValues { it.value.readFloat() }
        }

        override fun close() {
            inputs.values.forEach { runCatching { it.close() } }
            outputs.values.forEach { runCatching { it.close() } }
            model.close()
        }

        companion object {
            fun open(file: File, environment: Environment, accelerator: Accelerator, inputNames: List<String>, outputNames: List<String>): Stage {
                val options = CompiledModel.Options(accelerator)
                if (accelerator == Accelerator.GPU) options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                val model = CompiledModel.create(file.absolutePath, options, environment)
                val inputs = mutableMapOf<String, TensorBuffer>()
                val outputs = mutableMapOf<String, TensorBuffer>()
                try {
                    inputNames.forEach {
                        validate(model.getInputTensorType(it, SIGNATURE), it, true)
                        inputs[it] = model.createInputBuffer(it, SIGNATURE)
                    }
                    outputNames.forEach {
                        validate(model.getOutputTensorType(it, SIGNATURE), it, false)
                        outputs[it] = model.createOutputBuffer(it, SIGNATURE)
                    }
                    return Stage(model, inputs, outputs)
                } catch (failure: Throwable) {
                    inputs.values.forEach { runCatching { it.close() } }
                    outputs.values.forEach { runCatching { it.close() } }
                    model.close()
                    throw failure
                }
            }

            private fun validate(type: TensorType, name: String, input: Boolean) {
                val expected = when (name) {
                    "rgb_input" -> listOf(1, 1024, 1024, 3)
                    "enc_stage0_out" -> listOf(1, 256, 256, 96)
                    "enc_stage1_out" -> listOf(1, 128, 128, 192)
                    "enc_stage3_in" -> listOf(1, 64, 64, 384)
                    "sam_level0" -> if (input) listOf(1, 64, 64, 256) else listOf(1, 256, 64, 64)
                    "sam_level1" -> listOf(1, 32, 32, 256)
                    "L_bw" -> listOf(1, 512, 512, 1)
                    "rgb_pred" -> listOf(1, 512, 512, 3)
                    else -> error("Unexpected Manga Light tensor: $name")
                }
                check(type.elementType == TensorType.ElementType.FLOAT && type.layout?.dimensions == expected) {
                    "Invalid Manga Light tensor $name: ${type.layout?.dimensions}"
                }
            }
        }
    }

    data class PinnedArtifact(val name: String, val bytes: Long, val sha256: String) {
        fun verify(modelsDir: File): File {
            val file = File(modelsDir, name)
            check(file.isFile) { "Missing $name in ${modelsDir.absolutePath} — run tools/sam_viz/provision_models.py" }
            check(file.length() == bytes) { "Wrong size for $name: ${file.length()} != $bytes" }
            val digest = sha256Of(file)
            check(digest == sha256) { "Wrong sha256 for $name: $digest" }
            return file
        }
    }

    companion object {
        const val WORK_SIZE = 512
        private const val SIGNATURE = "serving_default"

        val ARTIFACTS = listOf(
            PinnedArtifact("sam0.tflite", 36_312_536L, "06a0436722c853e54c23a3b810a39f7bb47b77a33e74cbbd1763aa7cffe8253f"),
            PinnedArtifact("sam1.tflite", 32_891_512L, "7da91f7f4d00abd4110e6da9583897d833d0c65f60e4e341baca74896c5bc7c7"),
            PinnedArtifact("sam2.tflite", 23_498_360L, "cc0b844f39f6e5a1ca8faa6742102947ba904889cb725c19b49a9cb7ca2aeed0"),
            PinnedArtifact("sam3.tflite", 52_386_032L, "2f9deffb67b09f4d0d328c287c01e31ddfe83b30c06daf03bb26a1b5147ec382"),
            PinnedArtifact("generator.tflite", 179_058_976L, "49f13c8d8dd8f543ebed6a3b76ee60fcf3195680ec9acd93d4fe83e3cfcb72da"),
        )

        fun modelsDir(context: Context): File = File(context.filesDir, "models")

        /** Verifies pins and opens all five stages; throws a descriptive failure otherwise. */
        fun open(context: Context): MangaLightRunner {
            val modelError = qnnDispatchPrecheck(File(context.applicationInfo.nativeLibraryDir))
            check(modelError == null) { modelError ?: "Qualcomm NPU runtime unavailable" }
            val files = ARTIFACTS.map { it.verify(modelsDir(context)) }
            val environments = mutableListOf<Environment>()
            val stages = mutableListOf<Stage>()
            try {
                val npu = Environment.create(context.applicationContext, mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir)).also(environments::add)
                val gpu = Environment.create(context.applicationContext).also(environments::add)
                val stageInputs = listOf("rgb_input", "enc_stage0_out", "enc_stage1_out", "enc_stage3_in")
                val stageOutputs = listOf(listOf("enc_stage0_out"), listOf("enc_stage1_out"), listOf("enc_stage3_in"), listOf("sam_level0", "sam_level1"))
                for (i in 0..3) stages += Stage.open(files[i], npu, Accelerator.NPU, listOf(stageInputs[i]), stageOutputs[i])
                val generator = Stage.open(files[4], gpu, Accelerator.GPU, listOf("L_bw", "sam_level0", "sam_level1"), listOf("rgb_pred"))
                return MangaLightRunner(environments, stages, generator)
            } catch (failure: Throwable) {
                val closables: List<AutoCloseable> = stages + environments
                closables.asReversed().forEach { runCatching { it.close() } }
                throw failure
            }
        }

        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun samLevel0ToNhwc(nchw: FloatArray): FloatArray {
            require(nchw.size == 256 * 64 * 64) { "Invalid SAM level 0" }
            val output = FloatArray(nchw.size)
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    for (c in 0 until 256) {
                        output[(y * 64 + x) * 256 + c] = nchw[(c * 64 + y) * 64 + x]
                    }
                }
            }
            return output
        }

        private fun samRgb(luma: FloatArray): FloatArray {
            require(luma.size == WORK_SIZE * WORK_SIZE)
            val output = FloatArray(1024 * 1024 * 3)
            for (y in 0 until 1024) {
                val fy = (y + 0.5f) / 2f - 0.5f
                val baseY = kotlin.math.floor(fy).toInt()
                val y0 = baseY.coerceIn(0, WORK_SIZE - 1)
                val y1 = (baseY + 1).coerceIn(0, WORK_SIZE - 1)
                val wy = (fy - kotlin.math.floor(fy)).coerceIn(0f, 1f)
                for (x in 0 until 1024) {
                    val fx = (x + 0.5f) / 2f - 0.5f
                    val baseX = kotlin.math.floor(fx).toInt()
                    val x0 = baseX.coerceIn(0, WORK_SIZE - 1)
                    val x1 = (baseX + 1).coerceIn(0, WORK_SIZE - 1)
                    val wx = (fx - kotlin.math.floor(fx)).coerceIn(0f, 1f)
                    val top = luma[y0 * WORK_SIZE + x0] * (1f - wx) + luma[y0 * WORK_SIZE + x1] * wx
                    val bottom = luma[y1 * WORK_SIZE + x0] * (1f - wx) + luma[y1 * WORK_SIZE + x1] * wx
                    val value = top * (1f - wy) + bottom * wy
                    val offset = (y * 1024 + x) * 3
                    output[offset] = value
                    output[offset + 1] = value
                    output[offset + 2] = value
                }
            }
            return output
        }
    }
}
