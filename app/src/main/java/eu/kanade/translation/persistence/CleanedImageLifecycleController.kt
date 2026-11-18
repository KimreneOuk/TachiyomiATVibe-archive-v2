package eu.kanade.translation.persistence

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.CleanedImageIdentity
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP = 64
private const val ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS = 30_000L

internal fun isFreshOrphanedCleanedImage(lastModified: Long, nowEpochMs: Long): Boolean =
    lastModified <= 0L || nowEpochMs - lastModified < ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS

/** Both derived-image domains live in the companion dir and share this lifecycle. */
private fun isDerivedCompanionImageName(name: String): Boolean =
    name.contains(".cleaned.") || name.contains(".colored.")

/** Outcome of retiring a chapter's companion images. */
data class ChapterCompanionRetirement(
    val confirmedRemoved: Int = 0,
    val deferredLeasedFiles: Int = 0,
) {
    val totalSubmitted: Int get() = confirmedRemoved + deferredLeasedFiles
}

/** Owns cleaned-image lookup, retirement, and cleanup for translation artifacts. */
internal class CleanedImageLifecycleController(
    private val applicationScopeProvider: () -> CoroutineScope,
    private val streamRegistryProvider: () -> TranslationStreamRegistry,
    private val providerProvider: () -> TranslationFileProvider,
    private val activeStoresProvider: (() -> ActiveChapterStoreRegistry)? = null,
) {

    // Resolve lifecycle dependencies on demand so unused cleanup paths do not create scopes.
    private val applicationScope get() = applicationScopeProvider()

    private val streamRegistry get() = streamRegistryProvider()

    private val provider get() = providerProvider()

    private val activeCleanupChapters = ConcurrentHashMap.newKeySet<Long>()

    /** Delete authorization routed by the name's intrinsic domain (colored vs cleaned). */
    private fun ChapterTranslationStore.mayDeleteImage(pageKey: String, name: String): Boolean =
        if (name.contains(".colored.")) {
            mayDeleteColoredImage(pageKey, name)
        } else {
            mayDeleteCleanedImage(pageKey, name)
        }

    /**
     * Reclaims previous committed cleaned images discovered while opening a
     * chapter. The store only exposes names after its committed pointer is
     * reconstructed; deletion still goes through the stream registry so a
     * reader stream held across a reopen cannot be invalidated.
     */
    fun scheduleRetiredCleanedImageCleanup(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) {
        val stableMangaId = mangaId ?: return
        if (!activeCleanupChapters.add(chapterId)) return
        applicationScope.launch {
            try {
                store.state.value.keys.forEach { pageKey ->
                    val retiredNames = store.drainRetiredCleanedImages(pageKey) +
                        store.drainRetiredColoredImages(pageKey)
                    retiredNames.forEach { imageName ->
                        streamRegistry.retireCleanedImage(
                            sourceId = source.id,
                            mangaId = stableMangaId,
                            chapterId = chapterId,
                            pageKey = pageKey,
                            imageName = imageName,
                        ) {
                            if (!store.mayDeleteImage(pageKey, imageName)) return@retireCleanedImage
                            val deleted = provider.findPageCleanedImageMemoized(
                                mangaTitle,
                                source,
                                chapterName,
                                scanlator,
                                imageName,
                            )?.delete() == true
                            if (deleted) {
                                provider.findPageCleanedImageMemoized(
                                    mangaTitle,
                                    source,
                                    chapterName,
                                    scanlator,
                                    CleanedImageIdentity.sidecarName(imageName),
                                )?.delete()
                            }
                            logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                                "TachiyomiAT chapter-load retired image drain: " +
                                    "pageKey=$pageKey file=$imageName deleted=$deleted"
                            }
                        }
                    }
                }
                sweepOrphanedCleanedImages(
                    store = store,
                    chapterId = chapterId,
                    chapterName = chapterName,
                    scanlator = scanlator,
                    mangaTitle = mangaTitle,
                    source = source,
                    mangaId = stableMangaId,
                )
            } finally {
                activeCleanupChapters.remove(chapterId)
            }
        }
    }

    fun sweepOrphanedCleanedImages(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long,
    ) {
        val directory = provider.findCompanionImageDirMemoized(mangaTitle, source, chapterName, scanlator) ?: return
        if (streamRegistry.hasActiveCleanedImageReadersForChapter(source.id, mangaId, chapterId)) return
        val activeStore = activeStoresProvider?.invoke()?.get(chapterId)
        val referenced = store.referencedCleanedImageNames() +
            (activeStore?.referencedCleanedImageNames().orEmpty())
        val pageKeys = store.state.value.keys
        val activePageKeys = activeStore?.state?.value?.keys.orEmpty()
        val now = System.currentTimeMillis()
        var deletedImages = 0
        directory.listFiles()
            ?.asSequence()
            ?.mapNotNull { file -> file.name?.let { it to file } }
            ?.filter { (name, file) ->
                // Conservatively defer all colored images: only clean non-colored derived companions
                file.isFile && isDerivedCompanionImageName(name) && !name.endsWith(".identity.json") && !name.contains(".colored.")
            }
            ?.filterNot { (name, file) ->
                name in referenced ||
                    streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, name) > 0 ||
                    pageKeys.any { pageKey -> !store.mayDeleteCleanedImage(pageKey, name) } ||
                    activePageKeys.any { pageKey -> activeStore?.mayDeleteCleanedImage(pageKey, name) == false } ||
                    isFreshOrphanedCleanedImage(file.lastModified(), now)
            }
            ?.take(MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP)
            ?.forEach { (name, file) ->
                val currentActive = activeStoresProvider?.invoke()?.get(chapterId)
                val currentReferenced = store.referencedCleanedImageNames() +
                    (currentActive?.referencedCleanedImageNames().orEmpty())
                if (name in currentReferenced) return@forEach
                if (streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, name) > 0) return@forEach
                if (store.state.value.keys.any { !store.mayDeleteCleanedImage(it, name) }) return@forEach
                if (currentActive != null && currentActive.state.value.keys.any { !currentActive.mayDeleteCleanedImage(it, name) }) return@forEach

                val deleted = runCatching { file.delete() }.getOrDefault(false)
                if (deleted) {
                    deletedImages++
                    directory.findFile(CleanedImageIdentity.sidecarName(name))?.delete()
                }
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT orphaned companion image sweep: chapter=$chapterName file=$name deleted=$deleted"
                }
            }

        val remainingBudget = MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP - deletedImages
        if (remainingBudget <= 0) return
        directory.listFiles()
            ?.asSequence()
            ?.mapNotNull { file -> file.name?.let { it to file } }
            ?.mapNotNull { (name, file) ->
                val imageName = name.removeSuffix(".identity.json")
                (imageName to file).takeIf {
                    file.isFile &&
                        name.endsWith(".identity.json") &&
                        isDerivedCompanionImageName(imageName) &&
                        !imageName.contains(".colored.")
                }
            }
            ?.filterNot { (imageName, sidecar) ->
                directory.findFile(imageName)?.exists() == true ||
                    imageName in referenced ||
                    streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, imageName) > 0 ||
                    pageKeys.any { pageKey -> !store.mayDeleteCleanedImage(pageKey, imageName) } ||
                    activePageKeys.any { pageKey -> activeStore?.mayDeleteCleanedImage(pageKey, imageName) == false } ||
                    isFreshOrphanedCleanedImage(sidecar.lastModified(), now)
            }
            ?.take(remainingBudget)
            ?.forEach { (imageName, sidecar) ->
                val currentActive = activeStoresProvider?.invoke()?.get(chapterId)
                val currentReferenced = store.referencedCleanedImageNames() +
                    (currentActive?.referencedCleanedImageNames().orEmpty())
                if (imageName in currentReferenced) return@forEach
                if (directory.findFile(imageName)?.exists() == true) return@forEach
                if (streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, imageName) > 0) return@forEach
                if (store.state.value.keys.any { !store.mayDeleteCleanedImage(it, imageName) }) return@forEach
                if (currentActive != null && currentActive.state.value.keys.any { !currentActive.mayDeleteCleanedImage(it, imageName) }) return@forEach

                val deleted = runCatching { sidecar.delete() }.getOrDefault(false)
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT orphaned cleaned image identity sweep: chapter=$chapterName " +
                        "file=${sidecar.name} image=$imageName deleted=$deleted"
                }
            }
    }

    /**
     * Retires every derived companion image of the chapter (cleaned, colored,
     * rendered), keeping `.nomedia`.
     */
    fun retireChapterCompanionImages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ChapterCompanionRetirement {
        val chapterId = chapter.id ?: return ChapterCompanionRetirement()
        val directory = provider.findCompanionImageDirMemoized(manga.title, source, chapter.name, chapter.scanlator)
        val namesAtRetirement = directory
            ?.listFiles()
            ?.asSequence()
            ?.mapNotNull { it.name }
            ?.filterNot { it == ".nomedia" }
            ?.toSet()
            .orEmpty()
        if (namesAtRetirement.isEmpty()) return ChapterCompanionRetirement()

        val confirmed = java.util.concurrent.atomic.AtomicInteger(0)
        val dispatch = streamRegistry.retireCleanedImagesForChapter(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
        ) {
            namesAtRetirement.forEach { imageName ->
                val deleted = directory?.findFile(imageName)?.delete() == true
                if (deleted) confirmed.incrementAndGet()
            }
        }
        return when (dispatch) {
            is TranslationStreamRegistry.ChapterRetirementDispatch.Executed ->
                ChapterCompanionRetirement(confirmedRemoved = confirmed.get(), deferredLeasedFiles = 0)
            is TranslationStreamRegistry.ChapterRetirementDispatch.Deferred ->
                ChapterCompanionRetirement(confirmedRemoved = 0, deferredLeasedFiles = namesAtRetirement.size)
        }
    }

    fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
        isReferencedElsewhere: () -> Boolean = { false },
    ) {
        val chapterId = chapter.id ?: return
        streamRegistry.retireCleanedImage(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
            pageKey = pageKey,
            imageName = imageName,
        ) {
            if (isReferencedElsewhere()) return@retireCleanedImage
            val imageDeleted = provider.findPageCleanedImageMemoized(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                imageName,
            )?.let { !it.exists() || it.delete() } ?: true
            if (imageDeleted) {
                provider.findPageCleanedImageMemoized(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    CleanedImageIdentity.sidecarName(imageName),
                )?.delete()
            }
        }
    }

    fun getCleanedImageStream(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
        cleanedImageName: String,
        pageKey: String? = null,
        mangaId: Long? = null,
        chapterId: Long? = null,
    ): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageCleanedImageMemoized(
                mangaTitle,
                source,
                chapterName,
                chapterScanlator,
                cleanedImageName,
            )
            if (file?.exists() == true) {
                val raw = { file.openInputStream() }
                if (pageKey == null || mangaId == null || chapterId == null) {
                    raw()
                } else {
                    streamRegistry.openCleanedImageStream(
                        sourceId = source.id,
                        mangaId = mangaId,
                        chapterId = chapterId,
                        pageKey = pageKey,
                        imageName = cleanedImageName,
                        open = raw,
                    )
                }
            } else {
                throw java.io.FileNotFoundException("Cleaned image not found: $cleanedImageName")
            }
        }
    }
}
