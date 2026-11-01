package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class WatchingSessionTest {
    @Test fun defaultCheckTriggersAtFourEpisodesAndResetsAfterInteraction() {
        val session = WatchingSession().apply { begin("series") }
        repeat(3) { session.completeEpisode(); assertFalse(session.due(WatchingConfiguration())) }
        session.completeEpisode()
        assertTrue(session.due(WatchingConfiguration()))
        session.interact()
        assertFalse(session.due(WatchingConfiguration()))
        assertEquals(0, session.completedEpisodes)
    }
    @Test fun twoHoursOfActualPlaybackTriggersWithoutRequiringFourEpisodes() {
        val session = WatchingSession()
        for (second in 0 until 7200) session.sample(second * 1000L, true)
        assertFalse(session.due(WatchingConfiguration()))
        session.sample(7_200_000, true)
        assertTrue(session.due(WatchingConfiguration()))
        assertFalse(session.due(WatchingConfiguration(enabled = false)))
    }
    @Test fun pausesBufferingAndBackgroundGapsDoNotCountAsWatchTime() {
        val session = WatchingSession()
        session.sample(0, true); session.sample(1000, true); session.sample(2000, false)
        session.sample(900_000, false); session.sample(901_000, true); session.sample(902_000, true)
        assertEquals(2000L, session.watchedMillis)
        session.interact()
        assertEquals(0L, session.watchedMillis)
        session.sample(999_000, true)
        assertEquals(0L, session.watchedMillis)
    }
    @Test fun customLimitsAndSeriesChangesAreRespected() {
        val session = WatchingSession().apply { begin("one") }
        repeat(2) { session.completeEpisode() }
        assertTrue(session.due(WatchingConfiguration(episodes = 2)))
        assertFalse(session.due(WatchingConfiguration(episodes = 6)))
        session.begin("one")
        assertEquals(2, session.completedEpisodes)
        session.begin("two")
        assertEquals(0, session.completedEpisodes)
    }
    @Test fun automaticEpisodeTransitionsKeepCountersButManualSelectionResetsThem() {
        val session = WatchingSession().apply { begin("series", "one") }
        session.completeEpisode()
        session.expectNextEpisode()
        session.begin("series", "two")
        session.begin("series", "two") // Parent and native player share one episode session.
        assertEquals(1, session.completedEpisodes)
        session.completeEpisode()
        session.begin("series", "five")
        assertEquals(0, session.completedEpisodes)
    }

}
