package com.nikhil.niktv.data

/** Serialize cache misses and bound retained catalogue records. */
internal class CatalogResponseCache<K, V>(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 5 * 60_000L,
    private val maxRecords: Int = 20_000
) {
    private data class Entry<V>(val time: Long, val items: List<V>)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    @Synchronized
    fun get(key: K, fetch: () -> List<V>): List<V> {
        entries[key]?.let { if (now() - it.time < ttlMillis) return it.items }
        entries.remove(key)
        val items = fetch()
        entries.entries.removeAll { now() - it.value.time >= ttlMillis }
        if (items.size <= maxRecords) {
            while (entries.isNotEmpty() &&
                (entries.size >= 8 || entries.values.sumOf { it.items.size } + items.size > maxRecords)) {
                entries.remove(entries.keys.first())
            }
            entries[key] = Entry(now(), items)
        }
        return items
    }

    @Synchronized
    fun invalidate(key: K) { entries.remove(key) }
}
