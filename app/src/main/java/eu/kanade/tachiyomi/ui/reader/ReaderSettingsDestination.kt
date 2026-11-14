package eu.kanade.tachiyomi.ui.reader

/** Pane indices are shared by the settings routing and tab bar. */
enum class ReaderSettingsDestination(val paneIndex: Int) {
    READER(0),
    ENGINES(1),
    VISION(2),
    SESSION(3),
}
