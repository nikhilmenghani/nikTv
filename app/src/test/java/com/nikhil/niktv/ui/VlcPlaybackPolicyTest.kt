package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class VlcPlaybackPolicyTest {
    @Test fun livePrefetchDoesNotBecomeAStartupCacheOrDisableClockCorrection() {
        val options = vlcPlaybackOptions(live = true, offline = false, hls = true)
        assertTrue(options.contains("--network-caching=1500"))
        assertTrue(options.contains("--adaptive-lowlatency=0"))
        assertTrue(options.contains("--adaptive-livedelay=30000"))
        assertTrue(options.contains("--adaptive-maxbuffer=30000"))
        assertTrue(options.contains("--no-ts-trust-pcr"))
        assertFalse(options.any { it.startsWith("--clock-") })
    }

    @Test fun moviesAndEpisodesRetainTheirExistingBufferPolicy() {
        assertEquals(listOf("--network-caching=1500"), vlcPlaybackOptions(false, false, true))
    }

    @Test fun offlinePlaybackDoesNotUseAdaptiveLiveTiming() {
        assertEquals(listOf("--network-caching=1500"), vlcPlaybackOptions(true, true, true))
        assertEquals(listOf("--network-caching=1500"), vlcPlaybackOptions(false, true))
    }

    @Test fun progressiveLiveStreamsKeepTheirExistingTiming() {
        assertEquals(listOf("--network-caching=1500"), vlcPlaybackOptions(true, false, false))
    }

    @Test fun hlsDetectionHandlesSignedUrlsAndProviderFormatHints() {
        assertTrue(isHlsPlayback("hls", "https://example.com/live"))
        assertTrue(isHlsPlayback("", "https://example.com/live/INDEX.M3U8?token=example#video"))
        assertFalse(isHlsPlayback("MPEG-TS", "https://example.com/live.ts?label=m3u8"))
        assertFalse(isHlsPlayback("", "https://example.com/list.m3u"))
    }
}
