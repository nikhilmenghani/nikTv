package com.nikhil.niktv.sports

import com.nikhil.niktv.ui.iptvDecoderCandidateOrder
import org.junit.Assert.*
import org.junit.Test

class SportsPlaybackIsolationTest {
    @Test fun initialSportsNativeSettingsPreserveParityWithoutUsingIptvPolicyAtRuntime() {
        // Separation is not a buffer/latency retune. Detect accidental differences
        // while the independently owned policies are first introduced.
        assertEquals(com.nikhil.niktv.ui.mpvPlaybackOptions(true), SportsPlaybackPolicy.mpvOptions())
        assertEquals(com.nikhil.niktv.ui.vlcPlaybackOptions(true, false, true), SportsPlaybackPolicy.vlcOptions(true))
        assertEquals(com.nikhil.niktv.ui.vlcPlaybackOptions(true, false, false), SportsPlaybackPolicy.vlcOptions(false))
    }
    private val hardware = "OMX.MTK.VIDEO.DECODER.AVC"
    private val software = "c2.android.avc.decoder"
    private val candidates = listOf(hardware, software)

    @Test fun sportsUsesNativeOrderWhileIptvRetainsItsHistoricalPolicy() {
        val session = java.util.UUID.randomUUID().toString()
        assertEquals(listOf(software), iptvDecoderCandidateOrder(candidates, setOf(hardware.lowercase()), emptySet(), true))
        assertEquals(candidates, SportsDecoderPolicy.candidates(session, candidates))
        assertEquals(hardware, SportsDecoderPolicy.record(IllegalStateException("Decoder failed: $hardware"), session))
        assertEquals(listOf(software), SportsDecoderPolicy.candidates(session, candidates))
        assertEquals(candidates, iptvDecoderCandidateOrder(candidates, emptySet(), emptySet(), false))
        assertEquals(candidates, SportsDecoderPolicy.candidates("fresh-$session", candidates))
    }

    @Test fun sportsExhaustionIsBoundedWithoutRearmingOtherSessions() {
        val session = java.util.UUID.randomUUID().toString()
        candidates.forEach { SportsDecoderPolicy.record(IllegalStateException("Decoder failed: $it"), session) }
        assertTrue(SportsDecoderPolicy.candidates(session, candidates).isEmpty())
        assertNull(SportsDecoderPolicy.record(IllegalStateException("Decoder init failed: [-49999], Format"), session))
        assertTrue(SportsDecoderPolicy.canRecover(2))
        assertFalse(SportsDecoderPolicy.canRecover(3))
        assertTrue(SportsDecoderPolicy.candidates(session, candidates).isEmpty())
        assertEquals(candidates, SportsDecoderPolicy.candidates("retry-$session", candidates))
    }
}
