package eu.kanade.tachiyomi.ui.manga

import androidx.compose.runtime.Immutable

/** Chapter IDs keep range anchors stable across list reordering; null means no anchor. */
@Immutable
data class SelectionAnchors(val firstAnchorId: Long?, val lastAnchorId: Long?)

internal object ChapterSelectionReducer {

    /** Missing anchors do not range-select against stale positions. */
    fun toggle(
        state: MangaScreenModel.State.Success,
        item: ChapterList.Item,
        selected: Boolean,
        userSelected: Boolean = false,
        fromLongPress: Boolean = false,
    ): MangaScreenModel.State.Success {
        val processed = state.processedChapters
        val selectedIndex = processed.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return state

        val selectedItem = processed[selectedIndex]
        if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) {
            return state
        }

        val stored = state.selectionAnchors
        var firstPos = stored?.firstAnchorId?.let { id -> processed.indexOfFirst { it.id == id } } ?: -1
        var lastPos = stored?.lastAnchorId?.let { id -> processed.indexOfLast { it.id == id } } ?: -1

        var selectedIds = state.selectedChapterIds
        val firstSelection = processed.none { it.selected }
        selectedIds = if (selected) selectedIds + item.id else selectedIds - item.id

        if (selected && userSelected && fromLongPress) {
            if (firstSelection) {
                firstPos = selectedIndex
                lastPos = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < firstPos) {
                    range = selectedIndex + 1..<firstPos
                    firstPos = selectedIndex
                } else if (selectedIndex > lastPos) {
                    range = lastPos + 1..<selectedIndex
                    lastPos = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach {
                    val inbetweenItem = processed[it]
                    if (!inbetweenItem.selected) {
                        selectedIds += inbetweenItem.id
                    }
                }
            }
        } else if (userSelected && !fromLongPress) {
            if (!selected) {
                if (selectedIndex == firstPos) {
                    firstPos = processed.indexOfFirst { it.id != item.id && it.selected }
                } else if (selectedIndex == lastPos) {
                    lastPos = processed.indexOfLast { it.id != item.id && it.selected }
                }
            } else {
                if (selectedIndex < firstPos) {
                    firstPos = selectedIndex
                } else if (selectedIndex > lastPos) {
                    lastPos = selectedIndex
                }
            }
        }

        val firstAnchorItem: ChapterList.Item? = processed.getOrNull(firstPos)
        val lastAnchorItem: ChapterList.Item? = processed.getOrNull(lastPos)
        val anchors = if (firstAnchorItem != null || lastAnchorItem != null) {
            SelectionAnchors(
                firstAnchorId = firstAnchorItem?.id,
                lastAnchorId = lastAnchorItem?.id,
            )
        } else {
            null
        }

        // Apply updated selection across all chapters (preserving filtered-out chapters)
        val updatedChapters = state.chapters.stampSelection(selectedIds)
        return state.copy(chapters = updatedChapters, selectedChapterIds = selectedIds, selectionAnchors = anchors)
    }

    /** Selects or deselects every chapter, including ones currently filtered out. */
    fun setAll(state: MangaScreenModel.State.Success, selected: Boolean): MangaScreenModel.State.Success {
        val selectedIds = if (selected) state.chapters.map { it.id }.toSet() else emptySet()
        val chapters = state.chapters.map { item ->
            if (item.selected == selected) item else item.copy(selected = selected)
        }
        return state.copy(chapters = chapters, selectedChapterIds = selectedIds, selectionAnchors = null)
    }

    fun invert(state: MangaScreenModel.State.Success): MangaScreenModel.State.Success {
        val selectedIds = state.selectedChapterIds.toMutableSet()
        val chapters = state.chapters.map { item ->
            val nowSelected = !item.selected
            if (nowSelected) selectedIds += item.id else selectedIds -= item.id
            item.copy(selected = nowSelected)
        }
        return state.copy(chapters = chapters, selectedChapterIds = selectedIds, selectionAnchors = null)
    }
}

/** Reconciles a rebuilt list against the latest selection before publication. */
internal fun List<ChapterList.Item>.stampSelection(selectedChapterIds: Set<Long>): List<ChapterList.Item> =
    map { item ->
        val selected = item.chapter.id in selectedChapterIds
        if (item.selected == selected) item else item.copy(selected = selected)
    }
