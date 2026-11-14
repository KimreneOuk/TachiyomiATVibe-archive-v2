package eu.kanade.translation.engines.inpainting.aot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.InpaintPadOps
import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.PushPullGradient
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.FEATHER_RAMP_PX
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.MAX_INFERENCE_DIM
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator.Companion.MAX_TOTAL_PIXELS
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.processing.memory.BitmapPool
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Variable-shape ONNX inference stage (legacy dynamic path): crop/resize,
 * tensor packing, decode, output guard, and feathered blending.
 */

internal fun NeuralInpaintingCoordinator.inpaint(
    sess: OrtSession,
    image: Bitmap,
    maskBitmap: Bitmap,
    cropBounds: IntArray,
    maskAlreadyCropped: Boolean = false,
    featherRampPx: Int = FEATHER_RAMP_PX,
    fixedShape: Boolean = false,
    route: String = "dynamic",
): Bitmap {
    val cropMargin = 32
    val originalWidth = image.width
    val originalHeight = image.height

    val boxX1 = cropBounds[0]
    val boxY1 = cropBounds[1]
    val boxX2 = cropBounds[2]
    val boxY2 = cropBounds[3]

    val yMin = if (maskAlreadyCropped) boxY1 else max(0, boxY1 - cropMargin)
    val yMax = if (maskAlreadyCropped) boxY2 else min(originalHeight, boxY2 + cropMargin + 1)
    val xMin = if (maskAlreadyCropped) boxX1 else max(0, boxX1 - cropMargin)
    val xMax = if (maskAlreadyCropped) boxX2 else min(originalWidth, boxX2 + cropMargin + 1)

    val cropWidth = xMax - xMin
    val cropHeight = yMax - yMin

    val cropPixelsOriginal = IntArray(cropWidth * cropHeight)
    image.getPixels(cropPixelsOriginal, 0, cropWidth, xMin, yMin, cropWidth, cropHeight)

    var totalChroma = 0
    val sampleStride = max(1, cropPixelsOriginal.size / 400)
    for (i in cropPixelsOriginal.indices step sampleStride) {
        val px = cropPixelsOriginal[i]
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        totalChroma += (maxC - minC)
    }
    val avgChroma = totalChroma / (cropPixelsOriginal.size / sampleStride)
    val isGrayscale = avgChroma < 15

    require(!fixedShape || (cropWidth == cropHeight && cropWidth in 1..InpaintPadOps.SIZE)) {
        "fixed AOT requires a square crop in 1..${InpaintPadOps.SIZE}, actual=${cropWidth}x$cropHeight"
    }
    val needsResize = !fixedShape && max(cropWidth, cropHeight) > MAX_INFERENCE_DIM
    val inferenceWidth: Int
    val inferenceHeight: Int
    if (fixedShape) {
        inferenceWidth = InpaintPadOps.SIZE
        inferenceHeight = InpaintPadOps.SIZE
    } else if (needsResize) {
        val scale = MAX_INFERENCE_DIM.toFloat() / max(cropWidth, cropHeight)
        val wScaled = max(8, (cropWidth * scale).toInt())
        val hScaled = max(8, (cropHeight * scale).toInt())
        inferenceWidth = wScaled + (8 - wScaled % 8) % 8
        inferenceHeight = hScaled + (8 - hScaled % 8) % 8
    } else {
        val padW = (8 - cropWidth % 8) % 8
        val padH = (8 - cropHeight % 8) % 8
        inferenceWidth = cropWidth + padW
        inferenceHeight = cropHeight + padH
    }
    val fixedOffset = if (fixedShape) InpaintPadOps.centeredOffset(cropWidth) else 0

    var imgInput: Bitmap? = null
    var maskInput: Bitmap? = null
    var imgTensor: OnnxTensor? = null
    var maskTensor: OnnxTensor? = null
    var imgBuffer: FloatBuffer? = null
    var maskBuffer: FloatBuffer? = null
    var results: OrtSession.Result? = null
    var resultBitmap: Bitmap? = null
    var scaled: Bitmap? = null
    var blended: Bitmap? = null
    try {
        imgInput = BitmapPool.getARGB8888(inferenceWidth, inferenceHeight)
        val fixedBackground = if (fixedShape) {
            val cropMask = ByteArray(cropWidth * cropHeight)
            val localMaskPixels = IntArray(cropWidth * cropHeight)
            maskBitmap.getPixels(localMaskPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
            for (i in cropMask.indices) {
                if (InpaintPixelOps.maskValue(localMaskPixels[i]) > 127) cropMask[i] = 1
            }
            PushPullGradient.localRingMedian(cropPixelsOriginal, cropWidth, cropHeight, cropMask, PushPullGradient.DEFAULT_RING)
        } else {
            0
        }
        imgInput.eraseColor(fixedBackground)
        val imgInputCanvas = android.graphics.Canvas(imgInput)
        if (fixedShape) {
            imgInputCanvas.drawBitmap(
                image,
                android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight),
                android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                null,
            )
        } else if (needsResize) {
            imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
        } else {
            imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
        }

        maskInput = BitmapPool.getARGB8888(inferenceWidth, inferenceHeight)
        maskInput.eraseColor(0)
        val maskInputCanvas = android.graphics.Canvas(maskInput)
        if (maskAlreadyCropped) {
            if (fixedShape) {
                maskInputCanvas.drawBitmap(
                    maskBitmap,
                    android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height),
                    android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                    null,
                )
            } else if (needsResize) {
                maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
            } else {
                maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
            }
        } else {
            if (needsResize) {
                maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
            } else {
                maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
            }
        }

        // Reuse the bounded scratch arrays already used by postprocess.
        // getPixels overwrites every element, so a prior page cannot bleed
        // into this tensor even when the inference dimensions shrink.
        val imgPixels = getImgPixels()
        imgInput.getPixels(imgPixels, 0, inferenceWidth, 0, 0, inferenceWidth, inferenceHeight)
        val maskPixels = getMaskPixels()
        maskInput.getPixels(maskPixels, 0, inferenceWidth, 0, 0, inferenceWidth, inferenceHeight)

        imgBuffer = imgInputPool.acquire()
        maskBuffer = maskInputPool.acquire()

        imgBuffer.clear()
        maskBuffer.clear()

        imgBuffer.limit(3 * inferenceWidth * inferenceHeight)
        maskBuffer.limit(1 * inferenceWidth * inferenceHeight)

        val channelSize = inferenceWidth * inferenceHeight
        for (i in 0 until channelSize) {
            val px = imgPixels[i]
            var r = (px shr 16 and 0xFF) / 127.5f - 1.0f
            var g = (px shr 8 and 0xFF) / 127.5f - 1.0f
            var b = (px and 0xFF) / 127.5f - 1.0f

            val maskPixel = maskPixels[i]
            val maskVal = if (InpaintPixelOps.maskValue(maskPixel) > 127) 1.0f else 0.0f
            maskBuffer.put(0 * channelSize + i, maskVal)

            r *= (1.0f - maskVal)
            g *= (1.0f - maskVal)
            b *= (1.0f - maskVal)

            imgBuffer.put(0 * channelSize + i, r)
            imgBuffer.put(1 * channelSize + i, g)
            imgBuffer.put(2 * channelSize + i, b)
        }

        val env = OnnxRuntimeProvider.environment
        imgTensor = OnnxTensor.createTensor(env, imgBuffer, longArrayOf(1, 3, inferenceHeight.toLong(), inferenceWidth.toLong()))
        maskTensor = OnnxTensor.createTensor(env, maskBuffer, longArrayOf(1, 1, inferenceHeight.toLong(), inferenceWidth.toLong()))

        val t0 = System.nanoTime()
        val imgTensorValue = imgTensor!!
        val maskTensorValue = maskTensor!!
        val feed = mapOf("image" to imgTensorValue, "mask" to maskTensorValue)
        results = sess.run(feed)
        val t1 = System.nanoTime()

        val outputTensor = results!![0] as OnnxTensor
        val outputShape = outputTensor.info.shape
        val outH = outputShape[2].toInt()
        val outW = outputShape[3].toInt()
        val outputBuf = outputTensor.floatBuffer

        val resultH = outH
        val resultW = outW

        val resultPixels = IntArray(resultW * resultH)
        val outChannels = outH * outW
        for (y in 0 until resultH) {
            for (x in 0 until resultW) {
                val idx = y * resultW + x
                val srcIdx = y * outW + x
                val rChannel = ((outputBuf.get(0 * outChannels + srcIdx) + 1.0f) * 127.5f)
                    .roundToInt().coerceIn(0, 255)
                val gChannel = ((outputBuf.get(1 * outChannels + srcIdx) + 1.0f) * 127.5f)
                    .roundToInt().coerceIn(0, 255)
                val bChannel = ((outputBuf.get(2 * outChannels + srcIdx) + 1.0f) * 127.5f)
                    .roundToInt().coerceIn(0, 255)
                if (isGrayscale) {
                    val l = (0.299f * rChannel + 0.587f * gChannel + 0.114f * bChannel).roundToInt()
                    resultPixels[idx] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
                } else {
                    resultPixels[idx] = (0xFF shl 24) or (rChannel shl 16) or (gChannel shl 8) or bChannel
                }
            }
        }

        resultBitmap = BitmapPool.getARGB8888(resultW, resultH)
        resultBitmap.setPixels(resultPixels, 0, resultW, 0, 0, resultW, resultH)

        scaled = BitmapPool.getARGB8888(cropWidth, cropHeight)
        val scaledCanvas = android.graphics.Canvas(scaled)
        if (fixedShape) {
            scaledCanvas.drawBitmap(
                resultBitmap,
                android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                android.graphics.Rect(0, 0, cropWidth, cropHeight),
                null,
            )
        } else if (needsResize) {
            scaledCanvas.drawBitmap(
                resultBitmap,
                null,
                android.graphics.RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
                null,
            )
        } else {
            scaledCanvas.drawBitmap(
                resultBitmap,
                android.graphics.Rect(0, 0, cropWidth, cropHeight),
                android.graphics.RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
                null,
            )
        }

        val candidate = scaled
        val guardStats = isSuspiciousUniformOutput(candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropWidth, cropHeight)
        if (guardStats != null) {
            logcat(LogPriority.INFO) {
                "[inpaint] route=$route output_diagnostic=suspicious_uniform decision=none " +
                    "mean=${"%.1f".format(guardStats.mean)} " +
                    "variance=${"%.1f".format(guardStats.variance)} " +
                    "channelDelta=${"%.1f".format(guardStats.channelDelta)} " +
                    "masked=${guardStats.maskedCount} crop=${cropWidth}x$cropHeight"
            }
        }

        blended = featherBlend(image, candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropWidth, cropHeight, featherRampPx)

        val canvas = android.graphics.Canvas(image)
        canvas.drawBitmap(blended ?: throw IllegalStateException("Inpainting blend was not created"), xMin.toFloat(), yMin.toFloat(), null)

        logcat(LogPriority.INFO) {
            "[inpaint] route=$route modelMs=${(t1 - t0) / 1_000_000.0} crop=${cropWidth}x$cropHeight " +
                "tensor=${inferenceWidth}x$inferenceHeight offset=$fixedOffset,$fixedOffset outputDiagnostic=only"
        }

        return image
    } finally {
        results?.close()
        imgTensor?.close()
        maskTensor?.close()
        imgBuffer?.let { imgInputPool.release(it) }
        maskBuffer?.let { maskInputPool.release(it) }
        if (blended != null) BitmapPool.putARGB8888(blended)
        // scaled is a fresh bitmap (not an alias of resultBitmap)
        // after the normalization step above, so it is released separately.
        if (scaled != null) BitmapPool.putARGB8888(scaled)
        if (resultBitmap != null) BitmapPool.putARGB8888(resultBitmap)
        if (maskInput != null) BitmapPool.putARGB8888(maskInput)
        if (imgInput != null) BitmapPool.putARGB8888(imgInput)
    }
}

/**
 * Returns masked-region stats when the AOT candidate is a suspicious uniform
 * fill (near-black / mid-gray / near-white), else null. Renamed from
 * isSuspiciousGrayOutput since the guard now covers three failure modes.
 */
internal fun NeuralInpaintingCoordinator.isSuspiciousUniformOutput(
    inpainted: Bitmap,
    mask: Bitmap,
    maskAlreadyCropped: Boolean,
    xMin: Int,
    yMin: Int,
    width: Int,
    height: Int,
): AotOutputGuard.GuardStats? {
    // clamp the read region to the actual bitmap bounds as a
    // safety net (a delegate shape mismatch / OOM partial fill could produce
    // a smaller bitmap; the guard result on the visible region is still valid).
    val inpW = min(width, inpainted.width)
    val inpH = min(height, inpainted.height)
    val mskW = min(width, mask.width)
    val mskH = min(height, mask.height)
    val safeW = min(inpW, mskW)
    val safeH = min(inpH, mskH)

    // size buffers to the ACTUAL read region, not the pooled
    // MAX_TOTAL_PIXELS. width/height are unbounded CROP dims (cropMargin can
    // exceed MAX_INFERENCE_DIM); reading them into the pooled buffer overflows
    // â†’ ArrayIndexOutOfBoundsException that crashed inpaint on large text
    // regions. Fall back to a transient allocation when the crop exceeds the pool cap.
    val readSize = safeW * safeH
    val inpaintedPixels = if (readSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(readSize)
    val maskPixels = if (readSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(readSize)
    inpainted.getPixels(inpaintedPixels, 0, safeW, 0, 0, safeW, safeH)
    if (maskAlreadyCropped) {
        mask.getPixels(maskPixels, 0, safeW, 0, 0, safeW, safeH)
    } else {
        mask.getPixels(maskPixels, 0, safeW, xMin, yMin, safeW, safeH)
    }
    val stats = AotOutputGuard.inspect(inpaintedPixels, maskPixels, safeW, safeH)
    return if (AotOutputGuard.classify(stats)) stats else null
}

internal fun NeuralInpaintingCoordinator.featherBlend(
    original: Bitmap,
    inpainted: Bitmap,
    mask: Bitmap,
    maskAlreadyCropped: Boolean,
    xMin: Int,
    yMin: Int,
    width: Int,
    height: Int,
    rampWidth: Int = FEATHER_RAMP_PX,
): Bitmap {
    val result = BitmapPool.getARGB8888(width, height)
    // size buffers to the ACTUAL crop dims, not the pooled
    // MAX_TOTAL_PIXELS â€” cropMargin can push the crop past the pool cap and
    // overflow (the page-24 inpaint crash). Transient alloc above the cap.
    val blendSize = width * height
    val origPixels = if (blendSize <= MAX_TOTAL_PIXELS) getImgPixels() else IntArray(blendSize)
    val inpPixels = if (blendSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(blendSize)
    val maskPixels = if (blendSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(blendSize)

    original.getPixels(origPixels, 0, width, xMin, yMin, width, height)

    // clamp the inpainted bitmap read to its actual size.
    // The unconditional normalization in inpaint() makes the inpainted
    // bitmap cropW Ã— cropH, but this clamp is a last-resort safety net.
    val inpW = min(width, inpainted.width)
    val inpH = min(height, inpainted.height)
    inpainted.getPixels(inpPixels, 0, inpW, 0, 0, inpW, inpH)
    // Fill pixels beyond the clamped region with the original so the
    // blend loop below never reads uninitialised data.
    for (y in 0 until inpH) {
        for (x in inpW until width) {
            inpPixels[y * width + x] = origPixels[y * width + x]
        }
    }
    for (y in inpH until height) {
        for (x in 0 until width) {
            inpPixels[y * width + x] = origPixels[y * width + x]
        }
    }

    if (maskAlreadyCropped) {
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
    } else {
        mask.getPixels(maskPixels, 0, width, xMin, yMin, width, height)
    }

    // distance-field feather. The old box-average alpha was a
    // thin cliff that exposed the box rectangle; the chamfer transform gives
    // a smooth monotonic alpha ramp over FEATHER_RAMP_PX from the mask edge.
    val maskBytes = ByteArray(blendSize)
    for (i in 0 until blendSize) {
        if (InpaintPixelOps.maskValue(maskPixels[i]) > 127) maskBytes[i] = 1
    }
    val alphaField = if (rampWidth <= 0) {
        FloatArray(blendSize) { idx -> if (maskBytes[idx] != 0.toByte()) 1.0f else 0.0f }
    } else {
        BubbleMaskBuilder.featherAlphaField(
            mask = maskBytes,
            width = width,
            height = height,
            rampWidth = rampWidth,
        )
    }
    InpaintPixelOps.compositeInto(origPixels, inpPixels, alphaField, origPixels)
    result.setPixels(origPixels, 0, width, 0, 0, width, height)
    return result
}
