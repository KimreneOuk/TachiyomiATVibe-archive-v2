package eu.kanade.tachiyomi.ui.reader

/**
 * Settings entry points shared by the routing and the sheet tab bar.
 * TRANSLATION is the unified language/engines/vision/color drawer.
 */
enum class ReaderSettingsDestination(val paneIndex: Int) {
    READER(0),
    TRANSLATION(1),
}
