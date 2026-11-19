package eu.kanade.translation.samvizprobe

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback-only HTTP bridge for the laptop visualizer (adb forward connects via
 *  the phone's own localhost; LAN peers cannot reach it): GET /health, POST /infer
 *  (multipart: "engine" text field, "image" file, optional "hints" RGBA PNG file),
 *  replying with the InferenceService tensor bundle. */
internal class VizServer(
    private val listenPort: Int,
    private val serviceProvider: () -> InferenceService?,
    private val statusProvider: () -> String,
) : NanoHTTPD("127.0.0.1", listenPort) {

    private val inFlight = AtomicBoolean(false)

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/health" -> health()
            "/infer" -> if (session.method == Method.POST) infer(session) else error(Response.Status.METHOD_NOT_ALLOWED, "POST only")
            else -> error(Response.Status.NOT_FOUND, "unknown path ${session.uri}")
        }
    }

    private fun health(): Response {
        val service = serviceProvider()
        val body = JSONObject()
            .put("status", if (service != null) "ready" else "starting")
            .put("detail", statusProvider())
            .put("busy", inFlight.get())
            .put("port", listenPort)
            .put(
                "engines",
                JSONObject().apply {
                    put("mangalight", engineStatus(service?.mangaLight != null, service?.mangaLightError))
                    put("mcv2", engineStatus(service?.mcV2 != null, service?.mcV2Error))
                },
            )
            .put(
                "models",
                JSONArray().apply {
                    MangaLightRunner.ARTIFACTS.forEach {
                        put(JSONObject().put("engine", "mangalight").put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256))
                    }
                    McV2Runner.ARTIFACT.let {
                        put(JSONObject().put("engine", "mcv2").put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256))
                    }
                },
            )
        return json(Response.Status.OK, body)
    }

    private fun engineStatus(ready: Boolean, failure: String?): JSONObject =
        JSONObject().put("ready", ready).put("detail", failure ?: "ok")

    private fun infer(session: IHTTPSession): Response {
        if (!inFlight.compareAndSet(false, true)) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "busy — one inference at a time")
        }
        try {
            val service = serviceProvider()
                ?: return error(Response.Status.SERVICE_UNAVAILABLE, "service not ready: ${statusProvider()}")
            val files = HashMap<String, String>()
            session.parseBody(files)
            val engine = session.parms["engine"] ?: "mangalight"
            val imagePath = files["image"]
                ?: return error(Response.Status.BAD_REQUEST, "missing multipart form field 'image'")
            val imageBytes = File(imagePath).readBytes(); File(imagePath).delete()
            val hintPath = files["hints"]
            val hintBytes = hintPath?.let { File(it).readBytes().also { _ -> File(it).delete() } }
            VizBus.requestCount.incrementAndGet()
            val payload = service.infer(engine, imageBytes, hintBytes)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(payload),
                payload.size.toLong(),
            )
        } finally {
            inFlight.set(false)
        }
    }

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.IStatus, message: String): Response =
        json(status, JSONObject().put("error", message))
}
