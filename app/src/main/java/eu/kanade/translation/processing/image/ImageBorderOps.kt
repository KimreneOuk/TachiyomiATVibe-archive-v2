package eu.kanade.translation.processing.image

internal object ImageBorderOps {

    /** cv2 BORDER_REFLECT_101: the edge pixel is mirrored without repeating it. */
    internal fun reflect101(index: Int, size: Int): Int {
        if (index in 0 until size) return index
        if (size == 1) return 0
        val period = 2 * size - 2
        val value = Math.floorMod(index, period)
        return if (value >= size) period - value else value
    }
}
