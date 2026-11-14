package eu.kanade.translation.coordinator

import android.content.Context
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class SharedChapterImageDownloadTest {
    @TempDir
    lateinit var tempDir: File

    private var cache: ChapterCache? = null

    private fun chapterCache(): ChapterCache {
        val context = mockk<Context>()
        every { context.cacheDir } returns tempDir
        return ChapterCache(context, Json).also { cache = it }
    }

    @AfterEach
    fun closeCache() {
        cache?.let {
            val field = ChapterCache::class.java.getDeclaredField("diskCache")
            field.isAccessible = true
            (field.get(it) as DiskLruCache).close()
        }
    }

    private fun response(url: String, bytes: ByteArray) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(bytes.toResponseBody())
        .build()

    @Test
    fun `reader and colorizer share the same in-flight image download`() = runBlocking {
        val cache = chapterCache()
        val url = "https://images.example/1.jpg"
        val bytes = byteArrayOf(1, 2, 3, 4)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val source = mockk<HttpSource>(relaxed = true)
        coEvery { source.getImage(any()) } coAnswers {
            started.complete(Unit)
            finish.await()
            response(url, bytes)
        }
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "page", url))
        val plan = OnlineColorizePageStreamResolver(cache)
            .resolve(mockk(relaxed = true), mockk(relaxed = true), source)!!
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 42
                manga_id = 100
                this.url = "chapter"
            },
        )
        val page = ReaderPage(0, "page", url).apply { this.chapter = chapter }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            val reader = async(Dispatchers.IO) { loader.internalLoadPage(page) }
            withTimeout(5_000) { started.await() }
            val colorizer = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { plan.prepareStream("1.jpg") }
            assertNull(withTimeoutOrNull(200) { colorizer.await() })
            finish.complete(Unit)
            withTimeout(5_000) {
                reader.await()
                colorizer.await()
            }
            assertEquals(Page.State.READY, page.status)
            assertArrayEquals(bytes, plan.streamFor("1.jpg").use { it.readBytes() })
            coVerify(exactly = 1) { source.getImage(any()) }
        } finally {
            finish.complete(Unit)
            loader.recycle()
        }
    }

    @Test
    fun `cancelled download releases image for the next requester`() = runBlocking {
        val cache = chapterCache()
        val url = "https://images.example/2.jpg"
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.IO) {
            cache.getOrDownloadImage(url) {
                started.complete(Unit)
                never.await()
                response(url, byteArrayOf(1))
            }
        }
        withTimeout(5_000) { started.await() }
        val waiter = async(Dispatchers.IO) {
            cache.getOrDownloadImage(url) { response(url, byteArrayOf(2)) }
        }
        owner.cancelAndJoin()
        assertEquals(ChapterCache.ImageCacheResult.DOWNLOADED, withTimeout(5_000) { waiter.await() })
        assertArrayEquals(byteArrayOf(2), cache.getImageFile(url).readBytes())
    }

    @Test
    fun `failed download releases image for the next requester`() = runBlocking {
        val cache = chapterCache()
        val url = "https://images.example/failed.jpg"
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.IO) {
            runCatching {
                cache.getOrDownloadImage(url) {
                    started.complete(Unit)
                    finish.await()
                    throw IOException("download failed")
                }
            }
        }
        withTimeout(5_000) { started.await() }
        val waiter = async(Dispatchers.IO) {
            cache.getOrDownloadImage(url) { response(url, byteArrayOf(3)) }
        }
        assertNull(withTimeoutOrNull(200) { waiter.await() })
        finish.complete(Unit)
        assertEquals("download failed", withTimeout(5_000) { owner.await() }.exceptionOrNull()?.message)
        assertEquals(ChapterCache.ImageCacheResult.DOWNLOADED, withTimeout(5_000) { waiter.await() })
        assertArrayEquals(byteArrayOf(3), cache.getImageFile(url).readBytes())
    }

    @Test
    fun `different images download independently`() = runBlocking {
        val cache = chapterCache()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val firstUrl = "https://images.example/first.jpg"
        val secondUrl = "https://images.example/second.jpg"
        val first = async(Dispatchers.IO) {
            cache.getOrDownloadImage(firstUrl) {
                started.complete(Unit)
                finish.await()
                response(firstUrl, byteArrayOf(1))
            }
        }
        withTimeout(5_000) { started.await() }
        try {
            withTimeout(5_000) { cache.getOrDownloadImage(secondUrl) { response(secondUrl, byteArrayOf(2)) } }
            assertArrayEquals(byteArrayOf(2), cache.getImageFile(secondUrl).readBytes())
        } finally {
            finish.complete(Unit)
            first.await()
        }
    }
}
