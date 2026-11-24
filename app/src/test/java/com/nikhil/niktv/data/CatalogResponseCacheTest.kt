package com.nikhil.niktv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogResponseCacheTest {
    @Test fun reusesExpiresAndInvalidates() = runTest {
        var time = 0L
        var calls = 0
        val cache = CatalogResponseCache<String, Int>(now = { time }, ttlMillis = 100)
        suspend fun read() = cache.get("a") { calls++; listOf(1, 2) }
        read(); read()
        assertEquals(1, calls)
        time = 100
        read()
        assertEquals(2, calls)
        cache.invalidate("a")
        read()
        assertEquals(3, calls)
    }
    @Test fun isolatesAndEvicts() = runTest {
        val cache = CatalogResponseCache<String, Int>(maxRecords = 3)
        cache.get("a") { listOf(1, 2) }
        cache.get("b") { listOf(3, 4) }
        assertEquals(listOf(3, 4), cache.get("b") { error("cached") })
        assertEquals(listOf(5), cache.get("a") { listOf(5) })
    }
    @Test fun retriesFailure() = runTest {
        val cache = CatalogResponseCache<String, Int>()
        runCatching { cache.get("a") { error("offline") } }
        assertEquals(listOf(1), cache.get("a") { listOf(1) })
    }

    @Test fun slowFetchDoesNotBlockInvalidationOrOtherCategoriesAndCannotRepopulateAfterRefresh() = runTest {
        val cache = CatalogResponseCache<String, Int>()
        val release = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("a") { release.await(); listOf(1) }
        }
        cache.invalidate("a")
        assertEquals(listOf(3), cache.get("b") { listOf(3) })
        assertEquals(listOf(2), cache.get("a") { listOf(2) })
        release.complete(Unit)
        assertEquals(listOf(1), old.await())
        assertEquals(listOf(2), cache.get("a") { error("New cache must survive old completion") })
    }

    @Test fun cancellingWarmupLetsAnAttachedReaderTakeOverTheFetch() = runTest {
        val cache = CatalogResponseCache<String, Int>()
        val warmup = async(start = CoroutineStart.UNDISPATCHED) { cache.get("movies") { awaitCancellation() } }
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { cache.get("movies") { listOf(42) } }
        assertFalse(foreground.isCompleted)
        warmup.cancelAndJoin()
        assertEquals(listOf(42), foreground.await())
        assertEquals(listOf(42), cache.get("movies") { error("Foreground result should be cached") })
    }
}
