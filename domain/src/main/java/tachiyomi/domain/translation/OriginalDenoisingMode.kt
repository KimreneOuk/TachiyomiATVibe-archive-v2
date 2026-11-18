package tachiyomi.domain.translation

/**
 * Pre-colorization denoising route for the ORIGINAL preset.
 * FFDNET: Author recommended on-device denoiser (sigma=25).
 * MFDNET: Experimental multi-scale fusion and decomposition filter.
 * NONE: Direct box area-downsample without denoising.
 */
enum class OriginalDenoisingMode(val prefValue: String) {
    NONE("NONE"),
    FFDNET("FFDNET"),
    MFDNET("MFDNET"),
    ;

    companion object {
        val DEFAULT = FFDNET

        fun fromPrefOrNull(value: String?): OriginalDenoisingMode? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
