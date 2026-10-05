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
    @Test fun `IPTV append uses indexed tile requester instead of provider id`() {
        assertEquals("2:c", appendedFocusRequesterKey(listOf("a", "b"), listOf("a", "b", "c"), listOf("0:a", "1:b", "2:c")))
    }
    @Test fun `no new items maps to the last attached requester`() {
        assertEquals("1:b", appendedFocusRequesterKey(listOf("a", "b"), listOf("a", "b"), listOf("0:a", "1:b")))
    }
    @Test fun `empty catalog does not create a detached requester`() {
        assertEquals(null, appendedFocusRequesterKey(emptyList(), emptyList(), emptyList()))
    }
}
