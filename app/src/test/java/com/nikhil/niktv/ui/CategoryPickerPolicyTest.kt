package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class CategoryPickerPolicyTest {
    @Test fun horizontalEdgesNeverJumpToAnotherRowOrSave() {
        assertEquals(0, categoryPickerDestination(0, 7, 2, CategoryPickerDirection.LEFT))
        assertEquals(1, categoryPickerDestination(1, 7, 2, CategoryPickerDirection.RIGHT))
        assertEquals(6, categoryPickerDestination(6, 7, 2, CategoryPickerDirection.RIGHT))
        assertEquals(3, categoryPickerDestination(2, 7, 2, CategoryPickerDirection.RIGHT))
    }
    @Test fun verticalNavigationHandlesIncompleteLastRowsAndExitsAtBoundaries() {
        assertEquals(6, categoryPickerDestination(5, 7, 2, CategoryPickerDirection.DOWN))
        assertNull(categoryPickerDestination(6, 7, 2, CategoryPickerDirection.DOWN))
        assertNull(categoryPickerDestination(1, 7, 2, CategoryPickerDirection.UP))
        assertEquals(2, categoryPickerDestination(4, 7, 2, CategoryPickerDirection.UP))
        assertNull(categoryPickerDestination(0, 0, 2, CategoryPickerDirection.DOWN))
    }
    @Test fun phoneAndLargerFontsRetainReadableRows() {
        assertEquals(1, categoryPickerColumns(380, 1f))
        assertEquals(2, categoryPickerColumns(840, 1f))
        assertEquals(1, categoryPickerColumns(700, 1.5f))
        assertEquals(2, categoryPickerColumns(1120, 1.5f))
    }
    @Test fun explicitEmptyAndUnsavedDraftOverrideDefaultsAndBackgroundUpdates() {
        assertTrue(categoryPickerSelection(null, emptyList(), listOf("default")).isEmpty())
        assertEquals(setOf("draft"), categoryPickerSelection(listOf("draft"), listOf("saved"), listOf("new-default")))
        assertEquals(setOf("default"), categoryPickerSelection(null, null, listOf("default")))
        assertEquals(setOf("saved"), categoryPickerSelection(null, listOf("saved", "saved"), emptyList()))
    }
}
