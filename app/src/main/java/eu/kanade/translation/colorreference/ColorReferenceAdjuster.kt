package eu.kanade.translation.colorreference

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

/** Applies story color reference grading to model output with ink and paper protection. */
object ColorReferenceAdjuster {

    /** Largest bitmap dimension used for the pass-1 midtone statistics. */
    private const val STATS_MAX_DIM = 384

    /**
     * Grades [colored] toward [snapshot]'s persisted profile. Pass-1 statistics
     * are computed from a downscaled copy (same distribution, ~100x fewer Lab
     * conversions); the grading pass itself runs at full resolution.
     */
    fun apply(
        colored: Bitmap,
        snapshot: ColorReferenceSnapshot?,
    ): Bitmap {
        if (snapshot == null || !snapshot.isEnabled) return colored
        val targetProfile = snapshot.profile ?: return colored
        if (!targetProfile.isUsable || snapshot.strength <= 0.001f) return colored

        val width = colored.width
        val height = colored.height
        if (width <= 0 || height <= 0) return colored

        val statsSource = if (max(width, height) <= STATS_MAX_DIM) {
            colored
        } else {
            createStatsDownscale(colored, STATS_MAX_DIM)
        }
        val statsPixels = IntArray(statsSource.width * statsSource.height)
        statsSource.getPixels(statsPixels, 0, statsSource.width, 0, 0, statsSource.width, statsSource.height)
        val stats = computeMidtoneStats(statsPixels)
        if (statsSource !== colored) statsSource.recycle()

        val scaleA = (targetProfile.stdA / max(stats.stdA, 1.0f)).coerceIn(0.6f, 1.6f)
        val scaleB = (targetProfile.stdB / max(stats.stdB, 1.0f)).coerceIn(0.6f, 1.6f)
        val strength = snapshot.strength.coerceIn(0f, 1f)

        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        colored.getPixels(pixels, 0, width, 0, 0, width, height)
        gradePixels(
            pixels = pixels,
            srcMeanA = stats.meanA,
            srcMeanB = stats.meanB,
            scaleA = scaleA,
            scaleB = scaleB,
            targetMeanA = targetProfile.meanA,
            targetMeanB = targetProfile.meanB,
            strength = strength,
        )

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** JVM-testable midtone Lab statistics over ARGB pixels. */
    fun computeMidtoneStats(pixels: IntArray): MidtoneStats {
        val labBuffer = FloatArray(3)
        var count = 0L
        var sumA = 0.0
        var sumB = 0.0
        var sumA2 = 0.0
        var sumB2 = 0.0

        for (argb in pixels) {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            ColorSpaceConverter.rgbToLab(r, g, b, labBuffer)
            val l = labBuffer[0]
            val a = labBuffer[1]
            val bVal = labBuffer[2]

            if (l in 15f..85f) {
                count++
                sumA += a
                sumB += bVal
                sumA2 += (a * a)
                sumB2 += (bVal * bVal)
            }
        }

        val meanA = if (count > 10) (sumA / count).toFloat() else 0f
        val meanB = if (count > 10) (sumB / count).toFloat() else 0f
        val stdA = if (count > 10) sqrt(max(0.0, (sumA2 / count) - (meanA * meanA))).toFloat() else 1f
        val stdB = if (count > 10) sqrt(max(0.0, (sumB2 / count) - (meanB * meanB))).toFloat() else 1f
        return MidtoneStats(meanA, meanB, stdA, stdB)
    }

    private fun createStatsDownscale(colored: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim / max(colored.width, colored.height).toFloat()
        val scaledWidth = max(1, (colored.width * scale).toInt())
        val scaledHeight = max(1, (colored.height * scale).toInt())
        return Bitmap.createScaledBitmap(colored, scaledWidth, scaledHeight, true)
    }

    /**
     * In-place per-pixel grading with ink (L < 25) and paper (L > 85)
     * protection ramps. Pure ARGB math so the weights are unit-testable.
     */
    fun gradePixels(
        pixels: IntArray,
        srcMeanA: Float,
        srcMeanB: Float,
        scaleA: Float,
        scaleB: Float,
        targetMeanA: Float,
        targetMeanB: Float,
        strength: Float,
    ) {
        val labBuffer = FloatArray(3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val alpha = argb and -0x1000000
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            ColorSpaceConverter.rgbToLab(r, g, b, labBuffer)
            val l = labBuffer[0]
            val a = labBuffer[1]
            val bVal = labBuffer[2]

            // Ink protection (L < 25) and paper protection (L > 85)
            val inkPaperWeight = when {
                l < 12f -> 0f
                l < 25f -> (l - 12f) / 13f
                l <= 82f -> 1.0f
                l <= 94f -> (94f - l) / 12f
                else -> 0f
            }

            if (inkPaperWeight <= 0.001f) {
                continue
            }

            val targetA = (a - srcMeanA) * scaleA + targetMeanA
            val targetB = (bVal - srcMeanB) * scaleB + targetMeanB

            val blend = strength * inkPaperWeight
            val newA = a * (1f - blend) + targetA * blend
            val newB = bVal * (1f - blend) + targetB * blend

            val newRgb = ColorSpaceConverter.labToRgb(l, newA, newB)
            pixels[i] = (alpha) or (newRgb and 0x00FFFFFF)
        }
    }

    /** Midtone chroma statistics in CIE L*a*b* space. */
    data class MidtoneStats(
        val meanA: Float,
        val meanB: Float,
        val stdA: Float,
        val stdB: Float,
    )
}
