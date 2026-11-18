package eu.kanade.translation.engines.coloring.denoising

import android.graphics.Bitmap
import eu.kanade.translation.engines.coloring.MangaColorizationPreprocessor
import kotlin.math.abs

/**
 * Experimental Multi-Scale Fusion and Decomposition (MFDNet) denoiser.
 * Decomposes image into structural base and high-frequency perturbation layers,
 * attenuating screentone frequency energy without requiring trained network weights.
 */
internal class MfdnetDenoiserEngine : MangaDenoiser {

    override fun isAvailable(): Boolean = true

    override fun denoiseStamp(): String = "+MFDNET_DECOMP"

    override fun denoise(source: Bitmap): ByteArray {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = MangaColorizationPreprocessor.grayLFromArgb(pixels)
        return decomposeAndAttenuate(gray, width, height)
    }

    /** Dual-path decomposition: separates base structure from high-pass screentone layer. */
    internal fun decomposeAndAttenuate(gray: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(gray.size)
        val radius = 2
        for (y in 0 until height) {
            val yMin = maxOf(0, y - radius)
            val yMax = minOf(height - 1, y + radius)
            val rowOffset = y * width
            for (x in 0 until width) {
                val origVal = gray[rowOffset + x].toInt() and 0xFF
                // Ink lines (< 35) are strictly protected
                if (origVal < 35) {
                    output[rowOffset + x] = origVal.toByte()
                    continue
                }
                val xMin = maxOf(0, x - radius)
                val xMax = minOf(width - 1, x + radius)
                var sum = 0
                var count = 0
                for (sy in yMin..yMax) {
                    val sRow = sy * width
                    for (sx in xMin..xMax) {
                        sum += gray[sRow + sx].toInt() and 0xFF
                        count++
                    }
                }
                val base = sum / count
                val detail = origVal - base
                // Attenuate midtone screentone perturbation while keeping crisp edges
                val isMidtone = origVal in 50..220
                val isSmallPerturbation = abs(detail) < 40
                val filtered = if (isMidtone && isSmallPerturbation) {
                    base + (detail * 0.25f).toInt()
                } else {
                    origVal
                }
                output[rowOffset + x] = filtered.coerceIn(0, 255).toByte()
            }
        }
        return output
    }
}
