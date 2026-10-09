package com.nikhil.niktv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamRecoveryTest {
    @Test fun stoppedStreamRecoversOnlyOnce() {
        val recovery = LiveStreamRecovery()
        assertTrue(recovery.shouldRecover(0, 100, true, true))
        assertFalse(recovery.shouldRecover(30_000, 100, true, true))
    }

    @Test fun stalledStreamGetsTwentySecondsToRecoverNaturally() {
        val recovery = LiveStreamRecovery()
        assertFalse(recovery.shouldRecover(0, 100, true, false))
        assertFalse(recovery.shouldRecover(19_999, 100, true, false))
        assertTrue(recovery.shouldRecover(20_000, 100, true, false))
    }

    @Test fun pauseOrBackgroundResetsStallTimer() {
        val recovery = LiveStreamRecovery()
        assertFalse(recovery.shouldRecover(0, 100, true, false))
        assertFalse(recovery.shouldRecover(30_000, 100, false, true))
        assertFalse(recovery.shouldRecover(60_000, 100, true, false))
        assertFalse(recovery.shouldRecover(79_000, 100, true, false))
    }

    @Test fun advancingPositionDoesNotReconnect() {
        val recovery = LiveStreamRecovery()
        assertFalse(recovery.shouldRecover(0, 100, true, false))
        assertFalse(recovery.shouldRecover(30_000, 200, true, false))
        assertFalse(recovery.shouldRecover(60_000, 50, true, false))
    }
}
