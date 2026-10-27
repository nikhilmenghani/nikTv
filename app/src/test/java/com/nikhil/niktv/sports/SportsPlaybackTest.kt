package com.nikhil.niktv.sports

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsPlaybackTest {
    private val a = SportsStream("https://example.test/a.m3u8", "A")
    private val b = SportsStream("https://example.test/b.m3u8", "B")

    @Test fun resolutionFailureAndPlayerFailureAdvanceAndIgnoreOldCallbacks() = runTest {
        val visited = mutableListOf<SportsStream>()
        val c = SportsPlaybackController(this, { emptyList() }) { visited += it; if (it == b) error("Unavailable"); it }
        c.start("event", listOf(b, a, a.copy(title = "Duplicate"), b.copy(link = "https://example.test/c.m3u8")))
        runCurrent()
        assertEquals(a, c.state.value.current)
        val firstId = c.state.value.attemptId
        c.failed(firstId); runCurrent()
        assertEquals("https://example.test/c.m3u8", c.state.value.current?.link)
        val secondId = c.state.value.attemptId
        c.failed(firstId); runCurrent()
        assertEquals(secondId, c.state.value.attemptId)
        assertEquals(3, visited.size)
        c.stop()
    }

    @Test fun allFailuresRefreshMetadataOnceAndStopWithoutLooping() = runTest {
        var refreshes = 0
        var attempts = 0
        val c = SportsPlaybackController(this, { refreshes++; listOf(a, b) }) { attempts++; error("Unavailable") }
        c.start("event", listOf(a, b)); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, refreshes)
        assertNotNull(c.state.value.error)
        assertFalse(c.state.value.resolving)
        c.retry(); runCurrent()
        assertEquals(4, attempts)
        assertEquals(2, refreshes)
    }

    @Test fun rotatedUrlGetsAnotherAttemptAndStopCancelsInFlightResolution() = runTest {
        val fresh = a.copy(link = a.link + "?newToken=fixture")
        val c = SportsPlaybackController(this, { listOf(fresh) }) { if (it == a) error("Expired"); it }
        c.start("event", listOf(a)); runCurrent()
        assertEquals(fresh, c.state.value.current)
        c.stop()
        val pending = SportsPlaybackController(this, { emptyList() }) { awaitCancellation() }
        pending.start("event", listOf(a)); runCurrent(); pending.stop(); runCurrent()
        assertFalse(pending.state.value.active)
        assertNull(pending.state.value.current)
    }

    @Test fun watchdogAllowsStartupAndBufferingAndResetsOnPause() {
        val w = SportsPlaybackWatchdog()
        fun tick(t: Long, play: Boolean = true, ready: Boolean = false, frame: Boolean = false, ended: Boolean = false) = w.failed(t, play, ready, ended, frame)
        assertFalse(tick(0)); assertFalse(tick(11999)); assertTrue(tick(12000))
        assertFalse(tick(13000, ready = true, frame = true))
        assertFalse(tick(14000, frame = true)); assertTrue(tick(22000, frame = true))
        assertFalse(tick(23000, play = false, frame = true))
        assertFalse(tick(50000, frame = true)); assertTrue(tick(58000, frame = true))
        assertFalse(tick(59000, play = false, ended = true))
        assertTrue(tick(60000, ended = true))
    }

    @Test fun refreshCadenceDoesNotDependOnAnArbitraryNumberOfDays() {
        assertTrue(sportsCatalogueNeedsRefresh(null, 1000))
        assertFalse(sportsCatalogueNeedsRefresh(1000, 120999))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 121000))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 1000 + 3 * 86400000L))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 0))
    }

    @Test fun readyPlayerWithFrozenPositionAlsoFallsBack() {
        val w = SportsPlaybackWatchdog()
        assertFalse(w.failed(0, true, true, false, true, 5000))
        assertFalse(w.failed(7000, true, true, false, true, 5100))
        assertFalse(w.failed(14999, true, true, false, true, 5100))
        assertTrue(w.failed(15000, true, true, false, true, 5100))
        assertFalse(w.failed(16000, false, true, false, true, 5100))
        assertFalse(w.failed(30000, true, true, false, true, 5100))
    }
}
