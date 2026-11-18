package eu.kanade.translation.colorreference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Manages persistent story color references keyed by manga ID under noBackupFilesDir. */
class StoryColorReferenceRepository(
    private val context: Context,
) {
    private val rootDir = File(context.noBackupFilesDir, "story-color-references")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val memoryState = ConcurrentHashMap<Long, MutableStateFlow<StoryColorReferenceState>>()
    private val mutationMutex = Mutex()

    init {
        rootDir.mkdirs()
        sweepTempFiles()
    }

    private fun getMangaDir(mangaId: Long): File = File(rootDir, mangaId.toString())

    private fun getMetaFile(mangaId: Long): File = File(getMangaDir(mangaId), "reference.meta.json")

    private fun getImageFile(mangaId: Long): File = File(getMangaDir(mangaId), "reference.png")

    /** Removes *.tmp.* files left behind by a crash during a reference swap. */
    private fun sweepTempFiles() {
        try {
            rootDir.walkTopDown()
                .filter { it.isFile && it.name.contains(".tmp.") }
                .forEach { it.delete() }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to sweep story color reference temp files" }
        }
    }

    private fun getFlowForManga(mangaId: Long): MutableStateFlow<StoryColorReferenceState> =
        memoryState.computeIfAbsent(mangaId) {
            MutableStateFlow(loadStateFromDisk(mangaId))
        }

    fun observe(mangaId: Long): Flow<StoryColorReferenceState> =
        getFlowForManga(mangaId).asStateFlow()

    fun getSnapshot(mangaId: Long): ColorReferenceSnapshot? {
        val metaFile = getMetaFile(mangaId)
        if (!metaFile.exists()) return null
        // The image is only a thumbnail: grading runs from the persisted
        // profile, so its absence does not invalidate the snapshot.
        val imageFile = getImageFile(mangaId).takeIf { it.exists() && it.length() > 0L }
        return try {
            val meta = json.decodeFromString<StoryColorReferenceMetadata>(metaFile.readText())
            ColorReferenceSnapshot(
                mangaId = mangaId,
                isEnabled = meta.isEnabled,
                contentHash = meta.contentHash,
                algorithmVersion = meta.algorithmVersion,
                strength = meta.strength,
                profile = meta.profile,
                imageFile = imageFile,
            )
        } catch (e: Throwable) {
            // Enabled sentinel: the gates (run-level, manual, per-page) treat
            // an undecodable meta as enabled-but-unavailable and fail visibly
            // instead of silently running ungraded.
            logcat(LogPriority.WARN, e) { "Failed to decode story color reference snapshot for manga $mangaId" }
            ColorReferenceSnapshot(mangaId = mangaId, isEnabled = true)
        }
    }

    suspend fun setEnabled(mangaId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val metaFile = getMetaFile(mangaId)
            if (!metaFile.exists()) return@withLock
            try {
                val meta = json.decodeFromString<StoryColorReferenceMetadata>(metaFile.readText())
                val updated = meta.copy(isEnabled = enabled)
                metaFile.writeText(json.encodeToString(updated))
                getFlowForManga(mangaId).value = StoryColorReferenceState(
                    isEnabled = enabled,
                    hasReference = true,
                    contentHash = updated.contentHash,
                    thumbnailFile = getImageFile(mangaId).takeIf { it.exists() && it.length() > 0L },
                )
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to update story color reference enabled state for manga $mangaId" }
            }
        }
    }

    suspend fun setReferenceImage(
        mangaId: Long,
        inputStream: InputStream,
        enabled: Boolean = true,
    ): Result<StoryColorReferenceState> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            try {
                val bytes = inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) {
                    return@withLock Result.failure(IllegalArgumentException("Reference image stream is empty"))
                }

                // Decode bounds
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                    return@withLock Result.failure(IllegalArgumentException("Cannot decode reference image"))
                }

                // Bound max stored image dimension to 1024 to protect memory and storage
                var sampleSize = 1
                val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
                while ((maxDim / sampleSize) > 1024) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: return@withLock Result.failure(IllegalArgumentException("Failed to decode reference bitmap"))

                val profile = try {
                    ColorReferenceProfileExtractor.extract(bitmap)
                } finally {
                    bitmap.recycle()
                }

                if (!profile.isUsable) {
                    return@withLock Result.failure(
                        IllegalStateException("Reference image lacks sufficient color or is nearly monochrome"),
                    )
                }

                val hash = sha256(bytes)
                val mangaDir = getMangaDir(mangaId).apply { mkdirs() }
                sweepTempFiles()
                val targetImage = getImageFile(mangaId)
                val targetMeta = getMetaFile(mangaId)

                val tempImage = File(mangaDir, "reference.tmp.png")
                val tempMeta = File(mangaDir, "reference.meta.tmp.json")

                try {
                    FileOutputStream(tempImage).use { out ->
                        out.write(bytes)
                        out.flush()
                    }

                    val metadata = StoryColorReferenceMetadata(
                        mangaId = mangaId,
                        isEnabled = enabled,
                        contentHash = hash,
                        algorithmVersion = ColorReferenceSnapshot.CURRENT_ALGORITHM_VERSION,
                        strength = ColorReferenceSnapshot.DEFAULT_STRENGTH,
                        profile = profile,
                    )
                    tempMeta.writeText(json.encodeToString(metadata))

                    // Swap protocol: image first, meta last. The meta rename is
                    // the validity marker, so any crash window leaves a
                    // decodable state — a transient new-image-with-old-meta
                    // pair grades as the old reference until the meta lands.
                    // POSIX rename(2) replaces the target atomically; the
                    // delete-retry covers platforms where it does not.
                    if (!tempImage.renameTo(targetImage)) {
                        targetImage.delete()
                        check(tempImage.renameTo(targetImage)) { "Failed to swap reference image" }
                    }
                    if (!tempMeta.renameTo(targetMeta)) {
                        targetMeta.delete()
                        check(tempMeta.renameTo(targetMeta)) { "Failed to swap reference meta" }
                    }
                } catch (t: Throwable) {
                    tempImage.delete()
                    tempMeta.delete()
                    throw t
                }

                val newState = StoryColorReferenceState(
                    isEnabled = enabled,
                    hasReference = true,
                    contentHash = hash,
                    thumbnailFile = targetImage,
                )
                getFlowForManga(mangaId).value = newState
                Result.success(newState)
            } catch (t: Throwable) {
                logcat(LogPriority.ERROR, t) { "Failed to set story color reference for manga $mangaId" }
                Result.failure(t)
            }
        }
    }

    suspend fun removeReference(mangaId: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            try {
                getImageFile(mangaId).delete()
                getMetaFile(mangaId).delete()
                getFlowForManga(mangaId).value = StoryColorReferenceState(
                    isEnabled = false,
                    hasReference = false,
                )
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to remove story color reference for manga $mangaId" }
            }
        }
    }

    fun clearAllForManga(mangaId: Long) {
        try {
            getMangaDir(mangaId).deleteRecursively()
            memoryState.remove(mangaId)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to clear story color reference dir for manga $mangaId" }
        }
    }

    private fun loadStateFromDisk(mangaId: Long): StoryColorReferenceState {
        val metaFile = getMetaFile(mangaId)
        if (!metaFile.exists()) {
            return StoryColorReferenceState(isEnabled = false, hasReference = false)
        }
        return try {
            val meta = json.decodeFromString<StoryColorReferenceMetadata>(metaFile.readText())
            StoryColorReferenceState(
                isEnabled = meta.isEnabled,
                hasReference = true,
                contentHash = meta.contentHash,
                thumbnailFile = getImageFile(mangaId).takeIf { it.exists() && it.length() > 0L },
            )
        } catch (e: Throwable) {
            // Mirror getSnapshot's enabled sentinel: a corrupt meta file keeps
            // the reference visible and on so the failure gates and the UI
            // tell the same story (Remove recovers).
            StoryColorReferenceState(
                isEnabled = true,
                hasReference = true,
                error = e.message ?: "Reference metadata is unreadable",
            )
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
