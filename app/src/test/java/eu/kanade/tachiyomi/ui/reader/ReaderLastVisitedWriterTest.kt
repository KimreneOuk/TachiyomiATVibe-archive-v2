package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.UpsertMangaLastVisited
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.manga.repository.MangaLastVisitedRepository
import java.util.concurrent.CopyOnWriteArrayList

class ReaderLastVisitedWriterTest {

    private class FakeMangaLastVisitedRepository : MangaLastVisitedRepository {
        val recorded = CopyOnWriteArrayList<Record>()
        var current: MangaLastVisited? = null
        var throwOnUpsert: Boolean = false

        data class Record(val mangaId: Long, val chapterId: Long, val page: Long, val visitedAt: Long)

        override suspend fun getByMangaId(mangaId: Long): MangaLastVisited? = current
        override fun subscribeByMangaId(mangaId: Long): Flow<MangaLastVisited?> = flowOf(current)
        override suspend fun getGlobalLastVisited(favoritesOnly: Boolean): MangaLastVisited? = current
        override fun subscribeGlobalLastVisited(favoritesOnly: Boolean): Flow<MangaLastVisited?> = flowOf(current)
        override suspend fun upsert(mangaId: Long, chapterId: Long, lastVisitedPage: Long, visitedAt: Long) {
            if (throwOnUpsert) {
                throwOnUpsert = false
                throw RuntimeException("Simulated SQLite write error")
            }
            recorded.add(Record(mangaId, chapterId, lastVisitedPage, visitedAt))
            current = MangaLastVisited(mangaId, chapterId, lastVisitedPage, visitedAt)
        }
        override suspend fun deleteByMangaId(mangaId: Long) {
            if (current?.mangaId == mangaId) current = null
        }
        override suspend fun deleteAll() {
            current = null
        }
    }

    @Test
    fun `recordVisit enqueues and flushes in FIFO order`() = runTest {
        val repo = FakeMangaLastVisitedRepository()
        val interactor = UpsertMangaLastVisited(repo)
        val writer = ReaderLastVisitedWriter(interactor, scope = this)

        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 0L, visitedAt = 100L)
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 1L, visitedAt = 101L)
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 2L, visitedAt = 102L)

        writer.flush()

        repo.recorded.size shouldBe 3
        repo.recorded.map { it.page } shouldBe listOf(0L, 1L, 2L)
        repo.current?.lastVisitedPage shouldBe 2L

        writer.close()
    }

    @Test
    fun `same-millisecond events preserve channel arrival order and commit latest position`() = runTest {
        val repo = FakeMangaLastVisitedRepository()
        val interactor = UpsertMangaLastVisited(repo)
        val writer = ReaderLastVisitedWriter(interactor, scope = this)

        // Multiple rapid navigation events occurring in the exact same millisecond
        val sameTime = 1700000000000L
        val seq1 = writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 3L, visitedAt = sameTime)
        val seq2 = writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 4L, visitedAt = sameTime)

        seq2 shouldBe (seq1 + 1)

        writer.flush()

        repo.recorded.size shouldBe 2
        repo.recorded[0].page shouldBe 3L
        repo.recorded[1].page shouldBe 4L
        repo.current?.lastVisitedPage shouldBe 4L

        writer.close()
    }

    @Test
    fun `intentional backward navigation overwrites SQLite position with earlier page`() = runTest {
        val repo = FakeMangaLastVisitedRepository()
        val interactor = UpsertMangaLastVisited(repo)
        val writer = ReaderLastVisitedWriter(interactor, scope = this)

        // Reading forward to page 15
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 15L, visitedAt = 1000L)
        writer.flush()
        repo.current?.lastVisitedPage shouldBe 15L

        // Navigating backward to page 5
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 5L, visitedAt = 1050L)
        writer.flush()

        repo.current?.lastVisitedPage shouldBe 5L
        repo.recorded.last().page shouldBe 5L

        writer.close()
    }

    @Test
    fun `stale out-of-order sequence event is dropped without modifying SQLite`() = runTest {
        val repo = FakeMangaLastVisitedRepository()
        val interactor = UpsertMangaLastVisited(repo)
        val writer = ReaderLastVisitedWriter(interactor, scope = this)

        // Enqueue event with sequence 2
        writer.enqueueCommand(
            ReaderLastVisitedWriter.Command.RecordVisit(
                mangaId = 1L,
                chapterId = 10L,
                page = 8L,
                visitedAt = 2000L,
                sequence = 2L,
            ),
        )
        writer.flush()
        repo.current?.lastVisitedPage shouldBe 8L
        repo.recorded.size shouldBe 1

        // Now a stale event with sequence 1 arrives out of order
        writer.enqueueCommand(
            ReaderLastVisitedWriter.Command.RecordVisit(
                mangaId = 1L,
                chapterId = 10L,
                page = 2L,
                visitedAt = 1900L,
                sequence = 1L,
            ),
        )
        writer.flush()

        // Seq 1 must be dropped: recorded size remains 1 and page remains 8
        repo.recorded.size shouldBe 1
        repo.current?.lastVisitedPage shouldBe 8L

        writer.close()
    }

    @Test
    fun `sqlite exception does not crash writer and subsequent events flush cleanly`() = runTest {
        val repo = FakeMangaLastVisitedRepository()
        repo.throwOnUpsert = true
        val interactor = UpsertMangaLastVisited(repo)
        val writer = ReaderLastVisitedWriter(interactor, scope = this)

        // First event throws
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 1L, visitedAt = 1000L)

        // Second event succeeds
        writer.recordVisit(mangaId = 1L, chapterId = 10L, page = 2L, visitedAt = 1010L)
        writer.flush()

        repo.recorded.size shouldBe 1
        repo.recorded[0].page shouldBe 2L
        repo.current?.lastVisitedPage shouldBe 2L

        writer.close()
    }
}
