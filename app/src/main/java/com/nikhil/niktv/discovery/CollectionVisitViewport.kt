package com.nikhil.niktv.discovery

import java.util.concurrent.atomic.AtomicLong

/** Visit IDs must not repeat when a ViewModel is recreated in the same process. */
internal object CatalogVisitIds {
    private val sequence = AtomicLong()
    fun next(): Long = sequence.incrementAndGet()
}

internal data class CollectionViewport(val index: Int = 0, val offset: Int = 0, val ready: Boolean = false)

/** Keeps playback returns within their visit, without reviving a previous category opening. */
internal class CollectionVisitViewportMemory(private val limit: Int = 128) {
    private val positions = linkedMapOf<String, CollectionViewport>()
    fun get(key: String): CollectionViewport? = positions[key]
    fun entry(key: String): CollectionViewport = positions[key]?.takeIf { it.ready } ?: CollectionViewport()
    fun put(key: String, index: Int, offset: Int) {
        positions[key] = CollectionViewport(index.coerceAtLeast(0), offset.coerceAtLeast(0), positions[key]?.ready == true)
        trim()
    }
    fun markReady(key: String) {
        positions[key] = (positions[key] ?: CollectionViewport()).copy(ready = true)
        trim()
    }
    private fun trim() { while (positions.size > limit) positions.remove(positions.keys.first()) }
}
