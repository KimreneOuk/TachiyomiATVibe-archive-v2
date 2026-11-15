package eu.kanade.translation.coordinator

import android.content.Context
import android.os.BatteryManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.model.BatchExecutionMode
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.ChapterBatchExecutor
import eu.kanade.translation.processing.batch.progress.BatchProgressReconciler
import eu.kanade.translation.processing.batch.progress.ReconciliationResult
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.processing.memory.BitmapPool
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/** Runs one chapter: resolves its store and page streams, pre-registers pages, manages trackers and reconciles. */
internal class BatchChapterPipeline(
    private val context: Context,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider,
    private val colorizeStreams: () -> OnlineColorizePageStreamResolver,
    private val pageOperation: ChapterBatchExecutor,
    private val resolveStore: suspend (Translation) -> ChapterTranslationStore?,
    private val batchTrackerFactory: (Long, ChapterTranslationStore, List<String>) -> TranslationBatchProgressTracker?,
    private val onBatchClosed: suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit,
    private val activeChapters: MutableSet<String>,
    private val beginTranslationRun: (Translation) -> Boolean,
) {

    suspend fun execute(
        translation: Translation,
        isJobActive: () -> Boolean,
        isTranslationQueued: (Translation) -> Boolean,
    ): ReconciliationResult? {
        if (translation.executionMode == BatchExecutionMode.ONLY_WHILE_CHARGING) {
            val batteryManager = runCatching { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }.getOrNull()
            translation.pausedForCharging = batteryManager != null && !batteryManager.isCharging
            if (translation.pausedForCharging) {
                logcat(LogPriority.INFO) { "Chapter ${translation.chapter.id} paused: waiting for charging" }
                translation.status = Translation.State.PAUSED
                return null
            }
        } else {
            translation.pausedForCharging = false
        }
        var store: ChapterTranslationStore? = null
        var tracker: TranslationBatchProgressTracker? = null
        var batchReconciliation: ReconciliationResult? = null
        var batchOrderedPageKeys: List<String> = emptyList()
        try {
            // Prefer the shared active store from TranslationManager so the reader
            // observes the same instance the pipeline writes to.
            store = resolveStore(translation)
            if (store == null) {
                val parent = provider.getMangaDir(translation.manga.title, translation.source)
                val fileName = provider.getTranslationFileName(
                    translation.chapter.name,
                    translation.chapter.scanlator,
                )
                store = ChapterTranslationStore.openArtifactSuspend(
                    parent,
                    fileName,
                )
            }

            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
            // Chapter files may be gone (deleted mid-translation) or never existed for a streamed
            // chapter: a TRANSLATION run needs the downloaded bytes and fails cleanly, while a
            // COLORIZE-ONLY run can stream every page from the source instead.
            if (chapterPath == null && !translation.colorizeOnly) {
                logcat(LogPriority.ERROR) {
                    "TachiyomiAT chapter files not found for ${translation.chapter.name}"
                }
                // Emit the typed terminal snapshot so the drawer
                // shows the real reason instead of a bare/living 0/0.
                failBeforePipeline(
                    translation,
                    store,
                    batchOrderedPageKeys,
                    reason = "Chapter files not found — the download may have been deleted",
                )
                return null
            }
            if (!beginTranslationRun(translation)) return null

            // Keep one ArchiveReader open for the batch. Its entry streams use
            // seek-based reads, avoiding repeated decompression; directory chapters
            // use cheap direct file opens.
            val streams: List<Pair<String, () -> InputStream>>
            val sharedArchive: mihon.core.archive.ArchiveReader?
            var onlineColorizePlan: OnlineColorizePageStreamResolver.Plan? = null
            if (chapterPath == null) {
                // Streamed chapter under a colorize-only run: the page list
                // comes from the source and images download lazily per page.
                sharedArchive = null
                onlineColorizePlan = colorizeStreams().resolve(
                    translation.manga,
                    translation.chapter,
                    translation.source,
                )
                if (onlineColorizePlan == null) {
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT online page list unavailable for colorize-only run: ${translation.chapter.name}"
                    }
                    failBeforePipeline(
                        translation,
                        store,
                        batchOrderedPageKeys,
                        reason = "Couldn't fetch this chapter's pages from the source for colorize",
                    )
                    return null
                }
                val plan = onlineColorizePlan
                streams = plan.orderedPageKeys.map { key ->
                    key to { plan.streamFor(key) }
                }
            } else if (chapterPath.isFile) {
                sharedArchive = chapterPath.archiveReader(context)
                // Each closure reads from the shared reader; it's mmap'd so
                // reads are seek-based, not re-decompressing.
                streams = sharedArchive.useEntries { entries ->
                    entries.filter {
                        it.isFile &&
                            ImageUtil.isImage(it.name) {
                                sharedArchive.getInputStream(it.name)
                                    ?: throw java.io.IOException("Archive entry '${it.name}' could not be opened (mmap)")
                            }
                    }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .map { entry ->
                            Pair(entry.name) {
                                sharedArchive.getInputStream(entry.name)
                                    ?: throw java.io.IOException(
                                        "Archive entry '${entry.name}' could not be opened (mmap)",
                                    )
                            }
                        }.toList()
                }
            } else {
                sharedArchive = null
                streams = getChapterPages(chapterPath)
            }
            try {
                // Chapter batches are deterministic natural-order traversals: reader
                // viewport/last-read position is intentionally NOT a scheduling input;
                // resume is decided per stage by the pipeline planner while pages remain 1..N.
                val orderedStreams = streams.toList()
                batchOrderedPageKeys = orderedStreams.map { it.first }
                // A rejected pre-registration is an explicit pipeline error: fail with a typed terminal snapshot,
                // not a live tracker whose totals silently stay zero. The source-count cross-check keeps the
                // manifest's trusted total the SOURCE total (or honestly unknown), never the found count.
                val preRegistration = store.preRegisterPages(
                    batchOrderedPageKeys,
                    translation.probedSourcePageCount,
                    translation.sourceCountKnown,
                )
                if (preRegistration is ChapterTranslationStore.PagePreRegistration.Rejected) {
                    failBeforePipeline(
                        translation,
                        store,
                        batchOrderedPageKeys,
                        reason = "Page pre-registration was rejected: ${preRegistration.reason}",
                    )
                    return null
                }
                val chapterId = translation.chapter.id
                tracker = if (chapterId != null) {
                    batchTrackerFactory(chapterId, store, orderedStreams.map { it.first })
                } else {
                    null
                }
                // Publish durable page progress before resumed work can emit
                // new phase events. Reuse-only pages may otherwise leave the
                // live tracker at its empty 0/0 snapshot until batch finish.
                tracker?.rebuildFromStore()
                if (!isJobActive()) {
                    logcat(LogPriority.INFO) { "TachiyomiAT batch cancelled before start: ${translation.chapter.name}" }
                } else {
                    if (orderedStreams.isEmpty()) {
                        translation.status = Translation.State.ERROR
                        tracker?.abort(emptySet(), "Chapter has no readable pages to translate")
                    } else {
                        val chapterKey = translation.chapter.id?.toString() ?: translation.chapter.name
                        activeChapters.add(chapterKey)
                        try {
                            val streamsByKey = orderedStreams.associate { it.first to it.second }
                            val result = pageOperation.execute(
                                chapterKey = chapterKey,
                                orderedPageKeys = batchOrderedPageKeys,
                                streamProvider = { pageKey ->
                                    streamsByKey[pageKey]?.invoke()
                                        ?: throw java.io.IOException("Page stream is missing for $pageKey")
                                },
                                store = store,
                                companionDir = provider.getCompanionImageDir(
                                    translation.manga.title,
                                    translation.source,
                                    translation.chapter.name,
                                    translation.chapter.scanlator,
                                ),
                                onProgress = { tracker?.rebuildFromStore() },
                                colorizeOnly = translation.colorizeOnly,
                                prepareStream = onlineColorizePlan?.let { plan ->
                                    { pageKey -> plan.prepareStream(pageKey) }
                                },
                            )
                            if (result.error != null) {
                                logcat(LogPriority.ERROR) {
                                    "TachiyomiAT batch run failed for ${translation.chapter.name}: ${result.error}"
                                }
                                tracker?.abort(batchOrderedPageKeys.toSet(), result.error)
                                translation.status = Translation.State.ERROR
                            } else {
                                batchReconciliation = BatchProgressReconciler.reconcileFlaggedCompleted(
                                    pageMap = store.state.value,
                                    orderedKeys = batchOrderedPageKeys,
                                    activeGeneration = store.currentGeneration,
                                    includeColoringFailures = translation.colorizeOnly,
                                )
                                tracker?.finish(batchReconciliation!!)
                            }
                        } finally {
                            activeChapters.remove(chapterKey)
                        }
                    }
                }
            } finally {
                try {
                    sharedArchive?.close()
                } catch (_: Exception) {}
            }

            // A durable pause/terminal outcome already owns its unresolved
            // anchor. Running the ordinary reconciler here would synthesize
            // tail failures and destroy the retry boundary.
            when {
                batchReconciliation?.nonDurableFailure == true -> {
                    // A guarded publication rejection was reconciled in memory
                    // only. Preserve that explicit warning instead of deriving a
                    // terminal ERROR from a snapshot that was not durably owned.
                    translation.status = batchReconciliation!!.chapterStatus
                }
                store.artifactStatus() == Translation.State.PAUSED -> {
                    translation.status = Translation.State.PAUSED
                }
                store.artifactStatus() == Translation.State.ERROR -> {
                    translation.status = Translation.State.ERROR
                }
                else -> {
                    val pageStates = store.state.value
                    val reconciliation =
                        BatchProgressReconciler.reconcileFlaggedCompleted(
                            pageMap = pageStates,
                            orderedKeys = batchOrderedPageKeys,
                            activeGeneration = store.currentGeneration,
                            includeColoringFailures = translation.colorizeOnly,
                        )
                    translation.status = reconciliation.chapterStatus
                }
            }
            return batchReconciliation
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // If it's no longer in the queue, it was explicitly removed (cancelled).
                // If it's still in the queue, it was merely paused or memory-requeued; do not abort the tracker so it can be resumed.
                if (!isTranslationQueued(translation)) {
                    tracker?.abort(batchOrderedPageKeys.toSet(), "Batch cancelled")
                }
                store?.flush()
                throw error
            }
            BitmapPool.releaseAll()
            translation.status = Translation.State.ERROR
            // An unexpected exception must leave a terminal
            // snapshot with the reason, never a live nonterminal tracker. Safe
            // when the batch already finished: a late event is ignored.
            tracker?.abort(
                batchOrderedPageKeys.toSet(),
                reason = "Translation failed unexpectedly: ${error.message ?: error::class.java.simpleName}",
            )
            logcat(LogPriority.ERROR, error)
            return null
        } finally {
            store?.let { closedStore ->
                runCatching {
                    onBatchClosed(
                        translation.manga,
                        translation.chapter,
                        translation.source,
                        closedStore,
                    )
                }.onFailure { closeError ->
                    logcat(LogPriority.WARN, closeError) {
                        "Batch coordinator teardown callback failed for ${translation.chapter.name}"
                    }
                }
            }
        }
    }

    private fun failBeforePipeline(
        translation: Translation,
        store: ChapterTranslationStore,
        batchOrderedPageKeys: List<String>,
        reason: String,
    ) {
        translation.status = Translation.State.ERROR
        val chapterId = translation.chapter.id ?: return
        val tracker = batchTrackerFactory(chapterId, store, batchOrderedPageKeys) ?: return
        // Publish the known ordered-key totals before the terminal event so
        // the aborted snapshot carries the real work set, not 0/0.
        tracker.rebuildFromStore()
        tracker.abort(batchOrderedPageKeys.toSet(), reason)
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> =
        eu.kanade.translation.util.getChapterPages(context, chapterPath)
}
