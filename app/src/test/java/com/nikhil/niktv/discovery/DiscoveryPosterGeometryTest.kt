package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryPosterGeometryTest {
    @Test fun focusInsetsContainEnlargementAcrossPhoneTabletAndTvWidths() {
        for (width in listOf(90f, 120f, 150f, 220f)) {
            val height = width / (2f / 3f)
            val growth = height * (DiscoveryPosterFocusScale - 1f) / 2f
            assertTrue(discoveryPosterFocusInset(width) >= growth + 1.99f)
        }
    }
    @Test fun reservedSpaceTracksPosterAspectAndKeepsOutlineRoom() {
        assertEquals(2f, discoveryPosterFocusInset(0f), .001f)
        assertTrue(discoveryPosterFocusInset(150f) > discoveryPosterFocusInset(150f, 16f / 9f))
    }
    @Test fun fullGridUsesTheSameCompactSizeAsLandingRails() {
        val tv = discoveryPosterWidth(960f, 540f)
        assertEquals(105.333f, tv, .001f)
        assertEquals(8, discoveryCollectionColumns(960f, tv))
        assertEquals(132f, discoveryPosterWidth(1280f, 800f), .001f)
        assertEquals(3, discoveryCollectionColumns(400f, phoneDiscoveryPosterWidth(400)))
    }
    @Test fun gridNavigationPreservesColumnsAndPartialLastRows() {
        assertEquals(11, discoveryGridTarget(3, 8, 8, 19))
        assertEquals(3, discoveryGridTarget(11, -8, 8, 19))
        assertEquals(18, discoveryGridTarget(15, 8, 8, 19))
        assertNull(discoveryGridTarget(16, 8, 8, 19))
        assertNull(discoveryGridTarget(0, -1, 8, 19))
        assertNull(discoveryGridTarget(7, 1, 8, 19))
        assertNull(discoveryGridTarget(0, 8, 8, 1))
    }
}
