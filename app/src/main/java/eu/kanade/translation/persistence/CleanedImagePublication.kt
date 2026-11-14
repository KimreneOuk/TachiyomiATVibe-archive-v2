package eu.kanade.translation.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.stampName
import tachiyomi.domain.translation.NeuralInpaintModel
import java.security.DigestOutputStream
import java.util.UUID
import java.security.MessageDigest

/** Stable byte identity for a cleaned image whose legacy name is not content-addressed. */
@Serializable
internal data class CleanedImageIdentitySidecar(
    val schemaVersion: Int = SCHEMA_VERSION,
    val pageKey: String,
    val imageName: String,
    val contentSha256: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object CleanedImageIdentity {
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun sidecarName(imageName: String): String = "$imageName.identity.json"

    fun create(pageKey: String, imageName: String, input: InputStream): CleanedImageIdentitySidecar =
        CleanedImageIdentitySidecar(
            pageKey = pageKey,
            imageName = imageName,
            contentSha256 = sha256(input),
        )

    fun encode(sidecar: CleanedImageIdentitySidecar): ByteArray =
        ArtifactDocumentJson.encodeToString(sidecar).encodeToByteArray()

    /** Verifies the exact sidecar lookup and the bytes it names, closing both streams. */
    fun verify(
        io: ChapterDocumentIo,
        chapterBaseName: String,
        imageName: String,
        expectedContentSha256: String,
    ): Boolean {
        if (!SHA256_HEX.matches(expectedContentSha256) || !isSafeFileName(imageName)) return false
        val imageDirectory = "${chapterBaseName}_images"
        val sidecarBytes = io.read("$imageDirectory/${sidecarName(imageName)}") ?: return false
        return verifyExisting(sidecarBytes, imageName, expectedContentSha256) {
            io.openInputStream("$imageDirectory/$imageName")
        }
    }

    /**
     * Verifies a same-name retry against the sidecar and exact image bytes.
     * The caller supplies the independently anchored hash of the content it
     * intended to write; an existing name is reusable only for those bytes.
     */
    fun verifyExisting(
        sidecarBytes: ByteArray?,
        imageName: String,
        expectedContentSha256: String,
        openImage: () -> InputStream?,
    ): Boolean {
        if (sidecarBytes == null || !SHA256_HEX.matches(expectedContentSha256) || !isSafeFileName(imageName)) return false
        val sidecar = runCatching {
            ArtifactDocumentJson.decodeFromString<CleanedImageIdentitySidecar>(sidecarBytes.decodeToString())
        }.getOrNull() ?: return false
        if (!hasExplicitSupportedSchema(sidecarBytes) ||
            sidecar.schemaVersion != CleanedImageIdentitySidecar.SCHEMA_VERSION ||
            sidecar.imageName != imageName ||
            !SHA256_HEX.matches(sidecar.contentSha256) ||
            sidecar.contentSha256 != expectedContentSha256
        ) {
            return false
        }
        val image = runCatching(openImage).getOrNull() ?: return false
        return runCatching { image.use { sha256(it) == sidecar.contentSha256 } }.getOrDefault(false)
    }

    private fun hasExplicitSupportedSchema(bytes: ByteArray): Boolean = runCatching {
        val root = ArtifactDocumentJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        root["schemaVersion"]?.jsonPrimitive?.intOrNull == CleanedImageIdentitySidecar.SCHEMA_VERSION
    }.getOrDefault(false)

    private fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name &&
            name.none(Char::isISOControl)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * JPEG quality for published cleaned/colored images. Line art survives 80
 * without visible loss and cuts the colored-page file (and its SAF re-read on
 * every open) roughly in half versus 90. Applies to newly written files only;
 * existing artifacts keep their original bytes.
 */
internal const val CLEANED_JPEG_QUALITY = 80

/** Owns verified cleaned-image files and retirement of superseded images. */
internal class CleanedPublication(
    private val provider: TranslationFileProvider,
    private val currentInpaintingMode: () -> InpaintingMode,
    private val currentNeuralInpaintModel: () -> NeuralInpaintModel = { NeuralInpaintModel.AOT_GAN },
) {
    internal fun currentInpaintingStamp(): String = currentInpaintingMode().stampName(
        neuralModel = currentNeuralInpaintModel(),
        neuralAvailable = true,
    )

    internal data class CleanedImageWriteResult(val name: String, val contentSha256: String)

    internal fun writeVerifiedCleanedImage(
        directory: UniFile,
        pageKey: String,
        bitmap: Bitmap,
        name: String = newCleanedImageName(pageKey),
    ): CleanedImageWriteResult {
        val identityName = CleanedImageIdentity.sidecarName(name)
        val existingImage = directory.findFile(name)?.takeIf(UniFile::exists)
        val existingIdentity = directory.findFile(identityName)?.takeIf(UniFile::exists)
        if (existingImage != null || existingIdentity != null) {
            val sidecarBytes = runCatching { existingIdentity?.openInputStream()?.use { it.readBytes() } }.getOrNull()
            val sidecar = runCatching {
                sidecarBytes?.let { ArtifactDocumentJson.decodeFromString<CleanedImageIdentitySidecar>(it.decodeToString()) }
            }.getOrNull()
            check(
                sidecar != null &&
                    CleanedImageIdentity.verifyExisting(
                        sidecarBytes = sidecarBytes,
                        imageName = name,
                        expectedContentSha256 = sidecar.contentSha256,
                    ) { existingImage?.openInputStream() },
            ) { "cleaned image name is already bound to different or unverified bytes" }
            return CleanedImageWriteResult(name, sidecar.contentSha256)
        }

        val imageFile = checkNotNull(directory.createFile(name)) { "could not create final cleaned image" }
        var identityFile: UniFile? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            imageFile.openOutputStream().use { output ->
                DigestOutputStream(output, digest).use { digestOutput ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, CLEANED_JPEG_QUALITY, digestOutput)) {
                        "JPEG encoding returned false"
                    }
                    digestOutput.flush()
                }
            }
            check(imageFile.exists() && imageFile.length() > 0L) { "published cleaned image is unavailable" }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val identity = CleanedImageIdentitySidecar(pageKey = pageKey, imageName = name, contentSha256 = hash)
            identityFile = checkNotNull(directory.createFile(identityName)) { "could not create cleaned image identity" }
            identityFile!!.openOutputStream().use { output ->
                output.write(CleanedImageIdentity.encode(identity))
                output.flush()
            }
            check(identityFile!!.exists() && identityFile!!.length() > 0L) { "cleaned image identity is unavailable" }
            return CleanedImageWriteResult(name, hash)
        } catch (failure: Throwable) {
            identityFile?.delete()
            imageFile.delete()
            throw failure
        }
    }

    private fun newCleanedImageName(pageKey: String): String {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val version = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().replace("-", "")}"
        return "$safeName.cleaned.$version.jpg"
    }

    /**
     * Publishes a derived colored image through the same verified write (JPEG +
     * sha256 identity sidecar) under a `.colored.` versioned name.
     */
    internal fun writeVerifiedColoredImage(
        directory: UniFile,
        pageKey: String,
        bitmap: Bitmap,
    ): CleanedImageWriteResult = writeVerifiedCleanedImage(
        directory,
        pageKey,
        bitmap,
        name = newColoredImageName(pageKey),
    )

    internal fun newColoredImageName(pageKey: String): String {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val version = "${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().replace("-", "")}"
        return "$safeName.colored.$version.jpg"
    }
}
