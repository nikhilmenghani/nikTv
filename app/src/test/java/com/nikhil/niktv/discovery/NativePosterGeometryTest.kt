package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class NativePosterGeometryTest {
    @Test fun phonePostersAreLargerWhileTwoWholeTilesRemainVisible() {
        for (width in listOf(320f, 360f, 384f, 411f)) {
            val phone = nativePosterGeometry(width, 800f, 1f, phone = true)
            assertTrue(phone.width > discoveryPosterWidth(width, 800f) * 1.3f)
            assertTrue("Two complete posters and spacing fit the phone", phone.width * 2 + 48f <= width)
            assertEquals((phone.width - 8f) * 1.5f, phone.posterHeight, .01f)
        }
    }
    @Test fun shortLandscapePhonesKeepTheWholeRailWithinTheAvailableHeight() {
        val phone = nativePosterGeometry(800f, 360f, 1f, phone = true)
        assertTrue(phone.railHeight + 29f <= 360f - 168f)
        assertEquals(discoveryPosterWidth(800f, 360f), nativePosterGeometry(800f, 360f, 1f).width, .01f)
    }
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
