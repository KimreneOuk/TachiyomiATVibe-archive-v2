package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.InMemorySharedPreferences
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.coordinator.TranslationRequestCoordinator
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A session admission rejection must never strand an acknowledged request
 * in STARTING/PREPARING: it resolves to an explicit retryable
 * ADMISSION_FAILED. Cancelled and replaced requests are never resurrected,
 * and the fenced cancel/handoff-failure writes cannot touch a newer request.
 */
class TranslationRequestSessionRejectionTest {

    private val source = mockk<HttpSource>(relaxed = true) {
        every { id } returns 1L
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { this@mockk.source } returns 1L
        every { title } returns "fixture"
    }
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 10L
        every { name } returns "chapter"
        every { scanlator } returns null
    }
    private val chapterB = mockk<Chapter>(relaxed = true) {
        every { id } returns 11L
        every { name } returns "chapter-b"
        every { scanlator } returns null
    }

    private val preferences = InMemorySharedPreferences()
    private val laneJob = SupervisorJob()
    private val scope = CoroutineScope(laneJob + Dispatchers.IO)
    private val admitted = AtomicBoolean(false)
    private val queueStateFlow = MutableStateFlow(emptyList<Translation>())

    private lateinit var manager: TranslationManager
    private lateinit var store: TranslationPendingRequestStore
    private lateinit var sessionCoordinator: TranslationSessionCoordinator

    @BeforeEach
    fun setUp() {
        preferences.entries.clear()
        store = TranslationPendingRequestStore(mockContext())
        admitted.set(false)
        queueStateFlow.value = emptyList()
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        every { translator.queueState } returns queueStateFlow
        every { translator.isRunning } returns false
        every { translator.isQueueConfigValid() } returns true
        every { translator.queueChapter(any(), any()) } answers {
            admitted.set(true)
            queueStateFlow.value =
                queueStateFlow.value + Translation(source, manga, secondArg<Chapter>())
                    .also { it.status = Translation.State.QUEUE }
            true
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(any()) } returns source
        sessionCoordinator = TranslationSessionCoordinator()
        manager = uninitializedManager(store, translator, sourceManager, sessionCoordinator)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `reader session fails the acknowledged request honestly instead of stranding STARTING`() = runBlocking<Unit> {
        sessionCoordinator.requestReaderSession(ReaderSessionIntent())
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!

        manager.translateChapter(manga, chapter, generation)

        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state.failureKind shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
        state.reason shouldBe "Reader translation session is active"
        state.generation shouldBe generation
        admitted.get() shouldBe false

        // The failure is actionable: once the reader session is released the
        // same retry path admits the chapter and clears the request.
        sessionCoordinator.finishSession()
        manager.translateChapter(manga, chapter)
        admitted.get() shouldBe true
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
    }

    @Test
    fun `session rejection never resurrects a cancelled request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)
        sessionCoordinator.requestReaderSession(ReaderSessionIntent())

        manager.translateChapter(manga, chapter, generation)

        admitted.get() shouldBe false
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
        store.record(10L).shouldBeNull()
    }

    @Test
    fun `stale rejected caller cannot fail a replaced request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val firstGeneration = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val secondGeneration = manager.pendingRequestGeneration(10L)!!
        sessionCoordinator.requestReaderSession(ReaderSessionIntent())

        manager.translateChapter(manga, chapter, firstGeneration)

        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe secondGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
        admitted.get() shouldBe false
    }

    @Test
    fun `multi-select session rejection fails every acknowledged request in the group`() = runBlocking<Unit> {
        sessionCoordinator.requestReaderSession(ReaderSessionIntent())
        manager.acknowledgeTranslationRequests(listOf(chapter, chapterB))
        val generations = mapOf(
            10L to manager.pendingRequestGeneration(10L)!!,
            11L to manager.pendingRequestGeneration(11L)!!,
        )

        val anyAdmitted = manager.translateChaptersIfCurrent(manga, listOf(chapter, chapterB), generations)

        anyAdmitted shouldBe false
        listOf(10L, 11L).forEach { chapterId ->
            val state = manager.pendingTranslationRequests.value.getValue(chapterId)
            state.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
            state.reason shouldBe "Reader translation session is active"
        }
        admitted.get() shouldBe false
    }

    @Test
    fun `session rejection spares chapters outside the caller's generation map`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter, chapterB))
        val generations = mapOf(10L to manager.pendingRequestGeneration(10L)!!)
        sessionCoordinator.requestReaderSession(ReaderSessionIntent())

        manager.translateChaptersIfCurrent(manga, listOf(chapter, chapterB), generations)

        manager.pendingTranslationRequests.value.getValue(10L).phase shouldBe
            TranslationRequestPhase.ADMISSION_FAILED
        manager.pendingTranslationRequests.value.getValue(11L).phase shouldBe
            TranslationRequestPhase.STARTING
    }

    @Test
    fun `fenced cancel clears the current-generation request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!

        val cleared = manager.cancelTranslationRequestIfCurrent(10L, generation)

        cleared shouldBe true
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
    }

    @Test
    fun `stale fenced cancel cannot cancel a replaced request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val firstGeneration = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val secondGeneration = manager.pendingRequestGeneration(10L)!!

        val cleared = manager.cancelTranslationRequestIfCurrent(10L, firstGeneration)

        cleared shouldBe false
        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe secondGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
    }

    @Test
    fun `fenced cancel clears its own terminal request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!
        manager.onDownloadCancelledForTranslation(10L)
        manager.pendingTranslationRequests.value.getValue(10L).phase shouldBe
            TranslationRequestPhase.CANCELLED

        val cleared = manager.cancelTranslationRequestIfCurrent(10L, generation)

        cleared shouldBe true
        manager.pendingTranslationRequests.value.containsKey(10L) shouldBe false
    }

    @Test
    fun `stale download handoff cannot fail a replaced request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        manager.queueTranslationAfterDownload(manga, chapter)
        manager.cancelTranslationRequest(10L)
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val currentGeneration = manager.pendingRequestGeneration(10L)!!

        val failed = manager.markAttachedDownloadHandoffFailed(
            10L,
            "Translation could not start after the chapter download",
            TranslationRequestFailureKind.CONFIG_INVALID,
        )

        failed shouldBe false
        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe currentGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
    }

    @Test
    fun `attached download handoff failure writes the typed failure`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        manager.queueTranslationAfterDownload(manga, chapter)
        val generation = manager.pendingRequestGeneration(10L)!!

        val failed = manager.markAttachedDownloadHandoffFailed(
            10L,
            "Translation could not start after the chapter download",
            TranslationRequestFailureKind.CONFIG_INVALID,
        )

        failed shouldBe true
        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state.failureKind shouldBe TranslationRequestFailureKind.CONFIG_INVALID
        state.generation shouldBe generation
    }

    @Test
    fun `handoff failure without an attached download is a no-op`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val generation = manager.pendingRequestGeneration(10L)!!

        val failed = manager.markAttachedDownloadHandoffFailed(
            10L,
            "Translation could not start after the chapter download",
            TranslationRequestFailureKind.CONFIG_INVALID,
        )

        failed shouldBe false
        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.phase shouldBe TranslationRequestPhase.STARTING
        state.generation shouldBe generation
    }

    @Test
    fun `rekey reconciliation failure fails the attached request as admission-failed`() = runBlocking<Unit> {
        val coordinator = directCoordinator()
        coordinator.acknowledgeTranslationRequests(listOf(chapter))
        coordinator.queueTranslationAfterDownload(manga, chapter)
        val generation = coordinator.pendingRequestGeneration(10L)!!

        coordinator.startTranslationAfterDownloadIfRequested(manga, chapter)

        val state = coordinator.pendingTranslationRequests.value.getValue(10L)
        state.phase shouldBe TranslationRequestPhase.ADMISSION_FAILED
        state.failureKind shouldBe TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
        state.generation shouldBe generation
    }

    @Test
    fun `stale rekey reconciliation failure cannot fail a replaced request`() = runBlocking<Unit> {
        val coordinator = directCoordinator()
        coordinator.acknowledgeTranslationRequests(listOf(chapter))
        coordinator.queueTranslationAfterDownload(manga, chapter)
        coordinator.cancelTranslationRequest(10L)
        coordinator.acknowledgeTranslationRequests(listOf(chapter))
        val currentGeneration = coordinator.pendingRequestGeneration(10L)!!

        coordinator.startTranslationAfterDownloadIfRequested(manga, chapter)

        val state = coordinator.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe currentGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
    }

    /**
     * Direct coordinator with a retained-but-failing re-key, isolating the
     * reconciliation-failure write from the full manager fixture.
     */
    private fun directCoordinator(): TranslationRequestCoordinator = TranslationRequestCoordinator(
        pendingRequestStore = TranslationPendingRequestStore(mockContext()),
        initialPendingRequests = MutableStateFlow(emptyMap()),
        storeScopeProvider = { scope },
        queueStateProvider = { MutableStateFlow(emptyList<Translation>()) },
        batchCoordinatorProvider = { mockk(relaxed = true) },
        hasPendingDownloadRekeyFn = { true },
        reconcilePendingDownloadRekeyFn = { false },
    )

    @Test
    fun `request seam acknowledges a fresh start and adopts the duplicate`() = runBlocking<Unit> {
        val generation = manager.beginTranslationRequestIfCurrent(10L, null)

        generation.shouldNotBeNull()
        manager.pendingTranslationRequests.value.getValue(10L).phase shouldBe
            TranslationRequestPhase.STARTING
        // A duplicate start adopts the same live request instead of re-acking.
        manager.beginTranslationRequestIfCurrent(10L, generation) shouldBe generation
    }

    @Test
    fun `request seam re-arms a terminal request with a fresh generation`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val terminalGeneration = manager.pendingRequestGeneration(10L)!!
        manager.markTranslationDownloadFailed(10L)
        manager.isTranslationRequestCurrent(10L, terminalGeneration) shouldBe false

        val retried = manager.beginTranslationRequestIfCurrent(10L, terminalGeneration)

        retried.shouldNotBeNull()
        retried!! shouldBe manager.pendingRequestGeneration(10L)
        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.phase shouldBe TranslationRequestPhase.STARTING
        manager.isTranslationRequestCurrent(10L, retried) shouldBe true
    }

    @Test
    fun `request seam refuses a stale caller instead of adopting newer work`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val staleGeneration = manager.pendingRequestGeneration(10L)!!
        manager.cancelTranslationRequest(10L)
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val liveGeneration = manager.pendingRequestGeneration(10L)!!

        manager.beginTranslationRequestIfCurrent(10L, staleGeneration) shouldBe null

        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe liveGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
    }

    @Test
    fun `request seam without a displayed generation never adopts a live request`() = runBlocking<Unit> {
        manager.acknowledgeTranslationRequests(listOf(chapter))
        val liveGeneration = manager.pendingRequestGeneration(10L)!!

        manager.beginTranslationRequestIfCurrent(10L, null) shouldBe null

        val state = manager.pendingTranslationRequests.value.getValue(10L)
        state.generation shouldBe liveGeneration
        state.phase shouldBe TranslationRequestPhase.STARTING
    }

    private fun mockContext(): Context = mockk {
        every { getSharedPreferences(any(), any()) } returns preferences
    }

    /** Mirrors TranslationRequestGenerationFenceTest's test factory fixture. */
    private fun uninitializedManager(
        pendingRequestStore: TranslationPendingRequestStore,
        translator: BatchTranslationCoordinator,
        sourceManager: SourceManager,
        sessions: TranslationSessionCoordinator,
    ): TranslationManager {
        val manager = TranslationManager.createForTesting(
            context = mockk<Context>(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = sourceManager,
            translationPreferences = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            processor = mockk(relaxed = true),
            batchCoordinator = translator,
            pendingRequestStore = pendingRequestStore,
            sessionCoordinator = sessions,
        )
        setField(manager, "storeScope", scope)
        return manager
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }
}
