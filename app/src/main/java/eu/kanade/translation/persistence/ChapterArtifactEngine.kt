package eu.kanade.translation.persistence

import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns one chapter's artifact manifest and immutable sidecar tree. Candidate
 * generation transitions, preconditioned stage commits, committed-pointer
 * promotion, cancellation and failure state, crash recovery, and bounded
 * retention all operate on artifact documents. Flat translation files from
 * pre-artifact builds are not read or migrated.
 */

import eu.kanade.translation.persistence.ChapterArtifactEngine.DestructiveManifestReceipt
import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome

typealias CandidateOpenState = ChapterCandidateTransactions.CandidateOpenState

class ChapterArtifactEngine(
    internal val documents: AtomicChapterDocuments,
    internal val layout: ChapterArtifactLayout,
) {
    internal val io: ChapterDocumentIo get() = documents.rawIo()

    /** Sidecars prepared off-lock before a store publishes the re-key pointers. */
    internal data class PreparedPageSnapshotRekey(
        val pages: Map<String, PageArtifactRecord>,
        /** Exact new-key state and identity to place in the BULK_REKEY journal record. */
        val journalMutations: Map<String, PreparedRekeyJournalMutation>,
    )

    internal data class PreparedRekeyJournalMutation(
        val state: PublishedPageTranslation,
        /** Null is an explicit retryable invalidation, never a completed state. */
        val artifactContentHash: String?,
    )

    /** Bounded retention sweep. */
    private val retentionSweep = ArtifactRetention(io, layout)
    private val retentionScopeJob = SupervisorJob()

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
    )

    val candidates: ChapterCandidateTransactions = ChapterCandidateTransactions(documents, layout, this)

    internal companion object {
        /**
         * Captured at first engine use in this process: any candidate written
         * later necessarily belongs to a live in-process run, while candidates
         * stamped earlier predate the process and can only be crash debris.
         */
        val processStartEpochMs: Long = System.currentTimeMillis()
    }

    fun load(): LoadResult {
        val readManifestStage = ReaderEntryTrace.begin("store.readManifest", null)
        val primary = readManifestDocument(layout.manifestFileName)
        val backup = readManifestDocument(backupName())
        readManifestStage.end()

        if (primary?.schemaVersion != null && primary.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            return LoadResult(primary)
        }
        if (backup?.schemaVersion != null && backup.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest backup has unsupported schema; preserved untouched: " +
                    "chapter=${layout.chapterKey} schema=${backup.schemaVersion}"
            }
            return if (primary != null) {
                LoadResult(primary)
            } else {
                LoadResult(backup)
            }
        }

        val recoverBackupStage = ReaderEntryTrace.begin("store.recoverBackup", null)
        val existing = primary
            ?: recoverPrimaryFromBackupOrNull(backup)
        recoverBackupStage.end()
        if (existing != null) {
            if (primary == null && readManifestDocument(layout.manifestFileName) == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact manifest primary recovery incomplete; backup preserved: " +
                        "chapter=${layout.chapterKey}"
                }
                return LoadResult(existing)
            }
            // The crawl is minutes of SAF listings on large chapters, so it never
            // runs here; event-driven retention (follow-up) owns their cleanup.
            // Small chapters sweep so crash/cancel garbage cannot accumulate.
            if (existing.pages.size <= 8) {
                val retentionStage = ReaderEntryTrace.begin("store.retention", null)
                reconcileRetention(existing)
                retentionStage.end()
            }
            val recoverInterruptedStage = ReaderEntryTrace.begin("store.recoverInterrupted", null)
            val recovered = candidates.recoverInterruptedStages(existing)
            val reconciled = candidates.closeOrphanedCandidates(recovered, processStartEpochMs)
            recoverInterruptedStage.end()
            // P3A: the open-time backup deletion was removed. The retained
            // `.bak` is the chapter's crash-recovery copy and repair/retry
            // evidence; the next manifest rotation replaces it, and explicit
            // chapter teardown remains the only other remover. Interrupted-
            // stage recovery (when it published) already rotated a fresh
            // backup into place.
            return LoadResult(reconciled)
        }

        val created = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        when (val receipt = publishDestructiveManifest(created)) {
            is DestructiveManifestReceipt.Complete -> {}
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest created without a verified backup: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
            }
            is DestructiveManifestReceipt.NotPublished -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest creation failed: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
            }
        }
        return LoadResult(created)
    }

    fun readManifest(): ChapterArtifactManifest? {
        val primary = readManifestDocument(layout.manifestFileName)
        if (primary != null) return primary
        val backup = readManifestDocument(backupName()) ?: return null
        if (backup.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) return backup
        return recoverPrimaryFromBackupOrNull(backup)
    }

    /** Crash-safe manifest publication: temp, validate, rotate backup, rename. */
    fun publishManifest(manifest: ChapterArtifactManifest): Boolean =
        publishManifestInternal(stampChapterKey(manifest))

    /**
     * P3A receipt of a destructive or initialization manifest publication:
     * the publication must leave a verified same-state backup, because
     * restoring a stale previous generation after a later primary corruption
     * would resurrect state the destructive operation removed.
     */
    sealed interface DestructiveManifestReceipt {
        /** Primary and backup verified same-state. */
        data class Complete(val manifest: ChapterArtifactManifest) : DestructiveManifestReceipt

        /**
         * Primary advanced but the same-state backup copy is unverified —
         * typed incomplete, never rolled back, never silent. The previous
         * generation may still occupy the backup name.
         */
        data class PrimaryAdvancedBackupIncomplete(
            val manifest: ChapterArtifactManifest,
            val reason: String,
        ) : DestructiveManifestReceipt

        /** Nothing published; the prior manifest remains authoritative. */
        data class NotPublished(val reason: String) : DestructiveManifestReceipt
    }

    /**
     * Checked destructive/initialization publication: same future-primary and
     * future-backup guards as [publishManifestInternal], then ONE
     * [AtomicChapterDocuments.publishVerifiedJson] publication that leaves a
     * verified same-state backup. Never routed through the coalescing stash —
     * stash acceptance is never durable success — but while a window is open
     * the stash baseline is refreshed to the published state so the owning
     * window cannot later overwrite it with an older intended manifest (the
     * store monitor serializes the whole chain).
     */
    internal fun publishDestructiveManifest(
        manifest: ChapterArtifactManifest,
        syncToDisk: Boolean = false,
    ): DestructiveManifestReceipt {
        if (readManifestDocument(layout.manifestFileName)?.schemaVersion
                ?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true
        ) {
            return DestructiveManifestReceipt.NotPublished(
                "future-schema primary preserved: chapter=${layout.chapterKey}",
            )
        }
        if (futureBackupPresent()) {
            return DestructiveManifestReceipt.NotPublished(
                "future-schema backup preserved: chapter=${layout.chapterKey}",
            )
        }
        val stamped = stampChapterKey(manifest)
        return when (val verified = documents.publishVerifiedJson(layout.manifestFileName, stamped, syncToDisk)) {
            is AtomicChapterDocuments.VerifiedPublishReceipt.Complete ->
                DestructiveManifestReceipt.Complete(stamped)
            is AtomicChapterDocuments.VerifiedPublishReceipt.BackupNotVerified ->
                DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete(stamped, verified.reason)
            AtomicChapterDocuments.VerifiedPublishReceipt.NotPublished ->
                DestructiveManifestReceipt.NotPublished(
                    "manifest publication failed; prior manifest remains authoritative",
                )
        }
    }

    // ------------------------------------------------------------------
    // Crash-safe store transactions.
    //
    // Every operation is crash-safe: immutable sidecars are published first
    // (temp/validate/rename), the manifest pointer moves second, and a crash
    // between the two leaves an orphan file that retention reconciliation
    // removes later — never a committed pointer at a missing file. Candidate
    // writes require the generation id, the expected page version, and the
    // dependency fingerprint, so a stale worker cannot commit over newer work.
    // ------------------------------------------------------------------

    /** Outcome of one store transaction; only [Committed] changed durable state. */
    sealed interface TransactionOutcome {
        data class Committed(
            val manifest: ChapterArtifactManifest,
            val generationId: String? = null,
            val deletedFiles: List<String> = emptyList(),
            val candidateOpenState: CandidateOpenState? = null,
            /**
             * P3A destructive-checked publications only: set when the manifest
             * publication physically succeeded but the same-state backup copy
             * could not be verified. Null keeps the plain Committed contract.
             */
            val unverifiedBackupReason: String? = null,
        ) : TransactionOutcome

        data class Rejected(
            val reason: String,
            val candidateOpenState: CandidateOpenState? = null,
        ) : TransactionOutcome
    }

    /**
     * One immutable sidecar to publish before its manifest pointer may move
     *. [contentFingerprint] is the semantic content identity
     * the caller established for the sidecar (it must match the pointer's);
     * [publish] must go through [AtomicChapterDocuments.publish]/[AtomicChapterDocuments.publishJson].
     */
    class SidecarPublication(
        val fileName: String,
        val contentFingerprint: String,
        val publish: () -> Boolean,
    )

    /**
     * 20: the generic sidecar-then-pointer transaction. Every
     * [SidecarPublication] is durably published FIRST, then [updatePointers]
     * installs all pointers in ONE atomic manifest publication. Crash or
     * failure windows:
     *
     * - before a sidecar publish completes → nothing visible;
     * - between sidecar and manifest publication → orphan sidecar files only;
     * - during the manifest publication → `.bak` rotation semantics.
     *
     * On any precondition or publication failure the prior manifest stays
     * authoritative  and pointers never dangle.
     */
    fun publishSidecarPointers(
        manifest: ChapterArtifactManifest,
        sidecars: List<SidecarPublication>,
        updatePointers: (ChapterArtifactManifest) -> ChapterArtifactManifest,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        sidecars.forEach { sidecar ->
            if (!ChapterArtifactLayout.isSafeSegment(sidecar.fileName.substringAfterLast('/'))) {
                return TransactionOutcome.Rejected("unsafe sidecar file name: file=${sidecar.fileName}")
            }
            if (!layout.isManagedPath(sidecar.fileName)) {
                return TransactionOutcome.Rejected("sidecar outside the managed tree: file=${sidecar.fileName}")
            }
        }
        sidecars.forEach { sidecar ->
            if (!sidecar.publish()) {
                return TransactionOutcome.Rejected("sidecar publication failed: file=${sidecar.fileName}")
            }
        }
        val updated = updatePointers(manifest).copy(updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated)
    }

    // ------------------------------------------------------------------
    // Generic sidecar publication support.
    // ------------------------------------------------------------------


    /**
     * Builds one immutable JSON sidecar publication for
     * [publishSidecarPointers] through the shared [AtomicChapterDocuments]
     * Json, so callers outside this package can stage
     * sidecar-then-pointer transactions without touching the document layer.
     */
    fun <T : Any> jsonSidecarPublication(
        fileName: String,
        contentFingerprint: String,
        document: T,
        serializer: KSerializer<T>,
    ): SidecarPublication = SidecarPublication(fileName, contentFingerprint) {
        val bytes = documents.json.encodeToString(serializer, document).toByteArray(Charsets.UTF_8)
        documents.publish(fileName, bytes) { written ->
            runCatching { documents.json.decodeFromStream(serializer, written.inputStream()) }.isSuccess
        }
    }

    /** Reads a complete live-store page snapshot referenced by a manifest pointer. */
    fun readPageSnapshot(fileName: String?): PageTranslation? =
        fileName?.let { documents.readValidated<PageTranslation>(it) }

    /** Verify the bytes of a referenced legacy-companion cleaned image through its identity sidecar. */
    fun hasVerifiedCleanedImageIdentity(imageName: String, expectedContentSha256: String): Boolean =
        CleanedImageIdentity.verify(io, layout.chapterKey, imageName, expectedContentSha256)

    internal fun newGenerationId(pageKey: String, nowEpochMs: Long): String =
        "g-$nowEpochMs-${layout.pageSegment(pageKey).takeLast(24)}"

    private val staleManifestRejectionReason = "stale manifest snapshot"

    internal fun staleManifestRejection(manifest: ChapterArtifactManifest): String? {
        val durable = casBaselineManifest()
            ?: return "manifest is not durable: chapter=${layout.chapterKey}"
        return if (durable == manifest) {
            null
        } else {
            "$staleManifestRejectionReason: chapter=${layout.chapterKey}"
        }
    }

    internal fun isStaleManifestRejection(reason: String): Boolean =
        reason.split(": ").any { it == staleManifestRejectionReason }

    internal fun stagePayloadIsValid(record: StageArtifactRecord?): Boolean {
        val fileName = record?.artifactFileName ?: return false
        return io.exists(fileName) &&
            io.length(fileName) > 0L &&
            documents.readValidated<JsonObject>(fileName) != null
    }

    /**
     * Bounded retention (lifecycle contract §15): preserves exactly the files
     * reachable from the manifest/generation graph — committed, candidate, and
     * one-previous bundles, their generation records, and referenced stage
     * sidecars — and deletes every other
     * file under the managed artifact tree using store reachability, never
     * filename age alone. All comparisons use canonical root-relative paths;
     * only contained managed paths are ever deleted. Legacy documents outside
     * the artifact tree (flat JSON, summary, companion
     * images) are never touched.
     */
    fun reconcileRetention(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult =
        retentionSweep.reconcileRetention(manifest, stagedReachable)

    /** Cancels and joins every asynchronous sweep at a store teardown boundary. */
    suspend fun cancelRetentionWorkAndJoin() = retentionScopeJob.cancelAndJoin()

    /**
     * Reclaims explicitly unlinked artifact files without a full tree crawl.
     * Files referenced by [stagedReachable] or [durableManifest] are preserved
     * so a concurrent publication cannot delete a still-reachable document.
     */
    fun deleteKnownOrphans(
        candidateOrphans: Collection<String>,
        stagedReachable: Set<String> = emptySet(),
        durableManifest: ChapterArtifactManifest? = null,
    ): RetentionResult =
        retentionSweep.deleteKnownOrphans(candidateOrphans, stagedReachable, durableManifest)

    internal fun readManifestDocument(name: String): ChapterArtifactManifest? {
        val bytes = io.read(name) ?: return null
        return parseManifest(bytes)
    }

    private fun parseManifest(bytes: ByteArray): ChapterArtifactManifest? = runCatching {
        documents.json.decodeFromStream<ChapterArtifactManifest>(bytes.inputStream())
    }.getOrNull()?.normalizeSupportedSchema()

    /**
     * Caches whether a supported schema version needs normalization. Future
     * schema checks remain fresh so a newer document is never hidden by a
     * stale normalization decision.
     */
    private val schemaNormalizationDecisionCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    internal fun isNormalizationRequired(schemaVersion: Int): Boolean =
        schemaNormalizationDecisionCache.getOrPut(schemaVersion) {
            schemaVersion in 1 until ChapterArtifactManifest.SCHEMA_VERSION
        }

    internal fun invalidateSchemaGuardCache() {
        schemaNormalizationDecisionCache.clear()
    }

    /**
     * Normalizes supported older manifests in memory so their next
     * publication uses the current schema version. This prevents new pointer
     * fields from being written into a schema that an older build might strip.
     * Future schemas remain untouched and are rejected by the schema guard.
     */
    private fun ChapterArtifactManifest.normalizeSupportedSchema(): ChapterArtifactManifest =
        if (isNormalizationRequired(schemaVersion)) {
            copy(schemaVersion = ChapterArtifactManifest.SCHEMA_VERSION)
        } else {
            this
        }

    private fun recoverPrimaryFromBackupOrNull(backup: ChapterArtifactManifest?): ChapterArtifactManifest? {
        if (backup == null) return null
        documents.recoverPrimaryFromBackup(layout.manifestFileName, backupName())
        return readManifestDocument(layout.manifestFileName) ?: backup
    }

    internal fun futureBackupPresent(): Boolean =
        readManifestDocument(backupName())?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION }
            ?: false

    // Candidate transactions compare against the persisted manifest and return
    // a stale-snapshot rejection for callers to rebase on.

    /** Manifest CAS baseline. Call only while the owning store mutex is held. */
    internal fun casBaselineManifest(): ChapterArtifactManifest? = readManifest()

    // Callers hold the owning ChapterTranslationStore facade Mutex.
    internal fun publishManifestInternal(manifest: ChapterArtifactManifest, syncToDisk: Boolean = false): Boolean {
        if (readManifestDocument(layout.manifestFileName)?.schemaVersion
                ?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT manifest publication skipped: reason=future-schema primary preserved " +
                    "chapter=${layout.chapterKey}"
            }
            return false
        }
        if (futureBackupPresent()) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT manifest publication skipped: reason=future-schema backup preserved " +
                    "chapter=${layout.chapterKey}"
            }
            return false
        }
        return documents.publishJson(layout.manifestFileName, manifest, syncToDisk = syncToDisk)
    }

    internal fun stampChapterKey(manifest: ChapterArtifactManifest): ChapterArtifactManifest =
        if (manifest.chapterKey == layout.chapterKey) {
            manifest
        } else {
            manifest.copy(chapterKey = layout.chapterKey)
        }

    internal fun backupName(): String = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
}

/** Stage sidecar lookup for one page record. */
internal fun PageArtifactRecord.stage(stage: ArtifactStage): StageArtifactRecord? = when (stage) {
    ArtifactStage.DETECTION -> detection
    ArtifactStage.OCR -> ocr
    ArtifactStage.INPAINT -> inpaint
    ArtifactStage.TRANSLATION -> translation
    ArtifactStage.LAYOUT -> layout
    ArtifactStage.COLORING -> null
}

class ChapterCandidateTransactions internal constructor(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
    private val engine: ChapterArtifactEngine,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Neutral outcome details for opening an artifact candidate. */
    sealed interface CandidateOpenState {
        data class Reused(
            val pageKey: String,
            val dependencyFingerprint: String,
        ) : CandidateOpenState

        data class FingerprintMismatch(
            val pageKey: String,
            val expectedFingerprint: String,
            val activeFingerprint: String?,
        ) : CandidateOpenState

        data class CandidateAlreadyActive(
            val pageKey: String,
            val requestedOrigin: ArtifactOrigin,
            val activeOrigin: ArtifactOrigin,
        ) : CandidateOpenState
    }

    /** Persists one immutable live candidate snapshot and advances its pointer. */
    fun persistLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        durableFailure: DurableFailureMetadata? = null,
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        val fileName = page.candidate?.pageSnapshotFileName
            ?: layout.candidatePageSnapshotFile(pageKey, generationId)
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
        if (!documents.publishJson(fileName, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
        }
        val failureKey = durableFailure?.let { "${it.pageKey}:${it.stage.name}" }
        val failureRecord = durableFailure?.let { failure ->
            StageArtifactRecord(
                status = failure.status,
                fingerprint = expectedDependencyFingerprint,
                origin = origin,
                // Durable failure metadata is page-level retry state, not candidate work product.
                generationId = null,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        val updatedPage = page.copy(
            source = sourceIdentity ?: page.source,
            candidate = page.candidate.copy(
                pageSnapshotFileName = fileName,
                pageSnapshotFingerprint = pageSnapshotFingerprint,
            ),
            pageVersion = page.pageVersion + 1,
        ).let { candidatePage ->
            if (durableFailure == null) candidatePage else candidatePage.withStage(durableFailure.stage, failureRecord)
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to updatedPage
                ),
            durableFailures = if (failureKey == null) {
                manifest.durableFailures
            } else {
                manifest.durableFailures + (failureKey to durableFailure)
            },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; candidate snapshot pointer unchanged"
            return TransactionOutcome.Rejected(reason)
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Candidate snapshot plus retry/terminal metadata in one manifest
     * publication. The immutable candidate sidecar is written first, then a
     * single manifest pointer update makes both pieces visible together.
     */
    fun persistLiveCandidateAndFailure(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        failure: DurableFailureMetadata,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = persistLiveCandidate(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        durableFailure = failure,
    )

    /** Commits the live candidate and atomically moves the committed pointer. */
    fun promoteLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = true,
    )

    /**
     * Fuses the candidate snapshot and terminal promotion into one recoverable publication.
     * Used only when the store already knows this mutation is immediately promotable. The
     * transaction still publishes committed snapshot, generation record, and final manifest in
     * that order; the existing two-step candidate protocol remains for in-progress mutations.
     * If terminal publication fails, it restores the candidate snapshot and pointer so reopening
     * retains the same recoverable work as the two-step path.
     */
    fun promoteLiveCandidateImmediately(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = false,
    )

    private fun promoteLiveCandidateTransaction(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
        persistCandidateSnapshot: Boolean,
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        val resolvedPage = page.copy(source = sourceIdentity ?: page.source)
        val candidate = page.candidate
            ?: return TransactionOutcome.Rejected("candidate missing: pageKey=$pageKey")
        if (candidate.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${candidate.origin} actual=$origin",
            )
        }
        pageSnapshot.cleanedImageName?.let { cleanedName ->
            if (!ChapterArtifactLayout.isSafeSegment(cleanedName)) {
                return TransactionOutcome.Rejected("unsafe cleaned display file name: pageKey=$pageKey")
            }
        }
        if (persistCandidateSnapshot) {
            val candidateFile = candidate.pageSnapshotFileName
                ?: layout.candidatePageSnapshotFile(pageKey, generationId)
            val candidateMatches = documents.readValidated<PageTranslation>(candidateFile)?.let {
                it == pageSnapshot
            } == true
            if (!candidateMatches && !documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
                return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
            }
        }
        val committedFile = layout.committedPageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(committedFile, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
        val displayBase = DisplayBaseReference(
            kind = if (pageSnapshot.cleanedImageName != null) DisplayBaseKind.CLEANED_IMAGE else DisplayBaseKind.ORIGINAL_SOURCE,
            fileName = pageSnapshot.cleanedImageName,
            validated = pageSnapshot.cleanedImageName != null,
            legacyLayout = false,
        )
        val committed = CommittedBundleMetadata(
            generationId = generationId,
            bundleFingerprint = StageFingerprints.committedBundle(
                sourceIdentity = resolvedPage.source,
                displayBase = displayBase,
                translationFingerprint = pageSnapshotFingerprint,
                layoutFingerprint = null,
            ),
            displayBase = displayBase,
            translationFingerprint = pageSnapshotFingerprint,
            origin = origin,
            provisional = false,
            hasManualEdits = pageSnapshot.blocks.any { it.userEditedAt != null },
            promotedAtEpochMs = nowEpochMs,
            pageSnapshotFileName = committedFile,
        )
        val promotedTranslation = resolvedPage.translation?.copy(
            status = if (pageSnapshot.isTextlessTerminal) ArtifactStageStatus.TEXTLESS else ArtifactStageStatus.READY,
            fingerprint = pageSnapshotFingerprint,
            origin = origin,
            generationId = null,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        ) ?: StageArtifactRecord(
            status = if (pageSnapshot.isTextlessTerminal) ArtifactStageStatus.TEXTLESS else ArtifactStageStatus.READY,
            fingerprint = pageSnapshotFingerprint,
            origin = origin,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    source = resolvedPage.source,
                    committed = committed,
                    previousCommitted = page.committed,
                    candidate = null,
                    displayState = if (pageSnapshot.isTextlessTerminal) {
                        PageDisplayState.TEXTLESS_COMPLETE
                    } else {
                        PageDisplayState.DISPLAY_READY
                    },
                    translation = promotedTranslation,
                    pageVersion = page.pageVersion + if (persistCandidateSnapshot) 1 else 2,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /** Cancels a live candidate while retaining the committed pointer. */
    fun cancelLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = cancelCandidate(manifest, pageKey, generationId, nowEpochMs)

    /** Explicit user reset: remove committed/candidate pointers durably. */
    fun demoteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        val candidateGenerationId = page.candidate?.generationId

        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = null,
                    previousCommitted = null,
                    candidate = null,
                    displayState = PageDisplayState.ORIGINAL_ONLY,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = candidateGenerationId?.let { manifest.activeCandidateGenerationIds - it }
                ?: manifest.activeCandidateGenerationIds,
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        // A user reset uses checked publication so backup recovery cannot
        // resurrect the removed committed/candidate pointers.
        return when (val receipt = engine.publishDestructiveManifest(updated)) {
            is DestructiveManifestReceipt.Complete ->
                TransactionOutcome.Committed(updated, candidateGenerationId)
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete ->
                TransactionOutcome.Committed(
                    updated,
                    candidateGenerationId,
                    unverifiedBackupReason = receipt.reason,
                )
            is DestructiveManifestReceipt.NotPublished -> TransactionOutcome.Rejected(
                "manifest publication failed; reset pointers remain authoritative",
            )
        }
    }

    /** Removes one page from the artifact-authoritative live manifest. */
    fun deleteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        if (pageKey !in manifest.pages) return TransactionOutcome.Committed(manifest)
        val updated = manifest.copy(
            pages = manifest.pages - pageKey,
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds -
                manifest.pages[pageKey]?.candidate?.generationId.orEmpty(),
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        // Page removal is destructive and uses checked manifest publication.
        return when (val receipt = engine.publishDestructiveManifest(updated)) {
            is DestructiveManifestReceipt.Complete ->
                TransactionOutcome.Committed(updated)
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete ->
                TransactionOutcome.Committed(updated, unverifiedBackupReason = receipt.reason)
            is DestructiveManifestReceipt.NotPublished -> TransactionOutcome.Rejected(
                "manifest publication failed; page remains authoritative: pageKey=$pageKey",
            )
        }
    }

    /** Opens or idempotently reopens a candidate generation. */
    fun openCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: PageArtifactRecord(pageKey = pageKey)
        if (page.pageVersion != expectedPageVersion) {
            return TransactionOutcome.Rejected(
                "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}",
            )
        }
        val existing = page.candidate
        var candidateOpenState: CandidateOpenState? = null
        val generationId = when {
            existing != null &&
                existing.origin == origin &&
                existing.origin != ArtifactOrigin.LEGACY &&
                existing.dependencyFingerprint == dependencyFingerprint -> {
                candidateOpenState = CandidateOpenState.Reused(
                    pageKey = pageKey,
                    dependencyFingerprint = dependencyFingerprint,
                )
                existing.generationId
            }
            existing != null && existing.origin == ArtifactOrigin.LEGACY -> engine.newGenerationId(pageKey, nowEpochMs)
            existing != null -> {
                candidateOpenState = if (existing.origin == origin) {
                    CandidateOpenState.FingerprintMismatch(
                        pageKey = pageKey,
                        expectedFingerprint = dependencyFingerprint,
                        activeFingerprint = existing.dependencyFingerprint,
                    )
                } else {
                    CandidateOpenState.CandidateAlreadyActive(
                        pageKey = pageKey,
                        requestedOrigin = origin,
                        activeOrigin = existing.origin,
                    )
                }
                return TransactionOutcome.Rejected(
                    "candidate already active with a different dependency fingerprint: pageKey=$pageKey",
                    candidateOpenState = candidateOpenState,
                )
            }
            else -> engine.newGenerationId(pageKey, nowEpochMs)
        }
        val candidate = CandidateGenerationMetadata(
            generationId = generationId,
            origin = origin,
            dependencyFingerprint = dependencyFingerprint,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            priorDisplayState = existing?.priorDisplayState ?: page.displayState,
        )

        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    candidate = candidate,
                    displayState = if (page.committed != null) {
                        PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
                    } else {
                        PageDisplayState.CANDIDATE_RUNNING
                    },
                    pageVersion = page.pageVersion + if (existing == null || existing.origin == ArtifactOrigin.LEGACY) 1L else 0L,
                )
                ),
            activeCandidateGenerationIds = (
                manifest.activeCandidateGenerationIds -
                    existing?.generationId.orEmpty()
                ) + generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; prior manifest remains authoritative"
            return TransactionOutcome.Rejected(
                reason,
                candidateOpenState = candidateOpenState,
            )
        }
        return TransactionOutcome.Committed(
            updated,
            generationId,
            candidateOpenState = candidateOpenState,
        )
    }

    /**
     * Cancels a candidate: removes only candidate-owned metadata/files, keeps
     * the committed bundle (and its retained previous generation) untouched
     * (lifecycle contract §13). Files are reclaimed exclusively through store
     * reachability after the manifest update — never by name pattern alone.
     */
    fun cancelCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        if (page.candidate?.generationId != generationId) {
            return TransactionOutcome.Rejected(
                "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}",
            )
        }
        fun isCandidateOwned(record: StageArtifactRecord?): Boolean =
            record != null && record.generationId == generationId

        val candidateFailureKeys = ArtifactStage.entries
            .filter { stage -> isCandidateOwned(page.stage(stage)) }
            .map { stage -> "$pageKey:${stage.name}" }

        val cleaned = page.copy(
            detection = page.detection.takeUnless(::isCandidateOwned),
            ocr = page.ocr.takeUnless(::isCandidateOwned),
            inpaint = page.inpaint.takeUnless(::isCandidateOwned),
            translation = page.translation.takeUnless(::isCandidateOwned),
            layout = page.layout.takeUnless(::isCandidateOwned),
            candidate = null,
            displayState = when {
                page.candidate?.priorDisplayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.displayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.committed != null -> PageDisplayState.DISPLAY_READY
                else -> PageDisplayState.ORIGINAL_ONLY
            },
            pageVersion = page.pageVersion + 1,
        )

        val updated = manifest.copy(
            pages = manifest.pages + (pageKey to cleaned),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - candidateFailureKeys.toSet(),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; candidate remains recorded"
            return TransactionOutcome.Rejected(reason)
        }
        // Delete only the known files unlinked by this cancellation; a full
        // SAF sweep per page made relaunch slow on large chapters. Cross-page
        // orphans wait for the deferred event-driven retention sweep.
        val orphans = buildList {
            listOf(page.detection, page.ocr, page.inpaint, page.translation, page.layout)
                .filter { it?.generationId == generationId }
                .forEach { it?.artifactFileName?.let(::add) }
        }
        val retention = engine.deleteKnownOrphans(orphans, durableManifest = updated)
        return TransactionOutcome.Committed(updated, generationId, retention.deletedNames)
    }

    private fun candidateWriteRejection(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
    ): String? {
        engine.staleManifestRejection(manifest)?.let { return it }
        val page = manifest.pages[pageKey] ?: return "page missing: pageKey=$pageKey"
        if (page.candidate?.generationId != generationId) {
            return "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}"
        }
        if (page.pageVersion != expectedPageVersion) {
            return "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}"
        }
        if (page.candidate?.dependencyFingerprint != expectedDependencyFingerprint) {
            return "dependency fingerprint changed: pageKey=$pageKey"
        }
        return null
    }

    /**
     * Process-death recovery (lifecycle contract §13): a stage persisted as
     * RUNNING without a complete commit marker becomes retryable. Committed
     * pointers are never touched.
     */
    internal fun recoverInterruptedStages(manifest: ChapterArtifactManifest): ChapterArtifactManifest {
        var changed = false
        val now = System.currentTimeMillis()
        val durableFailures = manifest.durableFailures.toMutableMap()
        val pages = manifest.pages.mapValues { (pageKey, page) ->
            var updated = page
            ArtifactStage.entries.forEach { stage ->
                val record = updated.stage(stage)
                if (record?.status == ArtifactStageStatus.RUNNING) {
                    changed = true
                    updated = updated.withStage(
                        stage,
                        record.copy(
                            status = ArtifactStageStatus.FAILED_RETRYABLE,
                            updatedAtEpochMs = now,
                        ),
                    )
                    durableFailures["$pageKey:${stage.name}"] = DurableFailureMetadata(
                        pageKey = pageKey,
                        stage = stage,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.LEGACY_UNKNOWN,
                        retryCount = 0,
                        lastFailureMessage = "process interrupted",
                        lastFailedAtEpochMs = now,
                        nextEligibleRetryAtEpochMs = now,
                        failureFingerprint = record.fingerprint,
                    )
                }
            }
            if (updated !== page) updated.copy(pageVersion = page.pageVersion + 1) else updated
        }
        if (!changed) return manifest
        val recovered = manifest.copy(
            pages = pages,
            durableFailures = durableFailures,
            updatedAtEpochMs = now,
        )
        if (!engine.publishManifestInternal(recovered)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT interrupted-stage recovery publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return manifest
        }
        return recovered
    }

    private fun PageArtifactRecord.withStage(
        stage: ArtifactStage,
        record: StageArtifactRecord?,
    ): PageArtifactRecord = when (stage) {
        ArtifactStage.DETECTION -> copy(detection = record)
        ArtifactStage.OCR -> copy(ocr = record)
        ArtifactStage.INPAINT -> copy(inpaint = record)
        ArtifactStage.TRANSLATION -> copy(translation = record)
        ArtifactStage.LAYOUT -> copy(layout = record)
        ArtifactStage.COLORING -> error("COLORING has no manifest record slot")
    }

    /**
     * Process-death candidate sweep (lifecycle contract §13): a candidate
     * opened before this process started can have no live owner, and leaving
     * its marker projects the chapter as in-flight forever. Committed bundles
     * are untouched; candidate-owned stage records drop with the marker.
     */
    internal fun closeOrphanedCandidates(
        manifest: ChapterArtifactManifest,
        processStartEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ChapterArtifactManifest {
        var changed = false
        val closedGenerationIds = mutableSetOf<String>()
        val pages = manifest.pages.mapValues { (_, page) ->
            val candidate = page.candidate
            if (candidate == null || candidate.createdAtEpochMs >= processStartEpochMs) return@mapValues page
            changed = true
            closedGenerationIds += candidate.generationId
            var updated = page.copy(candidate = null)
            ArtifactStage.entries.forEach { stage ->
                val record = updated.stage(stage)
                if (record?.generationId == candidate.generationId) {
                    updated = updated.withStage(stage, null)
                }
            }
            updated.copy(
                displayState = when {
                    updated.committed != null -> updated.displayState
                    updated.displayState == PageDisplayState.CANDIDATE_RUNNING ->
                        candidate.priorDisplayState
                            ?.takeIf { it != PageDisplayState.CANDIDATE_RUNNING }
                            ?: PageDisplayState.ORIGINAL_ONLY
                    else -> updated.displayState
                },
                pageVersion = updated.pageVersion + 1,
            )
        }
        if (!changed) return manifest
        val reconciled = manifest.copy(
            pages = pages,
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - closedGenerationIds,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(reconciled)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT orphaned-candidate sweep publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return manifest
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT closed ${closedGenerationIds.size} orphaned candidate(s) from a dead run: " +
                "chapter=${layout.chapterKey}"
        }
        return reconciled
    }
}

/**
 * Sidecar re-keying operations extracted from [ChapterArtifactEngine] to stay within file size bounds.
 */
internal fun ChapterArtifactEngine.preparePageSnapshotRekey(
    manifest: ChapterArtifactManifest,
    moves: Map<String, String>,
    livePages: Map<String, PublishedPageTranslation>,
    operationId: String,
): ChapterArtifactEngine.PreparedPageSnapshotRekey? {
    if (moves.isEmpty()) return ChapterArtifactEngine.PreparedPageSnapshotRekey(manifest.pages, emptyMap())
    val movedOldKeys = moves.keys
    val movedNewKeys = moves.values.toSet()
    // Do not merge into an artifact record that is outside this re-key
    // operation. The store's in-memory collision check cannot see dormant
    // manifest entries left by a prior partial lifecycle.
    if (movedNewKeys.any { it in manifest.pages && it !in movedOldKeys }) return null

    val pages = LinkedHashMap<String, PageArtifactRecord>()
    val journalMutations = LinkedHashMap<String, ChapterArtifactEngine.PreparedRekeyJournalMutation>()
    manifest.pages.forEach { (oldKey, record) ->
        val newKey = moves[oldKey] ?: oldKey
        if (newKey in pages) return null
        val isMoved = oldKey in moves
        var candidate = record.candidate
        var committed = record.committed
        var previousCommitted = record.previousCommitted

        if (isMoved) {
            if (candidate != null) {
                val live = livePages[oldKey] ?: return null
                if (live.sourceFileName != oldKey) return null
                val candidateSnapshot = live.toDraft().apply { sourceFileName = newKey }
                val published = publishRekeySnapshot(
                    snapshot = candidateSnapshot,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "candidate",
                    generationId = candidate.generationId,
                    operationId = operationId,
                ) ?: return null
                candidate = candidate.copy(
                    pageSnapshotFileName = published.fileName,
                    pageSnapshotFingerprint = published.fingerprint,
                )
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = published.snapshot.toPublishedPage(),
                    artifactContentHash = published.fingerprint,
                )
            }

            committed = committed?.let { bundle ->
                rekeyCommittedSnapshot(
                    bundle = bundle,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "committed",
                    operationId = operationId,
                    source = record.source?.copy(pageKey = newKey),
                ) ?: return null
            }
            previousCommitted = previousCommitted?.let { bundle ->
                rekeyCommittedSnapshot(
                    bundle = bundle,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "previous",
                    operationId = operationId,
                    source = record.source?.copy(pageKey = newKey),
                ) ?: return null
            }

            // A page with no active candidate remains on its committed
            // role. Do not synthesize a candidate or treat a pointerless
            // legacy record as a verifiable completed page.
            if (candidate == null && committed?.pageSnapshotFileName != null) {
                val committedBundle = checkNotNull(committed)
                val snapshot = readPageSnapshot(committedBundle.pageSnapshotFileName) ?: return null
                if (snapshot.sourceFileName != newKey) return null
                val fingerprint = StageFingerprints.pageSnapshot(snapshot)
                val committedFingerprint = committedBundle.translationFingerprint
                if (committedFingerprint != null && committedFingerprint != fingerprint) return null
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = snapshot.toPublishedPage(),
                    artifactContentHash = fingerprint,
                )
            }

            if (newKey !in journalMutations) {
                val live = livePages[oldKey] ?: return null
                if (live.sourceFileName != oldKey) return null
                val retryableState = live.toDraft().apply { sourceFileName = newKey }
                // Preserve inventory and the moved page's current logical state, but a
                // pointerless manifest has no artifact proof. The reducer consumes this
                // record as an invalid-page marker until a later verified publication.
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = retryableState.toPublishedPage(),
                    artifactContentHash = null,
                )
            }
        }

        pages[newKey] = record.copy(
            pageKey = newKey,
            pageVersion = record.pageVersion + if (isMoved) 1L else 0L,
            source = record.source?.let { source -> if (isMoved) source.copy(pageKey = newKey) else source },
            candidate = candidate,
            committed = committed,
            previousCommitted = previousCommitted,
        )
    }
    // Moves without a manifest page entry still need an explicit retryable mutation when
    // this transaction is published for another moved page in the same operation.
    moves.forEach { (oldKey, newKey) ->
        if (newKey !in journalMutations) {
            val live = livePages[oldKey] ?: return null
            if (live.sourceFileName != oldKey) return null
            val retryableState = live.toDraft().apply { sourceFileName = newKey }
            journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                state = retryableState.toPublishedPage(),
                artifactContentHash = null,
            )
        }
    }
    return ChapterArtifactEngine.PreparedPageSnapshotRekey(pages, journalMutations)
}

private data class RekeyedSnapshot(
    val fileName: String,
    val fingerprint: String,
    val snapshot: PageTranslation,
)

private fun ChapterArtifactEngine.rekeyCommittedSnapshot(
    bundle: CommittedBundleMetadata,
    oldKey: String,
    newKey: String,
    role: String,
    operationId: String,
    source: SourceIdentity?,
): CommittedBundleMetadata? {
    val oldFileName = bundle.pageSnapshotFileName
    if (oldFileName == null || bundle.translationFingerprint == null) {
        // A pointer or claimed digest without the paired semantic proof is
        // not sufficient to authenticate a completed re-key destination.
        // Keep the committed role metadata, but leave its snapshot retryable.
        val bundleFingerprint = bundle.bundleFingerprint?.let {
            StageFingerprints.committedBundle(
                sourceIdentity = source,
                displayBase = bundle.displayBase,
                translationFingerprint = null,
                layoutFingerprint = bundle.layoutFingerprint,
            )
        }
        return bundle.copy(
            bundleFingerprint = bundleFingerprint,
            translationFingerprint = null,
            pageSnapshotFileName = null,
        )
    }
    val original = readPageSnapshot(oldFileName) ?: return null
    if (original.sourceFileName != oldKey) return null
    val originalFingerprint = StageFingerprints.pageSnapshot(original)
    if (bundle.translationFingerprint != originalFingerprint) return null
    val rewritten = original.detachedCopy().apply { sourceFileName = newKey }
    val published = publishRekeySnapshot(
        snapshot = rewritten,
        oldKey = oldKey,
        newKey = newKey,
        role = role,
        generationId = bundle.generationId,
        operationId = operationId,
    ) ?: return null
    val updatedBundleFingerprint = bundle.bundleFingerprint?.let {
        StageFingerprints.committedBundle(
            sourceIdentity = source,
            displayBase = bundle.displayBase,
            translationFingerprint = published.fingerprint,
            layoutFingerprint = bundle.layoutFingerprint,
        )
    }
    return bundle.copy(
        bundleFingerprint = updatedBundleFingerprint,
        translationFingerprint = published.fingerprint,
        pageSnapshotFileName = published.fileName,
    )
}

private fun ChapterArtifactEngine.publishRekeySnapshot(
    snapshot: PageTranslation,
    oldKey: String,
    newKey: String,
    role: String,
    generationId: String,
    operationId: String,
): RekeyedSnapshot? {
    if (snapshot.sourceFileName != newKey) return null
    val fingerprint = StageFingerprints.pageSnapshot(snapshot)
    val fileName = layout.rekeyedPageSnapshotFile(
        pageKey = newKey,
        role = role,
        generationId = generationId,
        operationId = "$operationId:$oldKey:$newKey:$role:$fingerprint",
    )
    if (!layout.isManagedPath(fileName)) return null
    if (documents.exists(fileName)) {
        // Never overwrite a path that another/stale re-key may already
        // reference. This also makes an exact retry idempotent.
        val existing = io.read(fileName)?.let { bytes ->
            runCatching { documents.json.decodeFromStream<PageTranslation>(bytes.inputStream()) }.getOrNull()
        } ?: return null
        if (existing != snapshot || StageFingerprints.pageSnapshot(existing) != fingerprint) return null
    } else {
        if (!documents.publishJson(fileName, snapshot.detachedCopy())) return null
        val readBack = readPageSnapshot(fileName) ?: return null
        if (readBack != snapshot || StageFingerprints.pageSnapshot(readBack) != fingerprint) return null
    }
    return RekeyedSnapshot(fileName, fingerprint, snapshot.detachedCopy())
}

/**
 * Performs bounded retention work over chapter documents and artifact paths.
 * It is stateless; locking stays at the
 * [ChapterArtifactEngine.reconcileRetention] entry point.
 */
internal class ArtifactRetention(
    private val io: ChapterDocumentIo,
    private val layout: ChapterArtifactLayout,
) {

    internal fun reconcileRetention(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult = deleteVerifiedOrphans(
        collectOrphanCandidates(manifest, stagedReachable),
        manifest,
        stagedReachable,
    )

    /**
     * Enumerates deletion candidates without mutating anything. It is pure
     * over its inputs and takes no store monitor, so the potentially
     * minutes-long SAF crawl does not block page leases behind the store mutex.
     * The parent listing proves a child exists, so no per-file `io.exists`
     * probe is paid during the crawl; deletion re-verifies each candidate.
     */
    internal fun collectOrphanCandidates(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): Set<String> {
        val reachable = reachablePaths(manifest)
        val retainedImageGenerations = retainedImageGenerations(manifest)
        val candidates = mutableSetOf<String>()

        // Orphan temp sibling of the manifest itself (outside the managed tree).
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (manifestTemp !in stagedReachable) candidates += manifestTemp

        layout.managedDirectories.forEach { root ->
            collectDirectory(root, reachable, retainedImageGenerations, stagedReachable, candidates)
        }
        return candidates
    }

    /**
     * Recurses [directory]; returns true when every entry under it is itself a
     * candidate (a fully reclaimable directory, matching the old
     * became-empty rule). Managed roots are never candidates themselves.
     */
    private fun collectDirectory(
        directory: String,
        reachable: Set<String>,
        retainedImageGenerations: Set<String>,
        stagedReachable: Set<String>,
        candidates: MutableSet<String>,
    ): Boolean {
        val children = io.list(directory) ?: return false
        var allReclaimable = true
        children.forEach { child ->
            val path = "$directory/$child"
            val reclaimable = if (io.list(path) != null) {
                // Directory: reclaimable only when everything under it is.
                if (collectDirectory(path, reachable, retainedImageGenerations, stagedReachable, candidates) &&
                    path !in layout.managedDirectories
                ) {
                    candidates += path
                    true
                } else {
                    false
                }
            } else {
                // File — existence is proven by the parent listing.
                val orphan = path !in stagedReachable &&
                    !isRetained(path, reachable, retainedImageGenerations) &&
                    layout.isManagedPath(path)
                if (orphan) candidates += path
                orphan
            }
            if (!reclaimable) allReclaimable = false
        }
        return allReclaimable
    }

    /**
     * Deletes collected candidates after re-verifying each against
     * [manifest] — the LIVE manifest when called under the store mutex — so a
     * file a concurrent publish made reachable mid-crawl is spared (the sweep
     * ran off-lock by design). Deleting the candidates takes bounded IO: only
     * genuinely orphaned files pay a binder round-trip here.
     */
    internal fun deleteVerifiedOrphans(
        candidateOrphans: Collection<String>,
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult {
        val deleted = mutableListOf<String>()
        val reachable = reachablePaths(manifest)
        val retainedImageGenerations = retainedImageGenerations(manifest)
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (
            manifestTemp in candidateOrphans &&
            manifestTemp !in stagedReachable &&
            io.exists(manifestTemp) &&
            io.delete(manifestTemp)
        ) {
            deleted += manifestTemp
        }
        // Children sort before their parent directories so a reclaimable
        // directory is emptied before it is removed.
        candidateOrphans.asSequence()
            .filter { it != manifestTemp }
            .sortedByDescending { it.length }
            .forEach { path ->
                if (path in stagedReachable) return@forEach
                // Re-verification against the live reachability graph.
                if (isRetained(path, reachable, retainedImageGenerations)) return@forEach
                if (!layout.isManagedPath(path)) return@forEach
                if (pathIsDirectory(path)) {
                    // Recursive deletes must not consume a child a concurrent
                    // publish made reachable: only remove a verified-empty dir.
                    if (io.list(path).isNullOrEmpty() && io.delete(path)) deleted += path
                } else if (io.exists(path) && io.delete(path)) {
                    deleted += path
                }
            }
        return RetentionResult(deleted.size, deleted)
    }

    private fun pathIsDirectory(path: String): Boolean = io.list(path) != null

    /**
     * Event-driven deletion of known orphans.
     * Deletes explicitly known orphans (e.g. unlinked generation records or candidate
     * snapshots) without executing a full reachability crawl over the filesystem.
     * A file must be unreachable from both durable and staged state.
     *
     * When [durableManifest] is supplied (hot-path callers MUST supply it), every
     * candidate is additionally filtered through the full sweep's reachability
     * predicate, so the manifest-unreachability half of race register #6 is
     * enforced here instead of relying on caller discipline.
     */
    internal fun deleteKnownOrphans(
        candidateOrphans: Collection<String>,
        stagedReachable: Set<String> = emptySet(),
        durableManifest: ChapterArtifactManifest? = null,
    ): RetentionResult {
        val deleted = mutableListOf<String>()
        val reachable = durableManifest?.let { reachablePaths(it) } ?: emptySet()
        val retainedGenerations = durableManifest?.let { retainedImageGenerations(it) } ?: emptySet()
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (manifestTemp !in stagedReachable && io.exists(manifestTemp) && io.delete(manifestTemp)) {
            deleted += manifestTemp
        }
        for (path in candidateOrphans) {
            if (path in stagedReachable) continue
            if (durableManifest != null && isRetained(path, reachable, retainedGenerations)) continue
            if (layout.isManagedPath(path) && io.exists(path) && io.delete(path)) {
                deleted += path
            }
        }
        return RetentionResult(deleted.size, deleted)
    }

    private fun isRetained(
        path: String,
        reachable: Set<String>,
        retainedImageGenerations: Set<String>,
    ): Boolean {
        if (path in reachable) return true
        if (path.endsWith(".tmp")) return false
        // Backups of reachable files survive one sweep.
        if (reachable.any { reachablePath -> path == "$reachablePath.bak" }) return true
        // 17: a corrupt sidecar's quarantined `.corrupt` bytes are
        // reclaimed only when no manifest pointer references the sidecar.
        if (reachable.any { reachablePath -> path == "$reachablePath.corrupt" || path.startsWith("$reachablePath.corrupt.") }) {
            return true
        }
        if (!path.startsWith("${layout.imagesRootDirectory}/")) return false
        val fileName = path.removeSuffix(".bak").substringAfterLast('/')
        return retainedImageGenerations.any { generationSegment -> fileName.startsWith("$generationSegment-") }
    }

    /**
     * Image files under `images/<pageSegment>/` embed their generation id as
     * `<generationId>-<fingerprint>.<ext>`. A file is retained while its
     * generation belongs to the manifest/generation graph: a committed,
     * one-previous, or candidate bundle of any page, or any chapter-active
     * candidate generation. This bounds retention to exactly those
     * generations' files.
     */
    private fun retainedImageGenerations(manifest: ChapterArtifactManifest): Set<String> = buildSet {
        manifest.pages.values.forEach { page ->
            page.committed?.let { add(layout.generationSegment(it.generationId)) }
            page.previousCommitted?.let { add(layout.generationSegment(it.generationId)) }
            page.candidate?.let { add(layout.generationSegment(it.generationId)) }
        }
        manifest.activeCandidateGenerationIds.forEach { add(layout.generationSegment(it)) }
    }

    private fun reachablePaths(manifest: ChapterArtifactManifest): Set<String> = buildSet {
        manifest.pages.values.forEach { page ->
            listOfNotNull(page.committed, page.previousCommitted).forEach { bundle ->
                bundle.pageSnapshotFileName?.let(::add)
                bundle.displayBase.fileName?.let(::add)
            }
            page.candidate?.let {
                it.pageSnapshotFileName?.let(::add)
            }
            listOf(
                page.detection,
                page.ocr,
                page.inpaint,
                page.translation,
                page.layout,
            ).forEach { stage ->
                stage?.artifactFileName?.let(::add)
            }
        }
        // Every manifest pointer keeps its sidecar reachable. Sidecars with no
        // remaining pointer are orphans and are reclaimed here.
    }
}

data class RetentionResult(
    val deletedCount: Int,
    val deletedNames: List<String>,
)
