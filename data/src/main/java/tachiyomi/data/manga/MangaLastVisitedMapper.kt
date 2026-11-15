package tachiyomi.data.manga

import tachiyomi.domain.manga.model.MangaLastVisited

object MangaLastVisitedMapper {
    fun map(
        mangaId: Long,
        chapterId: Long,
        lastVisitedPage: Long,
        visitedAt: Long,
    ): MangaLastVisited = MangaLastVisited(
        mangaId = mangaId,
        chapterId = chapterId,
        lastVisitedPage = lastVisitedPage,
        visitedAt = visitedAt,
    )
}
