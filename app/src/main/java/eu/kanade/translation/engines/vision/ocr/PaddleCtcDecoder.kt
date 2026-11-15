package eu.kanade.translation.engines.vision.ocr

import java.nio.FloatBuffer

object PaddleCtcDecoder {
    private const val BLANK_INDEX = 0

    fun decode(indices: IntArray, dictionary: List<String>): String {
        val spaceIndex = dictionary.size + 1
        val builder = StringBuilder()
        var previous = -1
        for (index in indices) {
            if (index != previous && index != BLANK_INDEX) {
                when (index) {
                    in 1..dictionary.size -> builder.append(dictionary[index - 1])
                    spaceIndex -> builder.append(' ')
                }
            }
            previous = index
        }
        return builder.toString().trim()
    }

    fun decode(
        logits: FloatBuffer,
        shape: LongArray,
        dictionary: List<String>,
    ): List<Pair<String, Float>> {
        require(shape.size == 3) { "Paddle OCR output must be [B,T,C], got ${shape.contentToString()}" }
        val batchSize = shape[0].toIntChecked("batch")
        val timeSteps = shape[1].toIntChecked("time steps")
        val classCount = shape[2].toIntChecked("classes")
        val rowElements = Math.multiplyExact(timeSteps.toLong(), classCount.toLong())
        val required = Math.multiplyExact(batchSize.toLong(), rowElements)
        require(required <= logits.limit().toLong()) {
            "Paddle OCR output logits=${logits.limit()} expected=$required"
        }

        return ArrayList<Pair<String, Float>>(batchSize).apply {
            for (batchIndex in 0 until batchSize) {
                val rowStart = (batchIndex.toLong() * rowElements).toInt()
                val row = logits.duplicate().apply {
                    position(rowStart)
                    limit(rowStart + rowElements.toInt())
                }.slice()
                val (indices, maxValues) = argmaxWithProbs(
                    row,
                    timeSteps = timeSteps,
                    classCount = classCount,
                )
                add(decodeWithConf(indices, maxValues, dictionary))
            }
        }
    }

    private fun Long.toIntChecked(label: String): Int {
        require(this in 1..Int.MAX_VALUE) { "Paddle OCR $label=$this is invalid" }
        return toInt()
    }

    fun argmaxIndices(
        logits: FloatBuffer,
        timeSteps: Int,
        classCount: Int,
    ): IntArray {
        val indices = IntArray(timeSteps)
        if (timeSteps == 0 || classCount == 0) return indices
        val rowBuffer = FloatArray(classCount)
        val dup = logits.duplicate()
        val basePos = dup.position()
        for (timeStep in 0 until timeSteps) {
            dup.position(basePos + timeStep * classCount)
            dup.get(rowBuffer, 0, classCount)
            var maxIndex = 0
            var maxValue = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val value = rowBuffer[classIndex]
                if (value > maxValue) {
                    maxValue = value
                    maxIndex = classIndex
                }
            }
            indices[timeStep] = maxIndex
        }
        return indices
    }

    fun argmaxWithProbs(
        logits: FloatBuffer,
        timeSteps: Int,
        classCount: Int,
    ): Pair<IntArray, FloatArray> {
        val indices = IntArray(timeSteps)
        val maxProbs = FloatArray(timeSteps)
        if (timeSteps == 0 || classCount == 0) return Pair(indices, maxProbs)
        val rowBuffer = FloatArray(classCount)
        val dup = logits.duplicate()
        val basePos = dup.position()
        for (timeStep in 0 until timeSteps) {
            dup.position(basePos + timeStep * classCount)
            dup.get(rowBuffer, 0, classCount)
            var maxIndex = 0
            var maxValue = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val value = rowBuffer[classIndex]
                if (value > maxValue) {
                    maxValue = value
                    maxIndex = classIndex
                }
            }
            indices[timeStep] = maxIndex
            maxProbs[timeStep] = maxValue
        }
        return Pair(indices, maxProbs)
    }

    fun decodeWithConf(
        indices: IntArray,
        maxProbs: FloatArray,
        dictionary: List<String>,
    ): Pair<String, Float> {
        val spaceIndex = dictionary.size + 1
        val builder = StringBuilder()
        var previous = -1
        var confSum = 0f
        var confCount = 0
        for (i in indices.indices) {
            val index = indices[i]
            if (index != previous && index != BLANK_INDEX) {
                when (index) {
                    in 1..dictionary.size -> {
                        builder.append(dictionary[index - 1])
                        confSum += maxProbs[i]
                        confCount++
                    }
                    spaceIndex -> builder.append(' ')
                }
            }
            previous = index
        }
        val text = builder.toString().trim()
        val confidence = if (confCount > 0) confSum / confCount else 0f
        return text to confidence
    }
}
