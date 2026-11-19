package eu.kanade.translation.samvizprobe

import android.graphics.Bitmap

/** Mirrors the main app's ORIGINAL-preset gray path (MangaColorizationPreprocessor +
 *  AreaResampler semantics): PIL-exact gray conversion, cv2.INTER_AREA downsample. */
internal object GrayPreprocess {

    fun lumaPlane(source: Bitmap, size: Int): FloatArray {
        val width = source.width
        val height = source.height
        require(width > 0 && height > 0) { "invalid source bitmap dimensions: ${width}x$height" }
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = grayLFromArgb(pixels)
        val resampled = AreaResampler.resample(gray, width, height, size, size)
        return FloatArray(resampled.size) { (resampled[it].toInt() and 0xFF) / 255f }
    }

    /** Exact PIL convert("L") fixed point. */
    fun grayLFromArgb(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val l = (19595 * ((p shr 16) and 0xFF) + 38470 * ((p shr 8) and 0xFF) + 7471 * (p and 0xFF) + 32768) shr 16
            out[i] = l.toByte()
        }
        return out
    }
}

/** Exact-area resampling of uint8 gray planes — the cv2.INTER_AREA semantics of the
 *  prototype (each destination pixel averages its source coverage window). */
internal object AreaResampler {

    /** Resamples [src] ([srcWidth]x[srcHeight], row-major unsigned bytes) to [dstWidth]x[dstHeight]. */
    fun resample(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): ByteArray {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) { "invalid dimensions" }
        require(src.size == srcWidth * srcHeight) { "plane buffer does not match dimensions" }
        val scaleX = srcWidth.toDouble() / dstWidth
        val scaleY = srcHeight.toDouble() / dstHeight
        // Precompute per-destination-column coverage: first source index, per-index weights, total.
        val columnStart = IntArray(dstWidth)
        val columnWeight = Array(dstWidth) { DoubleArray(0) }
        val columnTotal = DoubleArray(dstWidth)
        for (dx in 0 until dstWidth) {
            val x0 = dx * scaleX
            val x1 = (dx + 1) * scaleX
            val ix0 = x0.toInt().coerceIn(0, srcWidth - 1)
            val ix1 = (x1.toInt() + 1).coerceAtMost(srcWidth)
            val weights = DoubleArray(ix1 - ix0)
            var total = 0.0
            for (k in weights.indices) {
                val sx = ix0 + k
                weights[k] = (x1.coerceAtMost(sx + 1.0) - x0.coerceAtLeast(sx.toDouble())).coerceIn(0.0, 1.0)
                total += weights[k]
            }
            columnStart[dx] = ix0
            columnWeight[dx] = weights
            columnTotal[dx] = total
        }
        // Precompute per-destination-row coverage: first source index, per-index weights, total.
        val rowStart = IntArray(dstHeight)
        val rowWeight = Array(dstHeight) { DoubleArray(0) }
        val rowTotal = DoubleArray(dstHeight)
        for (dy in 0 until dstHeight) {
            val y0 = dy * scaleY
            val y1 = (dy + 1) * scaleY
            val iy0 = y0.toInt().coerceIn(0, srcHeight - 1)
            val iy1 = (y1.toInt() + 1).coerceAtMost(srcHeight)
            val weights = DoubleArray(iy1 - iy0)
            var total = 0.0
            for (k in weights.indices) {
                val sy = iy0 + k
                weights[k] = (y1.coerceAtMost(sy + 1.0) - y0.coerceAtLeast(sy.toDouble())).coerceIn(0.0, 1.0)
                total += weights[k]
            }
            rowStart[dy] = iy0
            rowWeight[dy] = weights
            rowTotal[dy] = total
        }
        val dst = ByteArray(dstWidth * dstHeight)
        for (dy in 0 until dstHeight) {
            val iy0 = rowStart[dy]
            val rowWeights = rowWeight[dy]
            val rowCount = rowWeights.size
            val rTotal = rowTotal[dy]
            val dstRowBase = dy * dstWidth
            for (dx in 0 until dstWidth) {
                val colWeights = columnWeight[dx]
                val colCount = colWeights.size
                val col0 = columnStart[dx]
                var sum = 0.0
                for (r in 0 until rowCount) {
                    val rowBase = (iy0 + r) * srcWidth + col0
                    var rowSum = 0.0
                    for (k in 0 until colCount) {
                        rowSum += (src[rowBase + k].toInt() and 0xFF) * colWeights[k]
                    }
                    sum += rowSum * rowWeights[r]
                }
                val value = (sum / (rTotal * columnTotal[dx])) + 0.5
                dst[dstRowBase + dx] = value.toInt().coerceIn(0, 255).toByte()
            }
        }
        return dst
    }
}
