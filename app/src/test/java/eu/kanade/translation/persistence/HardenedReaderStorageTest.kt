package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
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
}
