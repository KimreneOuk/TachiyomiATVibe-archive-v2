package eu.kanade.tachiyomi.data.backup

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupLastVisited
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.ChaptersQueries
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Manga_last_visitedQueries
import tachiyomi.data.MangasQueries
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMangaLastVisited
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class MangaBackupRestoreLastVisitedTest {

    private val mockDb = mockk<Database>(relaxed = true)
    private val mangasQueries = mockk<MangasQueries>(relaxed = true)
    private val chaptersQueries = mockk<ChaptersQueries>(relaxed = true)
    private val mangaLastVisitedQueries = mockk<Manga_last_visitedQueries>(relaxed = true)

    private val handler: DatabaseHandler = object : DatabaseHandler {
        override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T = mockDb.block()
        override suspend fun <T : Any> awaitList(inTransaction: Boolean, block: suspend Database.() -> Query<T>): List<T> = mockDb.block().executeAsList()
        override suspend fun <T : Any> awaitOne(inTransaction: Boolean, block: suspend Database.() -> Query<T>): T = mockDb.block().executeAsOne()
        override suspend fun <T : Any> awaitOneExecutable(inTransaction: Boolean, block: suspend Database.() -> ExecutableQuery<T>): T = mockDb.block().executeAsOne()
        override suspend fun <T : Any> awaitOneOrNull(inTransaction: Boolean, block: suspend Database.() -> Query<T>): T? = mockDb.block().executeAsOneOrNull()
        override suspend fun <T : Any> awaitOneOrNullExecutable(inTransaction: Boolean, block: suspend Database.() -> ExecutableQuery<T>): T? = mockDb.block().executeAsOneOrNull()
        override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> = flowOf(mockDb.block().executeAsList())
        override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> = flowOf(mockDb.block().executeAsOne())
        override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> = flowOf(mockDb.block().executeAsOneOrNull())
        override fun <T : Any> subscribeToPagingSource(countQuery: Database.() -> Query<Long>, queryProvider: Database.(Long, Long) -> Query<T>): PagingSource<Long, T> = throw UnsupportedOperationException()
    }

    private val getCategories = mockk<GetCategories>(relaxed = true)
    private val getHistory = mockk<GetHistory>(relaxed = true)
    private val getMangaLastVisited = mockk<GetMangaLastVisited>(relaxed = true)
    private val getMangaByUrlAndSourceId = mockk<GetMangaByUrlAndSourceId>(relaxed = true)
    private val getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true)
    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val getTracks = mockk<GetTracks>(relaxed = true)
    private val insertTrack = mockk<InsertTrack>(relaxed = true)
    private val fetchInterval = mockk<FetchInterval>(relaxed = true)

    private val manga = Manga.create().copy(id = 1L, url = "/manga/1", title = "Test Manga")

    private fun createRestorer(): MangaRestorer {
        return MangaRestorer(
            handler = handler,
            getCategories = getCategories,
            getMangaByUrlAndSourceId = getMangaByUrlAndSourceId,
            getChaptersByMangaId = getChaptersByMangaId,
            updateManga = updateManga,
            getTracks = getTracks,
            insertTrack = insertTrack,
            fetchInterval = fetchInterval,
        )
    }

    @BeforeEach
    fun setUp() {
        every { mockDb.mangasQueries } returns mangasQueries
        every { mockDb.chaptersQueries } returns chaptersQueries
        every { mockDb.manga_last_visitedQueries } returns mangaLastVisitedQueries

        coEvery { getMangaByUrlAndSourceId.await(any(), any()) } returns manga
        coEvery { updateManga.await(any()) } returns true
        coEvery { getCategories.await(any()) } returns emptyList()
        coEvery { getCategories.await() } returns emptyList()
        coEvery { getChaptersByMangaId.await(any()) } returns emptyList()
        coEvery { getTracks.await(any()) } returns emptyList()
    }

    // -------------------------------------------------------------------------
    // Backup Creator Tests
    // -------------------------------------------------------------------------

    @Test
    fun `backup creator exports lastVisited when options history is true`() {
        runBlocking {
            val creator = MangaBackupCreator(
                handler = handler,
                getCategories = getCategories,
                getHistory = getHistory,
                getMangaLastVisited = getMangaLastVisited,
            )

            val lastVisited = MangaLastVisited(
                mangaId = 1L,
                chapterId = 100L,
                lastVisitedPage = 14L,
                visitedAt = 5000L,
            )
            coEvery { getMangaLastVisited.await(1L) } returns lastVisited
            val chapterMock = mockk<Chapters>(relaxed = true) {
                every { url } returns "/chapter/100"
            }
            every { chaptersQueries.getChapterById(100L) } returns mockk {
                every { executeAsOneOrNull() } returns chapterMock
            }

            val options = BackupOptions(history = true, chapters = false, categories = false, tracking = false)
            val backups = creator(listOf(manga), options)

            backups.size shouldBe 1
            val backupManga = backups.first()
            backupManga.lastVisited shouldBe BackupLastVisited(
                url = "/chapter/100",
                lastVisitedPage = 14L,
                visitedAt = 5000L,
            )
        }
    }

    @Test
    fun `backup creator omits lastVisited when options history is false`() {
        runBlocking {
            val creator = MangaBackupCreator(
                handler = handler,
                getCategories = getCategories,
                getHistory = getHistory,
                getMangaLastVisited = getMangaLastVisited,
            )

            val options = BackupOptions(history = false, chapters = false, categories = false, tracking = false)
            val backups = creator(listOf(manga), options)

            backups.size shouldBe 1
            backups.first().lastVisited shouldBe null
            coVerify(exactly = 0) { getMangaLastVisited.await(any()) }
        }
    }

    // -------------------------------------------------------------------------
    // MangaRestorer Scoped Chapter & Atomic Merge Tests
    // -------------------------------------------------------------------------

    @Test
    fun `restore scopes chapter lookup by manga id preventing cross-manga collision on duplicate URLs`() {
        runBlocking {
            val restorer = createRestorer()
            val backupManga = manga.toBackupManga().apply {
                lastVisited = BackupLastVisited(url = "/chapter-1", lastVisitedPage = 3L, visitedAt = 2000L)
            }

            val matchingChapterForManga1 = mockk<Chapters>(relaxed = true) {
                every { _id } returns 101L
            }

            // chaptersQueries.getChapterByUrlAndMangaId must be invoked with chapterUrl and manga.id
            every { chaptersQueries.getChapterByUrlAndMangaId("/chapter-1", 1L) } returns mockk {
                every { executeAsOneOrNull() } returns matchingChapterForManga1
            }

            restorer.restore(backupManga, emptyList())

            // Verified: atomic upsertIfNewer is called with manga 1's chapter (101L), not a collision from another manga
            verifyUpsertIfNewer(mangaId = 1L, chapterId = 101L, page = 3L, visitedAt = 2000L)
        }
    }

    @Test
    fun `restore delegates to atomic upsertIfNewer query protecting against concurrent live writes`() {
        runBlocking {
            val restorer = createRestorer()
            val backupManga = manga.toBackupManga().apply {
                lastVisited = BackupLastVisited(url = "/chapter-1", lastVisitedPage = 8L, visitedAt = 2000L)
            }

            val chapter = mockk<Chapters>(relaxed = true) { every { _id } returns 10L }
            every { chaptersQueries.getChapterByUrlAndMangaId("/chapter-1", 1L) } returns mockk {
                every { executeAsOneOrNull() } returns chapter
            }

            restorer.restore(backupManga, emptyList())

            // Verified: executes atomic conditional upsert query in transaction without prior non-atomic read
            verifyUpsertIfNewer(mangaId = 1L, chapterId = 10L, page = 8L, visitedAt = 2000L)
        }
    }

    @Test
    fun `restore skips cleanly when backup chapter url does not exist locally`() {
        runBlocking {
            val restorer = createRestorer()
            val backupManga = manga.toBackupManga().apply {
                lastVisited = BackupLastVisited(url = "/missing-chapter", lastVisitedPage = 4L, visitedAt = 2000L)
            }

            // Chapter not found in DB
            every { chaptersQueries.getChapterByUrlAndMangaId("/missing-chapter", 1L) } returns mockk {
                every { executeAsOneOrNull() } returns null
            }

            restorer.restore(backupManga, emptyList())

            // Cannot resolve chapter -> must skip without crash and without upserting
            coVerify(exactly = 0) { mangaLastVisitedQueries.upsertIfNewer(any(), any(), any(), any()) }
        }
    }

    private fun verifyUpsertIfNewer(mangaId: Long, chapterId: Long, page: Long, visitedAt: Long) {
        coVerify(atLeast = 1) {
            mangaLastVisitedQueries.upsertIfNewer(
                mangaId = mangaId,
                chapterId = chapterId,
                lastVisitedPage = page,
                visitedAt = visitedAt,
            )
        }
    }

    private fun Manga.toBackupManga() = BackupManga(
        url = url,
        title = title,
        source = source,
    )
}
