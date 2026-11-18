package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaLastVisited
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaLastVisited
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaScreenModelResumeTargetTest {

    private val mainThreadSurrogate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val mangaId = 1L

    private val manga: Manga = Manga.create().copy(
        id = mangaId,
        title = "Test Manga",
        source = 1L,
        favorite = true,
        initialized = true,
    )

    private val chapter1: Chapter = Chapter.create().copy(
        id = 101L,
        mangaId = mangaId,
        name = "Chapter 1",
        chapterNumber = 1.0,
        read = false,
        lastPageRead = 0L,
    )

    private val chapter2: Chapter = Chapter.create().copy(
        id = 102L,
        mangaId = mangaId,
        name = "Chapter 2",
        chapterNumber = 2.0,
        read = true,
        lastPageRead = 5L,
    )

    private val chapter3: Chapter = Chapter.create().copy(
        id = 103L,
        mangaId = mangaId,
        name = "Chapter 3",
        chapterNumber = 3.0,
        read = false,
        lastPageRead = 0L,
    )

    private val context = mockk<Context>(relaxed = true)
    private val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val lifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val getMangaAndChapters = mockk<GetMangaWithChapters>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val downloadPreferences = mockk<DownloadPreferences> {
        every { downloadOnlyOverWifi() } returns mockk { every { get() } returns false }
    }
    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val trackerManager = mockk<TrackerManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val getAvailableScanlators = mockk<GetAvailableScanlators>(relaxed = true)
    private val getExcludedScanlators = mockk<GetExcludedScanlators>(relaxed = true)
    private val getTracks = mockk<GetTracks>(relaxed = true)

    private val swipeActionPref = mockk<Preference<LibraryPreferences.ChapterSwipeAction>> {
        every { get() } returns LibraryPreferences.ChapterSwipeAction.Disabled
        every { changes() } returns emptyFlow()
    }
    private val autoTrackPref = mockk<Preference<AutoTrackState>> {
        every { get() } returns AutoTrackState.NEVER
        every { changes() } returns emptyFlow()
    }
    private val skipFilteredPref = mockk<Preference<Boolean>> {
        every { get() } returns false
        every { changes() } returns emptyFlow()
    }
    private val updateRestrictionsPref = mockk<Preference<Set<String>>> {
        every { get() } returns emptySet()
        every { changes() } returns emptyFlow()
    }
    private val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
    private val trackPreferences = mockk<TrackPreferences>(relaxed = true)
    private val readerPreferences = mockk<ReaderPreferences>(relaxed = true)
    private val getMangaLastVisited = mockk<GetMangaLastVisited>(relaxed = true)

    private val downloadQueueState = MutableStateFlow(emptyList<Download>())
    private val translationQueueState = MutableStateFlow(emptyList<Translation>())
    private val translationStatusFlow = MutableSharedFlow<Translation>(extraBufferCapacity = 16)
    private val pendingRequestsState = MutableStateFlow(emptyMap<Long, TranslationRequestState>())

    private lateinit var model: MangaScreenModel

    @BeforeAll
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        if (injektPrepared.compareAndSet(false, true)) {
            Injekt.addSingleton(fullType<SourceManager>(), sourceManager)
            val preferenceStore = mockk<PreferenceStore> {
                every { getBoolean(any(), any()) } returns mockk {
                    every { get() } returns false
                }
            }
            Injekt.addSingleton(
                fullType<BasePreferences>(),
                BasePreferences(context = mockk(relaxed = true), preferenceStore = preferenceStore),
            )
        }
        every { sourceManager.getOrStub(any()) } returns mockk<Source>(relaxed = true)
        every { lifecycleOwner.lifecycle } returns lifecycleRegistry
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val chapterList = listOf(chapter3, chapter2, chapter1)
        coEvery { getMangaAndChapters.subscribe(any(), any()) } returns flowOf(manga to chapterList)
        coEvery { getMangaAndChapters.awaitManga(any()) } returns manga
        coEvery { getMangaAndChapters.awaitChapters(any(), any()) } returns chapterList

        every { downloadManager.queueState } returns downloadQueueState
        every { downloadManager.statusFlow() } returns emptyFlow()
        every { downloadManager.progressFlow() } returns emptyFlow()
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any()) } returns false

        every { downloadCache.changes } returns MutableSharedFlow(extraBufferCapacity = 1)

        every { translationManager.queueState } returns translationQueueState
        every { translationManager.statusFlow() } returns translationStatusFlow
        every { translationManager.pendingTranslationRequests } returns pendingRequestsState
        coEvery {
            translationManager.getChapterTranslationStatus(any(), any(), any(), any(), any())
        } returns Translation.State.NOT_TRANSLATED
        coEvery {
            translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns null
        every { translationManager.hasPendingTranslationRequest(any()) } returns false

        every { getAvailableScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getExcludedScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getTracks.subscribe(any()) } returns flowOf(emptyList())

        every { libraryPreferences.swipeToEndAction() } returns swipeActionPref
        every { libraryPreferences.swipeToStartAction() } returns swipeActionPref
        every { libraryPreferences.autoUpdateMangaRestrictions() } returns updateRestrictionsPref
        every { trackPreferences.autoUpdateTrackOnMarkRead() } returns autoTrackPref
        every { readerPreferences.skipFiltered() } returns skipFilteredPref

        evictCachedScreenModelScope()

        model = MangaScreenModel(
            context = context,
            lifecycle = lifecycleRegistry,
            mangaId = mangaId,
            isFromSource = false,
            libraryPreferences = libraryPreferences,
            trackPreferences = trackPreferences,
            readerPreferences = readerPreferences,
            trackerManager = trackerManager,
            trackChapter = mockk(relaxed = true),
            downloadManager = downloadManager,
            downloadPreferences = downloadPreferences,
            translationManager = translationManager,
            translationPreferences = mockk {
                every { translationInpaintingMode() } returns mockk {
                    every { get() } returns "FAST"
                }
            },
            downloadCache = downloadCache,
            getMangaAndChapters = getMangaAndChapters,
            getDuplicateLibraryManga = mockk(relaxed = true),
            getAvailableScanlators = getAvailableScanlators,
            getExcludedScanlators = getExcludedScanlators,
            setExcludedScanlators = mockk(relaxed = true),
            setMangaChapterFlags = mockk(relaxed = true),
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            setReadStatus = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            syncChaptersWithSource = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            getTracks = getTracks,
            addTracks = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            filterChaptersForDownload = mockk(relaxed = true),
            getMangaLastVisited = getMangaLastVisited,
        )

        runBlocking {
            withTimeout(30_000) {
                model.state.first { it is MangaScreenModel.State.Success }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (::model.isInitialized) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            model.screenModelScope.cancel()
            runBlocking {
                withTimeout(30_000) {
                    model.screenModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        evictCachedScreenModelScope()
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun `exact chapter and page restore when last visited exists`() = runBlocking {
        coEvery { getMangaLastVisited.await(mangaId) } returns MangaLastVisited(
            mangaId = mangaId,
            chapterId = chapter1.id,
            lastVisitedPage = 14L,
            visitedAt = 1000L,
        )

        val target = model.getResumeTarget()

        assertNotNull(target)
        assertEquals(chapter1.id, target!!.chapter.id)
        assertEquals(14, target.initialPage)
    }

    @Test
    fun `read chapter preserves target page restore`() = runBlocking {
        // chapter2 has read = true and lastPageRead = 5
        coEvery { getMangaLastVisited.await(mangaId) } returns MangaLastVisited(
            mangaId = mangaId,
            chapterId = chapter2.id,
            lastVisitedPage = 25L,
            visitedAt = 2000L,
        )

        val target = model.getResumeTarget()

        assertNotNull(target)
        assertEquals(chapter2.id, target!!.chapter.id)
        assertEquals(25, target.initialPage)
    }

    @Test
    fun `deleted or filtered chapter falls back to next unread chapter with null page`() = runBlocking {
        // Recorded chapter 9999L is not in the manga chapter list
        coEvery { getMangaLastVisited.await(mangaId) } returns MangaLastVisited(
            mangaId = mangaId,
            chapterId = 9999L,
            lastVisitedPage = 10L,
            visitedAt = 3000L,
        )

        val target = model.getResumeTarget()

        assertNotNull(target)
        // Chapter 1 is the next unread chapter
        assertEquals(chapter1.id, target!!.chapter.id)
        assertNull(target.initialPage)
    }

    @Test
    fun `null history falls back to next unread chapter with null page`() = runBlocking {
        coEvery { getMangaLastVisited.await(mangaId) } returns null

        val target = model.getResumeTarget()

        assertNotNull(target)
        assertEquals(chapter1.id, target!!.chapter.id)
        assertNull(target.initialPage)
    }

    private fun evictCachedScreenModelScope() {
        try {
            val store = cafe.adriel.voyager.core.model.ScreenModelStore
            val getDependencies = store.javaClass.methods.first { it.name == "getDependencies" }
            val dependencies = getDependencies.invoke(store) as? MutableMap<Any?, Any?> ?: return
            dependencies.keys.removeAll { key -> key is String && "ScreenModelCoroutineScope" in key }
        } catch (_: Exception) {
            // Best effort
        }
    }

    private companion object {
        val injektPrepared = AtomicBoolean(false)
    }
}
