package eu.kanade.translation.colorreference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Extracts bounded color distribution statistics from an image for story color reference. */
object ColorReferenceProfileExtractor {
    private const val MAX_THUMBNAIL_DIMENSION = 384
    private const val MIN_VALID_SAMPLES = 50
    private const val MIN_CHROMA_MAGNITUDE = 3.0f
    private const val MIN_CHROMA_SPREAD = 5.0f

    fun extract(stream: InputStream): ColorReferenceProfile {
        val bytes = stream.readBytes()
        return extract(bytes)
    }

    fun extract(bytes: ByteArray): ColorReferenceProfile {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) {
            return unUsableProfile()
        }

        var sampleSize = 1
        val maxDim = max(origW, origH)
        while ((maxDim / sampleSize) > MAX_THUMBNAIL_DIMENSION) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return unUsableProfile()

        return try {
            extract(decoded)
        } finally {
            decoded.recycle()
        }
    }

    fun extract(bitmap: Bitmap): ColorReferenceProfile {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return unUsableProfile()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var count = 0L
        var sumA = 0.0
        var sumB = 0.0
        var sumA2 = 0.0
        var sumB2 = 0.0

        val lab = FloatArray(3)
        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < 128) continue

            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            ColorSpaceConverter.rgbToLab(r, g, b, lab)
            val l = lab[0]
            val a = lab[1]
            val bVal = lab[2]

            // Downweight / ignore ink and paper extremes
            if (l < 10f || l > 90f) continue

            count++
            sumA += a
            sumB += bVal
            sumA2 += (a * a)
            sumB2 += (bVal * bVal)
        }

        if (count < MIN_VALID_SAMPLES) {
            return unUsableProfile(sampleCount = count.toInt())
        }

        val meanA = (sumA / count).toFloat()
        val meanB = (sumB / count).toFloat()
        val varA = max(0.0, (sumA2 / count) - (meanA * meanA)).toFloat()
        val varB = max(0.0, (sumB2 / count) - (meanB * meanB)).toFloat()
        val stdA = sqrt(varA)
        val stdB = sqrt(varB)

        val chromaMagnitude = sqrt(meanA * meanA + meanB * meanB)
        val chromaSpread = stdA + stdB
        val isUsable = chromaMagnitude >= MIN_CHROMA_MAGNITUDE || chromaSpread >= MIN_CHROMA_SPREAD

        return ColorReferenceProfile(
            meanA = meanA,
            meanB = meanB,
            stdA = stdA,
            stdB = stdB,
            sampleCount = count.toInt(),
            isUsable = isUsable,
        )
    }

    private fun unUsableProfile(sampleCount: Int = 0): ColorReferenceProfile =
        ColorReferenceProfile(0f, 0f, 0f, 0f, sampleCount, isUsable = false)
}
