package eu.kanade.translation.models.source

import android.content.Context
import eu.kanade.translation.models.ModelFileSpec
import eu.kanade.translation.models.PackSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when a source cannot provide the requested bytes; mapped to PackFailure.SourceUnavailable. */
class PackSourceException(message: String) : IOException(message)

/** Provides pack file bytes into a staging target. Implementations never touch installed versions. */
interface ModelPackSource {
    val kind: PackSourceKind

    /** Streams the spec's source bytes into [target]; returns bytes written. */
    suspend fun copyInto(spec: ModelFileSpec, target: File): Long
}

class LocalDirectoryPackSource(private val root: File) : ModelPackSource {

    override val kind = PackSourceKind.LOCAL_DIR

    override suspend fun copyInto(spec: ModelFileSpec, target: File): Long {
        val ref = requireNotNull(spec.sourceRef) { "LOCAL_DIR source requires sourceRef for ${spec.path}" }
        val source = File(root, ref)
        if (!source.isFile) throw PackSourceException("Missing source file: ${source.path}")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.length()
    }
}

/** Mirrors the APK's build-time verified assets into a pack install. No network is contacted. */
class AssetPackSource(private val context: Context) : ModelPackSource {

    override val kind = PackSourceKind.ASSET_MIRROR

    override suspend fun copyInto(spec: ModelFileSpec, target: File): Long {
        val ref = requireNotNull(spec.sourceRef) { "ASSET_MIRROR source requires sourceRef for ${spec.path}" }
        context.assets.open(ref).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.length()
    }
}

class HttpPackSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(1, TimeUnit.HOURS)
        .build(),
) : ModelPackSource {

    override val kind = PackSourceKind.HTTP

    override suspend fun copyInto(spec: ModelFileSpec, target: File): Long = withContext(Dispatchers.IO) {
        val url = spec.sourceRef?.toHttpUrlOrNull()
        if (url == null || !url.isHttps) throw PackSourceException("Model downloads require an HTTPS URL")
        if (spec.sizeBytes < 0L || spec.sha256?.matches(Regex("[0-9a-f]{64}")) != true) {
            throw PackSourceException("Model downloads require a known size and pinned SHA-256")
        }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !response.request.url.isHttps) {
                throw PackSourceException("Model download failed with HTTP ${response.code}")
            }
            val declaredSize = response.body.contentLength()
            if (declaredSize >= 0 && declaredSize != spec.sizeBytes) {
                throw PackSourceException("Download size differs from the pinned size for ${spec.path}")
            }
            var written = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (written > spec.sizeBytes - count) {
                            throw PackSourceException("Download exceeds the pinned size for ${spec.path}")
                        }
                        output.write(buffer, 0, count)
                        written += count
                    }
                }
            }
            if (written != spec.sizeBytes) {
                throw PackSourceException("Download ended before the pinned size for ${spec.path}")
            }
            written
        }
    }
}
