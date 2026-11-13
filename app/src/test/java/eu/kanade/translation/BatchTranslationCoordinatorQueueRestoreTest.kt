package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchQueuePersistence
import eu.kanade.translation.coordinator.InMemorySharedPreferences
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.mergeRestoredQueueEntries
import eu.kanade.translation.coordinator.selectChapterWave
import eu.kanade.translation.model.Translation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class BatchTranslationCoordinatorQueueRestoreTest {

    @Test
    fun `restore merge retains concurrent queue additions in durable order`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(10L, 20L),
            restoredById = mapOf(10L to "restored-10"),
            liveById = mapOf(20L to "concurrent-20"),
        ) shouldContainExactly listOf("restored-10", "concurrent-20")
    }

    @Test
    fun `restore merge honors removals and drops stale lookup results`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L, 20L, 30L),
            restoredById = mapOf(10L to "stale-10", 20L to "restored-20"),
            liveById = emptyMap(),
        ) shouldContainExactly listOf("restored-20")
    }

    @Test
    fun `restore merge prefers a live requeue over stale restored state`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L),
            restoredById = mapOf(20L to "stale-paused"),
            liveById = mapOf(20L to "fresh-queue"),
        ) shouldContainExactly listOf("fresh-queue")
    }

    @Test
    fun `restoreQueue preserves colorizeOnly markers across consecutive restores`() = runTest {
        val preferences = InMemorySharedPreferences()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        val queueStore = TranslationQueueStore(context)
        // Chapter 10L is normal translation; chapter 20L is colorizeOnly
        queueStore.save(listOf(10L, 20L), colorizeOnlyIds = setOf(20L))

        val source = waveSource()
        fun createTranslation(id: Long) = Translation(
            source = source,
            manga = Manga.create().copy(id = 1L),
            chapter = Chapter.create().copy(id = id, name = "Chapter $id"),
        )

        val persistence = BatchQueuePersistence(
            context = mockk(relaxed = true),
            provider = mockk(relaxed = true),
            queueStore = queueStore,
            resolveStore = { null },
            resolveTranslation = { id -> createTranslation(id) },
        )

        val queueLock = Any()
        var restoredQueue1 = emptyList<Translation>()

        // First restore
        persistence.restoreQueue(
            queueLock = queueLock,
            currentQueue = { restoredQueue1 },
            applyRestored = { restoredQueue1 = it },
        )

        restoredQueue1.map { it.chapter.id } shouldContainExactly listOf(10L, 20L)
        restoredQueue1.first { it.chapter.id == 10L }.colorizeOnly shouldBe false
        restoredQueue1.first { it.chapter.id == 20L }.colorizeOnly shouldBe true
        queueStore.loadColorizeOnlyIds() shouldContainExactly setOf(20L)

        // Second restore: simulates another process restart reading the newly persisted queueStore
        var restoredQueue2 = emptyList<Translation>()
        persistence.restoreQueue(
            queueLock = queueLock,
            currentQueue = { restoredQueue2 },
            applyRestored = { restoredQueue2 = it },
        )

        restoredQueue2.map { it.chapter.id } shouldContainExactly listOf(10L, 20L)
        restoredQueue2.first { it.chapter.id == 10L }.colorizeOnly shouldBe false
        restoredQueue2.first { it.chapter.id == 20L }.colorizeOnly shouldBe true
        queueStore.loadColorizeOnlyIds() shouldContainExactly setOf(20L)
    }

    @Test
    fun `restoreQueue preserves colorizeOnly markers when merging concurrent additions during restore`() = runTest {
        val preferences = InMemorySharedPreferences()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        val queueStore = TranslationQueueStore(context)
        // Disk starts with chapter 10L (normal)
        queueStore.save(listOf(10L), colorizeOnlyIds = emptySet())

        val source = waveSource()
        fun createTranslation(id: Long, colorizeOnly: Boolean = false) = Translation(
            source = source,
            manga = Manga.create().copy(id = 1L),
            chapter = Chapter.create().copy(id = id, name = "Chapter $id"),
        ).apply { this.colorizeOnly = colorizeOnly }

        val concurrentItem = createTranslation(30L, colorizeOnly = true)
        val queueLock = Any()
        var liveQueue = listOf(concurrentItem)

        val persistence = BatchQueuePersistence(
            context = mockk(relaxed = true),
            provider = mockk(relaxed = true),
            queueStore = queueStore,
            resolveStore = { null },
            resolveTranslation = { id ->
                // Simulate concurrent enqueue occurring while lookups suspend:
                // disk records both items with 30L as colorizeOnly
                queueStore.save(listOf(10L, 30L), colorizeOnlyIds = setOf(30L))
                createTranslation(id, colorizeOnly = false)
            },
        )

        persistence.restoreQueue(
            queueLock = queueLock,
            currentQueue = { liveQueue },
            applyRestored = { liveQueue = it },
        )

        liveQueue.map { it.chapter.id } shouldContainExactly listOf(10L, 30L)
        liveQueue.first { it.chapter.id == 10L }.colorizeOnly shouldBe false
        liveQueue.first { it.chapter.id == 30L }.colorizeOnly shouldBe true
        queueStore.loadColorizeOnlyIds() shouldContainExactly setOf(30L)

        // Consecutive restore verifies durable preservation of the concurrent item
        var secondRestoreQueue = emptyList<Translation>()
        persistence.restoreQueue(
            queueLock = queueLock,
            currentQueue = { secondRestoreQueue },
            applyRestored = { secondRestoreQueue = it },
        )

        secondRestoreQueue.map { it.chapter.id } shouldContainExactly listOf(10L, 30L)
        secondRestoreQueue.first { it.chapter.id == 30L }.colorizeOnly shouldBe true
        queueStore.loadColorizeOnlyIds() shouldContainExactly setOf(30L)
    }

    private fun waveSource(): HttpSource = mockk(relaxed = true)

    private fun waveEntry(
        id: Long,
        status: Translation.State,
        source: HttpSource,
    ): Translation = Translation(
        source = source,
        manga = Manga.create(),
        chapter = Chapter.create().copy(id = id, name = "Chapter $id"),
    ).apply { this.status = status }

    @Test
    fun `selectChapterWave admits only the first entry of a fresh queue`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.QUEUE, source),
            waveEntry(2L, Translation.State.QUEUE, source),
            waveEntry(3L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave pairs the active chapter with the next queued chapter of its source`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, source),
            waveEntry(2L, Translation.State.QUEUE, source),
            waveEntry(3L, Translation.State.QUEUE, source),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `selectChapterWave keeps only the active chapter when the next queued is from another source`() {
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, waveSource()),
            waveEntry(2L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave keeps only the active chapter when no queued entries remain`() {
        val queue = listOf(waveEntry(1L, Translation.State.TRANSLATING, waveSource()))

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `selectChapterWave excludes terminal and non-participating statuses`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATED, source),
            waveEntry(2L, Translation.State.ERROR, waveSource()),
            waveEntry(3L, Translation.State.PAUSED, waveSource()),
            waveEntry(4L, Translation.State.READY_WITH_WARNINGS, waveSource()),
            waveEntry(5L, Translation.State.NOT_TRANSLATED, waveSource()),
            waveEntry(6L, Translation.State.QUEUE, waveSource()),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(6L)
    }

    @Test
    fun `selectChapterWave admits at most one translating entry even with same-source queued work`() {
        val source = waveSource()
        val queue = listOf(
            waveEntry(1L, Translation.State.TRANSLATING, source),
            waveEntry(2L, Translation.State.TRANSLATING, source),
            waveEntry(3L, Translation.State.QUEUE, source),
        )

        selectChapterWave(queue).map { it.chapter.id } shouldBe listOf(1L, 3L)
    }
}
