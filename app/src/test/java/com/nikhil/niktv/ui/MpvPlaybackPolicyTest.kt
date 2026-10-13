package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class MpvPlaybackPolicyTest {
    @Test fun gpuOutputWaitsForAnAttachedAndroidSurface() {
        assertEquals("null", mpvPlaybackOptions(true)["vo"])
        assertEquals("android", mpvPlaybackOptions(true)["gpu-context"])
    }
    @Test fun nativeSizingResetsCropZoomAndAspectWhenModesChange() {
        assertEquals("1", mpvVideoSizing(VideoResizeMode.FILL)["panscan"])
        assertEquals(1.25, Math.pow(2.0, mpvVideoSizing(VideoResizeMode.ZOOM).getValue("video-zoom").toDouble()), 0.000001)
        assertEquals("no", mpvVideoSizing(VideoResizeMode.STRETCH)["keepaspect"])
        val fit = mpvVideoSizing(VideoResizeMode.FIT)
        assertEquals("yes", fit["keepaspect"])
        assertEquals("0", fit["panscan"])
        assertEquals("0", fit["video-zoom"])
    }
    @Test fun audioRemainsTheSyncClockWithCompatibleOutputFallback() {
        val policy = mpvPlaybackOptions(true)
        assertEquals("aaudio,audiotrack", policy["ao"])
        assertEquals("audio", policy["video-sync"])
        assertNull(policy["audio-delay"])
    }
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
