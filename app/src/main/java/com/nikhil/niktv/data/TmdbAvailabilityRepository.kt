package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.TmdbAvailabilityCache
import com.nikhil.niktv.model.TmdbAvailabilityEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small, persistent match buckets. Both successful and empty lookups are cached. */
internal class TmdbAvailabilityRepository(private val context: Context) {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, TmdbAvailabilityCache>()
    private fun key(profile: String, type: CatalogType) = "tmdb-availability-v1:$profile:${type.name}"

    suspend fun read(profile: String, type: CatalogType): TmdbAvailabilityCache = mutex.withLock {
        val key = key(profile, type)
        memory.getOrPut(key) { TmdbAvailabilityCache() }.let { existing ->
            // The disk is read once per bucket, including when it is empty.
            if (key in loaded) existing else {
                val cache = CatalogDiskCache.read<TmdbAvailabilityCache>(context, key) ?: existing
                memory[key] = cache
                loaded.add(key)
                cache
            }
        }
    }

    private val loaded = mutableSetOf<String>()

    suspend fun save(profile: String, type: CatalogType, id: Int, entry: TmdbAvailabilityEntry) = withContext(Dispatchers.IO) {
        read(profile, type)
        mutex.withLock {
            val key = key(profile, type)
            val entries = (memory.getValue(key).entries + (id to entry))
                .entries.sortedByDescending { it.value.checkedAtMillis }.take(2_000)
                .associate { it.key to it.value }
            val cache = TmdbAvailabilityCache(entries)
            CatalogDiskCache.write(context, key, cache)
            memory[key] = cache
        }
    }
}
