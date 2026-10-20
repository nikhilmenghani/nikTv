package com.nikhil.niktv.discovery

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun expiryAndClockRollbackAreNotFresh() {
        val e = DiscoveryCacheEntry(1000, "payload")
        assertTrue(e.fresh(1000, 6000)); assertTrue(e.fresh(6999, 6000))
        assertFalse(e.fresh(7000, 6000)); assertFalse(e.fresh(999, 6000))
    }
    @Test fun publicEntriesPersistAcrossInstancesAndKeysRemainIsolated() = runBlocking {
        val root = temp.newFolder()
        DiscoveryCache(root, { 42 }).write("movies", "one")
        DiscoveryCache(root, { 43 }).write("series", "two")
        assertEquals("one", DiscoveryCache(root).read("movies")?.payload)
        assertEquals("two", DiscoveryCache(root).read("series")?.payload)
        assertNull(DiscoveryCache(root).read("absent"))
        assertEquals(42L, DiscoveryCache(root).read("movies")?.savedAt)
    }
    @Test fun cacheEvictsToItsByteBudgetAndRejectsOversizedEntries() = runBlocking {
        val root = temp.newFolder()
        val cache = DiscoveryCache(root, maxBytes = 150)
        cache.write("a", "a".repeat(60)); Thread.sleep(5)
        cache.write("b", "b".repeat(60))
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 150)
        assertEquals("b".repeat(60), cache.read("b")?.payload)
        cache.write("huge", "x".repeat(200)); assertNull(cache.read("huge"))
    }
    @Test fun malformedFilesDoNotBreakDiscovery() = runBlocking {
        val root = temp.newFolder(); val cache = DiscoveryCache(root)
        cache.write("a", "good")
        root.listFiles()!!.single().writeText("{invalid")
        assertNull(cache.read("a"))
        cache.write("a", "recovered"); assertEquals("recovered", cache.read("a")?.payload)
    }
    @Test fun concurrentWritesStayReadableAndLeaveNoPartialFiles() = runBlocking {
        val root = temp.newFolder(); val cache = DiscoveryCache(root)
        coroutineScope { (1..20).map { i -> async { cache.write("key-$i", "payload-$i") } }.awaitAll() }
        (1..20).forEach { assertEquals("payload-$it", cache.read("key-$it")?.payload) }
        assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
    }
    @Test fun cachedHitAvoidsNetworkAndFailedRefreshKeepsStaleData() = runBlocking {
        var now = 1000L; var calls = 0
        val cache = DiscoveryCache(temp.newFolder(), { now })
        assertEquals("first", cache.load("feed", 100, String.serializer()) { calls++; "first" })
        now = 1050
        assertEquals("first", cache.load("feed", 100, String.serializer()) { calls++; "wrong" })
        assertEquals(1, calls)
        now = 1200
        var published: String? = null
        assertEquals("first", cache.load("feed", 100, String.serializer(), { published = it }) { calls++; error("offline") })
        assertEquals("first", published); assertEquals(2, calls)
    }
    @Test fun canceledRefreshDoesNotBecomeASuccessOrOverwriteCache() = runBlocking {
        var now = 0L
        val cache = DiscoveryCache(temp.newFolder(), { now })
        cache.load("feed", 100, String.serializer()) { "saved" }; now = 200
        try { cache.load("feed", 100, String.serializer()) { throw CancellationException("obsolete") }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertTrue(cache.read("feed")!!.payload.contains("saved"))
    }
    @Test fun concurrentMatchingLoadsDeduplicateNetwork() = runBlocking {
        val cache = DiscoveryCache(temp.newFolder()); var calls = 0
        val values = coroutineScope { (1..5).map { async {
            cache.load("same", 10000, String.serializer()) { calls++; delay(20); "result" }
        } }.awaitAll() }
        assertEquals(List(5) { "result" }, values); assertEquals(1, calls)
    }
    @Test fun cancelingOneConsumerDoesNotBreakTheNextConsumer() = runBlocking {
        val cache = DiscoveryCache(temp.newFolder())
        val started = CompletableDeferred<Unit>()
        val first = async { cache.load("same", 10000, String.serializer()) { started.complete(Unit); delay(10000); "old" } }
        started.await()
        val next = async { cache.load("same", 10000, String.serializer()) { "selected" } }
        first.cancelAndJoin()
        assertEquals("selected", next.await())
    }
    @Test fun publicMetadataDeduplicatesAcrossProfileRepositoryInstances() = runBlocking {
        val directory = temp.newFolder(); val first = DiscoveryCache(directory); val second = DiscoveryCache(directory)
        var calls = 0
        coroutineScope {
            val a = async { first.load("same", 10000, String.serializer()) { calls++; delay(30); "shared" } }
            val b = async { second.load("same", 10000, String.serializer()) { calls++; "duplicate" } }
            assertEquals("shared", a.await()); assertEquals("shared", b.await())
        }
        assertEquals(1, calls)
    }
}
