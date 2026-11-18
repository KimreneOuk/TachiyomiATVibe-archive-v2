package eu.kanade.translation.colorreference

import android.content.Context
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class StoryColorReferenceRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val context: Context = mockk()
    private val json = Json { prettyPrint = false }
    private lateinit var repository: StoryColorReferenceRepository

    @BeforeEach
    fun setUp() {
        every { context.noBackupFilesDir } returns tempDir
        repository = StoryColorReferenceRepository(context)
    }

    @Test
    fun `getSnapshot returns null when no reference exists on disk`() {
        val snapshot = repository.getSnapshot(42L)
        snapshot.shouldBeNull()
    }

    @Test
    fun `getSnapshot loads valid snapshot from disk metadata and image`() {
        val mangaId = 101L
        val mangaDir = File(File(tempDir, "story-color-references"), mangaId.toString()).apply { mkdirs() }
        val imageFile = File(mangaDir, "reference.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val meta = StoryColorReferenceMetadata(
            mangaId = mangaId,
            isEnabled = true,
            contentHash = "test_hash_101",
            algorithmVersion = 1,
            strength = 0.75f,
            profile = ColorReferenceProfile(meanA = 12f, meanB = -8f, stdA = 10f, stdB = 14f, sampleCount = 300, isUsable = true),
        )
        File(mangaDir, "reference.meta.json").writeText(json.encodeToString(meta))

        val snapshot = repository.getSnapshot(mangaId)
        snapshot.shouldNotBeNull()
        snapshot.mangaId shouldBe mangaId
        snapshot.isEnabled shouldBe true
        snapshot.contentHash shouldBe "test_hash_101"
        snapshot.algorithmVersion shouldBe 1
        snapshot.strength shouldBe 0.75f
        snapshot.isReferenceAvailable() shouldBe true
    }

    @Test
    fun `setEnabled updates disk metadata and emits new state`() = runTest {
        val mangaId = 202L
        val mangaDir = File(File(tempDir, "story-color-references"), mangaId.toString()).apply { mkdirs() }
        File(mangaDir, "reference.png").writeBytes(byteArrayOf(5, 6, 7))
        val meta = StoryColorReferenceMetadata(
            mangaId = mangaId,
            isEnabled = true,
            contentHash = "hash_202",
            algorithmVersion = 1,
            strength = 0.8f,
            profile = ColorReferenceProfile(1f, 2f, 3f, 4f, 100, true),
        )
        File(mangaDir, "reference.meta.json").writeText(json.encodeToString(meta))

        // Initial observation
        val initialFlow = repository.observe(mangaId).first()
        initialFlow.isEnabled shouldBe true
        initialFlow.hasReference shouldBe true

        // Toggle disabled
        repository.setEnabled(mangaId, false)

        val updatedFlow = repository.observe(mangaId).first()
        updatedFlow.isEnabled shouldBe false
        updatedFlow.hasReference shouldBe true

        val snapshot = repository.getSnapshot(mangaId)
        snapshot.shouldNotBeNull()
        snapshot.isEnabled shouldBe false
    }

    @Test
    fun `removeReference deletes files and updates state to no reference`() = runTest {
        val mangaId = 303L
        val mangaDir = File(File(tempDir, "story-color-references"), mangaId.toString()).apply { mkdirs() }
        val img = File(mangaDir, "reference.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val metaFile = File(mangaDir, "reference.meta.json").apply {
            writeText(json.encodeToString(
                StoryColorReferenceMetadata(
                    mangaId = mangaId,
                    isEnabled = true,
                    contentHash = "hash",
                    algorithmVersion = 1,
                    strength = 0.8f,
                    profile = ColorReferenceProfile(1f, 1f, 1f, 1f, 100, true),
                )
            ))
        }

        repository.removeReference(mangaId)

        img.exists() shouldBe false
        metaFile.exists() shouldBe false
        val state = repository.observe(mangaId).first()
        state.hasReference shouldBe false
        state.isEnabled shouldBe false
        repository.getSnapshot(mangaId).shouldBeNull()
    }

    @Test
    fun `clearAllForManga recursively deletes folder`() {
        val mangaId = 404L
        val mangaDir = File(File(tempDir, "story-color-references"), mangaId.toString()).apply { mkdirs() }
        File(mangaDir, "reference.png").writeBytes(byteArrayOf(1))

        mangaDir.exists() shouldBe true
        repository.clearAllForManga(mangaId)
        mangaDir.exists() shouldBe false
    }

    @Test
    fun `setReferenceImage fails with empty stream`() = runTest {
        val result = repository.setReferenceImage(505L, ByteArrayInputStream(ByteArray(0)))
        result.isFailure shouldBe true
    }
}
