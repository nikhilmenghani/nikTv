package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test

class CatalogResponseCacheTest {
    @Test fun reusesExpiresAndInvalidates() {
        var time = 0L
        var calls = 0
        val cache = CatalogResponseCache<String, Int>(now = { time }, ttlMillis = 100)
        fun read() = cache.get("a") { calls++; listOf(1, 2) }
        read(); read()
        assertEquals(1, calls)
        time = 100
        read()
        assertEquals(2, calls)
        cache.invalidate("a")
        read()
        assertEquals(3, calls)
    }
    @Test fun isolatesAndEvicts() {
        val cache = CatalogResponseCache<String, Int>(maxRecords = 3)
        cache.get("a") { listOf(1, 2) }
        cache.get("b") { listOf(3, 4) }
        assertEquals(listOf(3, 4), cache.get("b") { error("cached") })
        assertEquals(listOf(5), cache.get("a") { listOf(5) })
    }
    @Test fun retriesFailure() {
        val cache = CatalogResponseCache<String, Int>()
        runCatching { cache.get("a") { error("offline") } }
        assertEquals(listOf(1), cache.get("a") { listOf(1) })
    }
}
