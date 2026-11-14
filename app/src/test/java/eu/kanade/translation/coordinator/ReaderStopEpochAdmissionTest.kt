package eu.kanade.translation.coordinator

import com.hippo.unifile.FakeUniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageStageLease
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic regressions for the reader auto-translation stall: a stale
 * background stop destroying a newer resumed session (R1), a bind straddling
 * store eviction (R2), and poisoned-outcome retry dead-ends (R3 and variants).
 */
class ReaderStopEpochAdmissionTest {

    @TempDir
    lateinit var tempDir: File

    private val manga = mockk<Manga>(relaxed = true)
    private val chapter = mockk<Chapter>(relaxed = true) { every { id } returns 42L }
    private val source = mockk<HttpSource>(relaxed = true)

    private fun lazyStore(fileName: String): ChapterTranslationStore =
        ChapterTranslationStore.lazy(
            artifactParent = FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = fileName,
        )

    /** Mirrors production store resolution: registered fast path, else open fresh and register. */
    private fun registryResolver(
        activeStores: ActiveChapterStoreRegistry,
        freshStore: AtomicReference<ChapterTranslationStore>,
    ): suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore? = { _, _, _ ->
        activeStores.get(42L) ?: freshStore.get()
    }

    private fun newCoordinator(
        scope: CoroutineScope,
        activeStores: ActiveChapterStoreRegistry,
        sessions: TranslationSessionCoordinator,
        processor: TranslationProcessor = mockk(relaxed = true),
        isBatchActiveForChapter: (String) -> Boolean = { false },
        batchCoordinator: BatchTranslationCoordinator? = null,
        resolveSessionStore: suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore? = { _, _, _ -> null },
        unregisterActiveStore: suspend (Long) -> Unit = { chapterId -> activeStores.remove(chapterId)?.markDefunct() },
    ): ReaderTranslationCoordinator = ReaderTranslationCoordinator(
        processor = processor,
        isBatchActiveForChapter = isBatchActiveForChapter,
        resolveSessionStore = resolveSessionStore,
        admitReaderSession = { chapterId ->
            sessions.requestReaderSession(ReaderSessionIntent(chapterId)) !is SessionAdmission.Rejected
        },
        manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
        activeStoresProvider = { activeStores },
        batchCoordinator = batchCoordinator,
        sessionCoordinator = sessions,
        unregisterActiveStore = unregisterActiveStore,
        scope = scope,
        autoStart = true,
    )

    private fun blockingProcessor(): Pair<TranslationProcessor, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>> {
        val processor = mockk<TranslationProcessor>(relaxed = true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            started.complete(Unit)
            // The in-flight work ignores cancellation until released, so the stop's
            // enumeration deterministically waits at its join while holding the
            // binding mutex — the interleaving point under test.
            withContext(NonCancellable) { release.await() }
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = firstArg(), translationStatus = StageStatus.READY),
            )
        }
        return processor to (started to release)
    }

    /** Waits until the teardown's enumeration invalidated the chapter binding. */
    private suspend fun awaitBindingInvalidated(coord: ReaderTranslationCoordinator) {
        withTimeout(10_000) {
            while (coord.sessionForChapter("42") != null) delay(10)
        }
    }

    private suspend fun awaitOutcome(
        coord: ReaderTranslationCoordinator,
        pageKey: String,
        predicate: (SinglePageOutcome?) -> Boolean,
    ) {
        withTimeout(10_000) {
            while (!predicate(coord.outcomeFor(42L, pageKey))) delay(10)
        }
    }

    /**
     * R1: a session bound after the background stop was requested must survive
     * the stop's destructive phase — binding kept, store neither evicted nor
     * defuncted, READER_SESSION not finished by the stale stop.
     */
    @Test
    fun `background stop skips a session bound after the stop request`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val (processor, startedRelease) = blockingProcessor()
        val freshStoreRef = AtomicReference(store1)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            resolveSessionStore = registryResolver(activeStores, freshStoreRef),
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val token = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                token shouldNotBe null
                // Production resume keeps the chapter's auto demand armed; the
                // armed chapter also guards the binding against an idle drain.
                coord.setAutoEnabled("42", true)
                coord.submitPage(
                    token,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                withTimeout(10_000) { startedRelease.first.await() }

                val ticket = coord.beginReaderStop("reader backgrounded", terminal = false)
                awaitBindingInvalidated(coord)

                // The resume re-drive binds while the stop still joins the old
                // work: it queues on the binding mutex, records this stop's
                // epoch, and lands after the enumeration.
                val admission = async {
                    // Mirrors translateCurrentPageForAuto: preparation guards the
                    // bind against an idle drain, and the viewport arms auto
                    // demand before preparation ends.
                    coord.beginSessionPreparation()
                    try {
                        val admitted = coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                        if (admitted is ReaderAdmission.Admitted) coord.setAutoEnabled("42", true)
                        admitted
                    } finally {
                        withContext(NonCancellable) { coord.endSessionPreparationAndReleaseIfIdle() }
                    }
                }
                // Run the admission until it suspends on the binding mutex before
                // letting the (joined) enumeration complete.
                yield()
                startedRelease.second.complete(Unit)
                val result = withTimeout(10_000) { admission.await() }
                val admittedToken = (result as ReaderAdmission.Admitted).token
                ticket.await()

                coord.sessionTokenForChapter("42") shouldBe admittedToken
                coord.sessionForChapter("42")!!.store shouldBe store1
                store1.isDefunct shouldBe false
                activeStores.get(42L) shouldBe store1
                sessions.state.value shouldBe TranslationSessionState.READER_SESSION
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /**
     * R2: a bind blocked on a chapter's admission lock behind an in-flight
     * eviction must resolve the store AFTER the removal — a fresh store, never
     * the defuncted one.
     */
    @Test
    fun `bind during store eviction resolves a fresh store instead of a defunct one`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val evictionStarted = CompletableDeferred<Unit>()
        val releaseEviction = CompletableDeferred<Unit>()
        val freshStoreRef = AtomicReference<ChapterTranslationStore>()
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            unregisterActiveStore = { chapterId ->
                evictionStarted.complete(Unit)
                releaseEviction.await()
                activeStores.remove(chapterId)?.markDefunct()
            },
            resolveSessionStore = { _, _, _ ->
                activeStores.get(42L) ?: lazyStore("ch42b.json").also { store ->
                    freshStoreRef.set(store)
                    activeStores.register(42L, store)
                }
            },
        )
        try {
            runBlocking {
                val ticket = coord.beginReaderStop("reader backgrounded", terminal = false)
                withTimeout(10_000) { evictionStarted.await() }

                // The bind waits on the chapter admission lock the gated eviction
                // holds; releasing the gate removes store1 before the bind resolves.
                val admission = async {
                    coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                }
                releaseEviction.complete(Unit)
                val result = withTimeout(10_000) { admission.await() }
                val admittedToken = (result as ReaderAdmission.Admitted).token
                ticket.await()

                store1.isDefunct shouldBe true
                val boundStore = coord.sessionForChapter("42")!!.store
                boundStore shouldNotBe store1
                boundStore shouldBe freshStoreRef.get()
                boundStore.isDefunct shouldBe false
                activeStores.get(42L) shouldBe freshStoreRef.get()
                coord.sessionTokenForChapter("42") shouldBe admittedToken
                sessions.state.value shouldBe TranslationSessionState.READER_SESSION
            }
        } finally {
            releaseEviction.complete(Unit)
            coord.close()
            scope.cancel()
        }
    }

    /**
     * R3: a non-retryable rejection produced against a superseded store
     * generation must be dropped on rebind — by generation identity, not by
     * matching diagnostic text — so the page can retry without force.
     */
    @Test
    fun `rebind clears rejections from a superseded store generation without matching diagnostics`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processor = mockk<TranslationProcessor>(relaxed = true)
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = firstArg(), translationStatus = StageStatus.READY),
            )
        }
        val rejectedPatch = ChapterTranslationStore.PatchResult.Rejected("an unrelated persistence guard tripped")
        val doomedStore = mockk<ChapterTranslationStore>(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
            every { currentIdentity } returns ChapterTranslationStore.Identity(instanceId = -1L, generation = 2L)
            coEvery {
                tryAcquirePageStageLease(any(), any(), any())
            } coAnswers {
                LeaseAcquisition.Granted(PageStageLease(firstArg(), secondArg(), thirdArg(), generation = 2L, pageVersion = 0L, token = 1L))
            }
            coEvery {
                updatePageGuarded(any(), any(), any(), any())
            } coAnswers { rejectedPatch }
        }
        val freshStore = AtomicReference(lazyStore("ch42fresh.json"))
        val nextStore = AtomicReference<ChapterTranslationStore>(doomedStore)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            resolveSessionStore = { _, _, _ -> nextStore.get() },
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val doomedToken = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                coord.submitPage(
                    doomedToken,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Rejected }
                coord.outcomeFor(42L, "13.jpg").let { outcome ->
                    (outcome as SinglePageOutcome.Rejected).kind shouldBe PageRejectionKind.PERSISTENCE_FAILED
                }

                // The stale stop's binding removal left the escaped attempt's
                // rejection behind; the resumed session rebinds onto a fresh store.
                nextStore.set(freshStore.get())
                coord.invalidateSession("42")
                coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)

                // The rejection was recorded against generation 2; the fresh store is
                // generation 0, so the blocking outcome is purged and retry is admitted
                // without force.
                coord.outcomeFor(42L, "13.jpg") shouldBe null
                val submission = coord.submitPage(
                    coord.sessionTokenForChapter("42")!!,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                // Without the purge this submission returns Existing(rejection) and
                // the page stays dead-ended until a forced retry.
                submission shouldBe ReaderSubmission.Queued("13.jpg")
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Completed }
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /**
     * R3 variant: the replacement store sits at the SAME within-instance
     * generation as the superseded one — instance identity, not the generation
     * number, must purge the blocking rejection.
     */
    @Test
    fun `rebind clears rejections from a replaced store at the same generation`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processor = mockk<TranslationProcessor>(relaxed = true)
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = firstArg(), translationStatus = StageStatus.READY),
            )
        }
        val rejectedPatch = ChapterTranslationStore.PatchResult.Rejected("an unrelated persistence guard tripped")
        // Both the superseded and the replacement store report generation 0 —
        // every fresh store instance does. Only the instance id tells them apart.
        val doomedStore = mockk<ChapterTranslationStore>(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
            every { currentIdentity } returns ChapterTranslationStore.Identity(instanceId = -1L, generation = 0L)
            coEvery {
                tryAcquirePageStageLease(any(), any(), any())
            } coAnswers {
                LeaseAcquisition.Granted(PageStageLease(firstArg(), secondArg(), thirdArg(), generation = 0L, pageVersion = 0L, token = 1L))
            }
            coEvery {
                updatePageGuarded(any(), any(), any(), any())
            } coAnswers { rejectedPatch }
        }
        val freshStore = AtomicReference(lazyStore("ch42eq.json"))
        val nextStore = AtomicReference<ChapterTranslationStore>(doomedStore)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            resolveSessionStore = { _, _, _ -> nextStore.get() },
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val doomedToken = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                coord.submitPage(
                    doomedToken,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Rejected }

                nextStore.set(freshStore.get())
                coord.invalidateSession("42")
                coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)

                // Generation 0 == generation 0, so only the instance identity
                // can purge the blocking outcome.
                coord.outcomeFor(42L, "13.jpg") shouldBe null
                val submission = coord.submitPage(
                    coord.sessionTokenForChapter("42")!!,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                submission shouldBe ReaderSubmission.Queued("13.jpg")
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Completed }
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /**
     * A rejection published by an attempt still running on the superseded store
     * AFTER the replacement was admitted must be dropped at the gateway — a
     * late obsolete outcome can never poison the live session's retry gate.
     */
    @Test
    fun `late outcome from a superseded store cannot poison the admitted replacement`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val (processor, startedRelease) = blockingProcessor()
        val freshStore = AtomicReference(lazyStore("ch42late.json"))
        val nextStore = AtomicReference<ChapterTranslationStore>(
            mockk<ChapterTranslationStore>(relaxed = true) {
                every { state } returns MutableStateFlow(emptyMap())
                every { currentIdentity } returns ChapterTranslationStore.Identity(instanceId = -1L, generation = 0L)
                coEvery {
                    tryAcquirePageStageLease(any(), any(), any())
                } coAnswers {
                    LeaseAcquisition.Granted(PageStageLease(firstArg(), secondArg(), thirdArg(), generation = 0L, pageVersion = 0L, token = 1L))
                }
                coEvery {
                    updatePageGuarded(any(), any(), any(), any())
                } coAnswers { ChapterTranslationStore.PatchResult.Rejected("late poison candidate") }
            },
        )
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            resolveSessionStore = { _, _, _ -> nextStore.get() },
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val doomedToken = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                coord.submitPage(
                    doomedToken,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                withTimeout(10_000) { startedRelease.first.await() }

                // The replacement is admitted while the doomed attempt is still
                // in flight; binding-only invalidation leaves that attempt running.
                // Production resume guards the bind with preparation + armed auto
                // demand so an idle drain cannot release the fresh binding.
                nextStore.set(freshStore.get())
                coord.invalidateSession("42")
                coord.beginSessionPreparation()
                try {
                    val admission = coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                    if (admission is ReaderAdmission.Admitted) coord.setAutoEnabled("42", true)
                } finally {
                    withContext(NonCancellable) { coord.endSessionPreparationAndReleaseIfIdle() }
                }

                // The attempt's rejection lands AFTER admission: tagged with the
                // superseded identity, the gateway must drop it. The attempt's
                // outcome is cleared when its job completes (activePageKey -> null).
                startedRelease.second.complete(Unit)
                withTimeout(10_000) {
                    while (coord.activePageKey.value != null) delay(10)
                }
                coord.outcomeFor(42L, "13.jpg") shouldBe null

                val submission = coord.submitPage(
                    coord.sessionTokenForChapter("42")!!,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                submission shouldBe ReaderSubmission.Queued("13.jpg")
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Completed }
            }
        } finally {
            startedRelease.second.complete(Unit)
            coord.close()
            scope.cancel()
        }
    }

    /**
     * A background stop whose chapter was resumed mid-stop (a surviving
     * session) must leave the batch side untouched: batchCoordinator.stop
     * releases the recognition engine's native buffer pools, which in-flight
     * OCR borrows from. Without a survivor it still runs, as before.
     */
    @Test
    fun `superseded background stop skips the batch side while a resumed session survives`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val (processor, startedRelease) = blockingProcessor()
        val batchCoordinator = mockk<BatchTranslationCoordinator>(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList())
        }
        val freshStoreRef = AtomicReference(store1)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            batchCoordinator = batchCoordinator,
            resolveSessionStore = registryResolver(activeStores, freshStoreRef),
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val token = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                coord.setAutoEnabled("42", true)
                coord.submitPage(
                    token,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                withTimeout(10_000) { startedRelease.first.await() }

                val ticket = coord.beginReaderStop("reader backgrounded", terminal = false)
                awaitBindingInvalidated(coord)
                // The resume binds mid-stop, so the stop ends with a survivor.
                val admission = async {
                    coord.beginSessionPreparation()
                    try {
                        val admitted = coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                        if (admitted is ReaderAdmission.Admitted) coord.setAutoEnabled("42", true)
                        admitted
                    } finally {
                        withContext(NonCancellable) { coord.endSessionPreparationAndReleaseIfIdle() }
                    }
                }
                yield()
                startedRelease.second.complete(Unit)
                withTimeout(10_000) { admission.await() }
                ticket.await()

                verify(exactly = 0) { batchCoordinator.stop(any(), any()) }
                coord.sessionForChapter("42")!!.store shouldBe store1
                store1.isDefunct shouldBe false

                // Without a survivor the batch side still runs, as before.
                coord.beginReaderStop("reader backgrounded", terminal = false).await()
                verify(exactly = 1) { batchCoordinator.stop(any(), any()) }
                store1.isDefunct shouldBe true
                coord.sessionForChapter("42") shouldBe null
                sessions.state.value shouldBe TranslationSessionState.IDLE
            }
        } finally {
            startedRelease.second.complete(Unit)
            coord.close()
            scope.cancel()
        }
    }

    /**
     * The reopen path's bounded join must cover terminal stops too: a
     * fire-and-forget "reader closed" stop keeps tearing stores down while a
     * quick reopen re-binds, and an un-awaited terminal stop would defunct
     * the fresh store.
     */
    @Test
    fun `awaitPendingReaderStop joins an in-flight terminal stop`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val evictionStarted = CompletableDeferred<Unit>()
        val releaseEviction = CompletableDeferred<Unit>()
        val freshStoreRef = AtomicReference<ChapterTranslationStore>()
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            unregisterActiveStore = { chapterId ->
                evictionStarted.complete(Unit)
                releaseEviction.await()
                activeStores.remove(chapterId)?.markDefunct()
            },
            resolveSessionStore = { _, _, _ ->
                activeStores.get(42L) ?: lazyStore("ch42b.json").also { store ->
                    freshStoreRef.set(store)
                    activeStores.register(42L, store)
                }
            },
        )
        try {
            runBlocking {
                val ticket = coord.beginReaderStop("reader closed", terminal = true)
                withTimeout(10_000) { evictionStarted.await() }

                // The join may not resolve while the terminal destructive phase
                // is gated; a fixed wait distinguishes suspension from the
                // instant true an untracked stop would return.
                val joined = async { coord.awaitPendingReaderStop() }
                delay(200)
                joined.isActive shouldBe true
                releaseEviction.complete(Unit)
                withTimeout(10_000) { joined.await() } shouldBe true
                ticket.await()
                store1.isDefunct shouldBe true

                // The reopen binds only after the stop finished and survives.
                val admission = coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                (admission as ReaderAdmission.Admitted).token shouldNotBe null
                freshStoreRef.get()!!.isDefunct shouldBe false
                activeStores.get(42L) shouldBe freshStoreRef.get()
            }
        } finally {
            releaseEviction.complete(Unit)
            coord.close()
            scope.cancel()
        }
    }

    /**
     * A transient SESSION_DENIED rejection recorded while admission was
     * refused must not dead-end the page: the next successful admission
     * purges it, by admission identity rather than diagnostic matching.
     */
    @Test
    fun `rebind clears a session-denied rejection from the admission-denied window`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processor = mockk<TranslationProcessor>(relaxed = true)
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = firstArg(), translationStatus = StageStatus.READY),
            )
        }
        val store = lazyStore("ch42denied.json")
        val batchGate = AtomicReference(true)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            isBatchActiveForChapter = { batchGate.get() },
            resolveSessionStore = { _, _, _ -> store },
        )
        try {
            runBlocking {
                coord.translatePage(manga, chapter, source, "13.jpg")
                awaitOutcome(coord, "13.jpg") {
                    (it as? SinglePageOutcome.Rejected)?.kind == PageRejectionKind.SESSION_DENIED
                }

                // The window clears and the reader re-admits: the stale denial
                // must be purged so the page can retry without force.
                batchGate.set(false)
                coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                coord.outcomeFor(42L, "13.jpg") shouldBe null
                val submission = coord.submitPage(
                    coord.sessionTokenForChapter("42")!!,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                submission shouldBe ReaderSubmission.Queued("13.jpg")
                awaitOutcome(coord, "13.jpg") { it is SinglePageOutcome.Completed }
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /** Correction 3: terminal stops tear down even a session bound after the stop request. */
    @Test
    fun `terminal stop tears down even a session bound after the stop request`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val (processor, startedRelease) = blockingProcessor()
        val freshStoreRef = AtomicReference(store1)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            processor = processor,
            resolveSessionStore = registryResolver(activeStores, freshStoreRef),
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                val token = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
                coord.submitPage(
                    token,
                    ReaderPageWork("13.jpg", 12, { ByteArrayInputStream(byteArrayOf(1)) }),
                    ReaderTrigger.MANUAL_TAP,
                )
                withTimeout(10_000) { startedRelease.first.await() }

                val ticket = coord.beginReaderStop("reader closed", terminal = true)
                awaitBindingInvalidated(coord)
                val admission = async {
                    coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                }
                yield()
                startedRelease.second.complete(Unit)
                withTimeout(10_000) { admission.await() }
                ticket.await()

                // No epoch skip for terminal stops: the store is evicted and
                // defuncted despite the fresh binding, and the session is finished.
                store1.isDefunct shouldBe true
                activeStores.get(42L) shouldBe null
                sessions.state.value shouldBe TranslationSessionState.IDLE
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /** Rapid background -> resume -> background: the resumed session is a pre-epoch binding for the second stop. */
    @Test
    fun `rapid re-background after resume tears the resumed session down`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        val store2 = lazyStore("ch42b.json")
        activeStores.register(42L, store1)
        val firstStopStore = AtomicReference(store1)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            resolveSessionStore = { _, _, _ -> firstStopStore.get() },
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)
                coord.beginReaderStop("reader backgrounded", terminal = false).await()
                coord.sessionForChapter("42") shouldBe null
                store1.isDefunct shouldBe true
                sessions.state.value shouldBe TranslationSessionState.IDLE

                // Resume re-binds onto a fresh store.
                firstStopStore.set(store2)
                activeStores.register(42L, store2)
                val admission = coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                (admission as ReaderAdmission.Admitted).token shouldNotBe null

                // A second background trip is newer than the resumed binding and tears it down.
                coord.beginReaderStop("reader backgrounded", terminal = false).await()
                coord.sessionForChapter("42") shouldBe null
                store2.isDefunct shouldBe true
                sessions.state.value shouldBe TranslationSessionState.IDLE
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /** Paused/queued batch-owned stores are never evicted by reader stops, at any epoch. */
    @Test
    fun `background stop never evicts batch-owned stores`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val batchChapter = mockk<Chapter>(relaxed = true) { every { id } returns 7L }
        val pausedBatch = Translation(source, manga, batchChapter).also { it.status = Translation.State.PAUSED }
        val batchCoordinator = mockk<BatchTranslationCoordinator>(relaxed = true) {
            every { queueState } returns MutableStateFlow(listOf(pausedBatch))
        }
        val store7 = lazyStore("ch7.json")
        val store1 = lazyStore("ch42a.json")
        activeStores.register(7L, store7)
        activeStores.register(42L, store1)
        val currentStore = AtomicReference(store1)
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            batchCoordinator = batchCoordinator,
            isBatchActiveForChapter = { it == "7" },
            resolveSessionStore = { _, _, _ -> currentStore.get() },
        )
        try {
            runBlocking {
                val session = coord.openSession(manga, chapter, source)!!
                coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)
                coord.beginReaderStop("reader backgrounded", terminal = false).await()
                store1.isDefunct shouldBe true

                // Resume re-binds, then background again: the paused batch store survives both.
                currentStore.set(lazyStore("ch42b.json"))
                activeStores.register(42L, currentStore.get())
                coord.admitReaderSessionUse(manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
                coord.beginReaderStop("reader backgrounded", terminal = false).await()

                store7.isDefunct shouldBe false
                activeStores.get(7L) shouldBe store7
            }
        } finally {
            coord.close()
            scope.cancel()
        }
    }

    /** The resume path's bounded join: awaitPendingReaderStop resolves with and without a pending stop. */
    @Test
    fun `awaitPendingReaderStop joins the latest background stop`() {
        val activeStores = ActiveChapterStoreRegistry()
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store1 = lazyStore("ch42a.json")
        activeStores.register(42L, store1)
        val evictionStarted = CompletableDeferred<Unit>()
        val releaseEviction = CompletableDeferred<Unit>()
        val coord = newCoordinator(
            scope = scope,
            activeStores = activeStores,
            sessions = sessions,
            unregisterActiveStore = { chapterId ->
                evictionStarted.complete(Unit)
                releaseEviction.await()
                activeStores.remove(chapterId)?.markDefunct()
            },
        )
        try {
            runBlocking {
                coord.awaitPendingReaderStop() shouldBe true

                // Production entry point: the fire-and-forget stop must be joinable.
                coord.stopReaderTranslations("reader backgrounded")
                withTimeout(10_000) { evictionStarted.await() }
                coord.awaitPendingReaderStop(timeoutMs = 100) shouldBe false
                releaseEviction.complete(Unit)
                withTimeout(10_000) { coord.awaitPendingReaderStop() shouldBe true }
                store1.isDefunct shouldBe true
            }
        } finally {
            releaseEviction.complete(Unit)
            coord.close()
            scope.cancel()
        }
    }
}
