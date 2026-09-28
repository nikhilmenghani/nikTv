package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileFocusViewportTest {
    @Test fun `visible focus changes do not move the viewport`() {
        assertEquals(0f, tileFocusScrollDistance(80f, 300f, 800f), 0f)
        assertEquals(0f, tileFocusScrollDistance(500f, 300f, 800f), 0f)
    }

    @Test fun `clipped rows scroll only far enough to reveal the tile`() {
        assertEquals(-40f, tileFocusScrollDistance(-40f, 300f, 800f), 0f)
        assertEquals(50f, tileFocusScrollDistance(550f, 300f, 800f), 0f)
    }

    @Test fun `oversized tile spanning viewport does not oscillate between edges`() {
        assertEquals(0f, tileFocusScrollDistance(-40f, 900f, 800f), 0f)
    }
}
