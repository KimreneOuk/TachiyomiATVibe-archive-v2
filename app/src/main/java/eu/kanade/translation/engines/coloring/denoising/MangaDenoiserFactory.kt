package eu.kanade.translation.engines.coloring.denoising

import android.content.Context
import tachiyomi.domain.translation.OriginalDenoisingMode

internal object MangaDenoiserFactory {
    fun create(mode: OriginalDenoisingMode, context: Context?): MangaDenoiser = when (mode) {
        OriginalDenoisingMode.NONE -> PassThroughDenoiser()
        OriginalDenoisingMode.FFDNET -> if (context != null) FfdnetDenoiserEngine(context) else PassThroughDenoiser()
        OriginalDenoisingMode.MFDNET -> MfdnetDenoiserEngine()
    }
}
