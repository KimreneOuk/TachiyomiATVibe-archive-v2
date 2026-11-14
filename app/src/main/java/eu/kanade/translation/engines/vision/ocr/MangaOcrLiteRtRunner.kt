package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Autoregressive LiteRT runner for [MangaOcrEngine].
 */
internal class MangaOcrLiteRtRunner : AutoCloseable {

    private var liteRtEnv: Environment? = null
    private var encoderLiteRt: LiteRtModel? = null
    private var decoderInitLiteRt: LiteRtModel? = null
    private var decoderStepLiteRt: LiteRtModel? = null
    private var vocab: List<String> = emptyList()

    private val decodeMutex = Mutex()
    private val hiddenScratch = FloatArray(MangaOcrEngine.HIDDEN_SEQ * MangaOcrEngine.HIDDEN_DIM)
    private val stepHiddenScratch = FloatArray(MangaOcrEngine.HIDDEN_SEQ * MangaOcrEngine.HIDDEN_DIM)
    private val selfKScratch = FloatArray(4 * 1 * 4 * MangaOcrEngine.MAX_LEN * 64)
    private val selfVScratch = FloatArray(4 * 1 * 4 * MangaOcrEngine.MAX_LEN * 64)
    private val crossKScratch = FloatArray(4 * 1 * 4 * MangaOcrEngine.HIDDEN_SEQ * 64)
    private val crossVScratch = FloatArray(4 * 1 * 4 * MangaOcrEngine.HIDDEN_SEQ * 64)
    private val nhwcInputScratch = FloatArray(3 * 224 * 224)

    val isInitialized: Boolean
        get() = encoderLiteRt != null

    fun initialize(
        encoderFile: File,
        decoderInitFile: File,
        decoderStepFile: File,
        vocabFile: File,
        numThreads: Int,
    ) {
        logcat(LogPriority.INFO) {
            "OCR LiteRT init: encoder=${encoderFile.name} decoderInit=${decoderInitFile.name} " +
                "decoderStep=${decoderStepFile.name} (${encoderFile.length()}B/${decoderInitFile.length()}B/" +
                "${decoderStepFile.length()}B) threads=$numThreads"
        }
        val env = Environment.create()
        try {
            val encoder = buildLiteRtModel(
                env,
                encoderFile,
                numThreads,
                inputBindings = listOf(BindingSpec("pixel_values", isInt64 = false, dims = listOf(1, 224, 224, 3))),
                outputBindings = listOf(BindingSpec("hidden_states", isInt64 = false, dims = listOf(1, MangaOcrEngine.HIDDEN_SEQ, MangaOcrEngine.HIDDEN_DIM))),
            )
            val decoderInit = buildLiteRtModel(
                env,
                decoderInitFile,
                numThreads,
                inputBindings = listOf(
                    BindingSpec("encoder_hidden_states", isInt64 = false, dims = listOf(1, MangaOcrEngine.HIDDEN_SEQ, MangaOcrEngine.HIDDEN_DIM)),
                    BindingSpec("input_ids", isInt64 = true, dims = listOf(1, 1)),
                ),
                outputBindings = listOf(
                    BindingSpec("logits", isInt64 = false, dims = listOf(1, MangaOcrEngine.VOCAB_SIZE)),
                    BindingSpec("self_k", isInt64 = false, dims = listOf(4, 1, 4, 1, 64)),
                    BindingSpec("self_v", isInt64 = false, dims = listOf(4, 1, 4, 1, 64)),
                    BindingSpec("cross_k", isInt64 = false, dims = listOf(4, 1, 4, MangaOcrEngine.HIDDEN_SEQ, 64)),
                    BindingSpec("cross_v", isInt64 = false, dims = listOf(4, 1, 4, MangaOcrEngine.HIDDEN_SEQ, 64)),
                ),
            )
            val decoderStep = buildLiteRtModel(
                env,
                decoderStepFile,
                numThreads,
                inputBindings = listOf(
                    BindingSpec("encoder_hidden_states", isInt64 = false, dims = listOf(1, MangaOcrEngine.HIDDEN_DIM, MangaOcrEngine.HIDDEN_SEQ)),
                    BindingSpec("self_k_cache", isInt64 = false, dims = listOf(4, 4, MangaOcrEngine.MAX_LEN, 64, 1)),
                    BindingSpec("self_v_cache", isInt64 = false, dims = listOf(4, 4, MangaOcrEngine.MAX_LEN, 64, 1)),
                    BindingSpec("cross_k_cache", isInt64 = false, dims = listOf(4, 4, MangaOcrEngine.HIDDEN_SEQ, 64, 1)),
                    BindingSpec("cross_v_cache", isInt64 = false, dims = listOf(4, 4, MangaOcrEngine.HIDDEN_SEQ, 64, 1)),
                    BindingSpec("input_ids", isInt64 = true, dims = listOf(1, 1)),
                    BindingSpec("position_ids", isInt64 = true, dims = listOf(1, 1)),
                ),
                outputBindings = listOf(
                    BindingSpec("logits", isInt64 = false, dims = listOf(1, MangaOcrEngine.VOCAB_SIZE)),
                    BindingSpec("self_k_slice", isInt64 = false, dims = listOf(4, 1, 4, 1, 64)),
                    BindingSpec("self_v_slice", isInt64 = false, dims = listOf(4, 1, 4, 1, 64)),
                ),
            )
            encoderLiteRt = encoder
            decoderInitLiteRt = decoderInit
            decoderStepLiteRt = decoderStep
            liteRtEnv = env
            vocab = loadVocab(vocabFile)
            logcat(LogPriority.INFO) { "OCR LiteRT ready (vocab=${vocab.size}, cpu xnnpack)" }
        } catch (failure: Throwable) {
            runCatching { env.close() }
            throw failure
        }
    }

    suspend fun recognize(cropBitmap: Bitmap): String {
        val encoder = encoderLiteRt ?: throw IllegalStateException("OCR LiteRT not initialized")
        val t0 = System.nanoTime()
        val pixels = MangaOcrEngine.preprocess(cropBitmap)
        if (pixels.isEmpty()) return ""
        val t1 = System.nanoTime()

        return decodeMutex.withLock {
            val hidden = runEncoder(encoder, pixels)
            val t2 = System.nanoTime()
            val result = decodeHiddenStateLiteRt(hidden)
            val t3 = System.nanoTime()
            if (MangaOcrEngine.isDiagnosticsEnabled()) {
                logcat(LogPriority.INFO) {
                    "[ocr] backend=litert total=${(t3 - t0) / 1_000_000.0}ms " +
                        "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                        "encoder=${(t2 - t1) / 1_000_000.0}ms " +
                        "decoder=${(t3 - t2) / 1_000_000.0}ms"
                }
            }
            result
        }
    }

    private fun runEncoder(encoder: LiteRtModel, pixels: IntArray): FloatArray {
        MangaOcrEngine.writeNormalizedNhwc(pixels, nhwcInputScratch)
        encoder.input("pixel_values").writeFloat(nhwcInputScratch)
        encoder.model.run(listOf(encoder.input("pixel_values")), listOf(encoder.output("hidden_states")))
        return encoder.output("hidden_states").readFloat()
    }

    // Greedy autoregressive decode over the fp32 graphlets. Host caches keep
    // the [4,1,4,L,64] layout; the step model's [4,4,L,64,1] inputs are a
    // stride-preserving reshape of the same flat floats, so they are fed
    // as-is. Cross caches are constant across steps — written once.
    private fun decodeHiddenStateLiteRt(hidden: FloatArray): String {
        val decInit = decoderInitLiteRt ?: throw IllegalStateException("OCR LiteRT not initialized")
        val decStep = decoderStepLiteRt ?: throw IllegalStateException("OCR LiteRT not initialized")
        require(hidden.size == MangaOcrEngine.HIDDEN_SEQ * MangaOcrEngine.HIDDEN_DIM) { "OCR encoder hidden size ${hidden.size}" }

        MangaOcrEngine.transposeHiddenToStepLayout(hidden, stepHiddenScratch)
        decStep.input("encoder_hidden_states").writeFloat(stepHiddenScratch)

        decInit.input("encoder_hidden_states").writeFloat(hidden)
        decInit.input("input_ids").writeLong(longArrayOf(MangaOcrEngine.START_TOKEN.toLong()))
        decInit.model.run(
            listOf(decInit.input("encoder_hidden_states"), decInit.input("input_ids")),
            listOf(
                decInit.output("logits"),
                decInit.output("self_k"),
                decInit.output("self_v"),
                decInit.output("cross_k"),
                decInit.output("cross_v"),
            ),
        )
        copyCache(decInit.output("cross_k").readFloat(), crossKScratch)
        copyCache(decInit.output("cross_v").readFloat(), crossVScratch)
        MangaOcrEngine.seedSelfCacheSlot0(decInit.output("self_k").readFloat(), selfKScratch)
        MangaOcrEngine.seedSelfCacheSlot0(decInit.output("self_v").readFloat(), selfVScratch)
        decStep.input("cross_k_cache").writeFloat(crossKScratch)
        decStep.input("cross_v_cache").writeFloat(crossVScratch)

        val initLogits = decInit.output("logits").readFloat()
        var maxIdx = argmax(initLogits)
        if (maxIdx == MangaOcrEngine.END_TOKEN) return ""
        val tokenIds = mutableListOf(maxIdx)

        var pos = 2
        var currentInputId = maxIdx.toLong()
        var currentPositionId = pos.toLong()
        for (stepIdx in 0 until MangaOcrEngine.MAX_GENERATION_LENGTH) {
            if (pos >= MangaOcrEngine.DECODER_POSITION_COUNT) break
            decStep.input("input_ids").writeLong(longArrayOf(currentInputId))
            decStep.input("position_ids").writeLong(longArrayOf(currentPositionId))
            decStep.input("self_k_cache").writeFloat(selfKScratch)
            decStep.input("self_v_cache").writeFloat(selfVScratch)
            decStep.model.run(
                listOf(
                    decStep.input("encoder_hidden_states"),
                    decStep.input("self_k_cache"),
                    decStep.input("self_v_cache"),
                    decStep.input("cross_k_cache"),
                    decStep.input("cross_v_cache"),
                    decStep.input("input_ids"),
                    decStep.input("position_ids"),
                ),
                listOf(
                    decStep.output("logits"),
                    decStep.output("self_k_slice"),
                    decStep.output("self_v_slice"),
                ),
            )
            MangaOcrEngine.writeCacheSliceAtSlot(decStep.output("self_k_slice").readFloat(), selfKScratch, pos - 1)
            MangaOcrEngine.writeCacheSliceAtSlot(decStep.output("self_v_slice").readFloat(), selfVScratch, pos - 1)
            maxIdx = argmax(decStep.output("logits").readFloat())
            if (maxIdx == MangaOcrEngine.END_TOKEN) break
            tokenIds.add(maxIdx)
            pos++
            currentInputId = maxIdx.toLong()
            currentPositionId = pos.toLong()
        }

        val text = buildString {
            for (tokenId in tokenIds) {
                if (tokenId < vocab.size) append(vocab[tokenId])
            }
        }
        return MangaOcrEngine.postprocess(text)
    }

    private fun argmax(logits: FloatArray): Int {
        var maxVal = Float.NEGATIVE_INFINITY
        var maxIdx = 0
        for (i in logits.indices) {
            if (logits[i] > maxVal) {
                maxVal = logits[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    private class BindingSpec(
        val key: String,
        val isInt64: Boolean,
        val dims: List<Int>,
    )

    /** One bound CompiledModel whose buffers are addressed by signature key. */
    private class LiteRtModel(
        val model: CompiledModel,
        private val inputs: Map<String, TensorBuffer>,
        private val outputs: Map<String, TensorBuffer>,
    ) : AutoCloseable {
        fun input(key: String): TensorBuffer = checkNotNull(inputs[key]) { "OCR LiteRT input '$key' not bound" }
        fun output(key: String): TensorBuffer = checkNotNull(outputs[key]) { "OCR LiteRT output '$key' not bound" }

        override fun close() {
            (inputs.values + outputs.values).forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    private fun buildLiteRtModel(
        env: Environment,
        modelFile: File,
        numThreads: Int,
        inputBindings: List<BindingSpec>,
        outputBindings: List<BindingSpec>,
    ): LiteRtModel {
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = numThreads)
        }
        val model = CompiledModel.create(modelFile.absolutePath, options, env)
        try {
            val inputs = inputBindings.associate { spec ->
                spec.key to resolveBuffer(model, spec, wantInput = true)
            }
            val outputs = outputBindings.associate { spec ->
                spec.key to resolveBuffer(model, spec, wantInput = false)
            }
            return LiteRtModel(model, inputs, outputs)
        } catch (failure: Throwable) {
            runCatching { model.close() }
            throw failure
        }
    }

    // Signature-key binding with graph-tensor-name fallback; validates dtype +
    // exact dims and fails closed so a k/v-shaped pair can never bind swapped.
    private fun resolveBuffer(model: CompiledModel, spec: BindingSpec, wantInput: Boolean): TensorBuffer {
        val candidates = listOf(spec.key, "serving_default_${spec.key}:0")
        for (name in candidates) {
            val tensorType = try {
                if (wantInput) model.getInputTensorType(name, MangaOcrEngine.SIGNATURE) else model.getOutputTensorType(name, MangaOcrEngine.SIGNATURE)
            } catch (_: Throwable) {
                continue
            }
            val elementTypeOk = if (spec.isInt64) {
                tensorType.elementType == TensorType.ElementType.INT64
            } else {
                tensorType.elementType == TensorType.ElementType.FLOAT
            }
            val dims = tensorType.layout?.dimensions
            if (elementTypeOk && dims != null && dims == spec.dims) {
                return if (wantInput) {
                    model.createInputBuffer(name, MangaOcrEngine.SIGNATURE)
                } else {
                    model.createOutputBuffer(name, MangaOcrEngine.SIGNATURE)
                }
            }
        }
        throw IllegalStateException(
            "OCR LiteRT tensor '${spec.key}' (${spec.dims}, int64=${spec.isInt64}) " +
                "did not resolve under signature '${MangaOcrEngine.SIGNATURE}'",
        )
    }

    private fun loadVocab(vocabFile: File): List<String> =
        BufferedReader(InputStreamReader(vocabFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }

    private fun copyCache(src: FloatArray, dst: FloatArray) {
        require(src.size == dst.size) { "OCR LiteRT cache size mismatch: ${src.size} vs ${dst.size}" }
        System.arraycopy(src, 0, dst, 0, src.size)
    }

    override fun close() {
        encoderLiteRt?.close()
        decoderInitLiteRt?.close()
        decoderStepLiteRt?.close()
        encoderLiteRt = null
        decoderInitLiteRt = null
        decoderStepLiteRt = null
        liteRtEnv?.close()
        liteRtEnv = null
    }
}
