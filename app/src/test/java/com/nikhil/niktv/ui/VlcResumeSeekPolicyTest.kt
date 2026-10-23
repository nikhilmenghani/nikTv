package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class VlcResumeSeekPolicyTest {
    private fun VlcResumeSeekPolicy.tick(time: Long, actual: Long = 0L, started: Boolean = true,
        requested: Boolean = true, seekable: Boolean = true, duration: Long = 120_000L, sequence: Long = time) =
        sample(time, started, requested, seekable, duration, actual, sequence)

    @Test fun playingAndDurationAloneDoNotIssueASeekBeforeTheDemuxerIsSeekable() {
        val policy = VlcResumeSeekPolicy(6_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(0L, seekable = false).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(500L, seekable = false).kind)
        val ready = policy.tick(1_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.ISSUE_SEEK, ready.kind)
        assertEquals(6_000L, ready.target)
    }

    @Test fun aQueuedCommandDoesNotClearThePendingTargetOrIssueAnotherSeek() {
        val policy = VlcResumeSeekPolicy(6_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.ISSUE_SEEK, policy.tick(0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(500L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(1_000L, actual = 6_000L).kind)
        policy.commandCompleted(1_100L, 0L, 1_100L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(1_500L, actual = 1_000L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(2_000L, actual = 6_200L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, policy.tick(2_500L, actual = 6_500L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, policy.tick(3_000L, actual = 7_000L).kind)
    }

    @Test fun nonSeekableVodKeepsPlayingAndStopsWaitingAfterABoundedActiveWindow() {
        val policy = VlcResumeSeekPolicy(6_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(0L, seekable = false, duration = 0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(4_999L, actual = 4_999L, seekable = false, duration = 0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, policy.tick(5_000L, actual = 5_000L, seekable = false, duration = 0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, policy.tick(6_000L, actual = 6_000L).kind)
    }

    @Test fun queuedOrUnacknowledgedSeeksAreBoundedWithoutReseeking() {
        val queued = VlcResumeSeekPolicy(60_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.ISSUE_SEEK, queued.tick(0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, queued.tick(5_000L).kind)
        queued.commandCompleted(5_100L, 0L, 5_100L)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, queued.tick(5_500L, actual = 60_000L).kind)

        val unacknowledged = VlcResumeSeekPolicy(60_000L)
        unacknowledged.tick(0L)
        unacknowledged.commandCompleted(100L, 0L, 100L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, unacknowledged.tick(4_099L, actual = 4_000L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, unacknowledged.tick(4_100L, actual = 4_100L).kind)
    }

    @Test fun userPauseAndUnstartedPlaybackDoNotConsumeTheResumeWindow() {
        val policy = VlcResumeSeekPolicy(6_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(0L, started = false, requested = false).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(60_000L, started = false, requested = false).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ISSUE_SEEK, policy.tick(61_000L, requested = false).kind)
        policy.commandCompleted(61_000L, 0L, 61_000L)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(90_000L, requested = false).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(90_100L, actual = 6_000L, requested = false).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, policy.tick(90_200L, actual = 6_000L, requested = false).kind)
    }

    @Test fun failedNativeCommandStopsWaitingAndNeverRetriesIt() {
        val policy = VlcResumeSeekPolicy(6_000L)
        policy.tick(0L)
        policy.commandCompleted(100L, -1L, 100L)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, policy.tick(200L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.UNAVAILABLE, policy.tick(500L).kind)
    }

    @Test fun targetsAreClampedAndZeroResumeNeverSeeks() {
        val policy = VlcResumeSeekPolicy(200_000L)
        assertEquals(119_000L, policy.tick(0L).target)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, VlcResumeSeekPolicy(0L).tick(0L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, VlcResumeSeekPolicy(500L).tick(0L, duration = 500L).kind)
    }

    @Test fun optimisticCommandTimeCannotAcknowledgeBeforeTheNativeClockSettlesAfterAKeyframe() {
        val policy = VlcResumeSeekPolicy(6_854L)
        assertEquals(VlcResumeSeekPolicy.Kind.ISSUE_SEEK, policy.tick(0L, sequence = 10L).kind)
        policy.commandCompleted(100L, 7L, 11L)
        // setTime immediately echoes the target; it isn't a fresh decoder clock sample.
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(500L, actual = 6_854L, sequence = 11L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(1_000L, actual = 6_854L, sequence = 12L).kind)
        // The tablet trace then delivered an older keyframe timestamp before settling.
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(1_500L, actual = 4_159L, sequence = 13L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(2_000L, actual = 7_223L, sequence = 14L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.PENDING, policy.tick(2_500L, actual = 7_213L, sequence = 15L).kind)
        assertEquals(VlcResumeSeekPolicy.Kind.ACKNOWLEDGED, policy.tick(3_000L, actual = 7_523L, sequence = 16L).kind)
    }
}
