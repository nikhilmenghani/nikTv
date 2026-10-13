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
    @Test fun threeEmptyChannelsPauseOnlyTheirProfile() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.record("wio", "$it", false, 1000) }
        assertEquals(301_000L, policy.retryAt("wio", "4"))
        assertEquals(0L, policy.retryAt("nikhil", "4"))
    }
    @Test fun successfulForcedFetchClearsProfilePause() {
        val policy = LiveGuideRetryPolicy()
        (1..3).forEach { policy.record("wio", "$it", false, 1000) }
        policy.record("wio", "4", true, 2000)
        assertEquals(0L, policy.retryAt("wio", "4"))
        assertEquals(301_000L, policy.retryAt("wio", "1"))
    }
}
