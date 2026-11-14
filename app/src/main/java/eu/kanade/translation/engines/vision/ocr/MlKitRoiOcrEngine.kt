package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.util.await
import eu.kanade.translation.processing.memory.BitmapPool
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MlKitRoiOcrEngine(language: TextRecognizerLanguage) : RoiOcrEngine {

    private val textRecognizer = TextRecognizer(language)

    override suspend fun recognize(crop: Bitmap): String {
        val preprocessed = MlKitOcrPreprocessor.preprocessRoi(crop)
        try {
            val image = InputImage.fromBitmap(preprocessed, 0)
            val result = textRecognizer.recognize(image)
            return result.textBlocks
                .flatMap { it.lines }
                .joinToString("\n") { it.text }
                .trim()
        } finally {
            BitmapPool.putARGB8888(preprocessed)
        }
    }

    override fun close() = textRecognizer.close()
}

internal class TextRecognizer(val language: TextRecognizerLanguage) : Closeable {

    private val recognizer = TextRecognition.getClient(
        when (language) {
            TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            // All other languages use the single Latin recognizer. Russian is Cyrillic
            // — ML Kit has no Cyrillic model, so it falls back to Latin best-effort;
            // PaddleOCR v6 small (Cyrillic-aware) is the recommended engine for Russian.
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        },
    )

    suspend fun recognize(image: InputImage): Text {
        return recognizer.process(image).await()
    }

    override fun close() {
        recognizer.close()
    }
}

internal object MlKitOcrPreprocessor {

    fun preprocessRoi(crop: Bitmap): Bitmap {
        val sourceWidth = crop.width.coerceAtLeast(1)
        val sourceHeight = crop.height.coerceAtLeast(1)
        val minSide = min(sourceWidth, sourceHeight)
        val initialScale = if (minSide < TARGET_MIN_SIDE) {
            (TARGET_MIN_SIDE.toFloat() / minSide).coerceIn(1f, MAX_SCALE)
        } else {
            1f
        }
        val maxScaledSide = max(sourceWidth, sourceHeight) * initialScale
        val scale = if (maxScaledSide > MAX_SIDE) {
            initialScale * (MAX_SIDE / maxScaledSide)
        } else {
            initialScale
        }
        val scaledWidth = ceil(sourceWidth * scale).toInt().coerceAtLeast(1)
        val scaledHeight = ceil(sourceHeight * scale).toInt().coerceAtLeast(1)
        val padding = ceil(min(scaledWidth, scaledHeight) * PADDING_FRACTION)
            .toInt()
            .coerceIn(MIN_PADDING, MAX_PADDING)
        val outputWidth = scaledWidth + padding * 2
        val outputHeight = scaledHeight + padding * 2

        val output = BitmapPool.getARGB8888(outputWidth, outputHeight)
        output.eraseColor(Color.WHITE)

        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        CONTRAST, 0f, 0f, 0f, CONTRAST_TRANSLATE,
                        0f, CONTRAST, 0f, 0f, CONTRAST_TRANSLATE,
                        0f, 0f, CONTRAST, 0f, CONTRAST_TRANSLATE,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(
            crop,
            null,
            RectF(
                padding.toFloat(),
                padding.toFloat(),
                (padding + scaledWidth).toFloat(),
                (padding + scaledHeight).toFloat(),
            ),
            paint,
        )
        return output
    }

    private const val TARGET_MIN_SIDE = 96
    private const val MAX_SCALE = 3f
    private const val MAX_SIDE = 1600f
    private const val PADDING_FRACTION = 0.08f
    private const val MIN_PADDING = 4
    private const val MAX_PADDING = 24
    private const val CONTRAST = 1.2f
    private const val CONTRAST_TRANSLATE = (1f - CONTRAST) * 127.5f
}
