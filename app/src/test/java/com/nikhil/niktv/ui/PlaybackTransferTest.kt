package com.nikhil.niktv.ui

import org.junit.Test
import org.junit.Assert.*

class PlaybackTransferTest {
    @Test fun pauseAndPositionSurviveBothDirections() {
        for (playing in listOf(false, true)) {
            val cast = playbackTransfer(75_000L, playing)
            val local = playbackTransfer(cast.positionMillis, cast.playWhenReady)
            assertEquals(75_000L, local.positionMillis)
            assertEquals(playing, local.playWhenReady)
        }
    }
    @Test fun UnknownClockDoesNotProduceNegativeSeek() {
        assertEquals(0L, playbackTransfer(androidx.media3.common.C.TIME_UNSET, false).positionMillis)
    }
    @Test fun reconnectHangIsOwnedByRecoveryInsteadOfInitialStartupTimer() {
        assertTrue(vlcInitialStartupTimedOut(true, false, false, true, false))
        assertFalse(vlcInitialStartupTimedOut(true, true, false, true, false))
        assertFalse(vlcInitialStartupTimedOut(true, false, false, false, false))
        assertTrue(vlcInitialStartupTimedOut(false, true, false, true, false))
        val recovery = LiveStreamRecovery(LiveStreamRecovery.VLC_RETRY_DELAYS_MILLIS)
        assertFalse(recovery.shouldRecover(0, 1000, true, false))
        assertFalse(recovery.shouldRecover(21_000, 1000, true, true))
        assertTrue(recovery.shouldRecover(23_000, 1000, true, true))
        assertFalse(recovery.shouldRecover(24_000, 0, true, true))
        assertTrue(recovery.shouldRecover(29_000, 0, true, true))
    }
}
