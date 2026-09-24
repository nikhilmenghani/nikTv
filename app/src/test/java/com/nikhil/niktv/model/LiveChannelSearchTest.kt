package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class LiveChannelSearchTest {
    private val now = 1_000_000L
    private val channel = MediaItem("1", "Kids HD", null, null)
    private fun programme(title: String, start: Long = now - 1000, end: Long = now + 1000) =
        LiveProgramme(title, start, end)

    @Test fun matchesChannelNameWithoutGuide() {
        assertTrue(channel.matchesLiveChannelQuery("kids", now))
    }

    @Test fun findsProgrammeEvenWhenChannelNameDoesNotMatch() {
        val item = channel.copy(liveSchedule = listOf(programme("Shin-chan")))
        assertTrue(item.matchesLiveChannelQuery("shinchan", now))
        assertTrue(item.matchesLiveChannelQuery("SHIN CHAN", now))
    }

    @Test fun excludesPastAndFutureProgrammes() {
        val item = channel.copy(liveSchedule = listOf(
            programme("Shinchan", end = now),
            programme("Shinchan", start = now + 1000, end = now + 2000)
        ))
        assertFalse(item.matchesLiveChannelQuery("shinchan", now))
    }

    @Test fun checksEveryKeywordInMovieTitle() {
        val item = channel.copy(liveProgramme = programme("How I Met Your Mother"))
        assertTrue(item.matchesLiveChannelQuery("mother", now))
        assertTrue(item.matchesLiveChannelQuery("how mother", now))
        assertFalse(item.matchesLiveChannelQuery("mother shinchan", now))
    }

    @Test fun rejectsStaleUntimedTextAndLegacyCacheEntries() {
        assertFalse(channel.copy(liveProgramme = LiveProgramme("Shinchan"))
            .matchesLiveChannelQuery("shinchan", now))
        assertFalse(channel.copy(liveProgramme = LiveProgramme("Shinchan", observedAtMillis = now - 300_000))
            .matchesLiveChannelQuery("shinchan", now))
    }

    @Test fun acceptsFreshProviderNowPlayingText() {
        assertTrue(channel.copy(liveProgramme = LiveProgramme("Shinchan", observedAtMillis = now - 1000))
            .matchesLiveChannelQuery("shinchan", now))
    }

    @Test fun ignoresPlaceholderAndUntimedScheduleRows() {
        val item = channel.copy(liveProgramme = programme("No content available"),
            liveSchedule = listOf(LiveProgramme("Shinchan", observedAtMillis = now)))
        assertFalse(item.matchesLiveChannelQuery("content", now))
        assertFalse(item.matchesLiveChannelQuery("shinchan", now))
    }

    @Test fun timedCurrentScheduleWinsOverOldInlineTitle() {
        val item = channel.copy(liveProgramme = programme("Old film"),
            liveSchedule = listOf(programme("Shinchan")))
        assertEquals("Shinchan", item.currentLiveProgramme(now)?.title)
        assertFalse(item.matchesLiveChannelQuery("old film", now))
    }
}
