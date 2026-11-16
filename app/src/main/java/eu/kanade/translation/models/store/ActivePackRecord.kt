package eu.kanade.translation.models.store

import eu.kanade.translation.models.PackId
import eu.kanade.translation.models.PackSourceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single commit point of a pack: packs/<id>/active.json. A version
 * directory without this record pointing at it is NOT installed. The record
 * carries the previous version it replaced so retention survives crashes
 * between commit and cleanup.
 */
@Serializable
data class ActivePackRecord(
    val packId: PackId,
    val version: String,
    val previousVersion: String?,
    val activatedAtEpochMs: Long,
    val sourceKind: PackSourceKind,
    val files: List<Entry>,
) {
    @Serializable
    data class Entry(val path: String, val sizeBytes: Long, val sha256: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(record: ActivePackRecord): String = json.encodeToString(record)

        fun decode(text: String): ActivePackRecord? = try {
            json.decodeFromString<ActivePackRecord>(text)
        } catch (_: Exception) {
            // A corrupt index is never a reason to sweep versions; callers treat this as "no active version".
            null
        }
    }
}
