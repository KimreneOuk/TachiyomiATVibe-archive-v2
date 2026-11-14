package eu.kanade.translation.processing.memory

import android.content.Context
import coil3.imageLoader
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.processing.memory.TranslationMemoryBudget.DecodeDecision
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import eu.kanade.translation.processing.memory.BitmapPool

/**
 * Applies memory-budget decisions before bitmap decode and native analysis.
 * The recognition engine is supplied by the pipeline when needed.
 */
internal object MemoryGovernance {

    fun forceReleaseNativeBuffers(recognitionEngine: () -> PageRecognitionEngine) {
        try {
            recognitionEngine().forceReleaseNativeBuffers()
        } catch (_: Exception) {}
    }

    fun reclaimTranslationMemory(
        context: Context,
        recognitionEngine: () -> PageRecognitionEngine,
        reason: String,
        trimImageCache: Boolean,
    ) {
        val before = EngineMemoryBudget.heapSnapshot()
        BitmapPool.releaseAll()
        try {
            recognitionEngine().forceReleaseNativeBuffers()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "forceReleaseNativeBuffers threw during memory reclaim: $reason" }
        }
        if (trimImageCache) {
            try {
                context.imageLoader.memoryCache?.trimToSize(0)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Coil memory-cache trim failed during memory reclaim: $reason" }
            }
        }
        System.gc()
        val after = EngineMemoryBudget.heapSnapshot()
        logcat(LogPriority.WARN) {
            "[translation_reclaim] reason=$reason trimImageCache=$trimImageCache " +
                "heapBefore=${before.usedHeapBytes.toMiB()}MiB/${before.maxHeapBytes.toMiB()}MiB " +
                "availBefore=${before.availableHeapBytes.toMiB()}MiB " +
                "heapAfter=${after.usedHeapBytes.toMiB()}MiB/${after.maxHeapBytes.toMiB()}MiB " +
                "availAfter=${after.availableHeapBytes.toMiB()}MiB"
        }
    }

    fun logDecodeDecision(
        fileName: String,
        width: Int,
        height: Int,
        beforeReclaim: DecodeDecision?,
        afterReclaim: DecodeDecision,
    ) {
        val before = beforeReclaim?.let {
            " before=${it.kind}/sample=${it.sampleSize}/avail=${it.snapshot.availableHeapBytes.toMiB()}MiB"
        } ?: ""
        logcat(LogPriority.INFO) {
            "[translation_decode_decision] file=$fileName page=${width}x$height " +
                "decision=${afterReclaim.kind} sample=${afterReclaim.sampleSize} " +
                "raw=${afterReclaim.rawBitmapBytes.toMiB()}MiB " +
                "sampled=${afterReclaim.sampledBitmapBytes.toMiB()}MiB " +
                "avail=${afterReclaim.snapshot.availableHeapBytes.toMiB()}MiB$before"
        }
    }

    private fun Long.toMiB(): Long = this / (1024L * 1024L)
}
