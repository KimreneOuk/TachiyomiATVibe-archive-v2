package eu.kanade.tachiyomi.data.manga

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Manga_last_visitedQueries
import tachiyomi.data.manga.MangaLastVisitedRepositoryImpl
import tachiyomi.domain.manga.interactor.DeleteMangaLastVisited
import tachiyomi.domain.manga.interactor.GetGlobalLastVisited
import tachiyomi.domain.manga.interactor.GetMangaLastVisited
import tachiyomi.domain.manga.interactor.UpsertMangaLastVisited
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class MangaLastVisitedTest {

    private val mockDb = mockk<Database>(relaxed = true)
    private val queries = mockk<Manga_last_visitedQueries>(relaxed = true)

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

    private val repositoryImpl = MangaLastVisitedRepositoryImpl(handler)

    private val mockRepository = mockk<MangaLastVisitedRepository>(relaxed = true)
    private val getMangaLastVisited = GetMangaLastVisited(mockRepository)
    private val getGlobalLastVisited = GetGlobalLastVisited(mockRepository)
    private val upsertMangaLastVisited = UpsertMangaLastVisited(mockRepository)
    private val deleteMangaLastVisited = DeleteMangaLastVisited(mockRepository)

    @BeforeEach
    fun setUp() {
        every { mockDb.manga_last_visitedQueries } returns queries
    }

    // -------------------------------------------------------------------------
    // Repository Implementation Tests (executes actual queries via DatabaseHandler)
    // -------------------------------------------------------------------------

    @Test
    fun `repository getByMangaId executes query and maps record`() {
        runBlocking {
            val mapped = MangaLastVisited(mangaId = 1L, chapterId = 10L, lastVisitedPage = 5L, visitedAt = 1000L)
            val mockQuery = mockk<Query<MangaLastVisited>>()
            every { mockQuery.executeAsOneOrNull() } returns mapped
            every { queries.getLastVisitedByMangaId<MangaLastVisited>(1L, any()) } returns mockQuery

            val result = repositoryImpl.getByMangaId(1L)
            result shouldBe mapped
        }
    }

    @Test
    fun `repository getGlobalLastVisited executes query and returns latest visited story`() {
        runBlocking {
            val mapped = MangaLastVisited(mangaId = 5L, chapterId = 50L, lastVisitedPage = 12L, visitedAt = 9999L)
            val mockQuery = mockk<Query<MangaLastVisited>>()
            every { mockQuery.executeAsOneOrNull() } returns mapped
            every { queries.getGlobalLastVisited<MangaLastVisited>(any()) } returns mockQuery

            val result = repositoryImpl.getGlobalLastVisited(favoritesOnly = false)
            result shouldBe mapped
        }
    }

    @Test
    fun `repository getGlobalLastVisited with favoritesOnly executes favorite query`() {
        runBlocking {
            val mapped = MangaLastVisited(mangaId = 7L, chapterId = 70L, lastVisitedPage = 3L, visitedAt = 8888L)
            val mockQuery = mockk<Query<MangaLastVisited>>()
            every { mockQuery.executeAsOneOrNull() } returns mapped
            every { queries.getGlobalLastVisitedFavorite<MangaLastVisited>(any()) } returns mockQuery

            val result = repositoryImpl.getGlobalLastVisited(favoritesOnly = true)
            result shouldBe mapped
        }
    }

    @Test
    fun `repository upsert delegates coordinates to manga_last_visitedQueries upsert`() {
        runBlocking {
            repositoryImpl.upsert(mangaId = 1L, chapterId = 10L, lastVisitedPage = 7L, visitedAt = 12345L)

            coVerify(exactly = 1) {
                queries.upsert(
                    mangaId = 1L,
                    chapterId = 10L,
                    lastVisitedPage = 7L,
                    visitedAt = 12345L,
                )
            }
        }
    }

    @Test
    fun `repository delete methods delegate to manga_last_visitedQueries`() {
        runBlocking {
            repositoryImpl.deleteByMangaId(42L)
            coVerify(exactly = 1) { queries.deleteByMangaId(42L) }

            repositoryImpl.deleteAll()
            coVerify(exactly = 1) { queries.deleteAll() }
        }
    }

    // -------------------------------------------------------------------------
    // Interactor Seams Tests
    // -------------------------------------------------------------------------

    @Test
    fun `interactors delegate directly to repository`() {
        runBlocking {
            val expected = MangaLastVisited(mangaId = 1L, chapterId = 10L, lastVisitedPage = 5L, visitedAt = 1000L)
            coEvery { mockRepository.getByMangaId(1L) } returns expected
            coEvery { mockRepository.getGlobalLastVisited(any()) } returns expected
            coEvery { mockRepository.subscribeByMangaId(1L) } returns flowOf(expected)
            coEvery { mockRepository.subscribeGlobalLastVisited(any()) } returns flowOf(expected)

            getMangaLastVisited.await(1L) shouldBe expected
            getGlobalLastVisited.await(favoritesOnly = true) shouldBe expected
            getMangaLastVisited.subscribe(1L).first() shouldBe expected
            getGlobalLastVisited.subscribe(favoritesOnly = true).first() shouldBe expected

            upsertMangaLastVisited.await(1L, 10L, 5L, 1000L)
            coVerify(exactly = 1) { mockRepository.upsert(1L, 10L, 5L, 1000L) }

            deleteMangaLastVisited.await(1L)
            coVerify(exactly = 1) { mockRepository.deleteByMangaId(1L) }

            deleteMangaLastVisited.awaitAll()
            coVerify(exactly = 1) { mockRepository.deleteAll() }
        }
    }
}
