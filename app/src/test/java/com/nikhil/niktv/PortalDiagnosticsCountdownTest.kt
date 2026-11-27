package com.nikhil.niktv

import com.nikhil.niktv.model.portalDiagnosticsCountdown
import org.junit.Assert.*
import org.junit.Test

class PortalDiagnosticsCountdownTest {
    @Test fun ticksAgainstTheSameDeadlineAndPreservesTheMessage() {
        val message = "Portal protection is active. Wait 1h 0m 0s before making another server request."
        assertEquals(message, portalDiagnosticsCountdown(message, 3_600_000, 0))
        assertEquals("Portal protection is active. Wait 59m 59s before making another server request.",
            portalDiagnosticsCountdown(message, 3_600_000, 1000))
        assertEquals("Portal protection is active. Wait 29m 59s before making another server request.",
            portalDiagnosticsCountdown(message, 3_600_000, 1_801_000))
    }

    @Test fun expiryAllowsRetryWithoutNegativeOrStaleWaits() {
        val message = "Portal requests are paused for 2s (provider retry delay)"
        assertEquals("Portal requests are paused for 1s (provider retry delay)", portalDiagnosticsCountdown(message, 2000, 1001))
        assertEquals("The provider’s requested wait has ended. You can try again.", portalDiagnosticsCountdown(message, 2000, 3000))
    }

    @Test fun unrelatedDiagnosticsAndUnknownDeadlinesRemainUnchanged() {
        val message = "HTTP response body: retry_after=3600"
        assertEquals(message, portalDiagnosticsCountdown(message, 2000, 1000))
        val pause = "Portal requests are paused for 3600 seconds"
        assertEquals(pause, portalDiagnosticsCountdown(pause, null, 0))
        assertEquals("Portal requests are paused for 59m 59s", portalDiagnosticsCountdown(pause, 3_600_000, 1000))
    }
}
