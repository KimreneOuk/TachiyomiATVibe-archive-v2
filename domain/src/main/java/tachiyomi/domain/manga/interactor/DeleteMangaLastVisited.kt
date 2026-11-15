package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class DeleteMangaLastVisited(
    private val repository: MangaLastVisitedRepository,
) {
    suspend fun await(mangaId: Long) {
        repository.deleteByMangaId(mangaId)
    }

    suspend fun awaitAll() {
        repository.deleteAll()
    }
}
