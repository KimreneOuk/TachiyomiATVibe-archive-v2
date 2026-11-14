package eu.kanade.tachiyomi.data.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ImageDownloadCoordinator {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withKey(key: String, action: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(key)
            }
        }
    }
}
