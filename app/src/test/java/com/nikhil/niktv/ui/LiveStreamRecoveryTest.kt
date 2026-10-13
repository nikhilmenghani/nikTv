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

    @Test fun aMinuteOfContinuousPlaybackRearmsRecovery() {
        val recovery = LiveStreamRecovery()
        assertTrue(recovery.shouldRecover(0, 100, true, true))
        for (second in 1..61) {
            assertFalse(recovery.shouldRecover(second * 1_000L, second * 1_000L, true, false))
        }
        assertFalse(recovery.exhausted)
        assertTrue(recovery.shouldRecover(62_000, 61_000, true, true))
        assertFalse(recovery.shouldRecover(63_000, 61_000, true, true))
    }

    @Test fun intermittentProgressAndPausesDoNotRearmRecovery() {
        val recovery = LiveStreamRecovery()
        assertTrue(recovery.claimRecovery())
        for (second in 1..10) {
            assertFalse(recovery.shouldRecover(second * 10_000L, second * 1_000L, true, false))
        }
        assertTrue(recovery.exhausted)
        recovery.shouldRecover(110_000, 10_000, false, false)
        assertFalse(recovery.shouldRecover(200_000, 11_000, true, true))
    }

    @Test fun manualResetAllowsRecoveryWithoutWaiting() {
        val recovery = LiveStreamRecovery()
        assertTrue(recovery.claimRecovery())
        assertFalse(recovery.claimRecovery())
        recovery.reset()
        assertTrue(recovery.claimRecovery())
    }

    @Test fun briefRebuffersDoNotPreventRecoveryFromRearming() {
        val recovery = LiveStreamRecovery()
        recovery.observePlayback(0, 0, true)
        assertTrue(recovery.claimRecovery())
        var position = 0L
        for (second in 1..62) {
            if (second % 30 != 0) position += 1_000
            recovery.observePlayback(second * 1_000L, position, true)
        }
        assertFalse(recovery.exhausted)
        assertTrue(recovery.claimRecovery())
    }

    @Test fun stalledTimeDoesNotRearmAndObservationDoesNotSpendRetries() {
        val recovery = LiveStreamRecovery()
        recovery.observePlayback(0, 0, true)
        for (second in 1..90) recovery.observePlayback(second * 1_000L, 0, true)
        assertFalse(recovery.exhausted)
        assertTrue(recovery.claimRecovery())
        for (second in 91..180) recovery.observePlayback(second * 1_000L, 0, true)
        assertTrue(recovery.exhausted)
        assertFalse(recovery.shouldRecover(181_000, 0, true, true))
    }

    @Test fun aClockJumpDoesNotCountAsAMinuteOfPlayback() {
        val recovery = LiveStreamRecovery()
        recovery.observePlayback(0, 0, true)
        recovery.claimRecovery()
        recovery.observePlayback(1_000, 1_000_000, true)
        assertTrue(recovery.exhausted)
        recovery.observePlayback(60_000, 2_000_000, true)
        assertTrue(recovery.exhausted)
    }
}
