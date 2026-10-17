package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedPortalGuideCacheTest {
    private fun schedule(now: Long) = mapOf("1" to listOf(
        LiveProgramme("Current", now - 1000, now + 1000),
        LiveProgramme("Next", now + 1000, now + 5000)))

    @Test fun simultaneousTilesShareOneFetchAndCancellingOneDoesNotCancelAnother() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val cache = SharedPortalGuideCache(this, now = { 10_000L })
        val first = async { cache.get("wio") { loads++; release.await(); schedule(10_000L) } }
        yield()
        val second = async { cache.get("wio") { error("Duplicate download") } }
        yield()
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("Current", second.await()!!.schedules["1"]!!.first().title)
        assertEquals(1, loads)
    }

    @Test fun diskCacheAvoidsNetworkAndSchedulesAdvanceLocallyAtExactBoundary() = runBlocking {
        var now = 10_000L
        val stored = PortalGuideCache("wio", now, schedule(now))
        var reads = 0
        val cache = SharedPortalGuideCache(this, now = { now }, read = { reads++; stored })
        val first = cache.cached("wio")!!
        val item = MediaItem("1", "Channel", null, null).withFreshLiveSchedule(first.schedules["1"]!!, now)
        assertEquals("Current", item.currentLiveProgramme(now)?.title)
        now += 1000
        assertEquals("Next", item.currentLiveProgramme(now)?.title)
        assertNotNull(cache.get("wio") { error("Unnecessary provider request") })
        assertEquals(1, reads)
    }

    @Test fun freshNegativeGuideIsSharedAndManualRefreshHasOneMinuteDownloadFloor() = runBlocking {
        var now = 1_000L
        var loads = 0
        val cache = SharedPortalGuideCache(this, now = { now })
        suspend fun fetch(force: Boolean = false) = cache.get("wio", force) { loads++; emptyMap() }
        fetch(); fetch(); fetch(force = true)
        assertEquals(1, loads)
        now += 60_000
        fetch(force = true)
        assertEquals(2, loads)
        now += 300_000
        fetch()
        assertEquals(3, loads)
    }

    @Test fun transportFailureCoolsDownBulkFetchButAuthFailuresRemainRetryable() = runBlocking { supervisorScope {
        var now = 1_000L
        var loads = 0
        val cache = SharedPortalGuideCache(this, now = { now }, propagate = { it is SecurityException })
        assertNull(cache.get("wio") { loads++; error("Temporary network failure") })
        assertNull(cache.get("wio") { error("Should be cooling down") })
        now += 300_000
        try { cache.get("wio") { loads++; throw SecurityException("Expired token") }; fail() }
        catch (_: SecurityException) { }
        assertNotNull(cache.get("wio") { loads++; schedule(now) })
        assertEquals(3, loads)
    } }

    @Test fun profileReplacementRejectsObsoleteWorkAndWrongDiskIdentity() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = SharedPortalGuideCache(this, now = { 10_000L },
            read = { PortalGuideCache("other-profile", 10_000L, schedule(10_000L)) })
        val old = async { cache.get("wio") { started.complete(Unit); release.await(); schedule(10_000L) } }
        started.await()
        val fresh = cache.get("other-account") { mapOf("2" to schedule(10_000L).getValue("1")) }
        assertTrue(old.isCancelled)
        assertTrue(fresh!!.schedules.containsKey("2"))
        assertNull(cache.cached("wio"))
    }

    @Test fun foregroundPlaybackCancelsBulkDownloadWithoutWaitingForCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val cache = SharedPortalGuideCache(this, now = { 10_000L })
        val loading = async { cache.get("wio") {
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        } }
        started.await()
        cache.cancelPending()
        // Cancellation does not join obsolete work, and a replacement can complete immediately.
        assertNotNull(cache.get("other-profile") { schedule(10_000L) })
        cleanup.complete(Unit)
        loading.cancelAndJoin()
    }

    @Test fun expiredDiskSnapshotRefreshesAndWriteFailureDoesNotLoseGoodMetadata() = runBlocking {
        val cache = SharedPortalGuideCache(this, now = { 2_000_000L },
            read = { PortalGuideCache("wio", 0L, schedule(2_000_000L)) },
            write = { error("Disk full") })
        var loaded = false
        assertNotNull(cache.get("wio") { loaded = true; schedule(2_000_000L) })
        assertTrue(loaded)
        assertNotNull(cache.cached("wio"))
    }

    @Test fun authoritativeEmptyRefreshCannotReviveThePreviousProgramme() = runBlocking {
        var now = 1_000L
        val cache = SharedPortalGuideCache(this, now = { now })
        cache.get("wio") { mapOf("1" to listOf(LiveProgramme("Old", now, now + 24 * 60 * 60_000L))) }
        now += 60_000
        assertTrue(cache.get("wio", force = true) { emptyMap() }!!.schedules.isEmpty())
        assertTrue(cache.cached("wio")!!.schedules.isEmpty())
    }

    @Test fun boundsDropExpiredUntimedAndPlaceholderRowsAndCapMemory() {
        val now = 10_000L
        val rows = listOf(LiveProgramme("Expired", 1L, now), LiveProgramme("Untimed"),
            LiveProgramme("No channel info", now, now + 1000)) +
            (1..25_000).map { LiveProgramme("Programme $it", now + it, now + it + 1000) }
        val bounded = boundedPortalGuide("wio", now, mapOf("1" to rows), now)
        assertEquals(20_000, bounded.schedules.getValue("1").size)
        assertTrue(bounded.schedules.getValue("1").all { it.title.startsWith("Programme ") })
    }
}
