package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class RecycledPositionTest {
    @org.junit.Test fun televisionLeavesRoomForFirstPosterRowWithoutChangingTabletHeight() {
        org.junit.Assert.assertEquals(216f, trialHeroBaseHeight(540f, true, television = true), .01f)
        org.junit.Assert.assertEquals(240f, trialHeroFrameHeight(trialHeroBaseHeight(540f, true, true), 1f), .01f)
        org.junit.Assert.assertEquals(355f, trialHeroBaseHeight(750f, true), .01f)
    }

    @Test fun homeHeroFrameMatchesHomeHeightPolicyWithoutGrowingWithTitles() {
        assertEquals(297f, trialHeroBaseHeight(540f, true), .01f)
        assertEquals(355f, trialHeroBaseHeight(750f, true), .01f)
        assertEquals(243f, trialHeroBaseHeight(540f, false), .01f)
    }
    @Test fun viewportRoundTripRetainsStableIdentityAndPixelOffsets() {
        val saved = RecycledPosition("movies", "all").apply {
            anchorRow = "continue"; anchorOffset = -123
            columns["movies"] = "all"
            horizontal["movies"] = NativeRowViewport("movie-8", -17)
        }
        val restored = RecycledPosition.restore(saved.snapshot())
        assertEquals(saved.row, restored.row); assertEquals(saved.item, restored.item)
        assertEquals(saved.anchorRow, restored.anchorRow); assertEquals(-123, restored.anchorOffset)
        assertEquals(saved.columns, restored.columns); assertEquals(saved.horizontal, restored.horizontal)
    }
    @Test fun oldBookmarksStillRestoreAndMalformedStateIsSafe() {
        val old = RecycledPosition.restore(listOf("series", "episode", "series", "episode"))
        assertEquals("series", old.row); assertEquals("episode", old.columns["series"])
        assertEquals("", RecycledPosition.restore(listOf("viewport-v2", "broken")).row)
    }
    @Test fun savedStateIsBoundedWithoutDroppingSelectedOrVisibleAnchors() {
        val saved = RecycledPosition("r0", "last").apply {
            anchorRow = "r1"
            repeat(1000) { columns["r$it"] = "item$it"; horizontal["r$it"] = NativeRowViewport("item$it", -it) }
        }
        val restored = RecycledPosition.restore(saved.snapshot())
        assertEquals(256, restored.columns.size); assertEquals(256, restored.horizontal.size)
        assertTrue("r0" in restored.columns && "r1" in restored.horizontal)
    }
}
