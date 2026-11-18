package eu.kanade.translation.engines.coloring.denoising

import android.graphics.Bitmap
import eu.kanade.translation.engines.coloring.MangaColorizationPreprocessor

/** Direct grayscale extraction without denoising (pure raw mode). */
internal class PassThroughDenoiser : MangaDenoiser {
    override fun isAvailable(): Boolean = true

    override fun denoise(source: Bitmap): ByteArray {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return MangaColorizationPreprocessor.grayLFromArgb(pixels)
    }

    override fun denoiseStamp(): String = "+DIRECT_RAW"
}
