package eu.kanade.translation.engines.vision

import ai.onnxruntime.OrtSession
import com.google.ai.edge.litert.Environment
import eu.kanade.translation.engines.runtime.litert.LiteRtVisionRunner
import eu.kanade.translation.processing.memory.DirectBufferPool
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer

/**
 * Common runtime-phase session lifecycle, direct buffer pool, memory reclamation,
 * and fallback retry shared across vision models (OnnxPageTextDetector, OnnxPanelDetector,
 * OnnxBubbleSegmenter).
 */
internal class VisionModelRunner(
    val tag: String,
    val inputFloatsCount: Int = DEFAULT_INPUT_FLOATS,
    maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
) : AutoCloseable {

    var session: OrtSession? = null
    var liteRtRunner: LiteRtVisionRunner? = null
    var liteRtEnvironment: Environment? = null
    var liteRtInputFloats: FloatArray? = null

    var executionProviderLabel: String = "uninitialized"

    val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = inputFloatsCount * Float.SIZE_BYTES,
        maxPoolSize = maxPoolSize,
    )

    fun acquireBuffer(): FloatBuffer = inputBufferPool.acquire()

    fun releaseBuffer(buffer: FloatBuffer) {
        inputBufferPool.release(buffer)
    }

    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    fun handleLiteRtFailure(fallbackToOnnx: (() -> Unit)?, failure: Throwable) {
        if (fallbackToOnnx == null) throw failure
        logcat(LogPriority.WARN, failure) { "$tag LiteRT init failed; using ONNX session" }
        fallbackToOnnx.invoke()
    }

    fun initLiteRt(
        modelFile: File,
        inputCount: Int,
        outputCount: Int,
        preferGpu: Boolean = true,
        numThreads: Int = 4,
        fallbackToOnnx: (() -> Unit)? = null,
    ): LiteRtVisionRunner? {
        logcat(LogPriority.INFO) {
            "$tag LiteRT init: ${modelFile.name} (${modelFile.length()}B) preferGpu=$preferGpu"
        }
        val env = try {
            Environment.create()
        } catch (failure: Throwable) {
            handleLiteRtFailure(fallbackToOnnx, failure)
            return null
        }
        return try {
            val runner = LiteRtVisionRunner(
                modelFile = modelFile,
                inputCount = inputCount,
                outputCount = outputCount,
                preferGpu = preferGpu,
                numThreads = numThreads,
                onGpuFallback = { executionProviderLabel = "litert_xnnpack" },
                env = env,
            )
            liteRtRunner = runner
            liteRtEnvironment = env
            liteRtInputFloats = FloatArray(inputFloatsCount)
            executionProviderLabel = runner.executionProviderLabel
            logcat(LogPriority.INFO) { "$tag LiteRT ready: provider=$executionProviderLabel" }
            runner
        } catch (failure: Throwable) {
            runCatching { env.close() }
            handleLiteRtFailure(fallbackToOnnx, failure)
            null
        }
    }

    override fun close() {
        liteRtRunner?.close()
        liteRtRunner = null
        liteRtEnvironment?.close()
        liteRtEnvironment = null
        liteRtInputFloats = null
        session?.close()
        session = null
        inputBufferPool.clear()
    }

    companion object {
        const val DEFAULT_INPUT_FLOATS = 3 * 640 * 640
        const val DEFAULT_MAX_POOL_SIZE = 2
    }
}
