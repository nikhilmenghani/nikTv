package com.nikhil.niktv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamRecoveryTest {
    @Test fun vlcReplacementFailuresUseBoundedBackoffAcrossAnOutage() {
        val recovery = LiveStreamRecovery(LiveStreamRecovery.VLC_RETRY_DELAYS_MILLIS)
        recovery.observePlayback(0, 1_000, true)
        assertFalse(recovery.shouldRecover(1_000, 1_000, true, true))
        assertFalse(recovery.shouldRecover(2_999, 1_000, true, true))
        assertTrue(recovery.shouldRecover(3_000, 1_000, true, true))
        assertFalse(recovery.exhausted)
        // The replacement player is preparing; this must not reset the budget.
        recovery.observePlayback(3_500, 0, false)
        assertFalse(recovery.shouldRecover(4_000, 0, true, true))
        assertFalse(recovery.shouldRecover(8_999, 0, true, true))
        assertTrue(recovery.shouldRecover(9_000, 0, true, true))
        var now = 9_000L
        for (delay in LiveStreamRecovery.VLC_RETRY_DELAYS_MILLIS.drop(2)) {
            assertFalse(recovery.shouldRecover(now, 0, true, true))
            assertFalse(recovery.shouldRecover(now + delay - 1, 0, true, true))
            now += delay
            assertTrue(recovery.shouldRecover(now, 0, true, true))
        }
        assertEquals(8, recovery.attemptCount)
        assertTrue(recovery.exhausted)
        assertTrue(recovery.unrecoverable(now, true, true))
        assertFalse(recovery.shouldRecover(now + 300_000, 0, true, true))
    }

    @Test fun vlcPausesDoNotSpendTheBudgetAndHealthyPlaybackRearmsPartialBudgets() {
        val recovery = LiveStreamRecovery(LiveStreamRecovery.VLC_RETRY_DELAYS_MILLIS)
        assertFalse(recovery.shouldRecover(0, 1_000, true, true))
        assertTrue(recovery.shouldRecover(2_000, 1_000, true, true))
        assertFalse(recovery.shouldRecover(60_000, 0, false, true))
        assertEquals(1, recovery.attemptCount)
        for (second in 0..61) recovery.observePlayback(61_000 + second * 1_000L, second * 1_000L, true)
        assertEquals(0, recovery.attemptCount)
        assertFalse(recovery.exhausted)
        assertFalse(recovery.shouldRecover(123_000, 61_000, true, true))
        assertTrue(recovery.shouldRecover(125_000, 61_000, true, true))
        assertEquals(1, recovery.attemptCount)
    }

    @Test fun exhaustedReadyButFrozenEngineIsActionableWithoutSpendingAnotherRetry() {
        val recovery = LiveStreamRecovery()
        recovery.shouldRecover(0, 100, true, false)
        assertTrue(recovery.shouldRecover(20_000, 100, true, false))
        // Replacement is preparing; the existing channel budget is retained.
        recovery.shouldRecover(21_000, 0, false, false)
        recovery.shouldRecover(22_000, 200, true, false)
        assertFalse(recovery.unrecoverable(41_999, true, false))
        assertTrue(recovery.unrecoverable(42_000, true, false))
        assertFalse(recovery.unrecoverable(42_000, false, false))
        assertFalse(recovery.shouldRecover(42_000, 200, true, false))
        recovery.shouldRecover(43_000, 1_200, true, false)
        assertFalse(recovery.unrecoverable(43_000, true, false))
    }

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
