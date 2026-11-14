package eu.kanade.translation.engines.inpainting.litert

import android.content.Context
import eu.kanade.translation.engines.inpainting.InpaintingMode
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences
import java.io.File

internal object LiteRtInpaintingAssetManager {

    internal const val MODEL_FP16_PATH = "models/inpainting/manga_lama_fused_fp16.tflite"
    internal const val MODEL_INT8_PATH = "models/inpainting/manga_lama_fused_int8.tflite"
    internal const val FALLBACK_FP16_GZ = "models/inpainting/manga_lama_fused_fp16.tflite.gz"
    internal const val FALLBACK_INT8_GZ = "models/inpainting/manga_lama_fused_int8.tflite.gz"
    internal const val FALLBACK_DYN_INT8 = "models/inpainting/manga_lama_fused_snapdragon_dyn_int8.tflite"
    internal const val MODEL_CACHE_DIR = "models/inpainting"
    private const val MODEL_CACHE_PREFS = "litert_model_cache"
    private const val KEY_APP_UPDATE_TIME = "app_update_time_"
    private const val KEY_EXTRACTED_SIZE = "extracted_size_"

    /** Display names shared by preflight copy and stage stamps. */
    internal fun displayNameFor(neuralModel: NeuralInpaintModel): String = when (neuralModel) {
        NeuralInpaintModel.LAMA_LITERT_GPU -> "Manga LaMa (LiteRT GPU)"
        NeuralInpaintModel.AOT_GAN -> "AOT-GAN"
    }

    /**
     * Cheap presence-only probe of the model asset ladder: an extracted
     * cache hit or any bundled candidate counts. Never builds a LiteRT or
     * GPU session, so callers may preflight from the UI thread.
     */
    internal fun isModelAssetPresent(context: Context, neuralModel: NeuralInpaintModel): Boolean {
        val prefs = context.getSharedPreferences(MODEL_CACHE_PREFS, Context.MODE_PRIVATE)
        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        return assetCandidatesFor(neuralModel).any { assetPath ->
            val baseName = File(assetPath).name.removeSuffix(".gz")
            val extracted = File(context.filesDir, "$MODEL_CACHE_DIR/$baseName")
            isExtractedCacheValid(
                extractedFileExists = extracted.exists(),
                extractedLength = extracted.length(),
                cachedTime = prefs.getLong(KEY_APP_UPDATE_TIME + baseName, -1L),
                cachedSize = prefs.getLong(KEY_EXTRACTED_SIZE + baseName, -1L),
                appUpdateTime = appUpdateTime,
            ) ||
                (extracted.exists() && extracted.length() > 1_000_000L) ||
                bundledAssetPresent(context, assetPath)
        }
    }

    /** BALANCE/QUALITY starts are blocked while the selected model's assets are absent; FAST never blocks. */
    internal fun isNeuralStartBlocked(prefs: TranslationPreferences, context: Context): Boolean {
        val mode = InpaintingMode.fromPref(prefs.translationInpaintingMode().get())
        if (mode == InpaintingMode.FAST) return false
        return !isModelAssetPresent(context, prefs.translationInpaintingNeuralModel().get())
    }

    private fun assetCandidatesFor(neuralModel: NeuralInpaintModel): List<String> = when (neuralModel) {
        NeuralInpaintModel.LAMA_LITERT_GPU -> listOf(MODEL_FP16_PATH, FALLBACK_FP16_GZ, FALLBACK_DYN_INT8)
        NeuralInpaintModel.AOT_GAN -> listOf(tensorAdapterFor(neuralModel).modelAssetPath)
    }

    private fun bundledAssetPresent(context: Context, assetPath: String): Boolean =
        listOf(assetPath, if (assetPath.endsWith(".gz")) assetPath.removeSuffix(".gz") else "$assetPath.gz")
            .distinct()
            .any { candidate ->
                try {
                    context.assets.open(candidate).close()
                    true
                } catch (_: Exception) {
                    false
                }
            }

    /** Cache-hit rule shared by the extractor and the presence probe. */
    internal fun isExtractedCacheValid(
        extractedFileExists: Boolean,
        extractedLength: Long,
        cachedTime: Long,
        cachedSize: Long,
        appUpdateTime: Long,
    ): Boolean = extractedFileExists &&
        extractedLength > 1_000_000L &&
        cachedTime == appUpdateTime &&
        (cachedSize <= 0L || extractedLength == cachedSize)

    internal fun getOrExtractModelFile(context: Context, assetPath: String): File? {
        val targetDir = File(context.filesDir, MODEL_CACHE_DIR)
        if (!targetDir.exists()) targetDir.mkdirs()
        val baseName = File(assetPath).name.removeSuffix(".gz")
        val targetFile = File(targetDir, baseName)
        val prefs = context.getSharedPreferences(MODEL_CACHE_PREFS, Context.MODE_PRIVATE)

        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val cachedTime = prefs.getLong(KEY_APP_UPDATE_TIME + baseName, -1L)
        val cachedSize = prefs.getLong(KEY_EXTRACTED_SIZE + baseName, -1L)

        if (
            isExtractedCacheValid(
                extractedFileExists = targetFile.exists(),
                extractedLength = targetFile.length(),
                cachedTime = cachedTime,
                cachedSize = cachedSize,
                appUpdateTime = appUpdateTime,
            )
        ) {
            return targetFile
        }

        val candidates = listOf(
            assetPath,
            if (assetPath.endsWith(".gz")) assetPath.removeSuffix(".gz") else "$assetPath.gz",
        ).distinct()

        for (candidate in candidates) {
            val isGzip = candidate.endsWith(".gz")
            try {
                val tempFile = File(targetDir, "${targetFile.name}.tmp")
                context.assets.open(candidate).use { rawInput ->
                    val input = if (isGzip) java.util.zip.GZIPInputStream(rawInput) else rawInput
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                prefs.edit()
                    .putLong(KEY_APP_UPDATE_TIME + baseName, appUpdateTime)
                    .putLong(KEY_EXTRACTED_SIZE + baseName, targetFile.length())
                    .apply()
                logcat(LogPriority.INFO) {
                    "[LiteRTInpaint] Extracted asset $candidate to ${targetFile.absolutePath} (${targetFile.length()} bytes)"
                }
                return targetFile
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        if (targetFile.exists() && targetFile.length() > 1_000_000L) {
            prefs.edit()
                .putLong(KEY_APP_UPDATE_TIME + baseName, appUpdateTime)
                .putLong(KEY_EXTRACTED_SIZE + baseName, targetFile.length())
                .apply()
            return targetFile
        }
        logcat(LogPriority.ERROR) { "[LiteRTInpaint] Failed to extract any asset candidate for $assetPath to storage" }
        return null
    }
}
