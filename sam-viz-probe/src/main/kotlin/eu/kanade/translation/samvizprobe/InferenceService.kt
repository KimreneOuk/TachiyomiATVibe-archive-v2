package eu.kanade.translation.samvizprobe

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns both engine runners and turns requests into versioned tensor bundles.
 *  Bundle v2: ascii "SVIZ", u32 version=2, u32 header length, UTF-8 JSON header
 *  (engine, timings, extra, sections [{name,count}…]), then little-endian f32
 *  sections in declared order. */
internal class InferenceService(private val context: Context) {

    @Volatile
    var mangaLight: MangaLightRunner? = null
        private set

    @Volatile
    var mcV2: McV2Runner? = null
        private set

    @Volatile
    var mangaLightError: String? = null
        private set

    @Volatile
    var mcV2Error: String? = null
        private set

    /** Opens both engines; one failing must not block the other. */
    fun openAll() {
        try {
            mangaLight = MangaLightRunner.open(context)
        } catch (failure: Throwable) {
            mangaLightError = failure.message ?: failure.javaClass.simpleName
        }
        try {
            mcV2 = McV2Runner.open(context)
        } catch (failure: Throwable) {
            mcV2Error = failure.message ?: failure.javaClass.simpleName
        }
    }

    fun closeAll() {
        mangaLight?.close(); mangaLight = null
        mcV2?.close(); mcV2 = null
    }

    fun infer(engine: String, imageBytes: ByteArray, hintBytes: ByteArray?): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("undecodable image (${imageBytes.size} bytes)")
        try {
            return when (engine) {
                "mangalight" -> {
                    val runner = checkNotNull(mangaLight) { "MangaLight unavailable: $mangaLightError" }
                    val result = runner.run(bitmap)
                    bundle(
                        "mangalight",
                        JSONObject()
                            .put("preprocessMs", result.preprocessMs)
                            .put("samMs", result.samMs)
                            .put("packMs", result.packMs)
                            .put("generatorMs", result.generatorMs)
                            .put("totalMs", result.totalMs),
                        listOf(
                            "luma" to result.luma,
                            "level0" to result.level0,
                            "level1" to result.level1,
                            "rgb_pred" to result.rgbPred,
                        ),
                        JSONObject().put("workSize", MangaLightRunner.WORK_SIZE),
                    )
                }
                "mcv2" -> {
                    val runner = checkNotNull(mcV2) { "MCV2 unavailable: $mcV2Error" }
                    val result = runner.run(bitmap, hintBytes)
                    bundle(
                        "mcv2",
                        JSONObject()
                            .put("preprocessMs", result.preprocessMs)
                            .put("inferenceMs", result.inferenceMs)
                            .put("packMs", result.packMs)
                            .put("totalMs", result.totalMs),
                        listOf("rgb" to result.rgb),
                        JSONObject()
                            .put("contentWidth", result.contentWidth)
                            .put("contentHeight", result.contentHeight),
                    )
                }
                else -> throw IllegalArgumentException("unknown engine $engine")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bundle(
        engine: String,
        timings: JSONObject,
        sections: List<Pair<String, FloatArray>>,
        extra: JSONObject,
    ): ByteArray {
        val header = JSONObject()
            .put("engine", engine)
            .put("timings", timings)
            .put("extra", extra)
            .put(
                "sections",
                JSONArray().apply {
                    sections.forEach { (name, values) ->
                        put(JSONObject().put("name", name).put("count", values.size))
                    }
                },
            )
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val floatCount = sections.sumOf { it.second.size }
        val buffer = ByteBuffer.allocate(12 + headerBytes.size + floatCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("SVIZ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(VERSION)
        buffer.putInt(headerBytes.size)
        buffer.put(headerBytes)
        sections.forEach { (_, values) ->
            buffer.asFloatBuffer().put(values)
            buffer.position(buffer.position() + values.size * 4)
        }
        return buffer.array()
    }

    private companion object {
        const val VERSION = 2
    }
}
