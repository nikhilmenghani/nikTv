package com.nikhil.niktv.ui

/** Engine leases protect active streams; eviction never destroys an in-use engine. */
internal class ReusablePlaybackEnginePool<K, T : Any>(
    private val create: (K) -> T,
    private val destroy: (T) -> Unit,
    private val clock: () -> Long,
    private val idleMillis: Long = 45_000L
) {
    private data class Entry<T>(val value: T, var users: Int, var idleSince: Long = 0L)
    private val entries = linkedMapOf<K, Entry<T>>()
    private val retired = java.util.IdentityHashMap<T, Entry<T>>()

    @Synchronized fun acquire(key: K): T {
        trimIdle()
        val entry = entries.getOrPut(key) { Entry(create(key), 0) }
        entry.users++
        return entry.value
    }

    @Synchronized fun release(key: K, value: T) {
        val retiredEntry = retired[value]
        val entry = retiredEntry ?: entries[key] ?: return
        check(entry.value === value && entry.users > 0)
        entry.users--
        if (retiredEntry != null) {
            if (entry.users == 0) {
                retired.remove(value)
                destroy(value)
            }
            return
        }
        if (entry.users == 0) entry.idleSince = clock()
        // Retain at most one idle configuration; other in-use engines stay alive.
        entries.entries.filter { it.value.users == 0 && it.key != key }.forEach {
            entries.remove(it.key)
            destroy(it.value.value)
        }
    }

    /** Stop lending a failed engine, but never destroy a lease another player still owns. */
    @Synchronized fun invalidate(key: K, value: T) {
        val entry = entries[key]?.takeIf { it.value === value } ?: return
        entries.remove(key)
        if (entry.users == 0) destroy(value) else retired[value] = entry
    }

    @Synchronized fun trimIdle() {
        val now = clock()
        entries.entries.filter { it.value.users == 0 && now - it.value.idleSince >= idleMillis }.forEach {
            entries.remove(it.key)
            destroy(it.value.value)
        }
    }
}
