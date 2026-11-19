package eu.kanade.translation.engines.coloring.denoising

import tachiyomi.domain.translation.OriginalDenoisingMode

/**
 * Engine-owned denoiser lifecycle: one live denoiser per mode, reused across
 * pages and closed when the owning engine closes or the mode changes. The
 * previous per-page construction leaked a native ONNX session (memory arena
 * plus thread pool) on every colorized page.
 */
internal class CachingDenoiserProvider(
    private val factory: (OriginalDenoisingMode) -> MangaDenoiser,
) : AutoCloseable {

    private var cached: MangaDenoiser? = null
    private var cachedMode: OriginalDenoisingMode? = null

    @Synchronized
    fun obtain(mode: OriginalDenoisingMode): MangaDenoiser {
        val active = cached
        if (active != null && cachedMode == mode) return active
        cached?.close()
        val created = factory(mode)
        cached = created
        cachedMode = mode
        return created
    }

    @Synchronized
    override fun close() {
        cached?.close()
        cached = null
        cachedMode = null
    }
}
