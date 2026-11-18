package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class MangaLastVisitedRepositoryImpl(
    private val handler: DatabaseHandler,
) : MangaLastVisitedRepository {

    override suspend fun getByMangaId(mangaId: Long): MangaLastVisited? {
        return handler.awaitOneOrNull {
            manga_last_visitedQueries.getLastVisitedByMangaId(mangaId, MangaLastVisitedMapper::map)
        }
    }

    override fun subscribeByMangaId(mangaId: Long): Flow<MangaLastVisited?> {
        return handler.subscribeToOneOrNull {
            manga_last_visitedQueries.getLastVisitedByMangaId(mangaId, MangaLastVisitedMapper::map)
        }
    }

    override suspend fun getGlobalLastVisited(favoritesOnly: Boolean): MangaLastVisited? {
        return handler.awaitOneOrNull {
            if (favoritesOnly) {
                manga_last_visitedQueries.getGlobalLastVisitedFavorite(MangaLastVisitedMapper::map)
            } else {
                manga_last_visitedQueries.getGlobalLastVisited(MangaLastVisitedMapper::map)
            }
        }
    }

    override fun subscribeGlobalLastVisited(favoritesOnly: Boolean): Flow<MangaLastVisited?> {
        return handler.subscribeToOneOrNull {
            if (favoritesOnly) {
                manga_last_visitedQueries.getGlobalLastVisitedFavorite(MangaLastVisitedMapper::map)
            } else {
                manga_last_visitedQueries.getGlobalLastVisited(MangaLastVisitedMapper::map)
            }
        }
    }

    override suspend fun upsert(mangaId: Long, chapterId: Long, lastVisitedPage: Long, visitedAt: Long) {
        try {
            handler.await {
                manga_last_visitedQueries.upsert(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    lastVisitedPage = lastVisitedPage,
                    visitedAt = visitedAt,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteByMangaId(mangaId: Long) {
        try {
            handler.await {
                manga_last_visitedQueries.deleteByMangaId(mangaId)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAll() {
        try {
            handler.await {
                manga_last_visitedQueries.deleteAll()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
