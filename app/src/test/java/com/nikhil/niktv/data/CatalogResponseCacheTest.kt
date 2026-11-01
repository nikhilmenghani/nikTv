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

    @Test fun slowFetchDoesNotBlockInvalidationOrOtherCategoriesAndCannotRepopulateAfterRefresh() {
        val cache = CatalogResponseCache<String, Int>()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val old = executor.submit<List<Int>> {
                cache.get("a") { started.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); listOf(1) }
            }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val refreshed = executor.submit<List<Int>> {
                cache.invalidate("a")
                assertEquals(listOf(3), cache.get("b") { listOf(3) })
                cache.get("a") { listOf(2) }
            }
            assertEquals(listOf(2), refreshed.get(2, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            assertEquals(listOf(1), old.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf(2), cache.get("a") { error("New cache must survive old completion") })
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
