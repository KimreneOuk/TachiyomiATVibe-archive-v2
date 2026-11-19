package eu.kanade.translation.engines.coloring.denoising

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.OriginalDenoisingMode

/** The denoiser is engine-owned: reused per mode and closed exactly once each. */
class CachingDenoiserProviderTest {

    private class TrackingDenoiser : MangaDenoiser {
        var closeCount = 0
        override fun isAvailable() = true
        override fun denoise(source: Bitmap): ByteArray = ByteArray(0)
        override fun denoiseStamp() = "TEST"
        override fun close() {
            closeCount += 1
        }
    }

    private class CountingFactory {
        var creations = 0
        val created = mutableListOf<TrackingDenoiser>()

        fun provider(): (OriginalDenoisingMode) -> MangaDenoiser = { _ ->
            creations += 1
            TrackingDenoiser().also(created::add)
        }
    }

    @Test
    fun `same mode reuses one denoiser across pages`() {
        val factory = CountingFactory()
        val provider = CachingDenoiserProvider(factory.provider())

        val first = provider.obtain(OriginalDenoisingMode.FFDNET)
        val second = provider.obtain(OriginalDenoisingMode.FFDNET)

        factory.creations shouldBe 1
        second shouldBe first
        factory.created.sumOf { it.closeCount } shouldBe 0
    }

    @Test
    fun `mode switch closes the previous denoiser`() {
        val factory = CountingFactory()
        val provider = CachingDenoiserProvider(factory.provider())

        provider.obtain(OriginalDenoisingMode.FFDNET)
        provider.obtain(OriginalDenoisingMode.MFDNET)

        factory.creations shouldBe 2
        factory.created[0].closeCount shouldBe 1
        factory.created[1].closeCount shouldBe 0
    }

    @Test
    fun `close releases the live denoiser and obtain rebuilds it`() {
        val factory = CountingFactory()
        val provider = CachingDenoiserProvider(factory.provider())

        provider.obtain(OriginalDenoisingMode.FFDNET)
        provider.close()
        provider.obtain(OriginalDenoisingMode.FFDNET)

        factory.creations shouldBe 2
        factory.created[0].closeCount shouldBe 1
        factory.created[1].closeCount shouldBe 0
    }
}
