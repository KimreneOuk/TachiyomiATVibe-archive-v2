package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import eu.kanade.translation.processing.memory.BitmapPool
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Opt-in fixed-shape LiteRT backend for [PaddleOcrV6DetEngine].
 */
internal class PaddleOcrV6DetLiteRtRunner : Closeable {

    private var liteRtEnvironment: Environment? = null
    private var liteRtModel: CompiledModel? = null
    private var liteRtInputBuffer: TensorBuffer? = null
    private var liteRtOutputBuffer: TensorBuffer? = null
    private var liteRtInputFloats: FloatArray? = null
    private var liteRtModelFile: File? = null
    private var liteRtPreferGpu: Boolean = true
    private var liteRtAllowXnnpackFallback: Boolean = true
    private var liteRtNumThreads: Int = DEFAULT_LITERT_CPU_THREADS
    private var liteRtGpuActive: Boolean = false
    private val liteRtZeroRow = FloatArray(INPUT_ROW_ELEMENTS)
    private var scratchPixels: IntArray? = null

    val isInitialized: Boolean
        get() = liteRtModel != null

    var lastTiming: PaddleOcrV6DetTiming? = null
        private set

    var executionProviderLabel: String = "litert_uninitialized"
        private set

    var requestedProviderLabel: String = "litert_xnnpack"
        private set

    @Synchronized
    fun initialize(
        modelFile: File,
        preferGpu: Boolean = true,
        allowXnnpackFallback: Boolean = true,
        numThreads: Int = DEFAULT_LITERT_CPU_THREADS,
    ) {
        close()
        liteRtModelFile = modelFile
        liteRtPreferGpu = preferGpu
        liteRtAllowXnnpackFallback = allowXnnpackFallback
        liteRtNumThreads = numThreads
        requestedProviderLabel = if (preferGpu) "litert_gpu_delegate" else "litert_xnnpack"
        liteRtEnvironment = Environment.create()
        try {
            if (preferGpu) {
                try {
                    initializeLiteRtGpu(modelFile)
                } catch (failure: Throwable) {
                    closeLiteRtModel()
                    if (!allowXnnpackFallback) throw failure
                    liteRtPreferGpu = false
                    logcat(LogPriority.WARN, failure) {
                        "PaddleOCR detector LiteRT GPU init failed; falling back to CPU"
                    }
                    initializeLiteRtCpu(modelFile)
                }
            } else {
                initializeLiteRtCpu(modelFile)
            }
            allocateLiteRtBuffers()
            logcat(LogPriority.INFO) {
                "PaddleOCR detector LiteRT loaded: model=${modelFile.absolutePath} " +
                    "provider=$executionProviderLabel input=[1,$MAX_DET_SIZE,$MAX_DET_SIZE,3]"
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun detectLines(
        crop: Bitmap,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
    ): List<TextLine> {
        val w = crop.width
        val h = crop.height
        if (w <= 0 || h <= 0) return emptyList()
        val totalStart = System.nanoTime()
        if (liteRtInputFloats == null) allocateLiteRtBuffers()
        val inputFloats = checkNotNull(liteRtInputFloats) { "LiteRT detector float input is not initialized" }
        var inBuf = checkNotNull(liteRtInputBuffer) { "LiteRT input buffer is closed" }
        var outBuf = checkNotNull(liteRtOutputBuffer) { "LiteRT output buffer is closed" }
        var activeModel = checkNotNull(liteRtModel) { "LiteRT model is closed" }

        val preprocessStart = System.nanoTime()
        val letterbox = preprocessLiteRt(crop, inputFloats)
        val preprocessMs = (System.nanoTime() - preprocessStart) / 1_000_000.0

        var neuralForwardNanos = 0L
        var recoveryNanos = 0L

        // The demotion scope must span write/read too: a dead GPU delegate can
        // fail the tensor lock on output read while run() itself returns
        // silently, and only a full write->run->read retry produces real lines.
        var probabilityArray: FloatArray
        try {
            inBuf.writeFloat(inputFloats)
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
            probabilityArray = outBuf.readFloat()
        } catch (failure: Throwable) {
            if (!liteRtGpuActive || !liteRtAllowXnnpackFallback) throw failure
            val recoveryStart = System.nanoTime()
            logcat(LogPriority.WARN, failure) {
                "PaddleOCR detector LiteRT GPU invoke failed; retrying with CPU"
            }
            liteRtPreferGpu = false
            closeLiteRtModel()
            initializeLiteRtCpu(checkNotNull(liteRtModelFile))
            inBuf = checkNotNull(liteRtInputBuffer)
            outBuf = checkNotNull(liteRtOutputBuffer)
            activeModel = checkNotNull(liteRtModel)
            inBuf.writeFloat(inputFloats)
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                val retryForwardNanos = System.nanoTime() - forwardStart
                neuralForwardNanos += retryForwardNanos
                recoveryNanos = (System.nanoTime() - recoveryStart - retryForwardNanos).coerceAtLeast(0L)
            }
            probabilityArray = outBuf.readFloat()
        }

        val postprocessStart = System.nanoTime()
        val mapLines = DbPostProcess.detectLines(
            probabilityMap = probabilityArray,
            width = MAX_DET_SIZE,
            height = MAX_DET_SIZE,
            threshold = thresh,
            boxThreshold = boxThresh,
            unclipRatio = unclipRatio,
        )
        val cropLines = ArrayList<TextLine>(mapLines.size)
        mapLines.forEach { line ->
            val mapped = backProjectLetterboxed(line.bbox, letterbox, w, h)
            if (mapped[2] > mapped[0] && mapped[3] > mapped[1]) {
                cropLines += TextLine(mapped, line.meanScore)
            }
        }
        val dbPostProcessMs = (System.nanoTime() - postprocessStart) / 1_000_000.0
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000.0
        val neuralForwardMs = neuralForwardNanos / 1_000_000.0
        lastTiming = PaddleOcrV6DetTiming(
            preprocessMs = preprocessMs,
            neuralForwardMs = neuralForwardMs,
            dbPostProcessMs = dbPostProcessMs,
            totalMs = totalMs,
        )
        if (isDiagnosticsEnabled()) {
            logcat(LogPriority.INFO) {
                "[paddle_det] total=${totalMs}ms preprocess=${preprocessMs}ms " +
                    "neuralForward=${neuralForwardMs}ms " +
                    "requestedProvider=$requestedProviderLabel provider=$executionProviderLabel " +
                    "crop=${w}x$h map=${MAX_DET_SIZE}x$MAX_DET_SIZE lines=${cropLines.size}"
            }
        }
        return cropLines
    }

    private fun preprocessLiteRt(crop: Bitmap, out: FloatArray): LiteRtLetterbox {
        val scale = min(MAX_DET_SIZE.toFloat() / crop.width, MAX_DET_SIZE.toFloat() / crop.height)
        val resizedW = (crop.width * scale).toInt().coerceIn(1, MAX_DET_SIZE)
        val resizedH = (crop.height * scale).toInt().coerceIn(1, MAX_DET_SIZE)
        val offsetX = (MAX_DET_SIZE - resizedW) / 2
        val offsetY = (MAX_DET_SIZE - resizedH) / 2
        var resized: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(resizedW, resizedH)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, resizedW.toFloat(), resizedH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = getScratchPixels(resizedW * resizedH)
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

            val leftElements = offsetX * CHANNELS
            val rightElements = (MAX_DET_SIZE - offsetX - resizedW) * CHANNELS
            val fullRowElements = INPUT_ROW_ELEMENTS
            var outIndex = 0
            for (canvasY in 0 until MAX_DET_SIZE) {
                val contentY = canvasY - offsetY
                if (contentY !in 0 until resizedH) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, fullRowElements)
                    outIndex += fullRowElements
                    continue
                }

                if (leftElements > 0) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, leftElements)
                    outIndex += leftElements
                }
                var sourceIndex = contentY * resizedW
                for (x in 0 until resizedW) {
                    val pixel = pixels[sourceIndex++]
                    out[outIndex++] = normalizeR(pixel shr 16 and 0xFF)
                    out[outIndex++] = normalizeG(pixel shr 8 and 0xFF)
                    out[outIndex++] = normalizeB(pixel and 0xFF)
                }
                if (rightElements > 0) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, rightElements)
                    outIndex += rightElements
                }
            }
            return LiteRtLetterbox(resizedW, resizedH, offsetX, offsetY)
        } finally {
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    private fun backProjectLetterboxed(
        bbox: IntArray,
        letterbox: LiteRtLetterbox,
        cropWidth: Int,
        cropHeight: Int,
    ): IntArray {
        val scaleX = cropWidth.toFloat() / letterbox.resizedWidth
        val scaleY = cropHeight.toFloat() / letterbox.resizedHeight
        val x1 = ((bbox[0] - letterbox.offsetX) * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y1 = ((bbox[1] - letterbox.offsetY) * scaleY).toInt().coerceIn(0, cropHeight - 1)
        val x2 = ((bbox[2] - letterbox.offsetX) * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y2 = ((bbox[3] - letterbox.offsetY) * scaleY).toInt().coerceIn(0, cropHeight - 1)
        return intArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    private fun initializeLiteRtGpu(modelFile: File) {
        val env = checkNotNull(liteRtEnvironment) { "LiteRT Environment is not initialized" }
        val options = CompiledModel.Options(Accelerator.GPU)
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        liteRtInputBuffer = created.createInputBuffers()[0]
        liteRtOutputBuffer = created.createOutputBuffers()[0]
        liteRtModel = created
        liteRtGpuActive = true
        liteRtModelFile = modelFile
        executionProviderLabel = "litert_gpu_delegate"
    }

    private fun initializeLiteRtCpu(modelFile: File) {
        val env = checkNotNull(liteRtEnvironment) { "LiteRT Environment is not initialized" }
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = liteRtNumThreads)
        }
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        liteRtInputBuffer = created.createInputBuffers()[0]
        liteRtOutputBuffer = created.createOutputBuffers()[0]
        liteRtModel = created
        liteRtGpuActive = false
        liteRtModelFile = modelFile
        executionProviderLabel = "litert_xnnpack"
    }

    private fun allocateLiteRtBuffers() {
        if (liteRtInputFloats == null) {
            liteRtInputFloats = FloatArray(1 * MAX_DET_SIZE * MAX_DET_SIZE * CHANNELS)
        }
    }

    private fun closeLiteRtModel() {
        try {
            liteRtInputBuffer?.close()
        } catch (_: Throwable) {
        }
        liteRtInputBuffer = null
        try {
            liteRtOutputBuffer?.close()
        } catch (_: Throwable) {
        }
        liteRtOutputBuffer = null
        try {
            liteRtModel?.close()
        } catch (_: Throwable) {
        }
        liteRtModel = null
        liteRtGpuActive = false
    }

    override fun close() {
        closeLiteRtModel()
        liteRtInputFloats = null
        liteRtModelFile = null
        try {
            liteRtEnvironment?.close()
        } catch (_: Throwable) {
        }
        liteRtEnvironment = null
        scratchPixels = null
    }

    fun reclaimPooledMemory() {
        scratchPixels = null
    }

    private fun getScratchPixels(size: Int): IntArray {
        val existing = scratchPixels
        if (existing != null && existing.size >= size) return existing
        return IntArray(size).also { scratchPixels = it }
    }

    private fun normalizeB(v: Int) = (v / 255f - MEAN_B) / STD_B
    private fun normalizeG(v: Int) = (v / 255f - MEAN_G) / STD_G
    private fun normalizeR(v: Int) = (v / 255f - MEAN_R) / STD_R

    private data class LiteRtLetterbox(
        val resizedWidth: Int,
        val resizedHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    companion object {
        const val MAX_DET_SIZE = 960
        private const val CHANNELS = 3
        private const val INPUT_ROW_ELEMENTS = MAX_DET_SIZE * CHANNELS
        const val DEFAULT_LITERT_CPU_THREADS = 4

        // ImageNet normalize (RGB) from PP-OCRv6_manga v0.2
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        private fun isDiagnosticsEnabled(): Boolean = OcrDiagnostics.isEnabled()
    }
}
