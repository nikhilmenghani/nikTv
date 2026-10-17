package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGuideRetryPolicyTest {
    @Test fun emptyChannelWaitsFiveMinutesWithoutBlockingOthers() {
        val policy = LiveGuideRetryPolicy()
        policy.record("wio", "1", false, 1000)
        assertEquals(301_000L, policy.retryAt("wio", "1"))
        assertEquals(0L, policy.retryAt("wio", "2"))
    }
    @Test fun threeEmptyChannelsDoNotHideGuidesOnOtherChannels() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.record("wio", "$it", false, 1000) }
        assertEquals(0L, policy.retryAt("wio", "4"))
        assertEquals(301_000L, policy.retryAt("wio", "1"))
        assertEquals(0L, policy.retryAt("nikhil", "4"))
    }
    @Test fun threeRequestFailuresStillPauseOnlyTheirProfile() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.recordFailure("wio", "$it", 1000) }
        assertEquals(301_000L, policy.retryAt("wio", "4"))
        assertEquals(0L, policy.retryAt("nikhil", "4"))
    }
    @Test fun successfulForcedFetchClearsProfilePause() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.recordFailure("wio", "$it", 1000) }
        policy.record("wio", "4", true, 2000)
        assertEquals(0L, policy.retryAt("wio", "4"))
        assertEquals(301_000L, policy.retryAt("wio", "1"))
    }
    @Test fun validResponseWithoutGuideResetsConsecutiveRequestFailures() {
        val policy = LiveGuideRetryPolicy()
        (1..2).forEach { policy.recordFailure("wio", "$it", 1000) }
        policy.record("wio", "3", false, 2000)
        policy.recordFailure("wio", "4", 3000)
        assertEquals(0L, policy.retryAt("wio", "5"))
    }
    @Test fun failuresSeparatedByTheCooldownDoNotAccumulateForever() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.recordFailure("wio", "$it", it * 300_000L) }
        assertEquals(0L, policy.retryAt("wio", "4"))
    }
}
