package eu.kanade.translation.engines.translator.fumeto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.nio.LongBuffer

internal interface FumetoEncoder : Closeable {
    fun encode(source: LongArray): FumetoEncoderOutput
}

internal interface FumetoEncoderOutput : Closeable {
    val memoryBias: OnnxTensor
    val crossAttention: List<OnnxTensor>
}

internal class FumetoOnnxEncoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : FumetoEncoder {
    init {
        logcat(LogPriority.INFO) { "Fumeto encoder inputs=${session.inputNames} outputs=${session.outputNames}" }
    }

    override fun encode(source: LongArray): FumetoEncoderOutput {
        val shape = longArrayOf(1L, source.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(source), shape).use { inputIds ->
            val mask = LongArray(source.size) { 1L }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).use { attentionMask ->
                val result = session.run(mapOf("input_ids" to inputIds, "attention_mask" to attentionMask))
                return try {
                    OnnxEncoderOutput(result)
                } catch (failure: Exception) {
                    result.close()
                    throw failure
                }
            }
        }
    }

    override fun close() = session.close()

    private class OnnxEncoderOutput(private val result: OrtSession.Result) : FumetoEncoderOutput {
        override val memoryBias = result[0] as OnnxTensor
        override val crossAttention = List(8) { result[it + 1] as OnnxTensor }
        override fun close() = result.close()
    }
}
