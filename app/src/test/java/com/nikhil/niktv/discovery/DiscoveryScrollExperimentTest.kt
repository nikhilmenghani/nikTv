package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryScrollExperimentTest {
    @Test fun controlRetainsOriginalBudgetsAndFeatures() {
        val control = DiscoveryScrollVariant.CONTROL
        assertEquals(Int.MAX_VALUE, control.rowLimit)
        assertEquals(30, control.tileLimit)
        assertFalse(control.staticArtwork)
        assertFalse(control.simpleFocus)
    }

    @Test fun isolatedPresetsChangeOnlyTheirNamedVariable() {
        val rows = DiscoveryScrollVariant.FEWER_ROWS
        assertEquals(4, rows.rowLimit)
        assertEquals(30, rows.tileLimit)
        assertFalse(rows.staticArtwork)
        assertFalse(rows.simpleFocus)
        val tiles = DiscoveryScrollVariant.FEWER_TILES
        assertEquals(Int.MAX_VALUE, tiles.rowLimit)
        assertEquals(8, tiles.tileLimit)
        assertFalse(tiles.staticArtwork)
        assertFalse(tiles.simpleFocus)
        val artwork = DiscoveryScrollVariant.STATIC_ARTWORK
        assertEquals(Int.MAX_VALUE, artwork.rowLimit)
        assertEquals(30, artwork.tileLimit)
        assertTrue(artwork.staticArtwork)
        assertFalse(artwork.simpleFocus)
    }

    @Test fun leanCombinesBudgetsWithoutZeroOrUnboundedPreviewTiles() {
        val lean = DiscoveryScrollVariant.LEAN
        assertEquals(4, lean.rowLimit)
        assertEquals(8, lean.tileLimit)
        assertTrue(lean.staticArtwork)
        assertTrue(lean.simpleFocus)
        DiscoveryScrollVariant.entries.forEach {
            assertTrue(it.rowLimit > 0)
            assertTrue(it.tileLimit in 1..30)
        }
    }
    @Test fun nativeMotionRetainsTheSameDataBudgetsAsImmediateNativeRows() {
        val a = DiscoveryScrollVariant.NATIVE_ROWS
        val b = DiscoveryScrollVariant.NATIVE_MOTION
        assertEquals(a.rowLimit, b.rowLimit)
        assertEquals(a.tileLimit, b.tileLimit)
        assertTrue(a.nativeRows && b.nativeRows)
        assertFalse(a.animateNativeFocus)
        assertTrue(b.animateNativeFocus)
    }
    @Test fun previewScopeBoundsFiveHundredCategoriesWithoutChangingBackingSelections() {
        val selected = (1..500).map { number -> com.nikhil.niktv.model.Category(
            "$number", "Category $number", com.nikhil.niktv.model.CatalogType.SERIES,
            sourceCategoryIds = listOf("$number-a", "$number-b")) }
        val control = discoveryTrialProviderScope(selected, DiscoveryScrollVariant.CONTROL.rowLimit)
        assertSame(selected, control)
        assertEquals(500, control.size)
        for (variant in listOf(DiscoveryScrollVariant.NATIVE_ROWS, DiscoveryScrollVariant.NATIVE_MOTION,
            DiscoveryScrollVariant.FEWER_ROWS, DiscoveryScrollVariant.LEAN)) {
            val preview = discoveryTrialProviderScope(selected, variant.rowLimit)
            assertEquals(selected.take(4), preview)
            assertSame(selected.first(), preview.first())
            assertEquals(listOf("1-a", "1-b"), preview.first().sourceCategoryIds)
        }
        assertEquals(500, selected.size)
        assertEquals("500", selected.last().id)
        assertSame(selected, discoveryTrialProviderScope(selected, DiscoveryScrollVariant.FEWER_TILES.rowLimit))
    }
}
