package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Implementation of a left to right PagerViewer.
 */
class L2RPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new left to right pager.
     */
    override fun createPager(): Pager {
        return Pager(activity)
    }
}
