package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class GetMangaLastVisited(
    private val repository: MangaLastVisitedRepository,
) {
    suspend fun await(mangaId: Long): MangaLastVisited? {
        return repository.getByMangaId(mangaId)
    }

    fun subscribe(mangaId: Long): Flow<MangaLastVisited?> {
        return repository.subscribeByMangaId(mangaId)
    }
}
