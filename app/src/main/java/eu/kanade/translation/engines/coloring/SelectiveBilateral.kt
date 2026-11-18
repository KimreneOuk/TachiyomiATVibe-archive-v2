package eu.kanade.translation.engines.coloring

import eu.kanade.translation.processing.image.ImageBorderOps

/** Selective midtone smoothing: cv2.bilateralFilter(uint8 gray, d=5, sigmaColor=35, sigmaSpace=35)
 *  with reflect-101 borders, then prototype ink protection — original restored where the value is < 35
 *  (the prototype's uint8 form of "luma < 0.14"; pixel value 35 itself is NOT protected). */
internal object SelectiveBilateral {

    const val DIAMETER = 5
    const val SIGMA_COLOR = 35.0
    const val SIGMA_SPACE = 35.0
    const val INK_THRESHOLD = 35

    private val RADIUS = DIAMETER / 2
    private val SPATIAL = DoubleArray(DIAMETER) { i ->
        val d = i - RADIUS
        kotlin.math.exp(-(d.toDouble() * d) / (2.0 * SIGMA_SPACE * SIGMA_SPACE))
    }
    private val COLOR_DENOMINATOR = 2.0 * SIGMA_COLOR * SIGMA_COLOR
    private val KERNEL_WEIGHTS = DoubleArray(DIAMETER * DIAMETER * 256) { idx ->
        val k = idx / 256
        val diff = idx % 256
        val dy = k / DIAMETER - RADIUS
        val dx = k % DIAMETER - RADIUS
        val spatial = SPATIAL[dy + RADIUS] * SPATIAL[dx + RADIUS]
        spatial * kotlin.math.exp(-(diff.toDouble() * diff) / COLOR_DENOMINATOR)
    }

    /** Smooths [gray] ([width]x[height], row-major unsigned bytes) and re-applies ink protection. */
    fun apply(gray: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "invalid plane dimensions" }
        require(gray.size == width * height) { "plane buffer does not match dimensions" }
        val filtered = ByteArray(gray.size)
        val reflectedRows = IntArray(DIAMETER)
        val hasInterior = width >= DIAMETER && height >= DIAMETER
        val innerYStart = if (hasInterior) RADIUS else height
        val innerYEnd = if (hasInterior) height - RADIUS else height
        val innerXStart = RADIUS
        val innerXEnd = width - RADIUS

        for (y in 0 until innerYStart) {
            filterBorderRow(gray, filtered, width, height, y, reflectedRows)
        }

        val rows = IntArray(DIAMETER)
        for (y in innerYStart until innerYEnd) {
            val rowOffset = y * width
            for (dy in 0 until DIAMETER) {
                rows[dy] = (y + dy - RADIUS) * width
            }
            for (x in 0 until innerXStart) {
                filterPixelReflected(gray, filtered, width, rowOffset, x, rows)
            }
            for (x in innerXStart until innerXEnd) {
                val center = gray[rowOffset + x].toInt() and 0xFF
                if (center < INK_THRESHOLD) {
                    filtered[rowOffset + x] = center.toByte()
                    continue
                }
                var sum = 0.0
                var weights = 0.0
                var k = 0
                for (dy in 0 until DIAMETER) {
                    val base = rows[dy] + x - RADIUS
                    for (dx in 0 until DIAMETER) {
                        val neighbor = gray[base + dx].toInt() and 0xFF
                        val diff = kotlin.math.abs(neighbor - center)
                        val weight = KERNEL_WEIGHTS[(k shl 8) or diff]
                        k++
                        sum += weight * neighbor
                        weights += weight
                    }
                }
                filtered[rowOffset + x] = (sum / weights + 0.5).toInt().coerceIn(0, 255).toByte()
            }
            for (x in innerXEnd until width) {
                filterPixelReflected(gray, filtered, width, rowOffset, x, rows)
            }
        }

        for (y in innerYEnd until height) {
            filterBorderRow(gray, filtered, width, height, y, reflectedRows)
        }
        return filtered
    }

    private fun filterBorderRow(
        gray: ByteArray,
        filtered: ByteArray,
        width: Int,
        height: Int,
        y: Int,
        reflectedRows: IntArray,
    ) {
        for (dyIdx in 0 until DIAMETER) {
            reflectedRows[dyIdx] = ImageBorderOps.reflect101(y + dyIdx - RADIUS, height) * width
        }
        val rowOffset = y * width
        for (x in 0 until width) {
            filterPixelReflected(gray, filtered, width, rowOffset, x, reflectedRows)
        }
    }

    private fun filterPixelReflected(
        gray: ByteArray,
        filtered: ByteArray,
        width: Int,
        rowOffset: Int,
        x: Int,
        rowBases: IntArray,
    ) {
        val center = gray[rowOffset + x].toInt() and 0xFF
        if (center < INK_THRESHOLD) {
            filtered[rowOffset + x] = center.toByte()
            return
        }
        var sum = 0.0
        var weights = 0.0
        var k = 0
        for (dyIdx in 0 until DIAMETER) {
            val syRow = rowBases[dyIdx]
            for (dx in 0 until DIAMETER) {
                val sx = ImageBorderOps.reflect101(x + dx - RADIUS, width)
                val neighbor = gray[syRow + sx].toInt() and 0xFF
                val diff = kotlin.math.abs(neighbor - center)
                val weight = KERNEL_WEIGHTS[(k shl 8) or diff]
                k++
                sum += weight * neighbor
                weights += weight
            }
        }
        filtered[rowOffset + x] = (sum / weights + 0.5).toInt().coerceIn(0, 255).toByte()
    }
}
