package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class MpvPlaybackPolicyTest {
    @Test fun packetLimitDoesNotBecomeAnInitialThirtyTwoMegabyteWait() {
        val policy = mpvPlaybackOptions(live = true)
        assertEquals("33554432", policy["demuxer-max-bytes"])
        assertEquals("no", policy["cache-pause-initial"])
        assertEquals("1", policy["cache-pause-wait"])
        assertEquals("0", policy["demuxer-max-back-bytes"])
    }
    @Test fun supportedSdkPreservesOtherPlayersOnOlderDevices() {
        assertFalse(mpvSupportedSdk(24))
        assertFalse(mpvSupportedSdk(25))
        assertTrue(mpvSupportedSdk(26))
        assertTrue(mpvSupportedSdk(35))
    }
    @Test fun nativePlayerRetainsTlsVerificationAndDoesNotOwnRemoteBindings() {
        val policy = mpvPlaybackOptions(live = false)
        assertEquals("yes", policy["tls-verify"])
        assertEquals("no", policy["input-default-bindings"])
        assertEquals("no", policy["input-vo-keyboard"])
        assertEquals("no", policy["osc"])
    }
}
