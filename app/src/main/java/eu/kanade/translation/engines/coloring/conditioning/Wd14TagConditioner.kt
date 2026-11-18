package eu.kanade.translation.engines.coloring.conditioning

import android.content.Context
import eu.kanade.translation.engines.coloring.MangaColorizationLetterbox

/**
 * WD14 tag and hint conditioning adapter for MCV2.
 * Encapsulates channels 1 to 4 of the 5-channel MCV2 NCHW tensor.
 */
internal class Wd14TagConditioner(
    @Suppress("unused") private val context: Context? = null,
) : AutoCloseable {

    /** Populates channels 1 to 4 into [targetTensor] starting at plane offsets. */
    fun populateConditioning(
        targetTensor: FloatArray,
        letterbox: MangaColorizationLetterbox,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        val planeSize = targetWidth * targetHeight
        // Channels 1 to 4 are initialized with neutral/zero-shot priors
        for (c in 1..4) {
            val offset = c * planeSize
            // Zero-initialized by FloatArray allocation; hook point for WD14 tagger inference
        }
    }

    override fun close() {}
}
