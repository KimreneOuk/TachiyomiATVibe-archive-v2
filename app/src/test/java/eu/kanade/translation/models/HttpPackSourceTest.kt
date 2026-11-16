package eu.kanade.translation.models

import eu.kanade.translation.models.source.HttpPackSource
import eu.kanade.translation.models.source.PackSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

class HttpPackSourceTest {
    @TempDir
    lateinit var tmp: File

    private fun source(bytes: ByteArray): HttpPackSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody())
                .build()
        }.build()
        return HttpPackSource(client)
    }

    @Test
    fun `streams a pinned HTTPS file`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val target = File(tmp, "model.part")
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        assertEquals(3L, source(bytes).copyInto(spec, target))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `rejects insecure unpinned and wrong size sources`() {
        val source = source(byteArrayOf(1, 2, 3))
        val target = File(tmp, "model.part")
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        listOf(
            spec.copy(sourceRef = "http://example.com/model"),
            spec.copy(sha256 = null),
            spec.copy(sizeBytes = -1),
            spec.copy(sizeBytes = 2),
        ).forEach { bad ->
            assertThrows(PackSourceException::class.java) { runBlocking { source.copyInto(bad, target) } }
        }
    }

    @Test
    fun `rejects short and oversized streams without a content length`() {
        fun stream(bytes: ByteArray): HttpPackSource {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = object : ResponseBody() {
                    private val buffer = Buffer().write(bytes)
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = buffer
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
            }.build()
            return HttpPackSource(client)
        }
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        listOf(byteArrayOf(1, 2), byteArrayOf(1, 2, 3, 4)).forEach { bytes ->
            assertThrows(PackSourceException::class.java) {
                runBlocking { stream(bytes).copyInto(spec, File(tmp, "incomplete.part")) }
            }
        }
    }

    /**
     * A 3xx that reaches the source must fail the download — no bytes staged.
     * The application-interceptor seam short-circuits above OkHttp's redirect
     * follower, so this pins the source's rejection of an un-followed redirect
     * response, not OkHttp's follow policy itself.
     */
    @Test
    fun `a redirect response is rejected and never streamed`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "http://example.com/model")
                .body(byteArrayOf(9, 9, 9).toResponseBody())
                .build()
        }.build()
        val target = File(tmp, "redirect.part")
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        assertThrows(PackSourceException::class.java) {
            runBlocking { HttpPackSource(client).copyInto(spec, target) }
        }
        assertFalse(target.exists())
    }

    /**
     * Simulates a client that followed a downgrade redirect to http://: OkHttp
     * reports the FINAL request on response.request, so a permissive client
     * could hand the source a 200 with an http URL. Pins the final-URL https
     * re-check on the success path.
     */
    @Test
    fun `a 200 response whose request is not https is rejected`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request().newBuilder().url("http://example2.com/model").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(byteArrayOf(1, 2, 3).toResponseBody())
                .build()
        }.build()
        val target = File(tmp, "downgrade.part")
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        assertThrows(PackSourceException::class.java) {
            runBlocking { HttpPackSource(client).copyInto(spec, target) }
        }
        assertFalse(target.exists())
    }

    /** Drip-feeds one byte per read so cancellation lands between reads, exactly where the source checks it. */
    private fun dripBody(total: Int, onThirdByte: CompletableDeferred<Unit>, onClose: () -> Unit): ResponseBody {
        val stream = object : InputStream() {
            private var delivered = 0
            private var closed = false

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                if (delivered >= total) return -1
                b[off] = 0x42
                delivered++
                // Signal after the second byte: reads 1-2 are already flushed to the target by then.
                if (delivered == 3) onThirdByte.complete(Unit)
                return 1
            }

            override fun read(): Int = read(ByteArray(1), 0, 1)

            override fun close() {
                if (!closed) {
                    closed = true
                    onClose()
                }
            }
        }
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = stream.source().buffer()
        }
    }

    @Test
    fun `cancellation mid-stream stops the transfer and closes the stream`() = runBlocking<Unit> {
        val total = 4096
        val spec = ModelFileSpec("model", total.toLong(), "a".repeat(64), "https://example.com/model")
        val target = File(tmp, "cancel.part")
        var streamClosed = false
        val thirdByte = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(dripBody(total, thirdByte) { streamClosed = true })
                .build()
        }.build()
        val deferred = async { HttpPackSource(client).copyInto(spec, target) }
        thirdByte.await()
        assertTrue(target.length() in 1 until total, "source should have written bytes before cancellation")
        deferred.cancel()
        val cancelled = try {
            deferred.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(cancelled, "copyInto must surface CancellationException")
        val partial = target.length()
        assertTrue(partial < total, "download must be interrupted mid-stream, got $partial of $total bytes")
        assertTrue(streamClosed, "the response stream must be closed on cancellation")
    }

    @Test
    fun `mismatched download never becomes an active pack`() = runBlocking {
        val spec = ModelFileSpec("model", 3, "a".repeat(64), "https://example.com/model")
        val descriptor = ModelPackDescriptor(
            id = "fixture",
            version = "1",
            role = PackRole.OPTIONAL,
            title = "Fixture",
            licenseNote = "Test only",
            sourceKind = PackSourceKind.HTTP,
            portability = Portability(ModelFormat.ONNX, Portability.Qualification.Unqualified),
            variants = listOf(ModelVariantDescriptor(ExecutionTarget.CPU, ModelFormat.ONNX, listOf(spec))),
        )
        val root = File(tmp, "packs")
        val manager = ModelPackManagerImpl(
            rootDir = root,
            bundledCacheRoot = File(tmp, "cache"),
            catalogProvider = { listOf(descriptor) },
            sourceFor = { source(byteArrayOf(1, 2, 3)) },
            networkMeteredProvider = { false },
        )

        val result = manager.install("fixture") as InstallResult.Failed
        assertEquals("model", (result.failure as PackFailure.VerificationMismatch).path)
        assertEquals(VerifyResult.NotInstalled, manager.verify("fixture"))
        assertFalse(root.walk().any { it.name.startsWith("staging-") })
    }
}
