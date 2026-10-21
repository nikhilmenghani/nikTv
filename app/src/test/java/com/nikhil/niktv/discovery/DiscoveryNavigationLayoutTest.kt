package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.focus.FocusDirection

class DiscoveryNavigationLayoutTest {
    @Test fun recreatedHeaderRedirectsInitialEntryBeforeBackAndAllowsExplicitPopupReturn() {
        val entry = DiscoveryHeaderEntry()
        assertTrue(entry.redirect(FocusDirection.Enter))
        assertFalse(entry.redirect(FocusDirection.Enter))
        assertTrue(DiscoveryHeaderEntry().redirect(FocusDirection.Enter))
    }
    @Test fun scrollingBackUpAlwaysEntersTheSelectedTab() {
        val entry = DiscoveryHeaderEntry()
        entry.redirect(FocusDirection.Enter)
        repeat(5) {
            assertTrue(entry.redirect(FocusDirection.Up))
            assertFalse(entry.redirect(FocusDirection.Enter))
        }
    }
    @Test fun tabletAndTvFeedNavigationIncludesTheScrollingHeaderBeforeHeroAndRails() {
        val positions = discoveryFeedIndices(scrollingHeader = true, series = false)
        val rows = listOf(positions.header!!, positions.hero, positions.firstRail, positions.firstRail + 1)
        assertEquals(positions.hero, adjacentDiscoveryRow(positions.firstRail, -1, rows))
        assertEquals(positions.header, adjacentDiscoveryRow(positions.hero, -1, rows))
        assertEquals(positions.hero, adjacentDiscoveryRow(positions.header!!, 1, rows))
        assertEquals(positions.firstRail, adjacentDiscoveryRow(positions.hero, 1, rows))
    }
    @Test fun seriesSeasonsAndPhoneRowPositionsRemainDistinct() {
        val series = discoveryFeedIndices(scrollingHeader = true, series = true)
        assertEquals(2, series.season); assertEquals(3, series.firstRail)
        val phone = discoveryFeedIndices(scrollingHeader = false, series = false)
        assertNull(phone.header); assertEquals(0, phone.hero); assertEquals(1, phone.firstRail)
    }
    @Test fun menuWrapsItsButtonsOnWideScreensAndLeavesTheProfileAccessibleWhenConstrained() {
        for (available in listOf(552f, 752f, 912f, 1232f)) for (font in listOf(1f, 1.3f, 1.8f)) {
            val widths = discoveryHeaderWidths(available, 650f * font, 132f * font)
            assertTrue(widths.menu + widths.profile + 12f <= available + .01f)
            assertTrue(widths.menu <= 650f * font)
            assertTrue(widths.profile >= 132f)
        }
        // Regression: the menu panel used to stretch across all unused screen width.
        assertEquals(650f, discoveryHeaderWidths(1232f, 650f, 132f).menu, .01f)
    }
}
