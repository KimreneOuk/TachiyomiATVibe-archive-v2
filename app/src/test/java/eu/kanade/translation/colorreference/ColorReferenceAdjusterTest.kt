package eu.kanade.translation.colorreference

import android.graphics.Bitmap
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ColorReferenceAdjusterTest {

    private val coloredBitmap: Bitmap = mockk(relaxed = true)

    private fun usableProfile(meanA: Float = 10f, meanB: Float = 15f) = ColorReferenceProfile(
        meanA = meanA,
        meanB = meanB,
        stdA = 12f,
        stdB = 14f,
        sampleCount = 500,
        isUsable = true,
    )

    @Test
    fun `apply returns original colored bitmap when snapshot is null`() {
        val result = ColorReferenceAdjuster.apply(coloredBitmap, null)
        result shouldBe coloredBitmap
    }

    @Test
    fun `apply returns original colored bitmap when snapshot is disabled`() {
        val snapshot = ColorReferenceSnapshot(
            mangaId = 123L,
            isEnabled = false,
            contentHash = "hash123",
            algorithmVersion = 1,
            strength = 0.8f,
            profile = usableProfile(),
            imageFile = java.io.File("nonexistent.png"),
        )
        val result = ColorReferenceAdjuster.apply(coloredBitmap, snapshot)
        result shouldBe coloredBitmap
    }

    @Test
    fun `apply returns original colored bitmap when target profile is not usable`() {
        val snapshot = ColorReferenceSnapshot(
            mangaId = 123L,
            isEnabled = true,
            contentHash = "hash123",
            algorithmVersion = 1,
            strength = 0.8f,
            profile = usableProfile().copy(isUsable = false),
            imageFile = java.io.File("nonexistent.png"),
        )
        val result = ColorReferenceAdjuster.apply(coloredBitmap, snapshot)
        result shouldBe coloredBitmap
    }

    @Test
    fun `apply returns original colored bitmap when strength is zero`() {
        val snapshot = ColorReferenceSnapshot(
            mangaId = 123L,
            isEnabled = true,
            contentHash = "hash123",
            algorithmVersion = 1,
            strength = 0.0f,
            profile = usableProfile(),
            imageFile = java.io.File("nonexistent.png"),
        )
        val result = ColorReferenceAdjuster.apply(coloredBitmap, snapshot)
        result shouldBe coloredBitmap
    }

    @Test
    fun `midtone stats exclude ink and paper pixels`() {
        val midtone = ColorSpaceConverter.labToRgb(50f, 20f, 0f)
        val opposite = ColorSpaceConverter.labToRgb(50f, -20f, 0f)
        val paper = 0xFFFFFFFF.toInt()
        val ink = 0xFF080808.toInt()
        // 12 of each midtone: the stats guard needs count > 10 to engage.
        val pixels = IntArray(24) { if (it % 2 == 0) midtone else opposite } + intArrayOf(paper, ink)

        val stats = ColorReferenceAdjuster.computeMidtoneStats(pixels)

        // The a-channels cancel around zero; spread stays near ±20 Lab units.
        // Tolerances absorb the 8-bit Lab->sRGB quantization roundtrip.
        absDiff(stats.meanA, 0f).shouldBeLessThan(2f)
        absDiff(stats.stdA, 20f).shouldBeLessThan(4f)
    }

    @Test
    fun `ink pixels are protected from grading`() {
        val ink = 0xFF080808.toInt()
        val pixels = intArrayOf(ink)

        ColorReferenceAdjuster.gradePixels(
            pixels = pixels,
            srcMeanA = 0f,
            srcMeanB = 0f,
            scaleA = 1f,
            scaleB = 1f,
            targetMeanA = 20f,
            targetMeanB = 15f,
            strength = 1f,
        )

        pixels[0] shouldBe ink
    }

    @Test
    fun `paper pixels are protected from grading`() {
        val paper = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(paper)

        ColorReferenceAdjuster.gradePixels(
            pixels = pixels,
            srcMeanA = 0f,
            srcMeanB = 0f,
            scaleA = 1f,
            scaleB = 1f,
            targetMeanA = 20f,
            targetMeanB = 15f,
            strength = 1f,
        )

        pixels[0] shouldBe paper
    }

    @Test
    fun `full-strength grading moves midtone chroma to the target profile`() {
        val gray = 0xFF808080.toInt()
        val pixels = intArrayOf(gray)

        ColorReferenceAdjuster.gradePixels(
            pixels = pixels,
            srcMeanA = 0f,
            srcMeanB = 0f,
            scaleA = 1f,
            scaleB = 1f,
            targetMeanA = 20f,
            targetMeanB = 10f,
            strength = 1f,
        )

        val lab = FloatArray(3)
        val out = pixels[0]
        ColorSpaceConverter.rgbToLab((out ushr 16) and 0xFF, (out ushr 8) and 0xFF, out and 0xFF, lab)
        // Neutral gray (a=b=0) graded at strength 1 lands on the target
        // chroma; lightness is preserved. Absolute tolerances absorb the
        // 8-bit sRGB quantization of the Lab->RGB conversion.
        absDiff(lab[0], 53.5f).shouldBeLessThan(2f)
        absDiff(lab[1], 20f).shouldBeLessThan(3f)
        absDiff(lab[2], 10f).shouldBeLessThan(3f)
    }

    @Test
    fun `half strength grades halfway toward the target`() {
        val gray = 0xFF808080.toInt()
        val pixels = intArrayOf(gray)

        ColorReferenceAdjuster.gradePixels(
            pixels = pixels,
            srcMeanA = 0f,
            srcMeanB = 0f,
            scaleA = 1f,
            scaleB = 1f,
            targetMeanA = 20f,
            targetMeanB = 0f,
            strength = 0.5f,
        )

        val lab = FloatArray(3)
        val out = pixels[0]
        ColorSpaceConverter.rgbToLab((out ushr 16) and 0xFF, (out ushr 8) and 0xFF, out and 0xFF, lab)
        absDiff(lab[1], 10f).shouldBeLessThan(3f)
    }

    @Test
    fun `grading preserves alpha`() {
        val translucent = 0x80808080.toInt()
        val pixels = intArrayOf(translucent)

        ColorReferenceAdjuster.gradePixels(
            pixels = pixels,
            srcMeanA = 0f,
            srcMeanB = 0f,
            scaleA = 1f,
            scaleB = 1f,
            targetMeanA = 20f,
            targetMeanB = 15f,
            strength = 1f,
        )

        (pixels[0] ushr 24) shouldBe 0x80
    }

    private fun absDiff(a: Float, b: Float) = kotlin.math.abs(a - b)
}
