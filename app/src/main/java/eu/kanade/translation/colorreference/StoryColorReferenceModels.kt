package eu.kanade.translation.colorreference

import kotlinx.serialization.Serializable
import java.io.File

/** Color profile statistics extracted from midtone pixels of a reference image. */
@Serializable
data class ColorReferenceProfile(
    val meanA: Float,
    val meanB: Float,
    val stdA: Float,
    val stdB: Float,
    val sampleCount: Int,
    val isUsable: Boolean,
)

/** Immutable snapshot captured per page at colorization start. */
data class ColorReferenceSnapshot(
    val mangaId: Long,
    val isEnabled: Boolean,
    val contentHash: String? = null,
    val algorithmVersion: Int = CURRENT_ALGORITHM_VERSION,
    val strength: Float = DEFAULT_STRENGTH,
    val profile: ColorReferenceProfile? = null,
    val imageFile: File? = null,
) {
    /**
     * Grading capability is derived solely from the persisted profile in the
     * meta file: the image bytes are only a UI thumbnail and are never read
     * at grading time. A missing image therefore does NOT invalidate grading.
     */
    fun isReferenceAvailable(): Boolean =
        profile != null && profile.isUsable

    companion object {
        const val CURRENT_ALGORITHM_VERSION = 1
        const val DEFAULT_STRENGTH = 0.35f
    }
}

/** UI state for the reader settings sheet. */
data class StoryColorReferenceState(
    val isEnabled: Boolean = false,
    val hasReference: Boolean = false,
    val contentHash: String? = null,
    val thumbnailFile: File? = null,
    val error: String? = null,
)

/** Serialized metadata stored alongside the reference image file. */
@Serializable
data class StoryColorReferenceMetadata(
    val schemaVersion: Int = SCHEMA_VERSION,
    val mangaId: Long,
    val isEnabled: Boolean,
    val contentHash: String,
    val algorithmVersion: Int,
    val strength: Float,
    val profile: ColorReferenceProfile,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
