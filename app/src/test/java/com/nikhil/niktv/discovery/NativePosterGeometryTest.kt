package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class NativePosterGeometryTest {
    @Test fun matchesHomeWidthAndReservesCaptionBoundsForLargeFonts() {
        for ((width, height) in listOf(960f to 540f, 1200f to 750f, 750f to 1200f)) {
            val regular = nativePosterGeometry(width, height, 1f)
            assertEquals(discoveryPosterWidth(width, height), regular.width, .01f)
            val large = nativePosterGeometry(width, height, 2f)
            assertTrue(large.posterHeight >= regular.posterHeight)
            assertTrue(large.posterHeight >= 114f * large.textScale * 2f + 30f)
        }
    }
}
