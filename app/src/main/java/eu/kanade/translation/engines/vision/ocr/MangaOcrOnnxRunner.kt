package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import eu.kanade.translation.processing.memory.DirectBufferPool
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Autoregressive ONNX runner for [MangaOcrEngine].
 */
internal class MangaOcrOnnxRunner : AutoCloseable {

    private var encoderSession: OrtSession? = null
    private var decoderInitSession: OrtSession? = null
    private var decoderStepSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var loggedTensorShapes = false

    private val decoderThreadCount = maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 2, 2))
    private val kCachePool = DirectBufferPool(4 * 1 * 4 * MangaOcrEngine.MAX_LEN * 64 * 4, maxPoolSize = 2)
    private val vCachePool = DirectBufferPool(4 * 1 * 4 * MangaOcrEngine.MAX_LEN * 64 * 4, maxPoolSize = 2)

    // pooled DIRECT buffer for the encoder input. FloatBuffer.wrap
    // is heap-backed, forcing ORT to allocate a native copy per call that leaks
    // across recognize() calls (ORT #16937); a direct buffer is used in place.
    private val inputPixelPool = DirectBufferPool(3 * 224 * 224 * 4, maxPoolSize = 2)

    val isInitialized: Boolean
        get() = encoderSession != null

    fun initialize(
        encoderFile: File,
        decoderInitFile: File,
        decoderStepFile: File,
        vocabFile: File,
    ) {
        logcat(LogPriority.INFO) {
            "OCR init: encoder=${encoderFile.absolutePath} (${encoderFile.length()}B exists=${encoderFile.exists()}), " +
                "decoderInit=${decoderInitFile.absolutePath} (${decoderInitFile.length()}B exists=${decoderInitFile.exists()}), " +
                "decoderStep=${decoderStepFile.absolutePath} (${decoderStepFile.length()}B exists=${decoderStepFile.exists()}), " +
                "vocab=${vocabFile.absolutePath} (${vocabFile.length()}B exists=${vocabFile.exists()})"
        }
        // manga-ocr runs an autoregressive decoder loop on many
        // tiny per-step graphlets. Keep it on the shared CPU-only ONNX runtime.
        val encoderOpts = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false)
        try {
            encoderSession = OnnxRuntimeProvider.environment.createSession(encoderFile.absolutePath, encoderOpts)
            logcat(LogPriority.INFO) { "OCR init: encoder session created OK, inputs=${encoderSession?.inputNames}" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR init: encoder session FAILED for ${encoderFile.absolutePath}" }
            throw e
        } finally {
            encoderOpts.close()
        }

        val decoderOpts = OnnxRuntimeProvider.createSessionOptions { opts ->
            opts.setIntraOpNumThreads(decoderThreadCount)
        }
        try {
            decoderInitSession = OnnxRuntimeProvider.environment.createSession(decoderInitFile.absolutePath, decoderOpts)
            logcat(LogPriority.INFO) { "OCR init: decoder_init session created OK" }
            decoderStepSession = OnnxRuntimeProvider.environment.createSession(decoderStepFile.absolutePath, decoderOpts)
            logcat(LogPriority.INFO) { "OCR init: decoder_step session created OK" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR init: decoder session FAILED" }
            throw e
        } finally {
            decoderOpts.close()
        }

        vocab = BufferedReader(InputStreamReader(vocabFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }

        logcat(LogPriority.INFO) {
            "OCR engine loaded (vocab=${vocab.size} tokens, " +
                "encoderInputs=${encoderSession?.inputNames}, encoderOutputs=${encoderSession?.outputNames}, " +
                "decoderInitInputs=${decoderInitSession?.inputNames}, decoderInitOutputs=${decoderInitSession?.outputNames}, " +
                "decoderStepInputs=${decoderStepSession?.inputNames}, decoderStepOutputs=${decoderStepSession?.outputNames})"
        }
    }

    suspend fun recognize(cropBitmap: Bitmap): String {
        val enc = encoderSession ?: throw IllegalStateException("OCR not initialized")

        val t0 = System.nanoTime()
        val pixels = MangaOcrEngine.preprocess(cropBitmap)

        if (pixels.isEmpty()) return ""

        val t1 = System.nanoTime()

        var inputTensor: OnnxTensor? = null
        var encResult: OrtSession.Result? = null
        // declared nullable outside try and assigned inside, so the
        // pool acquire/release stays balanced even if a later line throws.
        var pixelBuffer: java.nio.FloatBuffer? = null

        try {
            // pooled DIRECT buffer avoids the per-call native copy
            // (see inputPixelPool). The one-time 600 KiB memcpy is far cheaper
            // than the leak.
            pixelBuffer = inputPixelPool.acquire().apply {
                clear()
                MangaOcrEngine.writeNormalizedChw(pixels, this)
                limit(3 * 224 * 224)
                position(0)
            }
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                pixelBuffer,
                longArrayOf(1, 3, 224, 224),
            )
            val inputTensorValue = inputTensor!!
            encResult = enc.run(mapOf(enc.inputNames.iterator().next() to inputTensorValue))
            val encHiddenTensor = encResult[0] as OnnxTensor

            val t2 = System.nanoTime()
            val result = decodeHiddenState(encHiddenTensor)
            val t3 = System.nanoTime()

            // Gate the per-ROI (30+/page) timing log behind the opt-in pref.
            if (MangaOcrEngine.isDiagnosticsEnabled()) {
                logcat(LogPriority.INFO) {
                    "[ocr] total=${(t3 - t0) / 1_000_000.0}ms " +
                        "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                        "encoder=${(t2 - t1) / 1_000_000.0}ms " +
                        "decoder=${(t3 - t2) / 1_000_000.0}ms"
                }
            }
            return result
        } finally {
            inputTensor?.close()
            encResult?.close()
            pixelBuffer?.let { inputPixelPool.release(it) }
        }
    }

    private fun decodeHiddenState(encHiddenTensor: OnnxTensor): String {
        val decInit = decoderInitSession ?: throw IllegalStateException("OCR not initialized")
        val decStep = decoderStepSession ?: throw IllegalStateException("OCR not initialized")

        var startIdsTensor: OnnxTensor? = null
        var initResult: OrtSession.Result? = null

        var selfKCacheTensor: OnnxTensor? = null
        var selfVCacheTensor: OnnxTensor? = null
        var stepInputIdsTensor: OnnxTensor? = null
        var stepPositionIdsTensor: OnnxTensor? = null
        var selfKCacheBuf: java.nio.FloatBuffer? = null
        var selfVCacheBuf: java.nio.FloatBuffer? = null

        try {
            val startIdsBuf = java.nio.ByteBuffer.allocateDirect(8)
                .order(java.nio.ByteOrder.nativeOrder())
                .asLongBuffer()
            startIdsBuf.put(0, MangaOcrEngine.START_TOKEN.toLong())
            startIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                startIdsBuf,
                longArrayOf(1, 1),
            )

            val initInputs = mapOf(
                "encoder_hidden_states" to encHiddenTensor,
                "input_ids" to startIdsTensor!!,
            )
            initResult = decInit.run(initInputs)

            val crossK = initResult[3] as OnnxTensor
            val crossV = initResult[4] as OnnxTensor

            selfKCacheBuf = kCachePool.acquire()
            selfVCacheBuf = vCachePool.acquire()

            val selfKInit = initResult[1] as OnnxTensor
            val selfVInit = initResult[2] as OnnxTensor
            copyInitToCache(selfKInit, selfKCacheBuf!!)
            copyInitToCache(selfVInit, selfVCacheBuf!!)

            if (!loggedTensorShapes) {
                loggedTensorShapes = true
                logcat(LogPriority.INFO) {
                    "OCR tensor shapes: encHidden=${encHiddenTensor.info.shape.contentToString()}, " +
                        "selfKInit=${selfKInit.info.shape.contentToString()}, " +
                        "selfVInit=${selfVInit.info.shape.contentToString()}, " +
                        "crossK=${crossK.info.shape.contentToString()}, crossV=${crossV.info.shape.contentToString()}"
                }
            }

            // N1-5: Consume init logits directly for token 1 (BOS at position 1)
            val initLogitsTensor = initResult[0] as OnnxTensor
            val initLogitsBuf = initLogitsTensor.floatBuffer
            val initVocabSize = initLogitsBuf.remaining()
            var initMaxVal = Float.NEGATIVE_INFINITY
            var initMaxIdx = 0
            for (vi in 0 until initVocabSize) {
                val v = initLogitsBuf.get(vi)
                if (v > initMaxVal) {
                    initMaxVal = v
                    initMaxIdx = vi
                }
            }

            if (initMaxIdx == MangaOcrEngine.END_TOKEN) {
                return ""
            }

            val tokenIds = mutableListOf<Int>()
            tokenIds.add(initMaxIdx)

            selfKCacheTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                selfKCacheBuf,
                longArrayOf(4, 1, 4, MangaOcrEngine.MAX_LEN.toLong(), 64),
            )
            selfVCacheTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                selfVCacheBuf,
                longArrayOf(4, 1, 4, MangaOcrEngine.MAX_LEN.toLong(), 64),
            )

            val stepInputIdsBuf = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
            val stepPositionIdsBuf = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()

            stepInputIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                stepInputIdsBuf,
                longArrayOf(1, 1),
            )
            stepPositionIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                stepPositionIdsBuf,
                longArrayOf(1, 1),
            )

            var pos = 2
            var currentInputId = initMaxIdx.toLong()
            var currentPositionId = pos.toLong()

            val stepInputs = mapOf(
                "encoder_hidden_states" to encHiddenTensor,
                "input_ids" to stepInputIdsTensor!!,
                "position_ids" to stepPositionIdsTensor!!,
                "self_k_cache" to selfKCacheTensor!!,
                "self_v_cache" to selfVCacheTensor!!,
                "cross_k_cache" to crossK,
                "cross_v_cache" to crossV,
            )

            for (stepIdx in 0 until MangaOcrEngine.MAX_GENERATION_LENGTH) {
                // bound pos by the 128-entry position-embedding
                // table, NOT MAX_LEN (256, the KV-cache dim). pos==128 overflows
                // node_embedding_1 -> native Gather throws -> chapter ERROR.
                if (pos >= MangaOcrEngine.DECODER_POSITION_COUNT) break

                stepInputIdsBuf.put(0, currentInputId)
                stepPositionIdsBuf.put(0, currentPositionId)

                var stepResult: OrtSession.Result? = null
                try {
                    stepResult = decStep.run(stepInputs)
                    val logitsTensor = stepResult[0] as OnnxTensor
                    val logitsBuf = logitsTensor.floatBuffer
                    val vocabSize = logitsBuf.remaining()
                    var maxVal = Float.NEGATIVE_INFINITY
                    var maxIdx = 0
                    for (vi in 0 until vocabSize) {
                        val v = logitsBuf.get(vi)
                        if (v > maxVal) {
                            maxVal = v
                            maxIdx = vi
                        }
                    }

                    // The graph convention writes the KV slice at slot = pos - 1.
                    MangaOcrEngine.writeCacheAtSlot(stepResult[1] as OnnxTensor, selfKCacheBuf, pos - 1)
                    MangaOcrEngine.writeCacheAtSlot(stepResult[2] as OnnxTensor, selfVCacheBuf, pos - 1)

                    if (maxIdx == MangaOcrEngine.END_TOKEN) {
                        break
                    }

                    tokenIds.add(maxIdx)
                    pos++
                    currentInputId = maxIdx.toLong()
                    currentPositionId = pos.toLong()
                } finally {
                    stepResult?.close()
                }
            }

            val text = buildString {
                for (tokenId in tokenIds) {
                    if (tokenId < vocab.size) {
                        append(vocab[tokenId])
                    }
                }
            }
            return MangaOcrEngine.postprocess(text)
        } finally {
            startIdsTensor?.close()
            selfKCacheTensor?.close()
            selfVCacheTensor?.close()
            stepInputIdsTensor?.close()
            stepPositionIdsTensor?.close()
            initResult?.close()
            selfKCacheBuf?.let { kCachePool.release(it) }
            selfVCacheBuf?.let { vCachePool.release(it) }
        }
    }

    private fun copyInitToCache(initTensor: OnnxTensor, dstBuf: FloatBuffer) {
        val srcBuf = initTensor.floatBuffer
        val srcShape = initTensor.info.shape
        val sliceSize = (srcShape[3].toInt()) * (srcShape[4].toInt())

        for (i in 0 until 4) {
            for (j in 0 until 1) {
                for (k in 0 until 4) {
                    val srcOffset = ((i * 1 + j) * 4 + k) * sliceSize
                    val dstOffset = ((i * 1 + j) * 4 + k) * MangaOcrEngine.MAX_LEN * 64
                    for (s in 0 until sliceSize) {
                        dstBuf.put(dstOffset + s, srcBuf.get(srcOffset + s))
                    }
                }
            }
        }
    }

    fun reclaimPooledMemory() {
        // Direct buffers are already returned to pools in recognize finally block.
    }

    fun forceReleaseNativeBuffers() {
        kCachePool.clear()
        vCachePool.clear()
        inputPixelPool.clear()
    }

    override fun close() {
        encoderSession?.close()
        decoderInitSession?.close()
        decoderStepSession?.close()
        encoderSession = null
        decoderInitSession = null
        decoderStepSession = null
        kCachePool.clear()
        vCachePool.clear()
        inputPixelPool.clear()
    }
}
