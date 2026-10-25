package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRemoteControlsTest {
    @Test fun `hold ramps by elapsed time and is bounded for short clips`() {
        val hold = SeekHoldAcceleration()
        val movie = 7_200_000L
        assertEquals(10_000L, hold.step(1, 100, false, movie))
        assertEquals(10_000L, hold.step(1, 799, true, movie))
        assertEquals(30_000L, hold.step(1, 800, true, movie))
        assertEquals(60_000L, hold.step(1, 1_900, true, movie))
        assertEquals(144_000L, hold.step(1, 3_600, true, movie))
        assertEquals(15_000L, hold.step(1, 6_000, true, 300_000L))
        assertEquals(300_000L, hold.step(1, 7_000, true, Long.MAX_VALUE))
    }
    @Test fun `release reversal new press and lost focus reset acceleration`() {
        val hold = SeekHoldAcceleration()
        val movie = 7_200_000L
        hold.step(1, 0, false, movie)
        assertEquals(144_000L, hold.step(1, 4_000, true, movie))
        assertEquals(10_000L, hold.step(-1, 4_100, true, movie))
        hold.release(1) // A late release of the old direction cannot end the new hold.
        org.junit.Assert.assertTrue(hold.isHeld)
        hold.release(-1)
        org.junit.Assert.assertFalse(hold.isHeld)
        assertEquals(10_000L, hold.step(-1, 8_000, true, movie))
        hold.reset()
        assertEquals(10_000L, hold.step(-1, 12_000, true, movie))
        assertEquals(10_000L, hold.step(-1, 20_000, false, movie))
    }
    @Test fun `simulated hold reaches three quarters of a two hour movie in under seven seconds`() {
        val hold = SeekHoldAcceleration()
        val duration = 7_200_000L
        var preview = 0L
        for (repeat in 0..65) preview = seekPreviewPosition(preview, 1, duration,
            hold.step(1, repeat * 100L, repeat > 0, duration))
        org.junit.Assert.assertTrue(preview >= duration * 3 / 4)
        assertEquals(duration, seekPreviewPosition(duration - 1, 1, duration, 300_000))
        assertEquals(0L, seekPreviewPosition(1, -1, duration, 300_000))
    }
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
