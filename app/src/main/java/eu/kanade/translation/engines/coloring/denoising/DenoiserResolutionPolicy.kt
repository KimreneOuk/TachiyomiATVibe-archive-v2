package eu.kanade.translation.engines.coloring.denoising

import android.graphics.Bitmap

/**
 * Author-recipe resolution cap for ORIGINAL-preset denoisers (long side <= 1200px).
 * The denoised plane is area-resampled to the <=832px model input afterwards, so
 * full-resolution denoising only burns memory and CPU.
 */
internal object DenoiserResolutionPolicy {
    const val MAX_LONG_SIDE = 1200

    /** Returns scaled [width, height] when the cap applies, or null when the source already fits. */
    fun cappedDimensions(width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0) return null
        val longSide = maxOf(width, height)
        if (longSide <= MAX_LONG_SIDE) return null
        val scale = MAX_LONG_SIDE.toFloat() / longSide
        return intArrayOf(
            (width * scale).toInt().coerceIn(1, width),
            (height * scale).toInt().coerceIn(1, height),
        )
    }

    /** May be [source] itself when no cap applies; recycle the result only when it differs. */
    fun cap(source: Bitmap): Bitmap {
        val dims = cappedDimensions(source.width, source.height) ?: return source
        return Bitmap.createScaledBitmap(source, dims[0], dims[1], true)
    }
}
