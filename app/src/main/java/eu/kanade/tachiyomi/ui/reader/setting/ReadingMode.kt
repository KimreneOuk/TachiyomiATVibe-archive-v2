package eu.kanade.tachiyomi.ui.reader.setting

import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR

enum class ReadingMode(
    val stringRes: StringResource,
    @DrawableRes val iconRes: Int,
    val flagValue: Int,
    val direction: Direction? = null,
    val type: ViewerType? = null,
) {
    DEFAULT(MR.strings.label_default, R.drawable.ic_reader_default_24dp, 0x00000000),
    LEFT_TO_RIGHT(
        ATMR.strings.reader_mode_manga,
        R.drawable.ic_reader_ltr_24dp,
        0x00000001,
        Direction.Horizontal,
        ViewerType.Pager,
    ),
    WEBTOON(
        ATMR.strings.reader_mode_webtoon,
        R.drawable.ic_reader_webtoon_24dp,
        0x00000004,
        Direction.Vertical,
        ViewerType.Webtoon,
    ),
    ;

    companion object {
        const val MASK = 0x00000007

        fun fromPreference(preference: Int?): ReadingMode = when (preference) {
            1, 2 -> LEFT_TO_RIGHT
            3, 4, 5 -> WEBTOON
            else -> DEFAULT
        }

        fun defaultFromPreference(preference: Int): ReadingMode =
            fromPreference(preference).takeUnless { it == DEFAULT } ?: LEFT_TO_RIGHT

        fun isPagerType(preference: Int): Boolean {
            val mode = fromPreference(preference)
            return mode.type is ViewerType.Pager
        }

        fun toViewer(preference: Int?, activity: ReaderActivity): Viewer {
            return when (fromPreference(preference)) {
                LEFT_TO_RIGHT -> L2RPagerViewer(activity)
                WEBTOON -> WebtoonViewer(activity)
                DEFAULT -> throw IllegalStateException("Preference value must be resolved: $preference")
            }
        }
    }

    sealed interface Direction {
        data object Horizontal : Direction
        data object Vertical : Direction
    }

    sealed interface ViewerType {
        data object Pager : ViewerType
        data object Webtoon : ViewerType
    }
}
