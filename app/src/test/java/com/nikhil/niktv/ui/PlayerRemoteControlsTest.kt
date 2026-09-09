package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRemoteControlsTest {
    @Test fun `repeated forward presses accumulate against preview not engine position`() {
        var preview = 30_000L
        repeat(12) { preview = seekPreviewPosition(preview, 1, 600_000L) }
        assertEquals(150_000L, preview)
    }
    @Test fun `reversing direction adjusts the pending destination`() {
        var preview = seekPreviewPosition(30_000L, 1, 600_000L)
        preview = seekPreviewPosition(preview, 1, 600_000L)
        preview = seekPreviewPosition(preview, -1, 600_000L)
        assertEquals(40_000L, preview)
    }
    @Test fun `seek is clamped to both ends and handles unknown duration`() {
        assertEquals(0L, seekPreviewPosition(5_000L, -1, 60_000L))
        assertEquals(60_000L, seekPreviewPosition(55_000L, 1, 60_000L))
        assertEquals(0L, seekPreviewPosition(5_000L, 1, -1L))
        assertEquals(Long.MAX_VALUE, seekPreviewPosition(Long.MAX_VALUE - 5, 1, Long.MAX_VALUE))
    }
}
