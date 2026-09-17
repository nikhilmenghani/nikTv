package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultNavigationTest {
    @Test fun downClampsToIncompleteLastRowBeforeFooter() {
        assertEquals(7, searchVerticalTarget(5, 6, 8, down = true, footer = true))
        assertEquals(8, searchVerticalTarget(7, 6, 8, down = true, footer = true))
    }
    @Test fun footerReturnsToLastResultAndFirstRowReturnsToControls() {
        assertEquals(7, searchVerticalTarget(8, 6, 8, down = false, footer = true))
        assertEquals(-1, searchVerticalTarget(3, 6, 8, down = false, footer = true))
    }
    @Test fun listsCanReachFooterAndReturn() {
        assertEquals(3, searchVerticalTarget(2, 1, 3, down = true, footer = true))
        assertEquals(2, searchVerticalTarget(3, 1, 3, down = false, footer = true))
    }
    @Test fun emptyFooterReturnsToControls() {
        assertEquals(-1, searchVerticalTarget(0, 1, 0, down = false, footer = true))
    }
}
