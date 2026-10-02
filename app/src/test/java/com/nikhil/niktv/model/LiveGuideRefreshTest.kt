package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class LiveGuideRefreshTest {
    private val now = 1_000_000L
    private val old = LiveProgramme("Old movie", now - 10_000, now + 10_000)
    private val channel = MediaItem("1", "Movies", null, "stream", liveProgramme = old, liveSchedule = listOf(old))

    @Test fun successfulEmptyResponseClearsPreviouslyCachedCurrentProgramme() {
        val updated = channel.withFreshLiveSchedule(emptyList(), now)
        assertNull(updated.currentLiveProgramme(now))
        assertNull(updated.liveProgramme)
        assertTrue(updated.liveSchedule.isEmpty())
        assertEquals("stream", updated.command)
    }

    @Test fun futureOnlyResponseDoesNotKeepPreviousMovieAsNowPlaying() {
        val next = LiveProgramme("Next", now + 5_000, now + 20_000)
        val updated = channel.withFreshLiveSchedule(listOf(next), now)
        assertNull(updated.liveProgramme)
        assertEquals(listOf(next), updated.liveSchedule)
        assertEquals(next, updated.currentLiveProgramme(now + 5_000))
    }

    @Test fun providerRecoveryReplacesEmptyGuideAndExpiresAtExactBoundary() {
        val blank = channel.withFreshLiveSchedule(emptyList(), now)
        val fresh = LiveProgramme("New movie", now, now + 5_000)
        val updated = blank.withFreshLiveSchedule(listOf(fresh), now)
        assertEquals(fresh, updated.currentLiveProgramme(now))
        assertNull(updated.currentLiveProgramme(now + 5_000))
    }

    @Test fun expiredOrPlaceholderResponseCannotReviveOldProgramme() {
        val updated = channel.withFreshLiveSchedule(listOf(
            old.copy(endTimeMillis = now), LiveProgramme("No content available", now, now + 1000)
        ), now)
        assertNull(updated.liveProgramme)
        assertTrue(updated.liveSchedule.isEmpty())
    }
}
