package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class UpsertMangaLastVisited(
    private val repository: MangaLastVisitedRepository,
) {
    suspend fun await(mangaId: Long, chapterId: Long, lastVisitedPage: Long, visitedAt: Long) {
        repository.upsert(mangaId, chapterId, lastVisitedPage, visitedAt)
    }
}
