package com.nikhil.niktv.data

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Deduplicate matching misses without holding the cache lock during network I/O. */
internal class CatalogResponseCache<K, V>(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 5 * 60_000L,
    private val maxRecords: Int = 20_000
) {
    private data class Entry<V>(val time: Long, val items: List<V>)
    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = HashMap<K, CompletableFuture<List<V>>>()

    fun get(key: K, fetch: () -> List<V>): List<V> {
        var owner = false
        val result = synchronized(lock) {
            entries[key]?.let { if (now() - it.time < ttlMillis) return it.items }
            entries.remove(key)
            pending[key] ?: CompletableFuture<List<V>>().also { pending[key] = it; owner = true }
        }
        if (!owner) {
            try { return result.get() }
            catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        }
        try {
            val items = fetch()
            synchronized(lock) {
                // Invalidation may have started a newer fetch. Never cache the obsolete result.
                if (pending[key] === result) {
                    pending.remove(key)
                    entries.entries.removeAll { now() - it.value.time >= ttlMillis }
                    if (items.size <= maxRecords) {
                        while (entries.isNotEmpty() &&
                            (entries.size >= 8 || entries.values.sumOf { it.items.size } + items.size > maxRecords)) {
                            entries.remove(entries.keys.first())
                        }
                        entries[key] = Entry(now(), items)
                    }
                }
            }
            result.complete(items)
            return items
        } catch (failure: Throwable) {
            synchronized(lock) { if (pending[key] === result) pending.remove(key) }
            result.completeExceptionally(failure)
            throw failure
        }
    }

    fun invalidate(key: K) = synchronized(lock) {
        entries.remove(key)
        // Existing consumers may finish, but refresh callers must not join an obsolete fetch.
        pending.remove(key)
        Unit
    }
}
