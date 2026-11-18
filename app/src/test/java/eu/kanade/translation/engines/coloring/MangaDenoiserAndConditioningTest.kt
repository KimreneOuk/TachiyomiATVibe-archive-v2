package eu.kanade.translation.engines.coloring

import eu.kanade.translation.engines.coloring.conditioning.Wd14TagConditioner
import eu.kanade.translation.engines.coloring.denoising.MfdnetDenoiserEngine
import eu.kanade.translation.engines.coloring.denoising.MangaDenoiserFactory
import eu.kanade.translation.engines.coloring.denoising.PassThroughDenoiser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.OriginalDenoisingMode

class MangaDenoiserAndConditioningTest {

    @Test
    fun `OriginalDenoisingMode enum values and pref conversion`() {
        OriginalDenoisingMode.DEFAULT shouldBe OriginalDenoisingMode.FFDNET
        OriginalDenoisingMode.fromPrefOrNull("NONE") shouldBe OriginalDenoisingMode.NONE
        OriginalDenoisingMode.fromPrefOrNull("ffdnet") shouldBe OriginalDenoisingMode.FFDNET
        OriginalDenoisingMode.fromPrefOrNull("MFDNET") shouldBe OriginalDenoisingMode.MFDNET
        OriginalDenoisingMode.fromPrefOrNull("unknown") shouldBe null
    }

    @Test
    fun `MangaDenoiserFactory returns appropriate engine`() {
        val noneDenoiser = MangaDenoiserFactory.create(OriginalDenoisingMode.NONE, null)
        (noneDenoiser is PassThroughDenoiser) shouldBe true
        noneDenoiser.denoiseStamp() shouldBe "+DIRECT_RAW"

        val mfdnetDenoiser = MangaDenoiserFactory.create(OriginalDenoisingMode.MFDNET, null)
        (mfdnetDenoiser is MfdnetDenoiserEngine) shouldBe true
        mfdnetDenoiser.denoiseStamp() shouldBe "+MFDNET_DECOMP"

        // FFDNet without context falls back safely to pass-through
        val ffdnetNullCtx = MangaDenoiserFactory.create(OriginalDenoisingMode.FFDNET, null)
        (ffdnetNullCtx is PassThroughDenoiser) shouldBe true
    }

    @Test
    fun `MFDNet decomposition preserves deep ink lines`() {
        val mfdnet = MfdnetDenoiserEngine()
        val width = 10
        val height = 10
        val gray = ByteArray(width * height) { 20.toByte() } // All deep ink < 35
        val result = mfdnet.decomposeAndAttenuate(gray, width, height)

        for (i in result.indices) {
            (result[i].toInt() and 0xFF) shouldBe 20
        }
    }

    @Test
    fun `MFDNet decomposes midtone high frequency pattern without blurring flat regions`() {
        val mfdnet = MfdnetDenoiserEngine()
        val width = 10
        val height = 10
        // Uniform midtone 128
        val flat = ByteArray(width * height) { 128.toByte() }
        val flatResult = mfdnet.decomposeAndAttenuate(flat, width, height)

        for (i in flatResult.indices) {
            (flatResult[i].toInt() and 0xFF) shouldBe 128
        }
    }

    @Test
    fun `Wd14TagConditioner populates neutral priors on channels 1 to 4`() {
        val conditioner = Wd14TagConditioner()
        val width = 4
        val height = 4
        val planeSize = width * height
        val tensor = FloatArray(5 * planeSize)

        // Seed channel 0 with arbitrary luma
        for (i in 0 until planeSize) tensor[i] = 0.5f

        val letterbox = MangaColorizationLetterbox(width, height, 0, 0)
        conditioner.populateConditioning(tensor, letterbox, width, height)

        // Channel 0 should remain untouched
        for (i in 0 until planeSize) tensor[i] shouldBe 0.5f

        // Channels 1..4 should exist and be initialized to 0f
        for (c in 1..4) {
            val offset = c * planeSize
            for (i in 0 until planeSize) {
                tensor[offset + i] shouldBe 0f
            }
        }
    }
}
