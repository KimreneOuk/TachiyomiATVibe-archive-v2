package eu.kanade.translation.models.imports

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Streams one imported file into staging. Implementations must never persist or
 * log credential-bearing URIs — [displayName] is the only recorded identifier.
 */
interface VariantImportSource {
    val displayName: String

    /** Streams bytes into [target]; returns bytes written. */
    suspend fun copyInto(target: File): Long
}

/** Test/sandbox source from a local directory (also the SAF seam's behavioral reference). */
class LocalDirectoryImportSource(private val root: File, private val relativePath: String) : VariantImportSource {

    override val displayName: String = relativePath.substringAfterLast('/')

    override suspend fun copyInto(target: File): Long {
        val canonicalRoot = root.canonicalFile
        val source = File(canonicalRoot, relativePath).canonicalFile
        if (source != canonicalRoot && !source.path.startsWith(canonicalRoot.path + File.separator)) {
            throw IOException("Import file escapes its source root: $relativePath")
        }
        if (!source.isFile) throw IOException("Missing import file: ${source.path}")
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.length()
    }
}

/**
 * SAF document-picker source. The URI is used only to open a read stream and,
 * when grant flags allow, to take a persistable read permission; it is never
 * stored or logged. One-shot streams work without persistence.
 */
class DocumentPickerImportSource(
    private val context: Context,
    private val uri: Uri,
    private val grantFlags: Int = 0,
) : VariantImportSource {

    override val displayName: String = uri.lastPathSegment?.substringAfterLast('/') ?: "document"

    override suspend fun copyInto(target: File): Long {
        if (grantFlags and (android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // One-shot read still works; persistence is an optimization only.
            }
        }
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $displayName")
        stream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.length()
    }
}

/** Declared seam only. Cloud transport is unimplemented until a verified protocol exists. */
class CloudImportSource : VariantImportSource {

    override val displayName: String = "cloud"

    override suspend fun copyInto(target: File): Long {
        throw UnsupportedOperationException(
            "Cloud import is not implemented in this prototype; no transport is wired",
        )
    }
}
