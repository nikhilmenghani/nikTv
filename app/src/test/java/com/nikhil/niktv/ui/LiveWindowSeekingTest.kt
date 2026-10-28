package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class LiveWindowSeekingTest {
    @Test fun enabledSportsCanSeekWhenNativeWindowIsSeekable() {
        assertTrue(liveWindowCanSeek(true, true, 120_000))
    }
    @Test fun ordinaryIptvAndUnknownOrUnseekableWindowsRemainUnchanged() {
        assertFalse(liveWindowCanSeek(false, true, 120_000))
        assertFalse(liveWindowCanSeek(true, false, 120_000))
        assertFalse(liveWindowCanSeek(true, true, -9223372036854775807L))
        assertFalse(liveWindowCanSeek(true, true, 0))
        assertFalse(liveWindowCanSeek(true, true, 20_000, liveTimeline = false))
    }
    @Test fun ordinaryIptvMediaDoesNotEnableLiveWindowSeeking() {
        val media = com.nikhil.niktv.model.PlayingMedia(
            com.nikhil.niktv.model.MediaItem("fixture", "Fixture", null, null),
            "https://example.test/live", com.nikhil.niktv.model.CatalogType.LIVE_TV)
        assertFalse(media.allowLiveWindowSeeking)
    }
}
