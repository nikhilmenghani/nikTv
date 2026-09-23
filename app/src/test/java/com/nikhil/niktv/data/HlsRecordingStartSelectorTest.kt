package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsRecordingStartSelectorTest {
    private val playlist = listOf(
        "#EXTM3U",
        "#EXT-X-TARGETDURATION:10",
        "#EXTINF:10.0,", "segment-1.ts",
        "#EXTINF:10.0,", "segment-2.ts",
        "#EXTINF:10.0,", "segment-3.ts",
        "#EXTINF:10.0,", "segment-4.ts",
        "#EXTINF:10.0,", "segment-5.ts"
    )

    @Test fun measuredPlayerOffsetSelectsItsBufferedSegment() {
        assertEquals(1, HlsRecordingStartSelector.segmentIndex(playlist, 30_000L))
        assertEquals(4, HlsRecordingStartSelector.segmentIndex(playlist, 0L))
    }

    @Test fun missingPlayerOffsetUsesThreeSegmentFallback() {
        assertEquals(1, HlsRecordingStartSelector.segmentIndex(playlist, null))
    }

    @Test fun shortOrVariablePlaylistStaysWithinAvailableSegments() {
        val short = listOf(
            "#EXT-X-TARGETDURATION:10",
            "#EXTINF:4.0,", "a.ts",
            "#EXTINF:6.0,", "b.ts"
        )
        assertEquals(0, HlsRecordingStartSelector.segmentIndex(short, 30_000L))
        assertEquals(1, HlsRecordingStartSelector.segmentIndex(short, 5_000L))
    }
}
