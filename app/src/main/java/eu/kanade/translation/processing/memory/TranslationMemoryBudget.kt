package eu.kanade.translation.processing.memory

import eu.kanade.translation.engines.runtime.EngineMemoryBudget

object TranslationMemoryBudget {
    private const val MAX_FULL_RES_DECODE_PIXELS = 45_000_000L

    enum class DecodeDecisionKind {
        FULL,
        HEAP_CONSTRAINED,
        SOURCE_TOO_LARGE,
    }

    data class DecodeDecision(
        val kind: DecodeDecisionKind,
        val sampleSize: Int,
        val rawBitmapBytes: Long,
        val sampledBitmapBytes: Long,
        val sourcePixels: Long,
        val sampledPixels: Long,
        val snapshot: EngineMemoryBudget.HeapSnapshot,
    )

    fun chooseDecodeDecision(
        width: Int,
        height: Int,
        sourceBytesSize: Long = 0L,
        snapshot: EngineMemoryBudget.HeapSnapshot = EngineMemoryBudget.heapSnapshot(),
    ): DecodeDecision {
        if (width <= 0 || height <= 0) {
            return DecodeDecision(
                kind = DecodeDecisionKind.FULL,
                sampleSize = 1,
                rawBitmapBytes = 0L,
                sampledBitmapBytes = 0L,
                sourcePixels = 0L,
                sampledPixels = 0L,
                snapshot = snapshot,
            )
        }
        val pixels = width.toLong() * height.toLong()
        val rawBitmapBytes = pixels * 4L
        val available = snapshot.availableHeapBytes

        if (pixels <= MAX_FULL_RES_DECODE_PIXELS) {
            return if (rawBitmapBytes <= available * 25L / 100L) {
                DecodeDecision(
                    kind = DecodeDecisionKind.FULL,
                    sampleSize = 1,
                    rawBitmapBytes = rawBitmapBytes,
                    sampledBitmapBytes = rawBitmapBytes,
                    sourcePixels = pixels,
                    sampledPixels = pixels,
                    snapshot = snapshot,
                )
            } else {
                val diagnosticSample = heapDiagnosticSampleSize(pixels, available)
                val sampledPixels = pixels / (diagnosticSample.toLong() * diagnosticSample.toLong())
                DecodeDecision(
                    kind = DecodeDecisionKind.HEAP_CONSTRAINED,
                    sampleSize = diagnosticSample,
                    rawBitmapBytes = rawBitmapBytes,
                    sampledBitmapBytes = sampledPixels * 4L,
                    sourcePixels = pixels,
                    sampledPixels = sampledPixels,
                    snapshot = snapshot,
                )
            }
        }

        val sourceSample = sourceLimitSampleSize(pixels)
        val sampledPixels = pixels / (sourceSample.toLong() * sourceSample.toLong())
        val sampledBytes = sampledPixels * 4L
        return if (sampledBytes <= available * 20L / 100L) {
            DecodeDecision(
                kind = DecodeDecisionKind.SOURCE_TOO_LARGE,
                sampleSize = sourceSample,
                rawBitmapBytes = rawBitmapBytes,
                sampledBitmapBytes = sampledBytes,
                sourcePixels = pixels,
                sampledPixels = sampledPixels,
                snapshot = snapshot,
            )
        } else {
            DecodeDecision(
                kind = DecodeDecisionKind.HEAP_CONSTRAINED,
                sampleSize = sourceSample,
                rawBitmapBytes = rawBitmapBytes,
                sampledBitmapBytes = sampledBytes,
                sourcePixels = pixels,
                sampledPixels = sampledPixels,
                snapshot = snapshot,
            )
        }
    }

    private fun heapDiagnosticSampleSize(pixels: Long, available: Long): Int {
        var sample = 1
        while (true) {
            val sampledPixels = pixels / (sample.toLong() * sample.toLong())
            val sampledBytes = sampledPixels * 4L
            if (sampledBytes <= available * 20L / 100L) return sample
            sample *= 2
        }
    }

    private fun sourceLimitSampleSize(pixels: Long): Int {
        var sample = 1
        while (pixels / (sample.toLong() * sample.toLong()) > MAX_FULL_RES_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }
}
