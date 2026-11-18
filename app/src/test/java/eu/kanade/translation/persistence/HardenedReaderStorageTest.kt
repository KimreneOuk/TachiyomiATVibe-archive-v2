package eu.kanade.translation.persistence

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HardenedReaderStorageTest {

    private fun block(text: String = "text") = TranslationBlock(
        text = text,
        translation = "trans",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    @Test
    fun `getOrLoadPageSnapshot prefers committed snapshot over candidate`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)

        val committedSnapshot = PageTranslation(
            sourceFileName = "p1",
            pageVersion = 10L,
            blocks = mutableListOf(block("committed")),
        )
        val candidateSnapshot = PageTranslation(
            sourceFileName = "p1",
            pageVersion = 11L,
            blocks = mutableListOf(block("candidate")),
        )

        val committedFp = StageFingerprints.pageSnapshot(committedSnapshot)
        val candidateFp = StageFingerprints.pageSnapshot(candidateSnapshot)

        val manifest = ChapterArtifactManifest(
            chapterKey = "c1",
            pages = mapOf(
                "p1" to PageArtifactRecord(
                    pageKey = "p1",
                    committed = CommittedBundleMetadata(
                        generationId = "gen0",
                        displayBase = DisplayBaseReference(kind = DisplayBaseKind.ORIGINAL_SOURCE),
                        pageSnapshotFileName = "committed_p1.json",
                        translationFingerprint = committedFp,
                    ),
                    candidate = CandidateGenerationMetadata(
                        generationId = "gen1",
                        pageSnapshotFileName = "candidate_p1.json",
                        pageSnapshotFingerprint = candidateFp,
                    ),
                ),
            ),
        )

        coEvery { engine.readPageSnapshot("committed_p1.json") } returns committedSnapshot
        coEvery { engine.readPageSnapshot("candidate_p1.json") } returns candidateSnapshot

        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = manifest,
            initialPages = mapOf("p1" to PageTranslation(sourceFileName = "p1")),
            persistenceDispatcher = testDispatcher,
            unhydratedPageKeys = setOf("p1"),
        )

        store.hasUnhydratedPages() shouldBe true
        store.isColoredReachabilityComplete() shouldBe false

        val loaded = store.getOrLoadPageSnapshot("p1")
        loaded shouldNotBe null
        loaded?.blocks?.firstOrNull()?.text shouldBe "committed"
        store.hasUnhydratedPages() shouldBe false
        store.isColoredReachabilityComplete() shouldBe true
        store.committedDisplayPage("p1")?.blocks?.firstOrNull()?.text shouldBe "committed"
    }

    @Test
    fun `getOrLoadPageSnapshot rejects snapshot with mismatched fingerprint`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)

        val corruptedSnapshot = PageTranslation(
            sourceFileName = "p1",
            pageVersion = 10L,
            blocks = mutableListOf(block("tampered")),
        )

        val manifest = ChapterArtifactManifest(
            chapterKey = "c1",
            pages = mapOf(
                "p1" to PageArtifactRecord(
                    pageKey = "p1",
                    committed = CommittedBundleMetadata(
                        generationId = "gen0",
                        displayBase = DisplayBaseReference(kind = DisplayBaseKind.ORIGINAL_SOURCE),
                        pageSnapshotFileName = "committed_p1.json",
                        translationFingerprint = "expected_fingerprint_hash",
                    ),
                ),
            ),
        )

        coEvery { engine.readPageSnapshot("committed_p1.json") } returns corruptedSnapshot

        val initialPage = PageTranslation(sourceFileName = "p1", pageVersion = 5L)
        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = manifest,
            initialPages = mapOf("p1" to initialPage),
            persistenceDispatcher = testDispatcher,
            unhydratedPageKeys = setOf("p1"),
        )

        val loaded = store.getOrLoadPageSnapshot("p1")
        // Tampered snapshot is rejected due to fingerprint mismatch; falls back to current placeholder
        loaded?.blocks?.isEmpty() shouldBe true
        // Failed snapshot read retains unhydrated protection to protect colored images and allow retry
        store.hasUnhydratedPages() shouldBe true
        store.isColoredReachabilityComplete() shouldBe false
        store.needsSnapshotHydration("p1") shouldBe true
    }

    @Test
    fun `getOrLoadPageSnapshot installs committed snapshot even when manifest record pageVersion was bumped by candidate attempt`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)

        val committedSnapshot = PageTranslation(
            sourceFileName = "p1",
            pageVersion = 1L,
            blocks = mutableListOf(block("committed_text")),
        )
        val committedFp = StageFingerprints.pageSnapshot(committedSnapshot)

        // Manifest record has pageVersion = 5 due to later candidate attempts/retries
        val manifest = ChapterArtifactManifest(
            chapterKey = "c1",
            pages = mapOf(
                "p1" to PageArtifactRecord(
                    pageKey = "p1",
                    pageVersion = 5L,
                    committed = CommittedBundleMetadata(
                        generationId = "gen0",
                        displayBase = DisplayBaseReference(kind = DisplayBaseKind.ORIGINAL_SOURCE),
                        pageSnapshotFileName = "committed_p1.json",
                        translationFingerprint = committedFp,
                    ),
                    candidate = CandidateGenerationMetadata(
                        generationId = "gen1",
                        pageSnapshotFileName = "candidate_p1.json",
                        pageSnapshotFingerprint = "cand_fp",
                    ),
                ),
            ),
        )

        coEvery { engine.readPageSnapshot("committed_p1.json") } returns committedSnapshot

        // Placeholder created with record.pageVersion (5L)
        val placeholder = PageTranslation(sourceFileName = "p1", pageVersion = 5L)
        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = manifest,
            initialPages = mapOf("p1" to placeholder),
            persistenceDispatcher = testDispatcher,
            unhydratedPageKeys = setOf("p1"),
        )

        val loaded = store.getOrLoadPageSnapshot("p1")
        loaded shouldNotBe null
        loaded?.blocks?.firstOrNull()?.text shouldBe "committed_text"
        store.committedDisplayPage("p1")?.blocks?.firstOrNull()?.text shouldBe "committed_text"
        store.hasUnhydratedPages() shouldBe false
        store.isColoredReachabilityComplete() shouldBe true
    }

    @Test
    fun `getOrLoadPageSnapshot candidate never installs into committedDisplay`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)

        val candidateSnapshot = PageTranslation(
            sourceFileName = "p1",
            pageVersion = 10L,
            blocks = mutableListOf(block("candidate_only")),
            translationStatus = StageStatus.READY,
        )
        val candidateFp = StageFingerprints.pageSnapshot(candidateSnapshot)

        val manifest = ChapterArtifactManifest(
            chapterKey = "c1",
            pages = mapOf(
                "p1" to PageArtifactRecord(
                    pageKey = "p1",
                    committed = null,
                    candidate = CandidateGenerationMetadata(
                        generationId = "gen1",
                        pageSnapshotFileName = "candidate_p1.json",
                        pageSnapshotFingerprint = candidateFp,
                    ),
                ),
            ),
        )

        coEvery { engine.readPageSnapshot("candidate_p1.json") } returns candidateSnapshot

        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = manifest,
            initialPages = mapOf("p1" to PageTranslation(sourceFileName = "p1")),
            persistenceDispatcher = testDispatcher,
            unhydratedPageKeys = setOf("p1"),
        )

        val loaded = store.getOrLoadPageSnapshot("p1")
        loaded shouldNotBe null
        loaded?.blocks?.firstOrNull()?.text shouldBe "candidate_only"
        // Must NOT install into committedDisplay!
        store.committedDisplayPage("p1") shouldBe null
    }

    @Test
    fun `markDefunct on clean store returns Flushed`() = runTest {
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        val outcome = store.markDefunct(force = false)
        outcome shouldBe StoreDefunctOutcome.Flushed
        store.isDefunct shouldBe true
    }

    @Test
    fun `markDefunct with incomplete drain returns IncompleteRetained and keeps store non-defunct`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)
        coEvery { engine.publishSidecarPointers(any(), any(), any(), any()) } returns
            ChapterArtifactEngine.TransactionOutcome.Rejected("failed")
        coEvery { engine.publishManifest(any()) } returns false

        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = ChapterArtifactManifest(chapterKey = "c1"),
            initialPages = mapOf("p1" to PageTranslation(sourceFileName = "p1")),
            persistenceDispatcher = testDispatcher,
        )
        store.writeDrain.pendingLazyMutations["p1"] = PendingLazyMutation(
            pageKey = "p1",
            previous = null,
            updated = PageTranslation(sourceFileName = "p1", pageVersion = 2L),
            expected = null,
            generation = store.generation,
        )
        var eventualUnregisterCalled = false
        store.writeDrain.onEventualUnregister = { eventualUnregisterCalled = true }

        val outcome = store.markDefunct(force = false)
        outcome shouldBe StoreDefunctOutcome.IncompleteRetained(1)
        store.isDefunct shouldBe false
        eventualUnregisterCalled shouldBe false
    }

    @Test
    fun `tryAdopt cancels pending unregister and protects store from concurrent worker defunct`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = mockk<ChapterArtifactEngine>(relaxed = true)
        coEvery { engine.publishSidecarPointers(any(), any(), any(), any()) } returns
            ChapterArtifactEngine.TransactionOutcome.Committed(ChapterArtifactManifest(chapterKey = "c1"))
        coEvery { engine.publishManifest(any()) } returns true

        val store = ChapterTranslationStore(
            artifactStore = engine,
            initialArtifactManifest = ChapterArtifactManifest(chapterKey = "c1"),
            initialPages = mapOf("p1" to PageTranslation(sourceFileName = "p1")),
            persistenceDispatcher = testDispatcher,
        )
        store.writeDrain.pendingLazyMutations["p1"] = PendingLazyMutation(
            pageKey = "p1",
            previous = null,
            updated = PageTranslation(sourceFileName = "p1", pageVersion = 2L),
            expected = null,
            generation = store.generation,
        )
        var eventualUnregisterCalled = false
        store.writeDrain.onEventualUnregister = { eventualUnregisterCalled = true }

        val outcome = store.markDefunct(force = false)
        outcome shouldBe StoreDefunctOutcome.IncompleteRetained(1)
        store.isDefunct shouldBe false

        // Re-adoption cancels pending unregister under store mutex
        val adopted = store.tryAdopt()
        adopted shouldBe true

        // Advance debounce timer and let drain worker finish
        testScheduler.advanceTimeBy(StoreWriteDrainCoordinator.PERSIST_DEBOUNCE_MS + 50L)
        testScheduler.runCurrent()

        // Worker finished flush, but saw pendingUnregisterRequested == false, so store is NOT defuncted
        store.isDefunct shouldBe false
        eventualUnregisterCalled shouldBe false
    }

    @Test
    fun `tryAdopt returns false when store is already defunct`() = runTest {
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        store.markDefunct(force = true)
        store.isDefunct shouldBe true

        store.tryAdopt() shouldBe false
    }

    @Test
    fun `ActiveChapterStoreRegistry evicts defunct stores on access and mutation`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )

        registry.register(10L, store) shouldBe true
        registry.registerFile("file_10", store) shouldBe true
        registry.get(10L) shouldBe store
        registry.getByFile("file_10") shouldBe store
        registry.hasStoreForFile("file_10") shouldBe true
        registry.chapterIds() shouldBe listOf(10L)

        // Mark store defunct
        store.markDefunct(force = true)
        store.isDefunct shouldBe true

        // Registry lookups must evict defunct store and return null
        registry.get(10L) shouldBe null
        registry.getByFile("file_10") shouldBe null
        registry.hasStoreForFile("file_10") shouldBe false
        registry.chapterIds() shouldBe emptyList()

        // Fresh store registration succeeds after eviction
        val freshStore = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        registry.register(10L, freshStore) shouldBe true
        registry.get(10L) shouldBe freshStore
    }

    @Test
    fun `ActiveChapterStoreRegistry removeStore removes from chapter and file mappings`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )

        registry.register(20L, store) shouldBe true
        registry.registerFile("file_20", store) shouldBe true

        registry.removeStore(store) shouldBe true
        registry.get(20L) shouldBe null
        registry.getByFile("file_20") shouldBe null
        registry.hasStoreForFile("file_20") shouldBe false
    }

    @Test
    fun `openExistingChapterTranslationStore wires eventual unregister that evicts and clears cache`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        val durableStatusResolver = mockk<DurableChapterStatusResolver>(relaxed = true)
        val cleanedImageLifecycle = mockk<CleanedImageLifecycleController>(relaxed = true)
        val controller = ChapterTranslationAccessController(
            provider = provider,
            activeStores = registry,
            durableStatusResolver = durableStatusResolver,
            cleanedImageLifecycle = cleanedImageLifecycle,
        )

        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        val parentUniFile = mockk<UniFile> {
            every { filePath } returns "/storage/manga"
            every { uri } returns mockk(relaxed = true)
        }
        val doc = TranslationDocument(parent = parentUniFile, fileName = "c30.json")
        val manifestProbe = ArtifactManifestProbe(exists = true, manifest = null)

        // Pre-register store into registry (simulating existing active store)
        registry.register(30L, store)
        registry.registerFile(doc.registryKey, store)

        val opened = controller.openExistingChapterTranslationStore(
            chapterId = 30L,
            chapterName = "Ch. 30",
            scanlator = null,
            mangaTitle = "Manga",
            source = mockk<Source>(relaxed = true),
            prefoundDocument = doc,
            preflightManifestProbe = manifestProbe,
        )
        opened shouldBe store
        store.writeDrain.onEventualUnregister shouldNotBe null

        // Trigger eventual unregister
        store.writeDrain.onEventualUnregister?.invoke()

        // Must be evicted from both mappings in registry, and durable cache cleared
        registry.get(30L) shouldBe null
        registry.getByFile(doc.registryKey) shouldBe null
        verify(atLeast = 1) { durableStatusResolver.clearDurableStatusCache() }
    }

    @Test
    fun `openExistingChapterTranslationStore with file-only store wires eventual unregister`() = runTest {
        val registry = ActiveChapterStoreRegistry()
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        val durableStatusResolver = mockk<DurableChapterStatusResolver>(relaxed = true)
        val cleanedImageLifecycle = mockk<CleanedImageLifecycleController>(relaxed = true)
        val controller = ChapterTranslationAccessController(
            provider = provider,
            activeStores = registry,
            durableStatusResolver = durableStatusResolver,
            cleanedImageLifecycle = cleanedImageLifecycle,
        )

        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = emptyMap(),
        )
        val parentUniFile = mockk<UniFile> {
            every { filePath } returns "/storage/manga"
            every { uri } returns mockk(relaxed = true)
        }
        val doc = TranslationDocument(parent = parentUniFile, fileName = "c40.json")
        val manifestProbe = ArtifactManifestProbe(exists = true, manifest = null)

        // Pre-register store by file key only (no chapter id)
        registry.registerFile(doc.registryKey, store)

        val opened = controller.openExistingChapterTranslationStore(
            chapterId = null,
            chapterName = "Ch. 40",
            scanlator = null,
            mangaTitle = "Manga",
            source = mockk<Source>(relaxed = true),
            prefoundDocument = doc,
            preflightManifestProbe = manifestProbe,
        )
        opened shouldBe store
        store.writeDrain.onEventualUnregister shouldNotBe null

        // Trigger eventual unregister
        store.writeDrain.onEventualUnregister?.invoke()

        // File-only entry must be cleanly evicted
        registry.getByFile(doc.registryKey) shouldBe null
        verify(atLeast = 1) { durableStatusResolver.clearDurableStatusCache() }
    }
}
