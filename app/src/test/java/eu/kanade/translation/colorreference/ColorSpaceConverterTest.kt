package eu.kanade.translation.colorreference

import io.kotest.matchers.floats.shouldBeBetween
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ColorSpaceConverterTest {

    @Test
    fun `black converts to L zero`() {
        val lab = FloatArray(3)
        ColorSpaceConverter.rgbToLab(0, 0, 0, lab)

        lab[0].shouldBeBetween(0.0f, 0.1f, 0.01f)
        lab[1].shouldBeBetween(-0.5f, 0.5f, 0.01f)
        lab[2].shouldBeBetween(-0.5f, 0.5f, 0.01f)
    }

    @Test
    fun `white converts to L near 100 with neutral chromaticity`() {
        val lab = FloatArray(3)
        ColorSpaceConverter.rgbToLab(255, 255, 255, lab)

        lab[0].shouldBeBetween(99.9f, 100.1f, 0.01f)
        lab[1].shouldBeBetween(-0.5f, 0.5f, 0.01f)
        lab[2].shouldBeBetween(-0.5f, 0.5f, 0.01f)
    }

    @Test
    fun `roundtrip conversion preserves RGB channels within quantization error`() {
        val testColors = listOf(
            Triple(255, 0, 0),     // Pure Red
            Triple(0, 255, 0),     // Pure Green
            Triple(0, 0, 255),     // Pure Blue
            Triple(255, 255, 0),   // Yellow
            Triple(0, 255, 255),   // Cyan
            Triple(255, 0, 255),   // Magenta
            Triple(128, 128, 128), // Mid Gray
            Triple(240, 200, 180), // Anime skin tone
            Triple(45, 60, 110),   // Navy / clothing
            Triple(180, 80, 50),   // Rust / hair
        )

        val lab = FloatArray(3)
        for ((r, g, b) in testColors) {
            ColorSpaceConverter.rgbToLab(r, g, b, lab)
            val rgbOut = ColorSpaceConverter.labToRgb(lab[0], lab[1], lab[2])

            val rOut = (rgbOut ushr 16) and 0xFF
            val gOut = (rgbOut ushr 8) and 0xFF
            val bOut = rgbOut and 0xFF

            abs(r - rOut).shouldBeInRange(0..1)
            abs(g - gOut).shouldBeInRange(0..1)
            abs(b - bOut).shouldBeInRange(0..1)
        }
    }
}
