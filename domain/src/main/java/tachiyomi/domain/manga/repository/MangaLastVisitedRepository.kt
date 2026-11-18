package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaLastVisited

interface MangaLastVisitedRepository {
    suspend fun getByMangaId(mangaId: Long): MangaLastVisited?
    fun subscribeByMangaId(mangaId: Long): Flow<MangaLastVisited?>
    suspend fun getGlobalLastVisited(favoritesOnly: Boolean = false): MangaLastVisited?
    fun subscribeGlobalLastVisited(favoritesOnly: Boolean = false): Flow<MangaLastVisited?>
    suspend fun upsert(mangaId: Long, chapterId: Long, lastVisitedPage: Long, visitedAt: Long)
    suspend fun deleteByMangaId(mangaId: Long)
    suspend fun deleteAll()
}
