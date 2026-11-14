package eu.kanade.translation.persistence

import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.toDraft
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-phase page re-key transaction behind [ChapterBulkMutator.rekeyPages]:
 * plan and validate under the store mutex, prepare the artifact snapshot
 * off-lock, then re-validate and durably publish under the store mutex.
 * Same lock order, staleness checks, and journal-frame semantics as before
 * the extraction; [store] is the same owning store instance.
 */
internal class ChapterPageRekeyMutator(private val store: ChapterTranslationStore) {

    private data class PageRekeyPlan(
        val generation: Long,
        val pages: PersistentMap<String, PublishedPageTranslation>,
        val committedDisplay: PersistentMap<String, ChapterTranslationStore.CommittedPageDisplay>,
        val manifest: ChapterArtifactManifest?,
        val moves: Map<String, String>,
        val oldArtifactContentHashes: Map<String, String?>,
        /** Published values are immutable; snapshot conversion and hashing happen off-lock. */
        val livePageSnapshots: Map<String, PublishedPageTranslation>,
        val leaseTokens: Map<String, Long?>,
        val requiresArtifactTransaction: Boolean,
        val changedDisplayKeys: Set<String>,
        /** Destination keys whose payload-less placeholder record is consumed by a move. */
        val consumedPlaceholders: Set<String>,
        /** Moves refused because the destination record carries real payload. */
        val skippedCollisions: Int,
    )

    private sealed class PageRekeyPlanResult {
        data class Ready(val plan: PageRekeyPlan) : PageRekeyPlanResult()

        data class Immediate(val outcome: ChapterTranslationStore.PageRekeyOutcome) : PageRekeyPlanResult()

        data class Rejected(val reason: String) : PageRekeyPlanResult()
    }

    /** Serializes two-phase re-key preparations without blocking ordinary page mutations. */
    private val pageRekeyMutex = Mutex()

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): ChapterTranslationStore.PageRekeyOutcome {
        if (store.defunct || onlineKeys.size != onDiskKeys.size) {
            return ChapterTranslationStore.PageRekeyOutcome.Rejected("defunct store or mapping size mismatch")
        }
        return pageRekeyMutex.withLock {
            val planResult = store.mutex.withLock { createPageRekeyPlanLocked(onlineKeys, onDiskKeys) }
            val plan = when (planResult) {
                is PageRekeyPlanResult.Ready -> planResult.plan
                is PageRekeyPlanResult.Immediate -> return@withLock planResult.outcome
                is PageRekeyPlanResult.Rejected ->
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(planResult.reason)
            }
            val artifact = store.artifactEngine
            val preparedSnapshots = if (plan.requiresArtifactTransaction) {
                withContext(store.persistenceDispatcher) {
                    artifact?.preparePageSnapshotRekey(
                        manifest = checkNotNull(plan.manifest),
                        moves = plan.moves,
                        livePages = plan.livePageSnapshots,
                        operationId = UUID.randomUUID().toString(),
                    )
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store rekeyPages rejected: page snapshot preparation failed"
                    }
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                        "page snapshot preparation failed",
                    )
                }
            } else {
                null
            }
            val preparedManifest = if (plan.requiresArtifactTransaction) {
                val prepared = checkNotNull(preparedSnapshots)
                rekeyedArtifactManifest(
                    base = checkNotNull(plan.manifest),
                    prepared = prepared,
                    moves = plan.moves,
                ) ?: return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                    "re-keyed artifact manifest could not be derived",
                )
            } else {
                null
            }

            store.mutex.withLock {
                if (!isCurrentPageRekeyPlanLocked(plan)) {
                    return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                        "store changed during re-key preparation",
                    )
                }
                if (plan.requiresArtifactTransaction) {
                    val durableManifest = try {
                        checkNotNull(artifact).readManifest()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                            "durable manifest read failed before re-key capture: ${failure.message}",
                            failure,
                        )
                    }
                    if (durableManifest != plan.manifest) {
                        if (durableManifest != null) store.artifactManifest = durableManifest
                        return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                            "durable manifest changed during re-key preparation",
                        )
                    }
                }

                val moveByOldKey = plan.moves
                val moveByNewKey = moveByOldKey.entries.associate { (oldKey, newKey) -> newKey to oldKey }
                val previousPages = store.pages
                var updatedPages = persistentMapOf<String, PublishedPageTranslation>()
                var updatedCommitted = persistentMapOf<String, ChapterTranslationStore.CommittedPageDisplay>()

                previousPages.forEach { (oldKey, page) ->
                    when {
                        moveByOldKey.containsKey(oldKey) -> {
                            val newKey = checkNotNull(moveByOldKey[oldKey])
                            val moved = store.ownedPage(newKey, page.toDraft().copy(sourceFileName = newKey))
                            updatedPages = updatedPages.put(newKey, store.publishPage(moved))
                            store.committedDisplay[oldKey]?.let { committed ->
                                val committedPage = committed.page.toDraft().apply {
                                    sourceFileName = newKey
                                }
                                updatedCommitted = updatedCommitted.put(
                                    newKey,
                                    committed.copy(
                                        page = store.publishPage(committedPage),
                                    ),
                                )
                            }
                        }
                        plan.consumedPlaceholders.contains(oldKey) -> Unit
                        else -> {
                            val updated = page.toDraft()
                            updatedPages = updatedPages.put(
                                oldKey,
                                store.publishPage(store.ownedPage(oldKey, updated)),
                            )
                            store.committedDisplay[oldKey]?.let { committed ->
                                updatedCommitted = updatedCommitted.put(oldKey, committed)
                            }
                        }
                    }
                }

                val pendingAfterRekey = store.pendingArtifactPageRegistrations
                    .mapTo(linkedSetOf()) { pageKey -> moveByOldKey[pageKey] ?: pageKey }

                if (plan.requiresArtifactTransaction) {
                    val base = checkNotNull(plan.manifest)
                    val next = checkNotNull(preparedManifest)
                    when (
                        val outcome = checkNotNull(artifact).publishSidecarPointers(
                            manifest = base,
                            sidecars = emptyList(),
                            updatePointers = { current ->
                                check(current == base) { "re-key manifest base changed after stale check" }
                                next
                            },
                        )
                    ) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            store.artifactManifest = outcome.manifest
                            store.afterRekeyManifestPublicationBeforeLiveSwap?.invoke()
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                            store.publicationBridge.rebaseManifestAfterStaleRejection(
                                checkNotNull(artifact),
                                outcome.reason,
                            )
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT artifact page re-key publication rejected: ${outcome.reason}"
                            }
                            return@withLock ChapterTranslationStore.PageRekeyOutcome.Rejected(
                                "artifact page re-key publication rejected: ${outcome.reason}",
                            )
                        }
                    }
                }

                store.pages = updatedPages
                store.committedDisplay = updatedCommitted
                store.pendingArtifactPageRegistrations.clear()
                store.pendingArtifactPageRegistrations.addAll(pendingAfterRekey)
                store.retiredCleanedImages.rekeyRetired(moveByOldKey, updatedPages.keys)
                store.retiredColoredImages.rekeyRetired(moveByOldKey, updatedPages.keys)
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(plan.changedDisplayKeys)
                if (plan.moves.isEmpty()) {
                    if (plan.skippedCollisions > 0) {
                        ChapterTranslationStore.PageRekeyOutcome.Moved(emptyList(), plan.skippedCollisions)
                    } else {
                        ChapterTranslationStore.PageRekeyOutcome.Noop
                    }
                } else {
                    ChapterTranslationStore.PageRekeyOutcome.Moved(
                        plan.moves.entries.map { it.key to it.value },
                        plan.skippedCollisions,
                    )
                }
            }
        }
    }

    /** Caller holds [store.mutex]; suspends on lazy first-write artifact admission. */
    private suspend fun createPageRekeyPlanLocked(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyPlanResult {
        when (val admission = store.publicationBridge.admitMutation()) {
            MutationAdmission.Granted -> Unit
            is MutationAdmission.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store rekeyPages rejected: code=${admission.code} reason=${admission.message}"
                }
                return PageRekeyPlanResult.Rejected(
                    "re-key mutation admission refused: ${admission.message}",
                )
            }
        }
        // The store may hold a SUBSET of the chapter's pages (partial reader
        // session); mapping is per-key, so only the owned keys are planned.
        if (store.pages.isEmpty() || store.pages.keys.all { it in onDiskKeys }) {
            return PageRekeyPlanResult.Immediate(ChapterTranslationStore.PageRekeyOutcome.Noop)
        }

        val moves = LinkedHashMap<String, String>()
        val consumedPlaceholders = LinkedHashSet<String>()
        var skippedCollisions = 0
        store.pages.keys.forEach { oldKey ->
            val index = onlineKeys.indexOf(oldKey)
            if (index < 0) return@forEach
            val newKey = onDiskKeys[index]
            if (newKey == oldKey || newKey in moves.values) return@forEach
            if (newKey in store.pages) {
                // Adopt the destination only when it is a payload-less
                // pre-registration placeholder; a real record is a collision.
                if (!isPayloadlessPlaceholderLocked(newKey)) {
                    skippedCollisions++
                    return@forEach
                }
                consumedPlaceholders += newKey
            }
            moves[oldKey] = newKey
        }
        if (moves.isEmpty()) {
            return PageRekeyPlanResult.Immediate(
                if (skippedCollisions > 0) {
                    ChapterTranslationStore.PageRekeyOutcome.Moved(emptyList(), skippedCollisions)
                } else {
                    ChapterTranslationStore.PageRekeyOutcome.Noop
                },
            )
        }
        if (moves.values.distinct().size != moves.size) {
            return PageRekeyPlanResult.Rejected("re-key plan maps multiple pages to one destination")
        }

        val manifest = store.artifactManifest
        val requiresArtifactTransaction = store.artifactEngine != null &&
            manifest != null &&
            moves.keys.any { oldKey ->
                oldKey in manifest.pages ||
                    manifest.durableFailures.values.any { it.pageKey == oldKey }
            }
        val oldArtifactContentHashes = moves.keys.associateWith { oldKey ->
            val record = manifest?.pages?.get(oldKey)
            // Never derive a fingerprint under the store mutex. A missing artifact fingerprint
            // stays absent and makes the corresponding replay mutation retryable.
            record?.candidate?.pageSnapshotFingerprint ?: record?.committed?.translationFingerprint
        }
        val livePageSnapshots = moves.keys
            .mapNotNull { oldKey -> store.pages[oldKey]?.let { oldKey to it } }
            .toMap()
        if (livePageSnapshots.size != moves.size) {
            return PageRekeyPlanResult.Rejected("re-key source page snapshot missing")
        }
        val leaseTokens = synchronized(store.pageLeases) {
            moves.keys.associateWith { oldKey -> store.pageLeases[oldKey]?.token }
        }
        return PageRekeyPlanResult.Ready(
            PageRekeyPlan(
                generation = store.generation,
                pages = store.pages,
                committedDisplay = store.committedDisplay,
                manifest = manifest,
                moves = moves,
                oldArtifactContentHashes = oldArtifactContentHashes,
                livePageSnapshots = livePageSnapshots,
                leaseTokens = leaseTokens,
                requiresArtifactTransaction = requiresArtifactTransaction,
                changedDisplayKeys = store.pages.keys + moves.values,
                consumedPlaceholders = consumedPlaceholders,
                skippedCollisions = skippedCollisions,
            ),
        )
    }

    /**
     * Caller holds [store.mutex]. True only when the record at [pageKey] carries no
     * payload a move would destroy: no blocks, no cleaned/render image, no
     * stage output, no fingerprints, no committed display, no artifact or
     * journal immutables. Pre-registration placeholders qualify.
     */
    private fun isPayloadlessPlaceholderLocked(pageKey: String): Boolean {
        if (store.committedDisplay.containsKey(pageKey)) return false
        if (store.retiredCleanedImages.containsKey(pageKey)) return false
        if (store.retiredColoredImages.containsKey(pageKey)) return false
        val manifest = store.artifactManifest ?: return placeholderRecordLocked(pageKey)
        if (manifest.pages.containsKey(pageKey)) return false
        if (manifest.durableFailures.values.any { it.pageKey == pageKey }) return false
        return placeholderRecordLocked(pageKey)
    }

    /** Caller holds [store.mutex]; checks the in-memory page record only. */
    private fun placeholderRecordLocked(pageKey: String): Boolean {
        val page = store.pages[pageKey] ?: return false
        if (page.blocks.isNotEmpty()) return false
        if (page.cleanedImageName != null || page.ocrArtifactId != null) return false
        if (page.originalImageFallback) return false
        if (page.imgWidth > 0f || page.imgHeight > 0f) return false
        if (page.detectionCount > 0 || page.ocrBlockCount > 0) return false
        if (
            page.ocrStatus != StageStatus.PENDING ||
            page.translationStatus != StageStatus.PENDING ||
            page.inpaintStatus != StageStatus.PENDING ||
            page.renderStatus != StageStatus.PENDING
        ) {
            return false
        }
        if (page.activeError != null || page.errorMessage != null) return false
        if (
            page.sourceFingerprint != null ||
            page.detectionFingerprint != null ||
            page.ocrFingerprint != null ||
            page.inpaintFingerprint != null ||
            page.translationFingerprint != null ||
            page.layoutFingerprint != null
        ) {
            return false
        }
        return true
    }

    /** Caller holds [store.mutex]. Reject any store-side change made while snapshots were prepared. */
    private fun isCurrentPageRekeyPlanLocked(plan: PageRekeyPlan): Boolean {
        if (store.defunct ||
            store.generation != plan.generation ||
            store.pages !== plan.pages ||
            store.committedDisplay !== plan.committedDisplay ||
            store.artifactManifest !== plan.manifest
        ) {
            return false
        }
        return synchronized(store.pageLeases) {
            plan.leaseTokens.all { (pageKey, token) -> store.pageLeases[pageKey]?.token == token }
        }
    }

    /** Caller holds [store.mutex]. Moves retained names to re-keyed pages and drops names of removed pages. */
    private fun ConcurrentHashMap<String, MutableSet<String>>.rekeyRetired(
        moveByOldKey: Map<String, String>,
        livePageKeys: Set<String>,
    ) {
        val moved = moveByOldKey.mapNotNull { (oldKey, newKey) ->
            this[oldKey]
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?.let { newKey to it }
        }
        moveByOldKey.keys.forEach { oldKey -> remove(oldKey) }
        moved.forEach { (newKey, names) ->
            if (newKey in livePageKeys) {
                computeIfAbsent(newKey) { ConcurrentHashMap.newKeySet<String>() }.addAll(names)
            }
        }
        keys.toList().forEach { pageKey ->
            if (pageKey !in livePageKeys) remove(pageKey)
        }
    }

    private fun rekeyedArtifactManifest(
        base: ChapterArtifactManifest,
        prepared: ChapterArtifactEngine.PreparedPageSnapshotRekey,
        moves: Map<String, String>,
    ): ChapterArtifactManifest? {
        val updatedFailures = LinkedHashMap<String, DurableFailureMetadata>()
        base.durableFailures.forEach { (_, failure) ->
            val newPageKey = moves[failure.pageKey] ?: failure.pageKey
            val updated = failure.copy(pageKey = newPageKey)
            val newMapKey = "$newPageKey:${failure.stage.name}"
            if (updatedFailures.put(newMapKey, updated) != null) return null
        }
        return base.copy(
            pages = prepared.pages,
            durableFailures = updatedFailures,
            expectedPageCount = maxOf(
                base.expectedPageCount ?: 0,
                prepared.pages.size,
            ),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
