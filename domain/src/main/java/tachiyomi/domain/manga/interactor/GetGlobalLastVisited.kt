package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.manga.repository.MangaLastVisitedRepository

class GetGlobalLastVisited(
    private val repository: MangaLastVisitedRepository,
) {
    suspend fun await(favoritesOnly: Boolean = false): MangaLastVisited? {
        return repository.getGlobalLastVisited(favoritesOnly)
    }

    fun subscribe(favoritesOnly: Boolean = false): Flow<MangaLastVisited?> {
        return repository.subscribeGlobalLastVisited(favoritesOnly)
    }
}
