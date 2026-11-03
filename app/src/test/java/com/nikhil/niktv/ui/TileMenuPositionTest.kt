package com.nikhil.niktv.ui

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class TileMenuPositionTest {
    @Test fun staysBesideTileAndInsideWindow() {
        val window = IntSize(1920, 1080); val menu = IntSize(320, 600)
        assertEquals(IntOffset(308, 120), tileMenuPosition(IntRect(100, 120, 300, 450), window, menu, 8, 8))
        assertEquals(IntOffset(1172, 472), tileMenuPosition(IntRect(1500, 750, 1710, 1060), window, menu, 8, 8))
    }
    @Test fun narrowWindowClampsWithoutNegativePosition() {
        assertEquals(IntOffset(8, 8), tileMenuPosition(IntRect(0, 0, 200, 400), IntSize(300, 400), IntSize(320, 500), 8, 8))
    }
}
