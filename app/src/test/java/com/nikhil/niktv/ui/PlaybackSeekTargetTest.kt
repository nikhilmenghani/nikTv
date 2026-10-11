package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekTargetTest {
    @Test fun repeatedPressesAccumulateBeforeTheDecoderCatchesUp() {
        val seek = PlaybackSeekTarget()
        assertEquals(30_000L, seek.target(20_000, 100_000, 10_000, 0))
        assertEquals(40_000L, seek.target(20_000, 100_000, 10_000, 100))
        assertEquals(30_000L, seek.target(20_000, 100_000, -10_000, 200))
    }

    @Test fun completedOrExpiredSeekUsesActualPlaybackPosition() {
        val seek = PlaybackSeekTarget()
        seek.target(20_000, 100_000, 10_000, 0)
        seek.acknowledge(30_000)
        assertEquals(42_000L, seek.target(32_000, 100_000, 10_000, 200))
        assertEquals(70_000L, seek.target(60_000, 100_000, 10_000, 6_000))
    }

    @Test fun seekingClampsAtBothEnds() {
        val seek = PlaybackSeekTarget()
        assertEquals(0L, seek.target(2_000, 100_000, -10_000, 0))
        assertEquals(100_000L, seek.absolute(200_000, 100_000, 100))
    }
}
