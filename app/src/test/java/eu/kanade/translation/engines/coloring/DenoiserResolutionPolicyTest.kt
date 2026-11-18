package eu.kanade.translation.engines.coloring

import eu.kanade.translation.engines.coloring.denoising.DenoiserResolutionPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DenoiserResolutionPolicyTest {

    @Test
    fun `dimensions at or under the cap are untouched`() {
        DenoiserResolutionPolicy.cappedDimensions(576, 832) shouldBe null
        DenoiserResolutionPolicy.cappedDimensions(832, 1200) shouldBe null
        DenoiserResolutionPolicy.cappedDimensions(1200, 1200) shouldBe null
    }

    @Test
    fun `landscape page caps long side to 1200 preserving aspect`() {
        val dims = DenoiserResolutionPolicy.cappedDimensions(2944, 2096)!!
        dims[0] shouldBe 1200
        dims[1] shouldBe 854
    }

    @Test
    fun `portrait page caps long side to 1200 preserving aspect`() {
        val dims = DenoiserResolutionPolicy.cappedDimensions(2096, 2944)!!
        dims[0] shouldBe 854
        dims[1] shouldBe 1200
    }

    @Test
    fun `extreme aspect ratio never collapses a dimension to zero`() {
        val landscape = DenoiserResolutionPolicy.cappedDimensions(4000, 20)!!
        landscape[0] shouldBe 1200
        landscape[1] shouldBe 6
        val portrait = DenoiserResolutionPolicy.cappedDimensions(1, 5000)!!
        portrait[0] shouldBe 1
        portrait[1] shouldBe 1200
    }

    @Test
    fun `invalid dimensions are rejected`() {
        DenoiserResolutionPolicy.cappedDimensions(0, 100) shouldBe null
        DenoiserResolutionPolicy.cappedDimensions(100, -1) shouldBe null
    }
}
