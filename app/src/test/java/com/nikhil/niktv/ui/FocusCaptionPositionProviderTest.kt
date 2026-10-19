package com.nikhil.niktv.ui

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class FocusCaptionPositionProviderTest {
    private val provider = FocusCaptionPositionProvider(4, 16)
    @Test fun keepsCaptionInsideRightEdgeForBothLayoutDirections() {
        for (direction in LayoutDirection.values()) {
            val position = provider.calculatePosition(IntRect(1800, 16, 1896, 112), IntSize(1920, 1080), direction, IntSize(320, 56))
            assertEquals(1584, position.x)
            assertEquals(116, position.y)
        }
    }
    @Test fun keepsCaptionInsideLeftAndBottomEdges() {
        val position = provider.calculatePosition(IntRect(0, 1000, 48, 1048), IntSize(1920, 1080), LayoutDirection.Ltr, IntSize(320, 56))
        assertEquals(16, position.x)
        assertEquals(1008, position.y)
    }
}
