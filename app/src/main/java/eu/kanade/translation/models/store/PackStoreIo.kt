package eu.kanade.translation.models.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * File primitives for the pack store. The crash-consistency invariant is
 * carried by the single active.json commit point, which is replaced
 * atomically or not at all (writeTextAtomic fails closed when a filesystem
 * cannot guarantee atomic replacement). Staging-file moves may fall back to
 * non-atomic operations because a crash there leaves swept staging, never a
 * partial commit.
 */
internal object PackStoreIo {

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes via a same-directory temp file + fsync, then replaces the target atomically. Crash before replace leaves target intact. */
    fun writeTextAtomic(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { channel ->
            channel.write(content.toByteArray(Charsets.UTF_8))
            channel.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // Fail closed for the commit point: a non-atomic fallback (delete+copy)
            // could leave active.json missing or partial after interruption. The
            // temp file stays for diagnosis and the previous commit is untouched.
            throw java.io.IOException(
                "Atomic replace unsupported at ${target.path}; previous commit preserved, new state left in ${temp.name}",
                e,
            )
        }
    }

    /**
     * Replace-existing move for NON-commit files (staging part -> final name).
     * Never for the active.json commit point — that path must stay atomic-only.
     */
    fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // fall through to non-atomic attempts
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: java.io.IOException) {
            // fall through to delete+rename/copy
        }
        if (target.exists()) target.delete()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    /** Moves [source] (staging dir) into [targetDir] under [name]; target must not exist. Returns the moved dir. */
    fun moveIntoDir(source: File, targetDir: File, name: String): File {
        val target = File(targetDir, name)
        if (target.exists()) throw IllegalStateException("Version directory already exists: ${target.path}")
        targetDir.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(target)) {
                source.copyRecursively(target)
                source.deleteRecursively()
            }
        }
        return target
    }

    /** Deletes recursively, returning bytes freed. */
    fun deleteRecursively(file: File): Long = when {
        file.isDirectory -> file.listFiles()?.sumOf { deleteRecursively(it) } ?: 0L
        file.isFile -> file.length().also { file.delete() }
        else -> 0L
    }.also { file.delete() }

    fun sizeRecursively(file: File): Long = when {
        file.isDirectory -> file.listFiles()?.sumOf { sizeRecursively(it) } ?: 0L
        file.isFile -> file.length()
        else -> 0L
    }

    /** Free bytes on the volume that will hold [dir]; a not-yet-created path probes its nearest existing ancestor. */
    fun availableBytes(dir: File): Long = try {
        var probe = dir
        while (!probe.exists()) {
            val parent = probe.parentFile ?: break
            probe = parent
        }
        probe.usableSpace
    } catch (_: Exception) {
        -1L
    }
}
