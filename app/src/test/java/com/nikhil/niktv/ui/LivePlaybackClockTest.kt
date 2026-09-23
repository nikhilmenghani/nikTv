package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackClockTest {
    @Test fun timerOnlyAdvancesWhileFramesArePlaying() {
        val clock = LivePlaybackClock()
        assertEquals(0L, clock.sample(1_000L, false))
        assertEquals(0L, clock.sample(4_000L, true))
        assertEquals(4_000L, clock.sample(8_000L, false))
        assertEquals(4_000L, clock.sample(20_000L, true))
        assertEquals(5_500L, clock.sample(21_500L, true))
    }
}
