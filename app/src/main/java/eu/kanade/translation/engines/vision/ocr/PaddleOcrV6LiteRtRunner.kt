package eu.kanade.translation.engines.vision.ocr

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer

internal data class PaddleOcrV6LiteRtRecognition(
    val text: String,
    val confidence: Float,
    val preprocessMs: Double,
    val neuralForwardMs: Double,
    val ctcDecodeMs: Double,
)

internal class PaddleOcrV6LiteRtRunner(
    private val modelFile: File,
    private val expectedWidth: Int,
    private val preferGpu: Boolean,
    private val allowXnnpackFallback: Boolean,
    private val numThreads: Int,
    private val onGpuFallback: () -> Unit,
    private val env: Environment,
) : AutoCloseable {

    private var model: CompiledModel? = null
    private var inputBuffer: TensorBuffer? = null
    private var outputBuffer: TensorBuffer? = null
    private var gpuActive: Boolean = false
    private val inputFloats: FloatArray = FloatArray(1 * PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT * expectedWidth * 3)
    private val timeSteps: Int = expectedWidth / 8
    private val classCount: Int = 18710

    var executionProviderLabel: String = "litert_uninitialized"
        private set

    init {
        try {
            if (preferGpu) {
                try {
                    initializeGpu()
                } catch (failure: Throwable) {
                    closeModel()
                    if (!allowXnnpackFallback) throw failure
                    onGpuFallback()
                    logcat(LogPriority.WARN, failure) {
                        "PaddleOCR LiteRT GPU init failed for width=$expectedWidth; falling back to CPU"
                    }
                    initializeCpu()
                }
            } else {
                initializeCpu()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized
    fun recognize(
        dictionary: List<String>,
        writeInput: (FloatBuffer) -> Unit,
    ): PaddleOcrV6LiteRtRecognition {
        var preprocessNanos = 0L
        var neuralForwardNanos = 0L
        val wrapBuf = FloatBuffer.wrap(inputFloats)
        val preprocessStart = System.nanoTime()
        writeInput(wrapBuf)
        preprocessNanos += System.nanoTime() - preprocessStart

        var inBuf = checkNotNull(inputBuffer) { "Input buffer is closed" }
        var outBuf = checkNotNull(outputBuffer) { "Output buffer is closed" }
        var activeModel = checkNotNull(model) { "Model is closed" }

        inBuf.writeFloat(inputFloats)
        try {
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
        } catch (failure: Throwable) {
            if (!gpuActive || !allowXnnpackFallback) throw failure
            logcat(LogPriority.WARN, failure) {
                "PaddleOCR LiteRT GPU invoke failed for width=$expectedWidth; retrying with CPU"
            }
            onGpuFallback()
            closeModel()
            initializeCpu()
            inBuf = checkNotNull(inputBuffer)
            outBuf = checkNotNull(outputBuffer)
            activeModel = checkNotNull(model)
            inBuf.writeFloat(inputFloats)
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
        }

        val decodeStart = System.nanoTime()
        val outputFloats = outBuf.readFloat()
        val (indices, probabilities) = PaddleCtcDecoder.argmaxWithProbs(
            FloatBuffer.wrap(outputFloats),
            timeSteps,
            classCount,
        )
        val (text, confidence) = PaddleCtcDecoder.decodeWithConf(indices, probabilities, dictionary)
        val ctcDecodeNanos = System.nanoTime() - decodeStart
        return PaddleOcrV6LiteRtRecognition(
            text = text,
            confidence = confidence,
            preprocessMs = preprocessNanos / 1_000_000.0,
            neuralForwardMs = neuralForwardNanos / 1_000_000.0,
            ctcDecodeMs = ctcDecodeNanos / 1_000_000.0,
        )
    }

    private fun initializeGpu() {
        val options = CompiledModel.Options(Accelerator.GPU)
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        val inBuf = created.createInputBuffers()[0]
        val outBuf = created.createOutputBuffers()[0]
        model = created
        inputBuffer = inBuf
        outputBuffer = outBuf
        gpuActive = true
        executionProviderLabel = "litert_gpu_delegate"
    }

    private fun initializeCpu() {
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = numThreads)
        }
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        val inBuf = created.createInputBuffers()[0]
        val outBuf = created.createOutputBuffers()[0]
        model = created
        inputBuffer = inBuf
        outputBuffer = outBuf
        gpuActive = false
        executionProviderLabel = "litert_xnnpack"
    }

    private fun closeModel() {
        try {
            inputBuffer?.close()
        } catch (_: Throwable) {
        }
        inputBuffer = null
        try {
            outputBuffer?.close()
        } catch (_: Throwable) {
        }
        outputBuffer = null
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
        gpuActive = false
    }

    override fun close() {
        closeModel()
    }
}
