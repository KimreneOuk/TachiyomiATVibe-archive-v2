package eu.kanade.translation.colorreference

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

class StoryColorReferenceProvenanceTest {

    private val context: Context = mockk(relaxed = true)
    private val processor = TranslationProcessor(context)
    private val fakeBitmap: Bitmap = mockk(relaxed = true)

    private class FakeEngine(private val stamp: String = "engine-stamp-v2") : PageColorizationEngine {
        override fun isAvailable(): Boolean = true
        override fun currentStamp(): String = stamp
        override fun initializationError(): String? = null
        override fun colorize(source: Bitmap, seed: Long): Bitmap = source
        override fun close() {}
    }

    @Test
    fun `colorize stamps page with grading version and reference content hash when enabled`() = runTest {
        val page = PageTranslation()
        val snapshot = ColorReferenceSnapshot(
            mangaId = 999L,
            isEnabled = true,
            contentHash = "abc123hash",
            algorithmVersion = 1,
            strength = 0.8f,
            profile = ColorReferenceProfile(1f, 1f, 1f, 1f, 100, true),
            imageFile = File("fake.png"),
        )

        processor.colorize(
            pageKey = "page_1",
            page = page,
            coloringEngine = FakeEngine("test-engine"),
            base = fakeBitmap,
            colorReferenceSnapshot = snapshot,
        )

        page.coloringStatus shouldBe StageStatus.READY
        page.coloringStamp shouldBe "test-engine:ref-v1-abc123hash"
    }

    @Test
    fun `colorize uses plain engine stamp when color reference is disabled`() = runTest {
        val page = PageTranslation()
        val snapshot = ColorReferenceSnapshot(
            mangaId = 999L,
            isEnabled = false,
            contentHash = "abc123hash",
            algorithmVersion = 1,
            strength = 0.8f,
            profile = ColorReferenceProfile(1f, 1f, 1f, 1f, 100, true),
            imageFile = File("fake.png"),
        )

        processor.colorize(
            pageKey = "page_1",
            page = page,
            coloringEngine = FakeEngine("test-engine"),
            base = fakeBitmap,
            colorReferenceSnapshot = snapshot,
        )

        page.coloringStatus shouldBe StageStatus.READY
        page.coloringStamp shouldBe "test-engine"
    }
}
