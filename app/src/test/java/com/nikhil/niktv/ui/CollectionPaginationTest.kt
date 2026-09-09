package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionPaginationTest {
    @Test fun `focus first genuinely new tile when pages overlap`() {
        assertEquals(2, appendedFocusIndex(listOf("a", "b"), listOf("a", "b", "c", "d")))
        assertEquals(3, appendedFocusIndex(listOf("a", "b"), listOf("a", "b", "b", "c")))
    }
    @Test fun `empty final page keeps focus on last existing tile`() {
        assertEquals(1, appendedFocusIndex(listOf("a", "b"), listOf("a", "b")))
    }
    @Test fun `empty catalog has no focus target`() {
        assertEquals(-1, appendedFocusIndex(emptyList(), emptyList()))
    }
}
