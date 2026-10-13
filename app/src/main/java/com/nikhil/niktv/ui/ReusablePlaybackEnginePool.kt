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

    @Synchronized fun acquire(key: K): T {
        trimIdle()
        val entry = entries.getOrPut(key) { Entry(create(key), 0) }
        entry.users++
        return entry.value
    }

    @Synchronized fun release(key: K, value: T) {
        val entry = entries[key] ?: return
        check(entry.value === value && entry.users > 0)
        entry.users--
        if (entry.users == 0) entry.idleSince = clock()
        // Retain at most one idle configuration; other in-use engines stay alive.
        entries.entries.filter { it.value.users == 0 && it.key != key }.forEach {
            entries.remove(it.key)
            destroy(it.value.value)
        }
    }

    @Synchronized fun trimIdle() {
        val now = clock()
        entries.entries.filter { it.value.users == 0 && now - it.value.idleSince >= idleMillis }.forEach {
            entries.remove(it.key)
            destroy(it.value.value)
        }
    }
}
