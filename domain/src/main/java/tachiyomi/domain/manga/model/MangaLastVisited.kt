package tachiyomi.domain.manga.model

data class MangaLastVisited(
    val mangaId: Long,
    val chapterId: Long,
    val lastVisitedPage: Long,
    val visitedAt: Long,
)
