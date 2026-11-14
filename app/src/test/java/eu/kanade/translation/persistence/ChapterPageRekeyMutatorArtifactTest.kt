package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChapterPageRekeyMutatorArtifactTest {

    private val layout = ChapterArtifactLayout("Rekey Chapter")
    private val onlineKey = "page-from-url.jpg"
    private val downloadedKey = "0001.jpg"
    private val dependencyFingerprint = "rekey-fixture-dependencies"

    private fun retryableFailure() = DurableFailureMetadata(
        pageKey = onlineKey,
        stage = ArtifactStage.TRANSLATION,
        status = ArtifactStageStatus.FAILED_RETRYABLE,
        category = FailureCategory.TRANSIENT,
        retryCount = 1,
        lastFailureMessage = "temporary translation failure",
        lastFailedAtEpochMs = 1_003L,
        nextEligibleRetryAtEpochMs = 1_500L,
        failureFingerprint = dependencyFingerprint,
    )

    private data class SeededArtifact(
        val artifact: ChapterArtifactEngine,
        val manifest: ChapterArtifactManifest,
        val page: PageTranslation,
    )

    private class SimulatedProcessCrash : RuntimeException()

    private fun page(): PageTranslation = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source text",
                translation = "translated text",
                width = 12f,
                height = 12f,
                x = 1f,
                y = 2f,
                symHeight = 12f,
                symWidth = 12f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 200f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        sourceFileName = onlineKey,
    )

    private fun seedCommittedArtifact(io: FakeChapterDocumentIo): SeededArtifact {
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val page = page()
        val seededManifest = artifact.loadArtifact(
            ArtifactSeed(
                pages = mapOf(onlineKey to ArtifactPageFacts(page)),
                createdAtEpochMs = 1_000L,
            ),
        ).manifest
        val opened = artifact.candidates.openCandidate(
            manifest = seededManifest,
            pageKey = onlineKey,
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = seededManifest.pages.getValue(onlineKey).pageVersion,
            dependencyFingerprint = dependencyFingerprint,
            nowEpochMs = 1_001L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val promoted = artifact.candidates.promoteLiveCandidate(
            manifest = opened.manifest,
            pageKey = onlineKey,
            generationId = generationId,
            expectedPageVersion = opened.manifest.pages.getValue(onlineKey).pageVersion,
            expectedDependencyFingerprint = dependencyFingerprint,
            pageSnapshot = page,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 1_002L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        return SeededArtifact(artifact, promoted.manifest, page)
    }

    private fun seedCommittedArtifactWithFailure(
        io: FakeChapterDocumentIo,
    ): Pair<SeededArtifact, DurableFailureMetadata> {
        val seed = seedCommittedArtifact(io)
        val failure = retryableFailure()
        val opened = seed.artifact.candidates.openCandidate(
            manifest = seed.manifest,
            pageKey = onlineKey,
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = seed.manifest.pages.getValue(onlineKey).pageVersion,
            dependencyFingerprint = dependencyFingerprint,
            nowEpochMs = 1_003L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val failedPage = seed.page.copy(translationStatus = StageStatus.PARTIAL)
        val persisted = seed.artifact.candidates.persistLiveCandidateAndFailure(
            manifest = opened.manifest,
            pageKey = onlineKey,
            generationId = generationId,
            expectedPageVersion = opened.manifest.pages.getValue(onlineKey).pageVersion,
            expectedDependencyFingerprint = dependencyFingerprint,
            pageSnapshot = failedPage,
            origin = ArtifactOrigin.BATCH,
            failure = failure,
            nowEpochMs = 1_004L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val canceled = seed.artifact.candidates.cancelCandidate(
            manifest = persisted.manifest,
            pageKey = onlineKey,
            generationId = generationId,
            nowEpochMs = 1_005L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val canceledPage = canceled.manifest.pages.getValue(onlineKey)
        canceledPage.candidate shouldBe null
        canceledPage.translation.shouldNotBeNull().status shouldBe failure.status
        canceled.manifest.durableFailures.values.single() shouldBe failure
        return seed.copy(manifest = canceled.manifest) to failure
    }

    private fun store(seed: SeededArtifact): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = mapOf(onlineKey to seed.page),
        artifactStore = seed.artifact,
        initialArtifactManifest = seed.manifest,
    )

    private fun assertDurableRekeySurvivesReopen(io: FakeChapterDocumentIo): ChapterArtifactManifest {
        val reopenedArtifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val reopenedManifest = reopenedArtifact.load().manifest

        reopenedManifest.pages.keys shouldBe setOf(downloadedKey)
        val committed = reopenedManifest.pages.getValue(downloadedKey).committed.shouldNotBeNull()
        val snapshotName = committed.pageSnapshotFileName.shouldNotBeNull()
        val reopenedPage = reopenedArtifact.readPageSnapshot(snapshotName).shouldNotBeNull()
        reopenedPage.sourceFileName shouldBe downloadedKey
        reopenedPage.blocks.single().text shouldBe "source text"
        reopenedPage.blocks.single().translation shouldBe "translated text"
        return reopenedManifest
    }

    @Test
    fun `artifact-backed rekey is readable after reopening the same durable documents`() = runTest {
        val io = FakeChapterDocumentIo()
        val (seed, failure) = seedCommittedArtifactWithFailure(io)
        val store = store(seed)
        store.durableFailure(onlineKey) shouldBe failure

        store.rekeyPages(
            onlineKeys = listOf(onlineKey),
            onDiskKeys = listOf(downloadedKey),
        ) shouldBe ChapterTranslationStore.PageRekeyOutcome.Moved(
            listOf(onlineKey to downloadedKey),
            skippedCollisions = 0,
        )

        store.state.value.keys shouldBe setOf(downloadedKey)
        store.state.value.getValue(downloadedKey).sourceFileName shouldBe downloadedKey
        store.durableFailure(downloadedKey) shouldBe failure.copy(pageKey = downloadedKey)
        val reopenedManifest = assertDurableRekeySurvivesReopen(io)
        reopenedManifest.durableFailures.values.single() shouldBe failure.copy(pageKey = downloadedKey)
    }

    @Test
    fun `missing committed snapshot rejects rekey without publishing or swapping pages`() = runTest {
        val io = FakeChapterDocumentIo()
        val seed = seedCommittedArtifact(io)
        val snapshotName = seed.manifest.pages.getValue(onlineKey).committed
            .shouldNotBeNull().pageSnapshotFileName.shouldNotBeNull()
        io.files.remove(snapshotName)
        val store = store(seed)
        val beforeState = store.state.value
        val beforeManifest = store.readArtifactManifest().shouldNotBeNull()
        io.writeAttempts.clear()

        val rejected = store.rekeyPages(
            onlineKeys = listOf(onlineKey),
            onDiskKeys = listOf(downloadedKey),
        ).shouldBeInstanceOf<ChapterTranslationStore.PageRekeyOutcome.Rejected>()

        rejected.reason shouldBe "page snapshot preparation failed"
        store.state.value shouldBe beforeState
        store.artifactManifest shouldBe seed.manifest
        store.readArtifactManifest() shouldBe beforeManifest
        io.writeAttempts shouldBe emptyList()
    }

    @Test
    fun `published artifact rekey survives a crash before the live map swap`() = runTest {
        val io = FakeChapterDocumentIo()
        val store = store(seedCommittedArtifact(io))
        var reachedPublicationBoundary = false
        store.afterRekeyManifestPublicationBeforeLiveSwap = {
            store.readArtifactManifest().shouldNotBeNull().pages.keys shouldBe setOf(downloadedKey)
            store.state.value.keys shouldBe setOf(onlineKey)
            reachedPublicationBoundary = true
            throw SimulatedProcessCrash()
        }

        var crashObserved = false
        try {
            store.rekeyPages(
                onlineKeys = listOf(onlineKey),
                onDiskKeys = listOf(downloadedKey),
            )
        } catch (_: SimulatedProcessCrash) {
            crashObserved = true
        }

        reachedPublicationBoundary shouldBe true
        crashObserved shouldBe true
        assertDurableRekeySurvivesReopen(io)
    }
}
