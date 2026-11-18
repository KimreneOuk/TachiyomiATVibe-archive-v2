package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslationView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Chapter-keyed owner of active stores; a reader's selected source never follows another chapter's store publish. */
internal class ActiveChapterStoreRegistry {
    private val stores = LinkedHashMap<Long, ChapterTranslationStore>()
    private val fileStores = LinkedHashMap<String, ChapterTranslationStore>()
    private val openingLocks = LinkedHashMap<String, Mutex>()
    private val chapterAdmissionLocks = ConcurrentHashMap<Long, Mutex>()
    private val _snapshots = MutableStateFlow<Map<Long, ChapterTranslationStore>>(emptyMap())
    val snapshots: StateFlow<Map<Long, ChapterTranslationStore>> = _snapshots.asStateFlow()

    /** Serializes reader/batch binding and file-backed maintenance for one chapter. */
    suspend fun <T> withChapterAdmissionLock(chapterId: Long, block: suspend () -> T): T =
        chapterAdmissionLocks.computeIfAbsent(chapterId) { Mutex() }.withLock { block() }

    @Synchronized
    fun get(chapterId: Long): ChapterTranslationStore? {
        val store = stores[chapterId] ?: return null
        if (store.isDefunct) {
            removeStore(store)
            return null
        }
        return store
    }

    @Synchronized
    fun getByFile(fileKey: String): ChapterTranslationStore? {
        val store = fileStores[fileKey] ?: return null
        if (store.isDefunct) {
            removeStore(store)
            return null
        }
        return store
    }

    @Synchronized
    fun register(chapterId: Long, store: ChapterTranslationStore): Boolean {
        if (store.isDefunct) return false
        val existing = stores[chapterId]
        if (existing != null && !existing.isDefunct) return false
        if (existing != null && existing.isDefunct) {
            removeStore(existing)
        }
        stores[chapterId] = store
        publish()
        return true
    }

    @Synchronized
    fun registerFile(fileKey: String, store: ChapterTranslationStore): Boolean {
        if (store.isDefunct) return false
        val existing = fileStores[fileKey]
        if (existing != null && !existing.isDefunct && existing !== store) return false
        if (existing != null && existing.isDefunct) {
            removeStore(existing)
        }
        fileStores[fileKey] = store
        return true
    }

    /** Opens without holding the registry monitor across disk I/O; callers share a per-chapter lock and recheck. */
    suspend fun getOrCreate(
        chapterId: Long,
        fileKey: String? = null,
        create: suspend () -> ChapterTranslationStore?,
    ): ChapterTranslationStore? {
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey ?: "chapter:$chapterId") { Mutex() }
        }
        return openingLock.withLock {
            get(chapterId)?.let { return@withLock it }
            fileKey?.let { getByFile(it) }?.let { store ->
                return@withLock if (register(chapterId, store)) store else get(chapterId)
            }
            val created = create() ?: return@withLock null
            if (!register(chapterId, created)) return@withLock get(chapterId)
            if (fileKey != null) registerFile(fileKey, created)
            created
        }
    }

    /** Opens one file-keyed store for callers that do not have a chapter id. */
    suspend fun getOrCreateFile(
        fileKey: String,
        create: suspend () -> ChapterTranslationStore?,
    ): ChapterTranslationStore? {
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock {
            getByFile(fileKey)?.let { return@withLock it }
            val created = create() ?: return@withLock null
            if (registerFile(fileKey, created)) created else getByFile(fileKey)
        }
    }

    /**
     * Runs a file-backed maintenance transaction under the same admission
     * mutex used by chapter, file, and probe opens. Callers must inspect
     * [hasStoreForFile] while holding this lock before opening an unregistered
     * maintenance instance. This closes the race where an opener passes its
     * fast-path lookup while a migration is preparing the same artifact.
     */
    suspend fun <T> withFileOpeningLock(fileKey: String, block: suspend () -> T): T {
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock { block() }
    }

    /** Caller should hold [withFileOpeningLock] when using this as an admission guard. */
    @Synchronized
    fun hasStoreForFile(fileKey: String): Boolean {
        stores.entries.removeIf { it.value.isDefunct }
        fileStores.entries.removeIf { it.value.isDefunct }
        return fileStores.containsKey(fileKey) ||
            stores.values.any { it.chapterKey == fileKey }
    }

    @Synchronized
    fun removeStore(store: ChapterTranslationStore): Boolean {
        val removedFromStores = stores.entries.removeIf { it.value === store }
        val removedFromFileStores = fileStores.entries.removeIf { it.value === store }
        if (removedFromStores || removedFromFileStores) {
            publish()
            return true
        }
        return false
    }

    @Synchronized
    fun removeFile(fileKey: String): ChapterTranslationStore? {
        val removed = fileStores.remove(fileKey) ?: return null
        stores.entries.removeIf { it.value === removed }
        publish()
        return removed
    }

    @Synchronized
    fun remove(chapterId: Long): ChapterTranslationStore? {
        val removed = stores.remove(chapterId) ?: return null
        fileStores.entries.removeIf { it.value === removed }
        publish()
        return removed
    }

    @Synchronized
    fun chapterIds(): List<Long> {
        stores.entries.removeIf { it.value.isDefunct }
        return stores.keys.toList()
    }

    @Synchronized
    fun storesFor(chapterIds: Collection<Long>): List<ChapterTranslationStore> =
        chapterIds.mapNotNull(::get)

    /** The currently selected chapter source, or null when that chapter is not active. */
    fun observe(chapterId: Long): StateFlow<Map<String, PageTranslationView>>? = get(chapterId)?.state

    /** Rebinds only when this [chapterId]'s source changes; another chapter's store updates are never emitted. */
    fun select(chapterId: Long): Flow<Map<String, PageTranslationView>> = snapshots.flatMapLatest { map ->
        map[chapterId]?.state ?: flowOf(emptyMap())
    }

    private fun publish() {
        stores.entries.removeIf { it.value.isDefunct }
        fileStores.entries.removeIf { it.value.isDefunct }
        _snapshots.value = stores.toMap()
    }
}
