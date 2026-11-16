package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import eu.kanade.translation.models.PackLease
import eu.kanade.translation.processing.memory.BitmapPool
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * MangaOCR engine for Japanese text recognition.
 * Coordinates between the LiteRT (CPU XNNPACK) runner and the ONNX runner.
 */
class MangaOcrEngine : RoiOcrEngine {

    private var liteRtRunner: MangaOcrLiteRtRunner? = null
    private var onnxRunner: MangaOcrOnnxRunner? = null

    /**
     * Lease held while this engine runs a runtime-installed pack version.
     * Released in [close]; deletion and version switches are refused while held.
     */
    internal var packLease: PackLease? = null

    internal fun attachPackLease(lease: PackLease) {
        packLease = lease
    }

    /**
     * Always CPU: the autoregressive decoder loop runs many tiny per-step
     * graphlets where NPU dispatch latency would dominate. Explicit override
     * so perf logging composes it like the other engines.
     */
    override val executionProviderLabel: String = "cpu"

    fun initialize(
        encoderFile: File,
        decoderInitFile: File,
        decoderStepFile: File,
        vocabFile: File,
    ) {
        liteRtRunner?.close()
        liteRtRunner = null
        val onnx = MangaOcrOnnxRunner()
        onnx.initialize(encoderFile, decoderInitFile, decoderStepFile, vocabFile)
        onnxRunner = onnx
    }

    /**
     * Initializes the LiteRT backend (fp32 encoder + decoders, CPU XNNPACK).
     * Buffers bind by SIGNATURE key -- the flatbuffer subgraph order permutes
     * the init outputs (cross before logits) and self_k/self_v are
     * shape-identical, so index binding could silently swap them.
     */
    fun initializeLiteRt(
        encoderFile: File,
        decoderInitFile: File,
        decoderStepFile: File,
        vocabFile: File,
        numThreads: Int = DEFAULT_DECODER_THREAD_COUNT,
        fallbackInitializer: (() -> Unit)? = null,
    ) {
        try {
            onnxRunner?.close()
            onnxRunner = null
            val runner = MangaOcrLiteRtRunner()
            runner.initialize(encoderFile, decoderInitFile, decoderStepFile, vocabFile, numThreads)
            liteRtRunner = runner
        } catch (failure: Throwable) {
            val fallback = fallbackInitializer ?: throw failure
            logcat(LogPriority.WARN, failure) { "OCR LiteRT init failed; falling back to ONNX sessions" }
            fallback()
        }
    }

    override suspend fun recognize(cropBitmap: Bitmap): String {
        val liteRt = liteRtRunner
        if (liteRt != null) return liteRt.recognize(cropBitmap)
        val onnx = onnxRunner ?: throw IllegalStateException("OCR not initialized")
        return onnx.recognize(cropBitmap)
    }

    override suspend fun recognizeBatch(crops: List<Bitmap>): List<String> {
        if (crops.isEmpty()) return emptyList()
        return crops.map { recognize(it) }
    }

    override fun reclaimPooledMemory() {
        onnxRunner?.reclaimPooledMemory()
    }

    override fun forceReleaseNativeBuffers() {
        onnxRunner?.forceReleaseNativeBuffers()
    }

    override fun close() {
        liteRtRunner?.close()
        liteRtRunner = null
        onnxRunner?.close()
        onnxRunner = null
        packLease?.release()
        packLease = null
    }

    internal fun writeCacheAtSlot(updatedTensor: OnnxTensor, dstBuf: FloatBuffer, slot: Int) {
        Companion.writeCacheAtSlot(updatedTensor, dstBuf, slot)
    }

    companion object {
        internal const val MAX_GENERATION_LENGTH = 300
        internal const val START_TOKEN = 2
        internal const val END_TOKEN = 3
        internal const val MAX_LEN = 256

        // real decode ceiling. The gpt2 position-embedding Gather
        // (node_embedding_1) has 128 entries; pos==128 overflows it and crashes
        // the chapter. MAX_LEN (256) is only the KV-cache dim, not a safe bound.
        internal const val DECODER_POSITION_COUNT = 128

        // LiteRT flatbuffer contract (sha-stamped deployment artifacts):
        // hidden [1,196,256]; decoder_step wants the TRANSPOSED [1,256,196]
        // view plus [4,4,L,64,1] caches -- a stride-preserving reshape of the
        // host [4,1,4,L,64] caches (batch dim 1 drops, trailing 1 appends).
        internal const val HIDDEN_SEQ = 196
        internal const val HIDDEN_DIM = 256
        internal const val VOCAB_SIZE = 9415
        internal const val SIGNATURE = "serving_default"

        internal val DEFAULT_DECODER_THREAD_COUNT: Int
            get() = maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 2, 2))

        internal fun preprocess(cropBitmap: Bitmap): IntArray {
            val w = cropBitmap.width
            val h = cropBitmap.height

            if (max(w, h) == 0) return IntArray(0)

            var grayBitmap: Bitmap? = null
            var resized: Bitmap? = null
            var padded: Bitmap? = null
            try {
                grayBitmap = BitmapPool.getARGB8888(w, h)
                val grayCanvas = Canvas(grayBitmap)
                val grayPaint = Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        ColorMatrix().apply { setSaturation(0f) },
                    )
                }
                grayCanvas.drawBitmap(cropBitmap, 0f, 0f, grayPaint)

                val ratio = 224.0f / max(w, h)
                val newW = (w * ratio).toInt().coerceAtLeast(1)
                val newH = (h * ratio).toInt().coerceAtLeast(1)

                resized = BitmapPool.getARGB8888(newW, newH)
                val resizedCanvas = Canvas(resized)
                resizedCanvas.drawBitmap(grayBitmap, null, android.graphics.RectF(0f, 0f, newW.toFloat(), newH.toFloat()), null)

                padded = BitmapPool.getARGB8888(224, 224)
                padded.eraseColor(Color.WHITE)
                val padCanvas = Canvas(padded)
                padCanvas.drawBitmap(resized, ((224 - newW) / 2).toFloat(), ((224 - newH) / 2).toFloat(), null)

                val pixels = IntArray(224 * 224)
                padded.getPixels(pixels, 0, 224, 0, 0, 224, 224)

                return pixels
            } finally {
                if (padded != null) BitmapPool.putARGB8888(padded)
                if (resized != null) BitmapPool.putARGB8888(resized)
                if (grayBitmap != null) BitmapPool.putARGB8888(grayBitmap)
            }
        }

        internal fun postprocess(text: String): String {
            var result = text.replace("\\s".toRegex(), "")
            result = result.replace("\u2026", "...")
            result = result.replace("[・.]{2,}".toRegex()) { match ->
                ".".repeat(match.value.length)
            }
            result = result.replace("([A-Za-z])0([A-Za-z])".toRegex(), "$1o$2")
            result = result.replace("N[0\u00b0\u00ba\u02da\u2070]".toRegex(), "")
            result = result.replace("\u2116", "")
            return result
        }

        internal fun writeCacheAtSlot(updatedTensor: OnnxTensor, dstBuf: FloatBuffer, slot: Int) {
            val srcBuf = updatedTensor.floatBuffer
            val totalElements = dstBuf.capacity()
            val cacheShape4 = MAX_LEN * 64

            for (i in 0 until 4) {
                for (j in 0 until 1) {
                    for (k in 0 until 4) {
                        val srcOffset = ((i * 1 + j) * 4 + k) * 64
                        val dstOffset = ((i * 1 + j) * 4 + k) * cacheShape4 + slot * 64
                        if (dstOffset + 64 <= totalElements) {
                            for (s in 0 until 64) {
                                dstBuf.put(dstOffset + s, srcBuf.get(srcOffset + s))
                            }
                        }
                    }
                }
            }
        }

        /**
         * Writes one ARGB pixel's normalized RGB into interleaved NHWC layout
         * at [pixelIndex]. Pure; unit-tested.
         */
        internal fun writeNormalizedNhwcPixel(pixel: Int, destination: FloatArray, pixelIndex: Int) {
            val base = 3 * pixelIndex
            destination[base] = ((pixel shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f
            destination[base + 1] = ((pixel shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f
            destination[base + 2] = ((pixel and 0xFF) / 255.0f - 0.5f) / 0.5f
        }

        internal fun writeNormalizedNhwc(sourcePixels: IntArray, destination: FloatArray) {
            require(sourcePixels.size == 224 * 224) {
                "MangaOCR preprocessing expected ${224 * 224} pixels, got ${sourcePixels.size}"
            }
            require(destination.size == 3 * 224 * 224) {
                "MangaOCR NHWC input buffer is too small: ${destination.size}"
            }
            for (i in sourcePixels.indices) {
                writeNormalizedNhwcPixel(sourcePixels[i], destination, i)
            }
        }

        /** [1,196,256] encoder output -> [1,256,196] decoder_step input. Pure; unit-tested. */
        internal fun transposeHiddenToStepLayout(hidden: FloatArray, destination: FloatArray) {
            require(hidden.size == HIDDEN_SEQ * HIDDEN_DIM) { "hidden size ${hidden.size}" }
            for (s in 0 until HIDDEN_SEQ) {
                for (d in 0 until HIDDEN_DIM) {
                    destination[d * HIDDEN_SEQ + s] = hidden[s * HIDDEN_DIM + d]
                }
            }
        }

        /** Copies init self-cache [4,1,4,1,64] into host slot 0. Pure; unit-tested. */
        internal fun seedSelfCacheSlot0(src: FloatArray, dst: FloatArray) {
            require(src.size == 4 * 4 * 64) { "self cache seed size ${src.size}" }
            for (slice in 0 until 4 * 4) {
                System.arraycopy(src, slice * 64, dst, slice * MAX_LEN * 64, 64)
            }
        }

        /** Writes one step's [4,1,4,1,64] KV slice into host slot [slot]. Pure; unit-tested. */
        internal fun writeCacheSliceAtSlot(src: FloatArray, dst: FloatArray, slot: Int) {
            require(src.size == 4 * 4 * 64) { "self cache slice size ${src.size}" }
            require(slot in 0 until MAX_LEN) { "slot $slot out of cache range" }
            for (slice in 0 until 4 * 4) {
                System.arraycopy(src, slice * 64, dst, slice * MAX_LEN * 64 + slot * 64, 64)
            }
        }

        internal fun writeNormalizedChw(sourcePixels: IntArray, destination: FloatBuffer, offset: Int = 0) {
            val channelSize = 224 * 224
            require(sourcePixels.size == channelSize) {
                "MangaOCR preprocessing expected $channelSize pixels, got ${sourcePixels.size}"
            }
            require(destination.capacity() >= offset + 3 * channelSize) {
                "MangaOCR input buffer is too small: ${destination.capacity()} < ${offset + 3 * channelSize}"
            }
            for (c in 0 until 3) {
                val channelOffset = offset + c * channelSize
                for (i in sourcePixels.indices) {
                    val pixel = sourcePixels[i]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        else -> (pixel and 0xFF) / 255.0f
                    }
                    destination.put(channelOffset + i, (channelValue - 0.5f) / 0.5f)
                }
            }
        }

        internal fun isDiagnosticsEnabled(): Boolean = OcrDiagnostics.isEnabled()
    }
}
