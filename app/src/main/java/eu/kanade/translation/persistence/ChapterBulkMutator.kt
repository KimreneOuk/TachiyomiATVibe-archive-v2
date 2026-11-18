package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toDraft
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

private fun PageTranslationView.isQueueVisibleTransient(): Boolean {
    return ocrStatus.isQueueTransient() ||
        translationStatus.isQueueTransient() ||
        inpaintStatus.isQueueTransient() ||
        renderStatus.isQueueTransient()
}

private fun String.isQueueTransient(): Boolean {
    return this == StageStatus.PENDING ||
        this == StageStatus.RUNNING ||
        this == StageStatus.FAILED ||
        this == StageStatus.CANCELLED
}

private fun String.cancelIfTransient(): String {
    return if (isQueueTransient()) StageStatus.CANCELLED else this
}

/**
 * Bulk-mutation collaborator (Phase 7 S7). Owns the multi-page store
 * transactions ([deletePage], [replaceAll], [rekeyPages],
 * [preRegisterPages], [clearTransientQueuePages]); the re-key transaction's
 * two-phase planning machinery lives in [ChapterPageRekeyMutator]. Every
 * transaction keeps its journal-frame semantics: one credit reservation and
 * one commit sequence per bulk frame, captured through the journal bridge
 * (Phase 7 S5). The owning store delegates each public entry point here
 * under the producer capture permit and the store mutex, so the Phase 7 S2
 * lock order is unchanged.
 */
internal class ChapterBulkMutator(private val store: ChapterTranslationStore) {

    private val pageRekeyMutator = ChapterPageRekeyMutator(store)

    /**
     * P3B typed reset receipt: null when the page is durably removed from the
     * authoritative manifest (verified destructive publication); a non-null
     * reason when the publication completed without a verified same-state
     * backup or was rejected — typed incomplete, never a silent success.
     */
    suspend fun deletePage(pageKey: String): String? {
        if (store.defunct) return null
        return store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store deletePage rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock "artifact admission rejected: ${admission.message}"
                }
            }
            var deletionIncomplete: String? = null
            if (store.pages.containsKey(pageKey)) {
                deletionIncomplete = deleteArtifactPageLocked(pageKey)
                store.pages = store.pages.remove(pageKey)
                store.committedDisplay = store.committedDisplay.remove(pageKey)
                store.retiredCleanedImages.remove(pageKey)
                store.retiredColoredImages.remove(pageKey)
                store.pageLeases.remove(pageKey)
                store.markPageHydratedLocked(pageKey)
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(listOf(pageKey))
            }
            deletionIncomplete
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store replaceAll rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            val changedDisplayKeys = store.pages.keys + updatedPages.keys
            if (store.artifactManifest != null) {
                store.artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                    deleteArtifactPageLocked(pageKey)
                }
            }
            store.pages = persistentMapOf()
            store.committedDisplay = persistentMapOf()
            store.retiredCleanedImages.clear()
            store.retiredColoredImages.clear()
            updatedPages.forEach { (pageKey, page) ->
                val owned = store.ownedPage(pageKey, page)
                store.pages = store.pages.put(pageKey, store.publishPage(owned))
                if (store.publicationBridge.persistArtifactMutation(pageKey, null, owned)) {
                    store.promoteDisplayIfReadyLocked(pageKey, owned)
                }
            }
            store.pendingExpectedPageCount = updatedPages.size
            store.pendingExpectedPageCountTrusted = false
            store.stateFlow.value = store.snapshotPages()
            store.displaySnapshotLocked(changedDisplayKeys)
        }
    }

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): ChapterTranslationStore.PageRekeyOutcome = pageRekeyMutator.rekeyPages(onlineKeys, onDiskKeys)

    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): ChapterTranslationStore.PagePreRegistration {
        if (pageKeys.isEmpty()) return ChapterTranslationStore.PagePreRegistration.Accepted
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store preRegisterPages rejected: store is defunct"
            }
            return ChapterTranslationStore.PagePreRegistration.Rejected("store is defunct")
        }
        return store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store preRegisterPages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock ChapterTranslationStore.PagePreRegistration.Rejected(admission.message)
                }
            }
            val foundCount = pageKeys.distinct().size
            val crossCheckKnown = sourceCountKnown && probedSourcePageCount != null
            val crossCheckUnknown = sourceCountKnown && probedSourcePageCount == null
            val targetCount = if (crossCheckKnown) maxOf(foundCount, probedSourcePageCount!!) else foundCount
            store.pendingExpectedPageCount = maxOf(store.pendingExpectedPageCount ?: 0, targetCount)
            store.pendingExpectedPageCountTrusted = store.pendingExpectedPageCountTrusted || !crossCheckUnknown

            val changedPageKeys = mutableSetOf<String>()
            pageKeys.forEach { pageKey ->
                if (!store.pages.containsKey(pageKey)) {
                    store.pages = store.pages.put(
                        pageKey,
                        store.publishPage(store.ownedPage(pageKey, PageTranslation(sourceFileName = pageKey))),
                    )
                    store.pendingArtifactPageRegistrations += pageKey
                    changedPageKeys += pageKey
                }
            }
            if (changedPageKeys.isNotEmpty()) {
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(changedPageKeys)
            }
            if (store.engineMode !is ChapterStoreEngineMode.Durable &&
                store.artifactParent != null &&
                store.artifactFileName != null
            ) {
                store.publicationBridge.ensureArtifactStore()
            }
            val artifact = store.artifactEngine
            val manifest = store.artifactManifest
            val expected = store.pendingExpectedPageCount
            val expectedTrusted = when {
                crossCheckUnknown -> false
                else ->
                    manifest?.expectedPageCountTrusted == true || store.pendingExpectedPageCountTrusted
            }

            val partialInfo = when {
                crossCheckKnown -> {
                    val missing = maxOf(0, probedSourcePageCount!! - foundCount)
                    if (missing == 0) {
                        null
                    } else {
                        PartialBatchInfo(
                            expectedSourcePageCount = probedSourcePageCount,
                            missingPageCount = missing,
                            determinedFrom = PartialBatchDetermination.DOWNLOAD_CROSSCHECK,
                            recordedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                }
                crossCheckUnknown -> PartialBatchInfo(
                    expectedSourcePageCount = null,
                    missingPageCount = 0,
                    determinedFrom = PartialBatchDetermination.UNKNOWN,
                    recordedAtEpochMs = System.currentTimeMillis(),
                )
                else -> manifest?.partialBatchInfo
            }
            if (artifact != null && manifest != null && expected != null) {
                val updated = manifest.copy(
                    expectedPageCount = maxOf(expected, manifest.expectedPageCount ?: 0),
                    expectedPageCountTrusted = expectedTrusted,
                    partialBatchInfo = partialInfo,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                if (
                    updated.expectedPageCount == manifest.expectedPageCount &&
                    updated.expectedPageCountTrusted == manifest.expectedPageCountTrusted &&
                    updated.partialBatchInfo == manifest.partialBatchInfo
                ) {
                    store.pendingExpectedPageCount = null
                    store.pendingExpectedPageCountTrusted = false
                } else if (artifact.publishManifest(updated)) {
                    store.artifactManifest = updated
                    store.pendingExpectedPageCount = null
                    store.pendingExpectedPageCountTrusted = false
                }
            }
            ChapterTranslationStore.PagePreRegistration.Accepted
        }
    }

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) {
        if (store.defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store clearTransientQueuePages rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store clearTransientQueuePages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            store.generation++
            logcat(LogPriority.INFO) {
                "TachiyomiAT store generation invalidated: " +
                    "generation=${store.generation} reason=${reason ?: "clear transient queue"}"
            }
            val changedPageKeys = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            var updatedPages = store.pages
            store.pages.forEach { (pageKey, page) ->
                if (page.hasRenderedResult || !page.isQueueVisibleTransient()) {
                } else {
                    val draft = page.toDraft().copy(
                        ocrStatus = page.ocrStatus.cancelIfTransient(),
                        translationStatus = page.translationStatus.cancelIfTransient(),
                        inpaintStatus = page.inpaintStatus.cancelIfTransient(),
                        renderStatus = page.renderStatus.cancelIfTransient(),
                        updatedAt = now,
                    ).also {
                        it.ocrError = reason
                        it.translationError = reason
                        it.inpaintError = reason
                        it.renderError = reason
                    }
                    updatedPages = updatedPages.put(pageKey, store.publishPage(store.ownedPage(pageKey, draft)))
                    changedPageKeys += pageKey
                }
            }
            if (changedPageKeys.isNotEmpty()) {
                store.pages = updatedPages
                // Cancel semantics: cancelled pages release their writer
                // leases; committed display bundles survive untouched.
                synchronized(store.pageLeases) {
                    store.pageLeases.keys.retainAll { pageKey ->
                        store.pages[pageKey]?.hasRenderedResult ?: false
                    }
                }
                store.pages.keys.forEach { pageKey ->
                    store.cancelArtifactCandidateLocked(pageKey)
                }
                store.stateFlow.value = store.snapshotPages()
                store.displaySnapshotLocked(changedPageKeys)
            }
        }
    }

    /** Caller holds [store.mutex]; null when the destructive deletion is durably complete. */
    private fun deleteArtifactPageLocked(pageKey: String): String? {
        val artifact = store.artifactEngine ?: return null
        val manifest = store.artifactManifest ?: return null
        val outcome = artifact.candidates.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                store.artifactManifest = outcome.manifest
                // P3A/P3B typed incomplete: the page pointer was durably
                // removed but the same-state backup could not be verified.
                outcome.unverifiedBackupReason?.let { reason ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact page deletion published without a verified backup: " +
                            "pageKey=$pageKey reason=$reason"
                    }
                    return "unverified backup after page deletion: $reason"
                }
                return null
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                store.publicationBridge.rebaseManifestAfterStaleRejection(artifact, outcome.reason)
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
                }
            }
        }
        return (outcome as? ChapterArtifactEngine.TransactionOutcome.Rejected)?.reason
    }
}
