package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchQueuePauseState
import eu.kanade.translation.coordinator.BatchQueueResumeResult
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ChapterQueuePreflight
import eu.kanade.translation.coordinator.InMemorySharedPreferences
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.SessionRejection
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.coordinator.TranslationSessionState
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.ArtifactStageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableFailureMetadata
import eu.kanade.translation.persistence.FailureCategory
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guards for batch start admission:
 * - a generic queue start must not resurrect an ERROR-restored queue entry
 *   (restore work resumes only through an explicit per-chapter request);
 * - an admission for a chapter whose batch is still in flight must be a no-op
 *   for the running work â€” exactly ONE concurrent batch schedule (two live
 *   schedules for one chapter make each store generation advance cancel the
 *   prior run; observed on device as back-to-back `schedule_start` events for
 *   the same chapter with `outcome=cancelled`).
 */
class BatchTranslationCoordinatorStartGuardTest {

    private val chapterId = 21L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter 21"
        every { scanlator } returns null
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
        every { source } returns 1L
    }
    private val source = mockk<HttpSource>(relaxed = true)

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns InMemorySharedPreferences()
    }
    private val provider = mockk<TranslationFileProvider>(relaxed = true)
    private val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val preferences = mockk<TranslationPreferences>(relaxed = true)
    private val processor = mockk<TranslationProcessor>(relaxed = true)
    private var storeResolver: suspend (Translation) -> eu.kanade.translation.persistence.ChapterTranslationStore? = { null }

    private fun translatorWithQueue(
        queue: List<Translation>,
        sessionCoordinator: TranslationSessionCoordinator? = null,
    ): BatchTranslationCoordinator {
        val translator = newTranslator(sessionCoordinator)
        // queueState is bound to the constructor-time _queueState instance, so
        // the injected flow must replace BOTH fields to be visible to start().
        val flow = MutableStateFlow(queue)
        setField(translator, "_queueState", flow)
        setField(translator, "queueState", flow.asStateFlow())
        return translator
    }

    private fun newTranslator(
        sessionCoordinator: TranslationSessionCoordinator? = null,
    ): BatchTranslationCoordinator = BatchTranslationCoordinator(
        context,
        provider,
        downloadProvider,
        sourceManager,
        preferences,
        TranslationQueueStore(context),
        processor,
        pageOperation = mockk(relaxed = true),
        resolveStore = { storeResolver(it) },
        batchTrackerFactory = { _, _, _ -> null },
        sessionCoordinator = sessionCoordinator,
    )

    private fun translation(status: Translation.State) =
        Translation(source, manga, chapter).also { it.status = status }

    private fun chapterFor(targetId: Long): Chapter = mockk(relaxed = true) {
        every { id } returns targetId
        every { name } returns "Chapter $targetId"
        every { scanlator } returns null
    }

    /**
     * Every batch "runs" by first entering the store resolver â€” the same
     * position the pipeline occupies before its generation is advanced. The
     * resolver holds its callback until released, so a cancelled-but-unwinding
     * batch stays in flight exactly like a mid-run uncancellable native call.
     */
    private fun stubInFlightResolver(
        concurrent: AtomicInteger,
        maxConcurrent: AtomicInteger,
        resolverEntered: CompletableDeferred<Unit>? = null,
        resolverRelease: CompletableDeferred<Unit>? = null,
    ) {
        storeResolver = {
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { previous -> maxOf(previous, now) }
            try {
                resolverEntered?.complete(Unit)
                resolverRelease?.let { release ->
                    withContext(NonCancellable) { withTimeout(5_000) { release.await() } }
                }
            } finally {
                concurrent.decrementAndGet()
            }
            null
        }
        // Fast ERROR exit after the resolver: no artifact directory resolves,
        // and no chapter dir means the typed pre-pipeline failure exit.
        every { provider.findMangaDir(any(), any()) } returns null
        every { downloadProvider.findChapterDir(any(), any(), any(), any()) } returns null
    }

    private fun translationJob(translator: BatchTranslationCoordinator): Job =
        readField(translator, "translationJob") as Job

    private fun awaitJobs(vararg jobs: Job) {
        runBlocking {
            withTimeout(5_000) {
                jobs.forEach { it.join() }
            }
        }
    }

    @Test
    fun `generic queue start does not resurrect an ERROR entry`() {
        val translation = translation(Translation.State.ERROR)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe false
        translator.isRunning shouldBe false
        translation.status shouldBe Translation.State.ERROR
    }

    @Test
    fun `generic queue start still admits a plain QUEUE entry`() {
        stubInFlightResolver(AtomicInteger(), AtomicInteger())
        val translation = translation(Translation.State.QUEUE)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe true
        awaitJobs(translationJob(translator))
        translator.isRunning shouldBe false
        translation.status shouldBe Translation.State.ERROR
    }

    @Test
    fun `re-clicking colorize chapter rearms a stopped entry for resume`() {
        // The reported recovery path: a colorize run that stopped leaves its
        // queue entry ERROR; tapping "Colorize chapter" again must re-arm that
        // same entry as a runnable COLORIZE-ONLY run.
        every { sourceManager.get(any()) } returns source
        val stopped = translation(Translation.State.ERROR)
        val translator = translatorWithQueue(listOf(stopped))

        translator.queueChapter(manga, chapter, colorizeOnly = true)

        val entry = translator.queuedTranslationOrNull(chapterId)!!
        entry.status shouldBe Translation.State.QUEUE
        entry.colorizeOnly shouldBe true
    }

    @Test
    fun `atomic replacement reservation rejects a newer translating run at the cancel boundary`() {
        val confirmedRun = translation(Translation.State.TRANSLATING)
        val newerRun = translation(Translation.State.TRANSLATING)
        val translator = translatorWithQueue(listOf(newerRun))

        translator.reserveRunningChapterForReplace(confirmedRun, confirmedRun.runGeneration) shouldBe false
        (translator.queuedTranslationOrNull(chapterId) === newerRun) shouldBe true
    }

    @Test
    fun `preflight captures the active queue run identity and generation`() {
        val run = translation(Translation.State.TRANSLATING)
        val translator = translatorWithQueue(listOf(run))

        val conflict = translator.queuePreflight(chapterId + 1, source.id)
            as ChapterQueuePreflight.RunningConflict

        (conflict.run === run) shouldBe true
        conflict.runGeneration shouldBe run.runGeneration
    }

    @Test
    fun `atomic replacement reservation rejects a newer generation of the same run object`() {
        val run = translation(Translation.State.TRANSLATING)
        val confirmedGeneration = run.activeRunGenerationOrNull()!!
        val translator = translatorWithQueue(listOf(run))
        run.status = Translation.State.QUEUE
        run.status = Translation.State.TRANSLATING

        translator.reserveRunningChapterForReplace(run, confirmedGeneration) shouldBe false
        (translator.queuedTranslationOrNull(chapterId) === run) shouldBe true
    }

    @Test
    fun `reserved replacement blocks a queued same-instance run from starting`() {
        val run = translation(Translation.State.TRANSLATING)
        val generation = run.activeRunGenerationOrNull()!!
        val translator = translatorWithQueue(listOf(run))
        translator.reserveRunningChapterForReplace(run, generation) shouldBe true
        run.status = Translation.State.QUEUE

        translator.beginTranslationRun(run) shouldBe false
        run.status shouldBe Translation.State.QUEUE
        run.runGeneration shouldBe generation
    }

    @Test
    fun `run start and replacement reservation linearize at the cancellation boundary`() {
        val run = translation(Translation.State.TRANSLATING)
        val generation = run.activeRunGenerationOrNull()!!
        val translator = translatorWithQueue(listOf(run))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reserved = executor.submit<Boolean> {
                start.await()
                translator.reserveRunningChapterForReplace(run, generation)
            }
            val began = executor.submit<Boolean> {
                start.await()
                run.status = Translation.State.QUEUE
                translator.beginTranslationRun(run)
            }
            start.countDown()

            val reservationWon = reserved.get(5, TimeUnit.SECONDS)
            val newRunBegan = began.get(5, TimeUnit.SECONDS)
            (reservationWon && newRunBegan) shouldBe false
            if (reservationWon) {
                run.runGeneration shouldBe generation
                translator.releaseChapterReplacementReservation(run, generation)
            } else {
                newRunBegan shouldBe true
                run.runGeneration shouldBe (generation + 1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement reservation blocks same-chapter admission until the exact run is removed`() {
        every { sourceManager.get(any()) } returns source
        every { preferences.translationRequireCharging() } returns mockk {
            every { get() } returns false
        }
        val confirmedRun = translation(Translation.State.TRANSLATING)
        val translator = translatorWithQueue(listOf(confirmedRun))

        val generation = confirmedRun.activeRunGenerationOrNull()!!
        translator.reserveRunningChapterForReplace(confirmedRun, generation) shouldBe true
        translator.queueChapter(manga, chapter, colorizeOnly = true) shouldBe false
        (translator.queuedTranslationOrNull(chapterId) === confirmedRun) shouldBe true
        confirmedRun.status = Translation.State.PAUSED
        runBlocking { translator.requeueExisting(chapterId, force = true) } shouldBe false
        translator.start() shouldBe false
        confirmedRun.status shouldBe Translation.State.PAUSED
        translator.removeReplacedChapterIfCurrent(confirmedRun, generation) shouldBe true

        translator.queueChapter(manga, chapter, colorizeOnly = true) shouldBe true
        (translator.queuedTranslationOrNull(chapterId) === confirmedRun) shouldBe false
    }

    @Test
    fun `re-arming a stopped entry adopts the caller's latest run mode`() {
        every { sourceManager.get(any()) } returns source
        val stoppedColorize = translation(Translation.State.ERROR).also { it.colorizeOnly = true }
        val translator = translatorWithQueue(listOf(stoppedColorize))
        // A plain translation re-request resolves the language config; valid
        // entries keep queueChapter on its happy path (AI category skips the
        // MLKit language gate).
        every { preferences.translateFromLanguage() } returns preferenceOf(TextRecognizerLanguage.CHINESE.name)
        every { preferences.translateToLanguage() } returns preferenceOf(TextTranslatorLanguage.ENGLISH.name)
        every { preferences.translationEngineCategory() } returns preferenceOf(TranslationEngineCategory.AI_MODEL)

        translator.queueChapter(manga, chapter)

        val entry = translator.queuedTranslationOrNull(chapterId)!!
        entry.status shouldBe Translation.State.QUEUE
        entry.colorizeOnly shouldBe false
    }

    private inline fun <reified T> preferenceOf(value: T): Preference<T> = mockk {
        every { get() } returns value
    }

    @Test
    fun `stop releases batch ownership after the queue reaches terminal states`() {
        val sessions = TranslationSessionCoordinator()
        sessions.requestBatchSession(setOf(chapterId))
        val translator = translatorWithQueue(
            queue = listOf(translation(Translation.State.TRANSLATED)),
            sessionCoordinator = sessions,
        )

        translator.stop()

        sessions.state.value shouldBe TranslationSessionState.IDLE
    }

    @Test
    fun `pause rejects resume while the previous batch claim is unwinding`() {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val resolverEntered = CompletableDeferred<Unit>()
        val resolverRelease = CompletableDeferred<Unit>()
        stubInFlightResolver(concurrent, maxConcurrent, resolverEntered, resolverRelease)
        val translation = translation(Translation.State.QUEUE)
        val translator = translatorWithQueue(listOf(translation))

        translator.start() shouldBe true
        runBlocking {
            withTimeout(5_000) { resolverEntered.await() }
        }
        val firstJob = translationJob(translator)

        translator.pause()
        translator.batchQueuePauseState.value shouldBe BatchQueuePauseState.REQUESTED
        translator.start() shouldBe false
        runBlocking {
            translator.resumeBatchChapter(chapterId) shouldBe BatchQueueResumeResult.PauseUnwinding
        }

        resolverRelease.complete(Unit)
        awaitJobs(firstJob)
        runBlocking {
            withTimeout(5_000) {
                while (translator.batchQueuePauseState.value != BatchQueuePauseState.PAUSED) delay(10)
            }
        }
        translator.isRunning shouldBe false
        concurrent.get() shouldBe 0
        maxConcurrent.get() shouldBe 1
        translation.status shouldBe Translation.State.QUEUE
        translator.batchQueuePauseState.value shouldBe BatchQueuePauseState.PAUSED
    }

    @Test
    fun `chapter resume starts only its target and global resume sees queued siblings`() = runBlocking {
        every { sourceManager.get(any()) } returns source
        every { preferences.translationRequireCharging() } returns preferenceOf(false)
        every { provider.findMangaDir(any(), any()) } returns null
        every { downloadProvider.findChapterDir(any(), any(), any(), any()) } returns null
        val translator = newTranslator()
        val focusChapterId = 31L
        val siblingChapterId = 32L
        val focusChapter = chapterFor(focusChapterId)
        val siblingChapter = chapterFor(siblingChapterId)
        translator.queueChapter(manga, focusChapter, colorizeOnly = true) shouldBe true
        translator.queueChapter(manga, siblingChapter, colorizeOnly = true) shouldBe true
        val focus = translator.queuedTranslationOrNull(focusChapterId)!!
        val sibling = translator.queuedTranslationOrNull(siblingChapterId)!!
        focus.status = Translation.State.PAUSED

        translator.resumeBatchChapter(focusChapterId, expectedRun = focus) shouldBe
            BatchQueueResumeResult.Resumed(setOf(focusChapterId))
        awaitJobs(translationJob(translator))
        focus.status shouldBe Translation.State.ERROR
        sibling.status shouldBe Translation.State.QUEUE
        withTimeout(5_000) {
            while (!translator.hasResumableBatchWork.value) delay(10)
        }

        translator.resumeBatchQueue() shouldBe BatchQueueResumeResult.Resumed(setOf(siblingChapterId))
        awaitJobs(translationJob(translator))
        sibling.status shouldBe Translation.State.ERROR
        withTimeout(5_000) {
            while (translator.hasResumableBatchWork.value) delay(10)
        }
    }

    @Test
    fun `chapter resume reports retry cooldown`() = runBlocking {
        val retryAt = System.currentTimeMillis() + 60_000
        val durableFailure = DurableFailureMetadata(
            pageKey = "page-1",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 1,
            lastFailedAtEpochMs = System.currentTimeMillis(),
            nextEligibleRetryAtEpochMs = retryAt,
        )
        val store = mockk<ChapterTranslationStore>(relaxed = true) {
            every { artifactStatus() } returns Translation.State.PAUSED
            every { durableFailuresSnapshot() } returns mapOf("page-1" to durableFailure)
        }
        storeResolver = { store }
        val run = translation(Translation.State.PAUSED)
        val translator = translatorWithQueue(listOf(run))

        translator.resumeBatchChapter(chapterId) shouldBe
            BatchQueueResumeResult.RetryDeferred(chapterId, retryAt)
        run.status shouldBe Translation.State.PAUSED
    }

    @Test
    fun `chapter resume reports a queue item replaced while reading retry metadata`() = runBlocking {
        every { sourceManager.get(any()) } returns source
        every { preferences.translationRequireCharging() } returns preferenceOf(false)
        val resolverEntered = CompletableDeferred<Unit>()
        val resolverRelease = CompletableDeferred<Unit>()
        storeResolver = {
            resolverEntered.complete(Unit)
            resolverRelease.await()
            null
        }
        val confirmedRun = translation(Translation.State.PAUSED)
        val translator = translatorWithQueue(listOf(confirmedRun))
        val resume = async { translator.resumeBatchChapter(chapterId, expectedRun = confirmedRun) }
        resolverEntered.await()

        translator.removeFromQueue(chapter)
        translator.queueChapter(manga, chapter, colorizeOnly = true) shouldBe true
        resolverRelease.complete(Unit)

        resume.await() shouldBe BatchQueueResumeResult.StaleQueueItem(chapterId)
        (translator.queuedTranslationOrNull(chapterId) === confirmedRun) shouldBe false
    }

    @Test
    fun `chapter resume reports session rejection without rearming the queue item`() = runBlocking {
        val sessions = TranslationSessionCoordinator()
        sessions.requestReaderSession(ReaderSessionIntent(chapterId = chapterId))
        val run = translation(Translation.State.PAUSED)
        val translator = translatorWithQueue(listOf(run), sessions)

        translator.resumeBatchChapter(chapterId) shouldBe
            BatchQueueResumeResult.SessionRejected(SessionRejection.READER_ACTIVE)
        run.status shouldBe Translation.State.PAUSED
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

    private fun readField(target: Any, fieldName: String): Any {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }
}
