package eu.kanade.translation.persistence

import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isColorizeOnlyComplete
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.isTranslationDisplayReady
import kotlinx.coroutines.flow.update
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ChapterTranslationStore internal constructor(
    initialPages: Map<String, PageTranslation> = emptyMap(),
    artifactStore: ChapterArtifactEngine? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    internal val artifactParent: UniFile? = null,
    internal val artifactFileName: String? = null,
    internal val artifactParentResolver: (() -> UniFile)? = null,
    internal val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Explicit memory, lazy-durable, or opened-durable storage mode. */
    internal var engineMode: ChapterStoreEngineMode =
        artifactStore?.let { ChapterStoreEngineMode.Durable(it) }
            ?: if (artifactParentResolver != null || artifactParent != null || artifactFileName != null) {
                ChapterStoreEngineMode.LazyDurable(
                    artifactParent = artifactParent,
                    artifactFileName = artifactFileName,
                    artifactParentResolver = artifactParentResolver,
                )
            } else {
                ChapterStoreEngineMode.Memory
            }

    /** The durable engine when this mode has been opened; memory and lazy modes are explicit. */
    internal val artifactEngine: ChapterArtifactEngine?
        get() = (engineMode as? ChapterStoreEngineMode.Durable)?.artifact

    internal fun hasArtifactPersistenceTarget(): Boolean =
        artifactParent != null || artifactFileName != null || artifactParentResolver != null

    /**
     * Store-side lock hierarchy: every acquisition in a write path moves DOWN
     * this list; nothing ever acquires a lock higher in the list while
     * holding a lower one:
     *
     * 1. the store [mutex] — suspending, so holders may await lower locks;
     * 2. the per-artifact open lock (`withArtifactOpenLock`) — acquired only
     *    from inside [mutex] via publication admission, as a coroutine.
     *
     * The open path (`openArtifactSuspend`) takes ONLY level 2 and never the
     * store mutex, so the order cannot invert and a first write never blocks a
     * carrier thread under the store mutex. `runBlocking` is banned anywhere
     * below level 1.
     */
    internal val mutex = Mutex()

    /** JVM regression seam for the durable-publication/live-map-swap crash boundary. */
    @Volatile
    internal var afterRekeyManifestPublicationBeforeLiveSwap: (() -> Unit)? = null

    private val publishedPageCopyCount = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Test-only counter of page publishes: production publishes increment it
     * so read-only-view tests can assert no-copy publication.
     */
    internal val publishedPageCopies: Int
        get() = publishedPageCopyCount.get()

    internal fun countPublishedPageCopy() {
        publishedPageCopyCount.incrementAndGet()
    }

    internal val chapterKey: String?
        get() = artifactEngine?.layout?.chapterKey ?: artifactFileName?.substringBeforeLast('.')

    /** Runs one external artifact transaction under the facade mutex. */
    internal suspend inline fun <T> withArtifactEngineLocked(
        crossinline block: (ChapterArtifactEngine) -> T,
    ): T? = mutex.withLock {
        artifactEngine?.let(block)
    }

    /** Read-only manifest projection for context/status consumers. */
    internal fun readArtifactManifest(): ChapterArtifactManifest? = artifactEngine?.readManifest()

    @Volatile
    internal var pages: PersistentMap<String, PublishedPageTranslation> = persistentMapOf()
    internal val stateFlow = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    /**
     * The last-known-good committed display bundle per
     * page. A candidate retry may mutate the live [pages] entry freely —
     * clearing statuses, wiping the cleaned name, stranding stages — but it
     * can never hide or mutate the committed bundle observed through
     * [display]. Promotion happens atomically inside the store mutex after the
     * page reached [PageTranslation.hasRenderedResult] shape, so observers
     * never see a half-promoted combination.
     */
    @Volatile
    internal var committedDisplay: PersistentMap<String, CommittedPageDisplay> = persistentMapOf()

    /** Durable artifact manifest snapshot used by the live writer bridge. */
    @Volatile
    internal var artifactManifest: ChapterArtifactManifest? = initialArtifactManifest

    @Volatile
    internal var pendingExpectedPageCount: Int? = null

    @Volatile
    internal var pendingExpectedPageCountTrusted: Boolean = false

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    internal val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()

    /** Colored-image names retained the same way as [retiredCleanedImages]; memory-only, never hydrated. */
    internal val retiredColoredImages = ConcurrentHashMap<String, MutableSet<String>>()
    internal val pendingArtifactPageRegistrations = LinkedHashSet<String>()

    internal val publishedDisplay = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    // Lease ownership stays in one table so the store and lease APIs share the
    // same map and synchronization boundary.
    private val pageStageLeaseTable = PageStageLeaseTable(this)

    /** Writer leases per page: one origin owns a page until it releases it. */
    internal val pageLeases get() = pageStageLeaseTable.pageLeases

    @Volatile
    internal var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslationView>> = stateFlow.asStateFlow()

    /**
     * Committed-pointer display projection: each page resolves to
     * its immutable committed display bundle when one exists, otherwise the
     * live candidate entry. Candidate emissions can replace live entries but
     * never null or mutate a committed bundle.
     */
    val display: StateFlow<Map<String, PageTranslationView>> = publishedDisplay.asStateFlow()

    val currentGeneration: Long get() = generation

    /**
     * Frozen snapshot of the last displayable page state, plus the identity
     * that lets the store decide when a promotion supersedes an older bundle.
     */
    data class CommittedPageDisplay(
        val page: PublishedPageTranslation,
        val pageVersion: Long,
        val displayFingerprint: String,
        val promotedAtEpochMs: Long,
    )

    data class PageSnapshot(
        val page: PageTranslation?,
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>,
        /** Lease fencing token held at snapshot time, when this writer owns the page. */
        val leaseToken: Long? = null,
        /** Artifact-manifest page version observed with this snapshot. */
        val artifactPageVersion: Long? = null,
        /** Candidate generation observed with this snapshot, when artifact authority owns it. */
        val candidateGenerationId: String? = null,
        /** Candidate dependency fingerprint observed with this snapshot. */
        val dependencyFingerprint: String? = null,
    ) {
        fun toPrecondition() = PatchPrecondition(
            generation = generation,
            pageVersion = pageVersion,
            blockFingerprints = blockFingerprints,
            leaseToken = leaseToken,
            candidateGenerationId = candidateGenerationId,
            dependencyFingerprint = dependencyFingerprint,
            artifactPageVersion = artifactPageVersion,
        )
    }

    data class PatchPrecondition(
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>? = null,
        /** A late result carrying an old lease token is rejected after release. */
        val leaseToken: Long? = null,
        /** Artifact candidate generation observed by this writer. */
        val candidateGenerationId: String? = null,
        /** Artifact candidate dependency fingerprint observed by this writer. */
        val dependencyFingerprint: String? = null,
        /** Artifact-manifest page version observed by this writer. */
        val artifactPageVersion: Long? = null,
    )

    sealed interface PatchResult {
        data class Accepted(val snapshot: PageSnapshot) : PatchResult
        data class Rejected(
            val reason: String,
            val detail: Detail? = null,
        ) : PatchResult {
            sealed interface Detail {
                data object ArtifactPublicationFailed : Detail
            }
        }
    }

    /**
     * Result of the single bounded revalidation allowed after a batch
     * translation candidate was rejected only because its page version moved.
     */
    internal sealed interface TranslationPublicationReconciliation {
        data class AlreadyDurable(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Rebased(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Deferred(val reason: String) : TranslationPublicationReconciliation
        data class PublicationFailed(val reason: String) : TranslationPublicationReconciliation
    }

    internal class GenerationContext(
        val store: ChapterTranslationStore,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<GenerationContext>
    }

    /**
     * Publication collaborator (Phase 7 S4). Owns mutation admission, lazy
     * artifact-store initialization, the registration CAS, and the candidate
     * open/cancel/persist/promote/failure orchestration. Module-internal so
     * the store and its in-module mutator collaborators call the publication
     * path directly under [mutex] — no facade shells in between.
     */
    internal val publicationBridge = ChapterStorePublicationBridge(this)

    // Lazy-write queue state is stored by the drain coordinator, but every
    // mutation is serialized by this store's mutex. Store-to-drain scheduling
    // is a synchronous handoff with no second state lock; drain paths re-enter
    // through mutex.withLock before reading or changing store state.
    internal val writeDrain = StoreWriteDrainCoordinator(this, persistenceDispatcher)

    /**
     * Bulk-mutation collaborator (Phase 7 S7). Owns [deletePage],
     * [replaceAll], [rekeyPages], [preRegisterPages],
     * [clearTransientQueuePages], and the two-phase re-key planning machinery;
     * the store delegates every public entry point here.
     */
    private val bulkMutator = ChapterBulkMutator(this)

    internal val pageMutations = ChapterPageMutations(this)

    /**
     * Typed result of [rekeyPages]. [Moved] with [Moved.skippedCollisions] > 0 is
     * explicitly partial: the caller must not treat it as a complete handoff.
     */
    sealed class PageRekeyOutcome {
        data class Moved(
            val moves: List<Pair<String, String>>,
            val skippedCollisions: Int,
        ) : PageRekeyOutcome()

        data object Noop : PageRekeyOutcome()

        /** [reason] names why the re-key was refused; the store is left untouched. */
        data class Rejected(val reason: String, val cause: Throwable? = null) : PageRekeyOutcome()
    }

    /**
     * Active reader stores publish live state first.  Their durable artifact
     * transactions are coalesced by [writeDrain] instead of running
     * synchronously in the stage-emission path.  Test/probe stores keep the
     * historical synchronous default unless the manager explicitly enables
     * this mode when registering the live chapter store.
     */
    @Volatile
    internal var lazyPersistenceEnabled = false

    /** Latest guarded page-write identity rejection, retained for failure diagnostics. */
    @Volatile
    internal var lastGuardedWriteRejectionDiagnostic: String? = null
        internal set


    /** Enables memory-first publication for a registered production chapter. */
    internal fun enableLazyPersistence() {
        lazyPersistenceEnabled = true
    }

    internal fun isLazyGenerationCurrent(expectedGeneration: Long): Boolean =
        !defunct && generation == expectedGeneration

    @Volatile
    internal var defunct = false

    suspend fun markDefunct() {
        writeDrain.markDefunct(
            markGenerationDefunctLocked = {
                defunct = true
                generation++
                synchronized(pageLeases) { pageLeases.clear() }
            },
        )
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }
    val isDefunct: Boolean
        get() = defunct

    init {
        initialRetiredCleanedImages.forEach { (pageKey, names) ->
            if (names.isNotEmpty()) {
                retiredCleanedImages[pageKey] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(names) }
            }
        }
        initialPages.forEach { (pageKey, page) ->
            val version = if (page.pageVersion > 0L) page.pageVersion else nextVersion()
            val owned = page.detachedCopy().apply {
                sourceFileName = sourceFileName ?: pageKey
                runGeneration = generation
                pageVersion = version
            }
            pages = pages.put(pageKey, publishPage(owned))
        }
        nextPageVersion = pages.values.maxOfOrNull(PublishedPageTranslation::pageVersion) ?: 0L
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = publishPage(page),
                    pageVersion = page.pageVersion,
                    displayFingerprint = displayFingerprintOf(page),
                    promotedAtEpochMs = page.updatedAt,
                ),
            )
        }
        // Memory-only stores and legacy pages without a durable artifact
        // snapshot retain the compatibility seed from their display-ready
        // initial state.
        pages.forEach { (pageKey, page) ->
            if (committedDisplay.containsKey(pageKey)) return@forEach
            if (page.hasRenderedResult) {
                committedDisplay = committedDisplay.put(
                    pageKey,
                    CommittedPageDisplay(
                        page = page,
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(pages.keys + committedDisplay.keys)
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    // Durable status reads stay on the store API; the projector derives them
    // from the store's consistent manifest/state/display snapshot.
    private val statusProjector = StoreStatusProjector(this)

    /**
     * One consistent read of the status-projection inputs for
     * [StoreStatusProjector], captured in the same order the projection body
     * reads them (manifest → live pages flow → display flow). The flows are
     * handed over by reference so their `.value` reads keep landing at the
     * projection body's original points.
     */
    internal fun statusProjectionInputs(): StoreStatusInputs =
        StoreStatusInputs(
            manifest = artifactManifest,
            state = state,
            display = display,
        )

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = statusProjector.durableFailure(pageKey, stage)

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        statusProjector.durableFailuresSnapshot()

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    // Lease entry points stay on the store so readers and pipeline writers use
    // one ownership boundary. The table uses the store mutex for mutations,
    // synchronized reads for lock-free inspection, and NonCancellable release
    // paths so cancellation cannot strand an owned lease.

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLease(pageKey, stage, origin)

    internal suspend fun tryAcquirePageStageLeaseIfUnowned(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLeaseIfUnowned(pageKey, stage, origin)

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) =
        pageStageLeaseTable.releasePageStageLease(pageKey, origin)

    /** Releases only the lease record held under [expectedToken]. */
    suspend fun releasePageStageLease(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = pageStageLeaseTable.releasePageStageLease(pageKey, origin, expectedToken)

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        pageStageLeaseTable.cancelPageStageWork(pageKey, origin)

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = pageStageLeaseTable.pageLeaseOwner(pageKey)

    suspend fun <T> withGeneration(generation: Long, block: suspend () -> T): T =
        withContext(GenerationContext(this, generation)) { block() }

    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.patchPage(pageKey, expected, description, patch)

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) =
        pageMutations.updatePage(pageKey, update)

    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageGuarded(pageKey, expected, description, update)

    internal suspend fun reconcileBatchTranslationPageVersionDrift(
        pageKey: String,
        expected: PatchPrecondition,
        candidate: PageTranslation,
    ): TranslationPublicationReconciliation =
        pageMutations.reconcileBatchTranslationPageVersionDrift(pageKey, expected, candidate)

    suspend fun persistDurableStageFailure(
        pageKey: String,
        expected: PatchPrecondition,
        failure: DurableFailureMetadata,
        description: String = "durable stage failure",
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.persistDurableStageFailure(pageKey, expected, failure, description, update)

    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageFromCurrentSnapshot(pageKey, description, update)

    /**
     * Publishes an in-flight COLORING entry so holders (dim + spinner via
     * isStageRunning) and the batch tracker see live state. Committed display
     * is untouched and real OCR data is never clobbered.
     */
    suspend fun markColorizeRunning(pageKey: String, description: String): PatchResult =
        updatePageFromCurrentSnapshot(pageKey, description) { current ->
            (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                coloringStatus = StageStatus.RUNNING
                updatedAt = System.currentTimeMillis()
            }
        }


/**
     * Atomic committed-display promotion: when the page just reached a fully
     * displayable state (cleaned image file published and verified upstream by
     * CleanedPublication, translation READY/PARTIAL, some
     * translated text), the committed pointer swaps to a frozen snapshot of
     * exactly that state. A superseded bundle\'s cleaned and colored files are retained
     * until the pipeline drains them, never deleted under a live committed pointer.
     */
    internal fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        val published = pages[pageKey] ?: return
        if (pageKey.isEmpty() ||
            (!published.isTranslationDisplayReady && !published.isTextlessTerminal && !published.isColorizeOnlyComplete)
        ) {
            return
        }
        val fingerprint = displayFingerprintOf(published)
        val existing = committedDisplay[pageKey]
        if (existing != null && existing.displayFingerprint == fingerprint) return
        if (existing != null) {
            retiredCleanedImages
                .retainSuperseded(pageKey, existing.page.cleanedImageName, published.cleanedImageName)
            retiredColoredImages
                .retainSuperseded(pageKey, existing.page.coloredImageName, published.coloredImageName)
        }
        committedDisplay = committedDisplay.put(
            pageKey,
            ChapterTranslationStore.CommittedPageDisplay(
                page = published,
                pageVersion = published.pageVersion,
                displayFingerprint = fingerprint,
                promotedAtEpochMs = published.updatedAt,
            ),
        )
    }

    /**
     * Atomically updates only changed display keys; all other immutable values are shared.
     * The fast-cancel path also calls this off-mutex, so the flow update must stay retry-safe.
     */
    internal fun displaySnapshotLocked(
        changedPageKeys: Iterable<String>,
    ) {
        val keys = changedPageKeys.toList()
        publishedDisplay.update { current ->
            var updated = current
            keys.forEach { pageKey ->
                val page = pages[pageKey]
                updated = if (page == null) {
                    updated.remove(pageKey)
                } else {
                    updated.put(pageKey, committedDisplay[pageKey]?.page ?: page)
                }
            }
            updated
        }
    }

    internal fun displayFingerprintOf(page: PageTranslationView): String =
        StageFingerprints.canonicalFingerprint(
            buildList {
                add(page.cleanedImageName)
                add(page.inpaintRevision)
                // A colorize-only re-publication changes only the colored image;
                // including it keeps the promotion fingerprint honest for both bases.
                add(page.coloredImageName)
                addAll(page.blockFingerprints())
            },
        )

    /** Resolves the reader-facing page state: committed bundle first, live candidate otherwise. */
    fun resolveDisplayPage(pageKey: String): PageTranslationView? =
        committedDisplay[pageKey]?.page ?: pages[pageKey]

    /**
     * Lazily loads full page snapshot (including text blocks) from durable storage
     * if the page is currently backed by a synthesized or empty placeholder.
     */
    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? =
        mutex.withLock {
            val current = pages[pageKey]
            if (current != null && current.blocks.isNotEmpty()) {
                return@withLock current.toDraft()
            }
            val record = artifactManifest?.pages?.get(pageKey)
            val snapshotFileName = record?.candidate?.pageSnapshotFileName
                ?: record?.committed?.pageSnapshotFileName
            val engine = artifactEngine
            if (snapshotFileName != null && engine != null) {
                val loaded = engine.readPageSnapshot(snapshotFileName)
                if (loaded != null) {
                    val published = publishPage(loaded)
                    pages = pages.put(pageKey, published)
                    stateFlow.value = snapshotPages()
                    if (record?.committed != null) {
                        committedDisplay = committedDisplay.put(
                            pageKey,
                            ChapterTranslationStore.CommittedPageDisplay(
                                page = published,
                                pageVersion = loaded.pageVersion,
                                displayFingerprint = displayFingerprintOf(loaded),
                                promotedAtEpochMs = loaded.updatedAt,
                            ),
                        )
                    }
                    displaySnapshotLocked(listOf(pageKey))
                    return@withLock loaded
                }
            }
            current?.toDraft()
        }

    /** The frozen committed display bundle for [pageKey], if one exists. */
    internal fun committedDisplayPage(pageKey: String): PageTranslationView? =
        committedDisplay[pageKey]?.page

    /**
     * True while [name] still backs the committed display bundle (or its
     * retained predecessor) for [pageKey]; such files must not be deleted
     * before a newer bundle promotes.
     */
    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean {
        if (pages[pageKey]?.cleanedImageName == name || committedDisplay[pageKey]?.page?.cleanedImageName == name) {
            return false
        }
        // Rekey can leave an already-queued retirement callback carrying the old
        // pageKey after ownership has moved. Check every live/committed/retained
        // reference before deleting the shared image or its identity sidecar.
        return name !in referencedCleanedImageNames()
    }

    /** Protect a companion image still owned by a different page after page reset/deletion. */
    fun isCleanedImageReferencedByAnotherPage(pageKey: String, name: String): Boolean =
        pages.any { (owner, page) -> owner != pageKey && page.cleanedImageName == name } ||
            committedDisplay.any { (owner, page) -> owner != pageKey && page.page.cleanedImageName == name } ||
            retiredCleanedImages.any { (owner, names) -> owner != pageKey && name in names } ||
            artifactManifest?.pages?.any { (owner, record) ->
                owner != pageKey &&
                    (record.committed?.displayBase?.fileName == name || record.previousCommitted?.displayBase?.fileName == name)
            } == true

    /** All cleaned-image names still reachable from live, committed, or retired state. */
    fun referencedCleanedImageNames(): Set<String> = buildSet {
        pages.values.mapNotNullTo(this) { it.cleanedImageName }
        committedDisplay.values.mapNotNullTo(this) { it.page.cleanedImageName }
        retiredCleanedImages.values.forEach { addAll(it) }
        artifactManifest?.pages?.values?.forEach { record ->
            record.committed?.displayBase?.fileName?.let(::add)
            record.previousCommitted?.displayBase?.fileName?.let(::add)
        }
    }

    /** Atomically takes every superseded cleaned-image name for [pageKey]. */
    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        retiredCleanedImages.remove(pageKey)?.toList().orEmpty()

    /** Colored-image counterpart of [mayDeleteCleanedImage]; the manifest records no colored names. */
    fun mayDeleteColoredImage(pageKey: String, name: String): Boolean {
        if (pages[pageKey]?.coloredImageName == name ||
            committedDisplay[pageKey]?.page?.coloredImageName == name
        ) {
            return false
        }
        return name !in referencedColoredImageNames()
    }

    internal fun referencedColoredImageNames(): Set<String> = buildSet {
        pages.values.mapNotNullTo(this) { it.coloredImageName }
        committedDisplay.values.mapNotNullTo(this) { it.page.coloredImageName }
        retiredColoredImages.values.forEach { addAll(it) }
    }

    /** Atomically takes every superseded colored-image name for [pageKey]. */
    fun drainRetiredColoredImages(pageKey: String): List<String> =
        retiredColoredImages.remove(pageKey)?.toList().orEmpty()

    /** Retains [previous] for a later drain once a promotion stops displaying it. */
    private fun ConcurrentHashMap<String, MutableSet<String>>.retainSuperseded(
        pageKey: String,
        previous: String?,
        current: String?,
    ) {
        previous
            ?.takeIf { it != current && it.isNotEmpty() }
            ?.let { computeIfAbsent(pageKey) { ConcurrentHashMap.newKeySet() }.add(it) }
    }

    /**
     * Explicitly drops the committed display pointer — reserved for user
     * resets (reset translation/inpaint data), never for candidate retries.
     */
    suspend fun demoteCommittedDisplay(pageKey: String, reason: String): String? {
        if (isDefunct) return null
        return mutex.withLock {
            if (committedDisplay.containsKey(pageKey)) {
                committedDisplay = committedDisplay.remove(pageKey)
                retiredCleanedImages.remove(pageKey)
                retiredColoredImages.remove(pageKey)
                val demotion = demoteArtifactPageLocked(pageKey)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT committed display demoted: pageKey=$pageKey reason=$reason"
                }
                displaySnapshotLocked(listOf(pageKey))
                demotion
            } else {
                null
            }
        }
    }

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean {
        val artifact = artifactEngine ?: return true
        val manifest = artifactManifest ?: return true
        val candidate = manifest.pages[pageKey]?.candidate ?: return true
        val outcome = artifact.candidates.cancelLiveCandidate(manifest, pageKey, candidate.generationId)
        if (outcome is TransactionOutcome.Committed) {
            artifactManifest = outcome.manifest
            return true
        } else if (outcome is TransactionOutcome.Rejected) {
            publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate cancel rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }

    /** Caller holds [mutex]; null when the destructive demotion is durably complete. */
    private fun demoteArtifactPageLocked(pageKey: String): String? {
        val artifact = artifactEngine ?: return null
        val manifest = artifactManifest ?: return null
        val outcome = artifact.candidates.demoteLivePage(manifest, pageKey)
        return when (outcome) {
            is TransactionOutcome.Committed -> {
                artifactManifest = outcome.manifest
                outcome.unverifiedBackupReason?.let { reason ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact display demotion published without a verified backup: " +
                            "pageKey=$pageKey reason=$reason"
                    }
                    return "unverified backup after demotion: $reason"
                }
                null
            }
            is TransactionOutcome.Rejected -> {
                publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact display demotion rejected: pageKey=$pageKey reason=${outcome.reason}"
                }
                outcome.reason
            }
        }
    }

    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean =
        pageMutations.shouldPersistUpdate(previous, updated)

    internal fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): PageWriteRejection? =
        pageMutations.pageWriteRejection(pageKey, expected)
    suspend fun deletePage(pageKey: String) = bulkMutator.deletePage(pageKey)
    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) =
        bulkMutator.replaceAll(updatedPages)

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyOutcome = bulkMutator.rekeyPages(onlineKeys, onDiskKeys)

    /**
     * Outcome of [preRegisterPages]. A rejection is observable so
     * the caller can fail the batch explicitly instead of running a tracker
     * whose totals silently stay zero.
     */
    sealed class PagePreRegistration {
        data object Accepted : PagePreRegistration()

        /** [reason] names why registration was refused (defunct store, artifact authority, ...). */
        data class Rejected(val reason: String) : PagePreRegistration()
    }

    /**
     * Registers the full ordered page set for a chapter before OCR starts.
     *
     * These placeholders are intentionally memory-only: they let progress UI show
     * the chapter total immediately, while the expected-page count is captured
     * in the artifact manifest when an artifact parent is already available.
     *
     * Returns [PagePreRegistration.Rejected] when the store refuses
     * registration (defunct store or artifact-authority failure), so the caller
     * can surface a typed terminal
     * error rather than a live zero tracker.
     *
     *  The trigger may attach its
     * admission-probe cross-check. When [sourceCountKnown] is true the
     * registered truth is honest about partiality:
     *  - a known SOURCE total ([probedSourcePageCount] != null) makes the
     *    trusted baseline the source total (progress shows 37/40, not 40/40)
     *    and records the delta as [PartialBatchInfo.DOWNLOAD_CROSSCHECK] —
     *    cleared again when a later cross-check derives missing == 0;
     *  - an unknown total (null count) keeps the found count but DEMOTES the
     *    trusted stamp and records `determinedFrom = UNKNOWN` — the durable
     *    record stops claiming trust it does not have.
     * Missing pages are never registered as page records; the manifest carries
     * the absence. Without context (both defaults) the self-derived
     * stamp below is byte-identical to its pre-existing behavior.
     */
    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): PagePreRegistration =
        bulkMutator.preRegisterPages(pageKeys, probedSourcePageCount, sourceCountKnown)

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) =
        bulkMutator.clearTransientQueuePages(reason)
    internal fun nextVersion(): Long = ++nextPageVersion

    internal fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    internal fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return PageSnapshot(
            page = page?.toDraft(),
            generation = generation,
            pageVersion = page?.pageVersion ?: 0L,
            blockFingerprints = page?.blockFingerprints().orEmpty(),
            leaseToken = pageLeases[pageKey]?.token,
            artifactPageVersion = artifactPage?.pageVersion,
            candidateGenerationId = artifactPage?.candidate?.generationId,
            dependencyFingerprint = artifactPage?.candidate?.dependencyFingerprint
                ?: page?.let(StageFingerprints::pageSnapshot),
        )
    }

    internal fun snapshotPages(): PersistentMap<String, PublishedPageTranslation> = pages

    internal fun publishPage(draft: PageTranslation): PublishedPageTranslation {
        countPublishedPageCopy()
        return draft.toPublishedPage()
    }

    /** Durable translation status derived from the committed artifact state. */
    fun artifactStatus(): Translation.State? = statusProjector.artifactStatus()

    suspend fun flush() = writeDrain.flush()

    suspend fun closeAndFlush() {
        try {
            writeDrain.closeAndFlush()
        } finally {
            artifactEngine?.cancelRetentionWorkAndJoin()
        }
    }

    companion object {
        /** Open mechanics live on the opener collaborator; these shells keep existing callers unaffected. */

        /** Reads only the small manifest header; page snapshots stay unopened. */
        internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
            ChapterStoreOpener.probeArtifactManifest(parent, fileName)

        /** Opens an artifact and installs the state reconstructed from its manifest and snapshots. */
        internal suspend fun openArtifactSuspend(
            parent: UniFile,
            fileName: String,
        ): ChapterTranslationStore = ChapterStoreOpener.openArtifactSuspend(parent, fileName)

        internal suspend fun <T> withArtifactOpenLock(
            parent: UniFile?,
            fileName: String,
            block: suspend (ChapterStoreOpener.ArtifactOpenLock) -> T,
        ): T = ChapterStoreOpener.withArtifactOpenLock(parent, fileName, block)

        /**
         * Creates a store whose artifact manifest is created lazily on the first
         * artifact-backed [updatePage]/[replaceAll] write. When
         * [artifactParent] is null (chapter never translated), the optional
         * [artifactParentResolver] resolves the parent directory on the first mutation.
         */
        fun lazy(
            artifactParent: UniFile? = null,
            artifactFileName: String? = null,
            artifactParentResolver: (() -> UniFile)? = null,
        ): ChapterTranslationStore = ChapterStoreOpener.lazy(
            artifactParent = artifactParent,
            artifactFileName = artifactFileName,
            artifactParentResolver = artifactParentResolver,
        )
    }
}
