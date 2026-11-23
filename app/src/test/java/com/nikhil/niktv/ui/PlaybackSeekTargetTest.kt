package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekTargetTest {
    @Test fun firstPollAfterPlaybackAdvancesDoesNotFreezeTheRequestedTime() {
        val seek = PlaybackSeekTarget()
        seek.absolute(20_000, 100_000, 0)
        assertEquals(21_000L, seek.displayPosition(21_000, 1_000))
        assertEquals(22_000L, seek.displayPosition(22_000, 2_000))
    }
    @Test fun staleClockCannotMoveTheDisplayedButtonTargetBackwards() {
        val seek = PlaybackSeekTarget()
        seek.absolute(30_000, 100_000, 0)
        assertEquals(30_000L, seek.displayPosition(20_000, 300))
        assertEquals(30_000L, seek.displayPosition(20_000, 1_000))
        assertEquals(30_100L, seek.displayPosition(30_100, 1_100))
        assertEquals(31_000L, seek.displayPosition(31_000, 1_500))
        seek.absolute(40_000, 100_000, 2_000)
        assertEquals(32_000L, seek.displayPosition(32_000, 7_001))
    }
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
