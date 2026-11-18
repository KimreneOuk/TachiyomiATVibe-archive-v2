package eu.kanade.translation.engines.coloring.denoising

import android.graphics.Bitmap

/**
 * Pre-colorization denoiser seam for the ORIGINAL preset.
 * Transforms source ARGB bitmap into an unsigned uint8 grayscale plane.
 */
internal interface MangaDenoiser : AutoCloseable {
    fun isAvailable(): Boolean
    fun denoise(source: Bitmap): ByteArray
    fun denoiseStamp(): String
    override fun close() {}
}
