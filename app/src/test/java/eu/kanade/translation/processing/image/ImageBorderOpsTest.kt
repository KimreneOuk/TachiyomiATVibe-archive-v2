package eu.kanade.translation.processing.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImageBorderOpsTest {

    @Test
    fun `reflect101 preserves in-range indices and mirrors across multiple periods`() {
        val size = 5
        val indices = intArrayOf(
            -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1,
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        )
        val expected = intArrayOf(
            3, 2, 1, 0, 1, 2, 3, 4, 3, 2, 1, 0, 1,
            2, 3, 4, 3, 2, 1, 0, 1, 2, 3, 4, 3,
        )

        indices.forEachIndexed { index, value ->
            ImageBorderOps.reflect101(value, size) shouldBe expected[index]
        }
    }

    @Test
    fun `reflect101 maps every index to zero for a one-pixel dimension`() {
        for (index in -20..20) {
            ImageBorderOps.reflect101(index, 1) shouldBe 0
        }
    }

    @Test
    fun `reflect101 alternates across both sides for a two-pixel dimension`() {
        for (index in -8..8) {
            ImageBorderOps.reflect101(index, 2) shouldBe Math.floorMod(index, 2)
        }
    }
}
