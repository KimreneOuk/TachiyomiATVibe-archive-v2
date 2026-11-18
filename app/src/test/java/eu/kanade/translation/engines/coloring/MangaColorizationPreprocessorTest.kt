package eu.kanade.translation.engines.coloring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pins the Option C preprocessing stages against the Python numeric twin (empirical cv2/PIL vectors).
 *  Bilateral per-pixel tolerance is +-18 LSB: cv2 5.0.0's uint8 bilateral implementation deviates from the
 *  ideal float formula at strong edges (bounded 17 LSB, 3% of pixels). Metric-level parity is the real
 *  contract and was verified at 40.4 dB PSNR with <=3% relative deltas on screentone-suppression metrics. */
class MangaColorizationPreprocessorTest {

    @Test
    fun `gray conversion matches the PIL fixed-point luma exactly`() {
        val triples = listOf(
            intArrayOf(102, 220, 225) to 185,
            intArrayOf(95, 179, 61) to 140,
            intArrayOf(234, 203, 92) to 200,
            intArrayOf(3, 98, 243) to 86,
            intArrayOf(14, 149, 245) to 120,
            intArrayOf(46, 106, 244) to 104,
            intArrayOf(255, 255, 255) to 255,
            intArrayOf(0, 0, 0) to 0,
            intArrayOf(127, 128, 129) to 128,
            intArrayOf(1, 254, 7) to 150,
        )
        val argb = IntArray(triples.size) { i ->
            val rgb = triples[i].first
            (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        val gray = MangaColorizationPreprocessor.grayLFromArgb(argb)
        triples.forEachIndexed { i, pair -> assertEquals(pair.second, gray[i].toInt() and 0xFF) }
    }

    @Test
    fun `area resample matches the pinned INTER_AREA vectors exactly`() {
        val page = syntheticPage()
        val thumb = AreaResampler.resample(page, 96, 64, 384, 256)
        val expectedThumbRow = intArrayOf(60, 60, 60, 60, 228, 228, 228, 228)
        expectedThumbRow.forEachIndexed { i, expected ->
            assertEquals(expected, thumb[i].toInt() and 0xFF)
        }
        val work = AreaResampler.resample(page, 96, 64, 48, 32)
        val expectedWorkRow0 = intArrayOf(
            187, 225, 181, 217, 213, 172, 205, 166, 197, 193, 116, 105, 112, 101, 99, 106, 95, 102,
            91, 89, 96, 85, 92, 81, 79, 86, 75, 82, 71, 69, 76, 65, 72, 61, 59, 66, 55, 62, 77, 73,
            67, 65, 61, 57, 53, 52, 45, 46,
        )
        expectedWorkRow0.forEachIndexed { i, expected ->
            assertEquals(expected, work[i].toInt() and 0xFF)
        }
        assertEquals(186928L, work.sumOf { (it.toInt() and 0xFF).toLong() })
    }

    @Test
    fun `selective bilateral matches the pinned vectors and the sub-threshold ink boundary`() {
        val work = AreaResampler.resample(syntheticPage(), 96, 64, 48, 32)
        val masked = SelectiveBilateral.apply(work, 48, 32)
        val expectedRow0 = intArrayOf(
            203, 220, 202, 213, 209, 192, 201, 186, 195, 190, 134, 119, 123, 114, 112, 118, 110, 115,
            107, 106, 110, 103, 106, 100, 99, 100, 95, 96, 91, 89, 88, 85, 83, 80, 77, 75, 74, 74,
            73, 71, 68, 64, 60, 57, 53, 50, 46, 45,
        )
        expectedRow0.forEachIndexed { i, expected ->
            val actual = masked[i].toInt() and 0xFF
            // +-18 LSB: cv2 5.0.0's uint8 bilateral deviates from the ideal float formula at strong
            // edges; metric-level parity (PSNR 40.4 dB, HP-RMS delta <=3%) was verified separately.
            assert(kotlin.math.abs(expected - actual) <= 18) { "column $i: expected $expected, got $actual" }
        }
        // Plane-sum drift bound: cv2 5.0's quantization averages ~1.5 LSB/pixel on this structured
        // plane (measured 2347/1536); metric-level parity (40.4 dB PSNR) is the real contract.
        assertEquals(187740.0, masked.sumOf { (it.toInt() and 0xFF).toLong() }.toDouble(), 2500.0)
        // This synthetic holds no sub-threshold pixels; the <35 boundary is pinned by the dedicated test below.
    }

    @Test
    fun `ink value 35 amid midtones is smoothed while 34 is restored`() {
        val width = 9
        val height = 5
        val plane = ByteArray(width * height) { 50.toByte() }
        plane[2 * width + 2] = 34.toByte()
        plane[2 * width + 4] = 35.toByte()
        val masked = SelectiveBilateral.apply(plane, width, height)
        assertEquals(34, masked[2 * width + 2].toInt() and 0xFF)
        val smoothed = masked[2 * width + 4].toInt() and 0xFF
        assert(smoothed in 47..51) { "35 should smooth toward the 50s, got $smoothed" }
    }

    @Test
    fun `clean bypass returns the plain downsample without smoothing`() {
        val result = MangaColorizationPreprocessor.planeFromGray(syntheticPage(), 96, 64, 48, 32)
        assertEquals(2.43229f, result.score, 0.02f)
        assertEquals(false, result.smoothingApplied)
        val expectedFirst12 = floatArrayOf(
            0.73333335f, 0.88235295f, 0.70980394f, 0.8509804f, 0.83529413f, 0.67450982f,
            0.80392158f, 0.65098041f, 0.77254903f, 0.75686276f, 0.45490196f, 0.41176471f,
        )
        expectedFirst12.forEachIndexed { i, expected ->
            assertEquals(expected, result.plane[i], 1e-6f)
        }
    }

    @Test
    fun `gray plane packs into the five channel NCHW input with paper-white padding`() {
        val content = MangaColorizationLetterbox(width = 2, height = 2, left = 1, top = 1)
        val gray = floatArrayOf(0f, 0.5f, 0.25f, 1f)
        val packed = MangaColorizationPreprocessor.grayNchwFromGrayPlane(gray, content, 4, 4)
        assertEquals(4 * 4 * 5, packed.size)
        assertEquals(1f, packed[0])
        assertEquals(0f, packed[1 * 4 + 1])
        assertEquals(0.5f, packed[1 * 4 + 2])
        assertEquals(0.25f, packed[2 * 4 + 1])
        assertEquals(1f, packed[2 * 4 + 2])
        assertEquals(0f, packed[16]) // channel 1 is the zero-filled hint plane
        assertEquals(0f, packed[16 + 12])
    }

    @Test
    fun `stage timing telemetry is populated and reports non-negative durations`() {
        val result = MangaColorizationPreprocessor.planeFromGray(syntheticPage(), 96, 64, 48, 32)
        val timing = result.timing
        assert(timing.gateUs >= 0)
        assert(timing.resampleUs >= 0)
        assert(timing.smoothUs >= 0)
        assert(timing.totalUs >= 0)
    }

    @Test
    fun `microbenchmark representative manga page profiling`() {
        val width = 1200
        val height = 1800
        val workWidth = 384
        val workHeight = 576
        val targetWidth = 832
        val targetHeight = 576
        val letterbox = MangaColorizationLetterbox(workWidth, workHeight, (targetWidth - workWidth) / 2, 0)

        // Generate synthetic screentone page (alternating midtone dots)
        val argb = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val luma = if ((x / 6 + y / 6) % 2 == 0) 70 else 185
            (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
        }

        // Warm up JIT
        val warmGray = MangaColorizationPreprocessor.grayLFromArgb(argb)
        val warmWork = AreaResampler.resample(warmGray, width, height, workWidth, workHeight)
        SelectiveBilateral.apply(warmWork, workWidth, workHeight)

        // Benchmark runs
        val iterations = 3
        var totalPixelsNs = 0L
        var totalGrayLNs = 0L
        var totalGateNs = 0L
        var totalResampleNs = 0L
        var totalSmoothNs = 0L
        var totalPackNs = 0L
        var measuredScore = 0f

        val dummyPixels = IntArray(width * height)
        for (iter in 0 until iterations) {
            val t0 = System.nanoTime()
            System.arraycopy(argb, 0, dummyPixels, 0, dummyPixels.size)
            val t1 = System.nanoTime()
            val gray = MangaColorizationPreprocessor.grayLFromArgb(dummyPixels)
            val t2 = System.nanoTime()
            measuredScore = ScreentoneGate.score(gray, width, height)
            val t3 = System.nanoTime()
            val work = AreaResampler.resample(gray, width, height, workWidth, workHeight)
            val t4 = System.nanoTime()
            val smoothed = SelectiveBilateral.apply(work, workWidth, workHeight)
            val t5 = System.nanoTime()
            val plane = FloatArray(smoothed.size) { (smoothed[it].toInt() and 0xFF) / 255f }
            MangaColorizationPreprocessor.grayNchwFromGrayPlane(plane, letterbox, targetWidth, targetHeight)
            val t6 = System.nanoTime()

            totalPixelsNs += (t1 - t0)
            totalGrayLNs += (t2 - t1)
            totalGateNs += (t3 - t2)
            totalResampleNs += (t4 - t3)
            totalSmoothNs += (t5 - t4)
            totalPackNs += (t6 - t5)
        }

        val avgPixelsMs = (totalPixelsNs / iterations) / 1_000_000.0
        val avgGrayLMs = (totalGrayLNs / iterations) / 1_000_000.0
        val avgGateMs = (totalGateNs / iterations) / 1_000_000.0
        val avgResampleMs = (totalResampleNs / iterations) / 1_000_000.0
        val avgSmoothMs = (totalSmoothNs / iterations) / 1_000_000.0
        val avgPackMs = (totalPackNs / iterations) / 1_000_000.0
        val avgTotalMs = avgPixelsMs + avgGrayLMs + avgGateMs + avgResampleMs + avgSmoothMs + avgPackMs

        // Measure end-to-end planeFromGray with deduplicated seam
        var totalEndToEndNs = 0L
        for (iter in 0 until iterations) {
            val t0 = System.nanoTime()
            val res = MangaColorizationPreprocessor.planeFromGray(warmGray, width, height, workWidth, workHeight)
            totalEndToEndNs += (System.nanoTime() - t0)
        }
        val avgEndToEndMs = (totalEndToEndNs / iterations) / 1_000_000.0

        println("=== MangaColorizationPreprocessor Profiling Breakdown (Page: ${width}x${height} -> Work: ${workWidth}x${workHeight}, GateScore: $measuredScore) ===")
        println("Pixels extraction (Bitmap.getPixels sim) : ${String.format("%.2f", avgPixelsMs)} ms (${String.format("%.1f", avgPixelsMs / avgTotalMs * 100)}%)")
        println("ARGB -> GrayL conversion                 : ${String.format("%.2f", avgGrayLMs)} ms (${String.format("%.1f", avgGrayLMs / avgTotalMs * 100)}%)")
        println("ScreentoneGate (score + thumb resample)  : ${String.format("%.2f", avgGateMs)} ms (${String.format("%.1f", avgGateMs / avgTotalMs * 100)}%)")
        println("AreaResampler (full resample)            : ${String.format("%.2f", avgResampleMs)} ms (${String.format("%.1f", avgResampleMs / avgTotalMs * 100)}%)")
        println("SelectiveBilateral (bilateral + ink)     : ${String.format("%.2f", avgSmoothMs)} ms (${String.format("%.1f", avgSmoothMs / avgTotalMs * 100)}%)")
        println("NCHW Packing (5-ch pad)                  : ${String.format("%.2f", avgPackMs)} ms (${String.format("%.1f", avgPackMs / avgTotalMs * 100)}%)")
        println("Total Preprocessing Duration (separate)  : ${String.format("%.2f", avgTotalMs)} ms")
        println("End-to-end planeFromGray (deduplicated)  : ${String.format("%.2f", avgEndToEndMs)} ms")
        println("===============================================================================================")
    }

    private fun syntheticPage(): ByteArray = ScreentoneGateTest().syntheticPage()
}
