package tachiyomi.domain.translation

/** Post-colorization combine look.
 *  ORIGINAL keeps each model's own lightness+chroma outside ink (faithful render);
 *  VIBRANT is the production prototype look (0.35 lightness mix, punchier colors). */
enum class ColorCombineMode(val prefValue: String) {
    ORIGINAL("ORIGINAL"),
    VIBRANT("VIBRANT"),
    ;

    companion object {
        val DEFAULT = VIBRANT

        fun fromPrefOrNull(value: String?): ColorCombineMode? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
