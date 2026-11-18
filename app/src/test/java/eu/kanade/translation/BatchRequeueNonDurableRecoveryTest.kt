package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.InMemorySharedPreferences
import eu.kanade.translation.coordinator.TranslationQueueStore
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.ChapterBatchExecutor
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * A guarded-publication rejection leaves the chapter queued at
 * READY_WITH_WARNINGS with NO durable result. The not-saved Retry must
 * recover it: requeueExisting rearms exactly that chapter — the durable
 * terminal snapshot's nonDurableFailure flag is the marker — while ordinary
 * warnings stay untouched and session admission still gates the requeue.
 */
class BatchRequeueNonDurableRecoveryTest {

    private val chapterId = 7L
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter 1"
        every { scanlator } returns null
    }
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
        every { this@mockk.source } returns 1L
    }
    private val source = mockk<HttpSource>(relaxed = true)

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns InMemorySharedPreferences()
    }
    private val provider = mockk<TranslationFileProvider>(relaxed = true)
    private val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val preferences = mockk<TranslationPreferences>(relaxed = true).also {
        every { it.translationRequireCharging() } returns mockk {
            every { get() } returns false
        }
        // queueChapter resolves the language pair; relaxed Preference mocks
        // would return Object and break the typed fromPref read. Valid enum
        // names keep queueChapter off the invalid-config toast path, whose
        // launchUI hop is unavailable on the JVM.
        every { it.translateFromLanguage() } returns mockk {
            every { get() } returns TextRecognizerLanguage.CHINESE.name
        }
        every { it.translateToLanguage() } returns mockk {
            every { get() } returns TextTranslatorLanguage.ENGLISH.name
        }
    }
    private val processor = mockk<TranslationProcessor>(relaxed = true)
    private val pageOperation = mockk<ChapterBatchExecutor>(relaxed = true)

    init {
        every { sourceManager.get(any()) } returns source
    }

    private val terminalSnapshots = ConcurrentHashMap<Long, TranslationProgressSnapshot>()
    private val sessionCoordinator = TranslationSessionCoordinator()

    private fun notSavedSnapshot() = TranslationProgressSnapshot.empty(chapterId)
        .copy(
            state = Translation.State.READY_WITH_WARNINGS,
            nonDurableFailure = true,
            nonDurableFailureReason = "guarded publication rejected",
        )

    private fun coordinator(): BatchTranslationCoordinator = BatchTranslationCoordinator(
        context = context,
        provider = provider,
        downloadProvider = downloadProvider,
        sourceManager = sourceManager,
        translationPreferences = preferences,
        queueStore = TranslationQueueStore(context),
        processor = processor,
        pageOperation = pageOperation,
        resolveStore = { null },
        sessionCoordinator = sessionCoordinator,
        terminalSnapshotProvider = { chapterId -> terminalSnapshots[chapterId] },
    )

    /** A parked job keeps the worker "running" so the rearm is deterministic. */
    private fun parkTranslatorJob(coordinator: BatchTranslationCoordinator): Job {
        val parked = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { awaitCancellation() }
        setField(coordinator, "translationJob", parked)
        return parked
    }

    private fun queueState(coordinator: BatchTranslationCoordinator): Translation =
        (readField(coordinator, "_queueState") as MutableStateFlow<List<Translation>>).value.single()

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

    private fun readField(target: Any, fieldName: String): Any? {
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

    @Test
    fun `not-saved warning chapter is rearmed for retry recovery`() = runBlocking<Unit> {
        terminalSnapshots[chapterId] = notSavedSnapshot()
        val coordinator = coordinator()
        coordinator.queueChapter(manga, chapter)
        queueState(coordinator).status = Translation.State.READY_WITH_WARNINGS
        val parked = parkTranslatorJob(coordinator)

        try {
            coordinator.requeueExisting(chapterId) shouldBe true
            queueState(coordinator).status shouldBe Translation.State.QUEUE
        } finally {
            parked.cancel()
        }
    }

    @Test
    fun `ordinary warning chapter is not rearmed by the not-saved retry`() = runBlocking<Unit> {
        terminalSnapshots[chapterId] = TranslationProgressSnapshot.empty(chapterId)
            .copy(state = Translation.State.READY_WITH_WARNINGS, nonDurableFailure = false)
        val coordinator = coordinator()
        coordinator.queueChapter(manga, chapter)
        queueState(coordinator).status = Translation.State.READY_WITH_WARNINGS
        val parked = parkTranslatorJob(coordinator)

        try {
            coordinator.requeueExisting(chapterId) shouldBe false
            queueState(coordinator).status shouldBe Translation.State.READY_WITH_WARNINGS
        } finally {
            parked.cancel()
        }
    }

    @Test
    fun `recovery rearm stays gated behind batch session admission`() = runBlocking<Unit> {
        terminalSnapshots[chapterId] = notSavedSnapshot()
        val coordinator = coordinator()
        coordinator.queueChapter(manga, chapter)
        queueState(coordinator).status = Translation.State.READY_WITH_WARNINGS
        val parked = parkTranslatorJob(coordinator)
        sessionCoordinator.requestReaderSession(eu.kanade.translation.coordinator.ReaderSessionIntent())

        try {
            coordinator.requeueWithSessionAdmission(chapterId) shouldBe false
            queueState(coordinator).status shouldBe Translation.State.READY_WITH_WARNINGS

            sessionCoordinator.finishSession()
            coordinator.requeueWithSessionAdmission(chapterId) shouldBe true
            queueState(coordinator).status shouldBe Translation.State.QUEUE
        } finally {
            parked.cancel()
        }
    }

    @Test
    fun `a chapter with no not-saved snapshot still refuses the warning rearm`() = runBlocking<Unit> {
        val coordinator = coordinator()
        coordinator.queueChapter(manga, chapter)
        queueState(coordinator).status = Translation.State.READY_WITH_WARNINGS
        val parked = parkTranslatorJob(coordinator)

        try {
            coordinator.requeueExisting(chapterId) shouldBe false
            queueState(coordinator).status shouldBe Translation.State.READY_WITH_WARNINGS
        } finally {
            parked.cancel()
        }
    }

    @Test
    fun `colorize-only chapter translation state uses the same recovery`() = runBlocking<Unit> {
        terminalSnapshots[chapterId] = notSavedSnapshot()
        val coordinator = coordinator()
        coordinator.queueChapter(manga, chapter, colorizeOnly = true)
        queueState(coordinator).status = Translation.State.READY_WITH_WARNINGS
        val parked = parkTranslatorJob(coordinator)

        try {
            coordinator.requeueExisting(chapterId) shouldBe true
            queueState(coordinator).status shouldBe Translation.State.QUEUE
        } finally {
            parked.cancel()
        }
    }
}
