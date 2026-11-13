package eu.kanade.translation.engines.coloring

import io.kotest.matchers.floats.shouldBeLessThan
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CpuChromaCombinerTest {

    @Test
    fun `rgbToLab matches CIELAB reference values`() {
        probes.forEach { (name, rgb) ->
            val lab = CpuChromaCombiner.rgbToLab(rgb)
            val expected = probeLab.getValue(name)
            for (c in 0 until 3) {
                abs(lab[c] - expected[c]) shouldBeLessThan LAB_TOLERANCE
            }
        }
    }

    @Test
    fun `labToRgb inverts rgbToLab for in-gamut colors`() {
        probes.forEach { (_, rgb) ->
            val roundTrip = CpuChromaCombiner.labToRgb(CpuChromaCombiner.rgbToLab(rgb))
            for (c in 0 until 3) {
                abs(roundTrip[c] - rgb[c]) shouldBeLessThan ROUND_TRIP_TOLERANCE
            }
        }
    }

    @Test
    fun `ink pixels keep original lightness regardless of strength`() {
        val orig = plane(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f)
        val colored = plane(0.9f, 0.2f, 0.2f, 0.2f, 0.9f, 0.3f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val origLab = CpuChromaCombiner.rgbToLab(orig)
        val coloredLab = CpuChromaCombiner.rgbToLab(colored)
        val expected = CpuChromaCombiner.labToRgb(
            floatArrayOf(origLab[0], coloredLab[1], coloredLab[2], origLab[3], coloredLab[4], coloredLab[5]),
        )
        for (i in merged.indices) abs(merged[i] - expected[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `ink zone output is strength independent`() {
        val orig = plane(0.05f, 0.05f, 0.05f, 0.0f, 0.0f, 0.0f, 0.12f, 0.12f, 0.12f)
        val colored = plane(0.8f, 0.3f, 0.3f, 0.3f, 0.8f, 0.3f, 0.3f, 0.3f, 0.8f)
        val weak = CpuChromaCombiner.merge(orig, colored, 0.35f)
        val strong = CpuChromaCombiner.merge(orig, colored, 1f)
        for (i in weak.indices) abs(weak[i] - strong[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `flat regions blend lightness by strength`() {
        val orig = plane(1f, 1f, 1f)
        val colored = plane(0.9f, 0.9f, 0.85f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val origLab = CpuChromaCombiner.rgbToLab(orig)
        val coloredLab = CpuChromaCombiner.rgbToLab(colored)
        val expectedL = 0.65f * origLab[0] + 0.35f * coloredLab[0]
        val expected = CpuChromaCombiner.labToRgb(floatArrayOf(expectedL, coloredLab[1], coloredLab[2]))
        for (i in merged.indices) abs(merged[i] - expected[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `chroma passes through from the work layer`() {
        val orig = plane(0.5f, 0.5f, 0.5f)
        val colored = plane(0.55f, 0.5f, 0.45f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val mergedLab = CpuChromaCombiner.rgbToLab(merged)
        val coloredLab = CpuChromaCombiner.rgbToLab(colored)
        // a/b passthrough is exact in Lab space; the RGB round trip re-derives it from
        // float32 cbrt differences amplified by the 500/200 factors — measured 1.25e-3 (a)
        // and 2.51e-3 (b) for this vector. Real passthrough bugs are orders larger.
        abs(mergedLab[1] - coloredLab[1]) shouldBeLessThan ROUNDTRIP_TOLERANCE
        abs(mergedLab[2] - coloredLab[2]) shouldBeLessThan ROUNDTRIP_TOLERANCE
    }

    /** Interleaved RGB01 values: each triple is one pixel. */
    private fun plane(vararg channels: Float): FloatArray = channels.toList().toFloatArray()

    private companion object {
        const val INK_SHADING_STRENGTH = 0.35f
        const val TOLERANCE = 1e-3f
        const val ROUNDTRIP_TOLERANCE = 5e-3f
        const val LAB_TOLERANCE = 0.01f
        const val ROUND_TRIP_TOLERANCE = 0.005f

        // Probe RGBs and their CIELAB (D65) values generated from scikit-image.
        val probes = mapOf(
            "black" to floatArrayOf(0.0000f, 0.0000f, 0.0000f),
            "white" to floatArrayOf(1.0000f, 1.0000f, 1.0000f),
            "gray50" to floatArrayOf(0.5000f, 0.5000f, 0.5000f),
            "gray10" to floatArrayOf(0.1000f, 0.1000f, 0.1000f),
            "red" to floatArrayOf(1.0000f, 0.0000f, 0.0000f),
            "green" to floatArrayOf(0.0000f, 1.0000f, 0.0000f),
            "blue" to floatArrayOf(0.0000f, 0.0000f, 1.0000f),
            "yellow" to floatArrayOf(1.0000f, 1.0000f, 0.0000f),
            "cyan" to floatArrayOf(0.0000f, 1.0000f, 1.0000f),
            "magenta" to floatArrayOf(1.0000f, 0.0000f, 1.0000f),
            "bug_pink" to floatArrayOf(0.9960f, 0.3410f, 0.4270f),
            "skin" to floatArrayOf(0.9600f, 0.7600f, 0.6800f),
            "rust" to floatArrayOf(0.8500f, 0.4400f, 0.3200f),
            "navy" to floatArrayOf(0.0500f, 0.0500f, 0.2000f),
            "dark_red" to floatArrayOf(0.3000f, 0.0000f, 0.0000f),
            "forest" to floatArrayOf(0.1000f, 0.3500f, 0.2000f),
            "lavender" to floatArrayOf(0.7000f, 0.6500f, 0.8500f),
            "teal" to floatArrayOf(0.2000f, 0.6000f, 0.6000f),
        )

        val probeLab = mapOf(
            "black" to floatArrayOf(0.0000f, 0.0000f, 0.0000f),
            "white" to floatArrayOf(100.0000f, -0.0025f, 0.0047f),
            "gray50" to floatArrayOf(53.3890f, -0.0015f, 0.0028f),
            "gray10" to floatArrayOf(9.0104f, -0.0005f, 0.0010f),
            "red" to floatArrayOf(53.2406f, 80.0923f, 67.2028f),
            "green" to floatArrayOf(87.7351f, -86.1830f, 83.1797f),
            "blue" to floatArrayOf(32.2957f, 79.1856f, -107.8573f),
            "yellow" to floatArrayOf(97.1395f, -21.5547f, 94.4781f),
            "cyan" to floatArrayOf(91.1133f, -48.0906f, -14.1263f),
            "magenta" to floatArrayOf(60.3235f, 98.2331f, -60.8210f),
            "bug_pink" to floatArrayOf(60.7675f, 64.5726f, 23.5153f),
            "skin" to floatArrayOf(82.3286f, 15.3622f, 17.2219f),
            "rust" to floatArrayOf(58.9309f, 38.3421f, 35.4417f),
            "navy" to floatArrayOf(5.4568f, 13.3084f, -24.7271f),
            "dark_red" to floatArrayOf(12.9695f, 33.5098f, 20.3359f),
            "forest" to floatArrayOf(33.2175f, -29.8839f, 16.4199f),
            "lavender" to floatArrayOf(70.6651f, 14.8499f, -24.1386f),
            "teal" to floatArrayOf(57.8316f, -28.4560f, -8.5633f),
        )
    }
}
