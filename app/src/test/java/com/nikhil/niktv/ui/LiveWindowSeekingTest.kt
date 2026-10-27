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
    @Test fun bothProviderTypesCanOptInWithoutEnablingOfflineOrVodSeekingPolicy() {
        assertTrue(providerLiveWindowSeekingEnabled(com.nikhil.niktv.model.CatalogType.LIVE_TV, false))
        assertFalse(providerLiveWindowSeekingEnabled(com.nikhil.niktv.model.CatalogType.LIVE_TV, true))
        assertFalse(providerLiveWindowSeekingEnabled(com.nikhil.niktv.model.CatalogType.MOVIES, false))
        assertFalse(providerLiveWindowSeekingEnabled(com.nikhil.niktv.model.CatalogType.SERIES, false))
    }
}
