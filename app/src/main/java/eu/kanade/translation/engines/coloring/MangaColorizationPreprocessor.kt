package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import eu.kanade.translation.util.isFatal
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** User-specified fast preprocessing seam (FFDNet replacement): full-resolution source plus target work
 *  dims in, normalized float gray plane (generator channel 0) out. Gate -> area downsample -> selective
 *  bilateral on screentone pages. Recoverable smoothing failures degrade to the plain downsample; fatal
 *  (OOM-family) errors always rethrow. */
internal object MangaColorizationPreprocessor {

    const val STAMP_SUFFIX = "+BILATERAL_SMOOTH"

    data class StageTiming(
        val pixelsMs: Long = 0L,
        val grayLMs: Long = 0L,
        val gateMs: Long = 0L,
        val resampleMs: Long = 0L,
        val smoothMs: Long = 0L,
        val packMs: Long = 0L,
        val pixelsUs: Long = pixelsMs * 1_000L,
        val grayLUs: Long = grayLMs * 1_000L,
        val gateUs: Long = gateMs * 1_000L,
        val resampleUs: Long = resampleMs * 1_000L,
        val smoothUs: Long = smoothMs * 1_000L,
        val packUs: Long = packMs * 1_000L,
    ) {
        val totalMs: Long
            get() = pixelsMs + grayLMs + gateMs + resampleMs + smoothMs + packMs

        val totalUs: Long
            get() = pixelsUs + grayLUs + gateUs + resampleUs + smoothUs + packUs
    }

    class GrayPlane(
        val plane: FloatArray,
        val score: Float,
        val smoothingApplied: Boolean,
        val timing: StageTiming = StageTiming(),
    )

    /** Full-resolution [source] aspect-true content dims [workWidth]x[workHeight] -> gray plane. */
    fun grayPlane(source: Bitmap, workWidth: Int, workHeight: Int): GrayPlane {
        require(workWidth > 0 && workHeight > 0) { "invalid work dimensions" }
        val t0 = System.nanoTime()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val t1 = System.nanoTime()
        val gray = grayLFromArgb(pixels)
        val t2 = System.nanoTime()
        val res = planeFromGray(gray, source.width, source.height, workWidth, workHeight)
        val pixelsNs = t1 - t0
        val grayLNs = t2 - t1
        val timing = res.timing.copy(
            pixelsMs = pixelsNs / 1_000_000,
            pixelsUs = pixelsNs / 1_000,
            grayLMs = grayLNs / 1_000_000,
            grayLUs = grayLNs / 1_000,
        )
        logcat(LogPriority.DEBUG) {
            "[PreprocessTiming] pixelsMs=${timing.pixelsMs} grayLMs=${timing.grayLMs} " +
                "gateMs=${timing.gateMs} resampleMs=${timing.resampleMs} smoothMs=${timing.smoothMs}"
        }
        return GrayPlane(res.plane, res.score, res.smoothingApplied, timing)
    }

    /** Pure composition over an unsigned uint8 gray plane (JVM-testable numeric twin target). */
    fun planeFromGray(gray: ByteArray, width: Int, height: Int, workWidth: Int, workHeight: Int): GrayPlane {
        val thumbHeight = ScreentoneGate.thumbnailHeight(width, height)
        val isThumbMatching = workWidth == ScreentoneGate.THUMBNAIL_WIDTH && workHeight == thumbHeight
        val initialWork: ByteArray
        val score: Float
        val gateNs: Long
        val resampleNs: Long
        if (isThumbMatching) {
            val rs0 = System.nanoTime()
            initialWork = AreaResampler.resample(gray, width, height, workWidth, workHeight)
            resampleNs = System.nanoTime() - rs0
            val sc0 = System.nanoTime()
            score = ScreentoneGate.scoreFromThumbnail(initialWork, workWidth, workHeight)
            gateNs = System.nanoTime() - sc0
        } else {
            val sc0 = System.nanoTime()
            score = ScreentoneGate.score(gray, width, height)
            gateNs = System.nanoTime() - sc0
            val rs0 = System.nanoTime()
            initialWork = AreaResampler.resample(gray, width, height, workWidth, workHeight)
            resampleNs = System.nanoTime() - rs0
        }
        var work = initialWork
        var smoothNs = 0L
        var applied = false
        if (score >= ScreentoneGate.THRESHOLD) {
            applied = true
            val st0 = System.nanoTime()
            work = try {
                SelectiveBilateral.apply(work, workWidth, workHeight)
            } catch (failure: Throwable) {
                if (failure.isFatal()) throw failure
                logcat(LogPriority.ERROR, failure) { "ScreentoneSmooth: bilateral failed; degraded to plain downsample" }
                applied = false
                initialWork
            }
            smoothNs = System.nanoTime() - st0
        }
        val plane = FloatArray(work.size)
        for (i in work.indices) plane[i] = (work[i].toInt() and 0xFF) / 255f
        val timing = StageTiming(
            gateMs = gateNs / 1_000_000,
            gateUs = gateNs / 1_000,
            resampleMs = resampleNs / 1_000_000,
            resampleUs = resampleNs / 1_000,
            smoothMs = smoothNs / 1_000_000,
            smoothUs = smoothNs / 1_000,
        )
        return GrayPlane(plane, score, applied, timing)
    }

    /** PIL convert("L") fixed point: (19595*R + 38470*G + 7471*B + 32768) >> 16, empirically exact. */
    fun grayLFromArgb(argb: IntArray): ByteArray {
        val gray = ByteArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            gray[i] = ((19595 * red + 38470 * green + 7471 * blue + 32768) shr 16).toByte()
        }
        return gray
    }

    /** Packs the content gray plane into the 5-ch NCHW input: ch0 luma over paper-white pad, ch1-4 zero. */
    fun grayNchwFromGrayPlane(
        gray: FloatArray,
        content: MangaColorizationLetterbox,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        require(gray.size == content.width * content.height) { "gray plane does not match content rect" }
        val planeSize = targetWidth * targetHeight
        val output = FloatArray(MCV2_CHANNELS * planeSize)
        java.util.Arrays.fill(output, 0, planeSize, 1.0f)
        for (y in 0 until content.height) {
            val outputRow = (y + content.top) * targetWidth + content.left
            System.arraycopy(gray, y * content.width, output, outputRow, content.width)
        }
        return output
    }

    private const val MCV2_CHANNELS = 5
}
