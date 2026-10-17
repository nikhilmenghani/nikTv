package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PlaybackEngine
import org.junit.Assert.*
import org.junit.Test

class FlowInspiredPlaybackTest {
    @Test fun constrainedDevicesKeepFlowBuffersWithinTheirHeapTier() {
        for (live in listOf(true, false)) for (heap in listOf(64, 128, 256, 384, 512)) {
            val plan = flowBufferPlan(heap, false, live)
            assertTrue(plan.minimumMs >= 3_000)
            assertTrue(plan.maximumMs >= plan.minimumMs)
            val limitMb = if (heap <= 256) 4 else if (heap <= 384) 12 else 32
            assertTrue(plan.targetBytes <= limitMb * 1024 * 1024)
            if (heap <= 256) assertTrue(plan.maximumMs <= 18_000)
        }
    }
    @Test fun lowRamFlagWinsOverLargeReportedHeap() {
        assertEquals(flowBufferPlan(128, false, true), flowBufferPlan(1024, true, true))
    }
    @Test fun flowRemainsAnExplicitChoiceWithoutReplacingAutoOrExistingPlayers() {
        assertTrue(PlaybackEngine.FLOW in PLAYER_ENGINE_OPTIONS)
        assertEquals(PlaybackEngine.FLOW, PlaybackEngine.valueOf("FLOW"))
        assertEquals(PlaybackEngine.FLOW, PlaybackEngine.MEDIA3.nextPlayerChoice())
        assertEquals(PlaybackEngine.VLC, PlaybackEngine.FLOW.nextPlayerChoice())
        assertTrue(PlaybackEngine.FLOW.isMedia3Player())
        assertFalse(PlaybackEngine.MPV.isMedia3Player())
    }
}
