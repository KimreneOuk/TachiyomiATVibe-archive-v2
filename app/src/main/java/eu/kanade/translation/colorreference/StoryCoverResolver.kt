package eu.kanade.translation.colorreference

import android.content.Context
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream

/** Resolves story cover bytes following the custom cover cache -> standard cover cache -> network hierarchy. */
object StoryCoverResolver {

    suspend fun resolveCoverStream(
        manga: Manga,
        coverCache: CoverCache = Injekt.get(),
        networkHelper: NetworkHelper = Injekt.get(),
        sourceManager: SourceManager = Injekt.get(),
    ): InputStream? = withContext(Dispatchers.IO) {
        // 1. Custom cover cached locally
        if (manga.hasCustomCover(coverCache)) {
            val customCover = coverCache.getCustomCoverFile(manga.id)
            if (customCover.exists() && customCover.length() > 0L) {
                return@withContext FileInputStream(customCover)
            }
        }

        // 2. Standard cover cached locally
        val cachedCover = coverCache.getCoverFile(manga.thumbnailUrl)
        if (cachedCover != null && cachedCover.exists() && cachedCover.length() > 0L) {
            return@withContext FileInputStream(cachedCover)
        }

        // 3. Fallback: fetch remote cover via network. Prefer the manga's own
        // source client so source-specific headers (Referer/UA) apply.
        val url = manga.thumbnailUrl ?: return@withContext null
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                val client = (sourceManager.get(manga.source) as? HttpSource)?.client
                    ?: networkHelper.client
                val response = client.newCall(GET(url)).awaitSuccess()
                val bytes = response.body.bytes()
                if (bytes.isNotEmpty()) {
                    return@withContext ByteArrayInputStream(bytes)
                }
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "Failed to fetch remote cover for story color reference: $url" }
            }
        }

        null
    }
}
