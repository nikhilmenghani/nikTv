package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class NativeRowReconciliationTest {
    @Test fun removalUsesAdjacentRowAndInsertionPreservesIdentity() {
        val before = listOf("hero", "a", "b", "c")
        assertEquals("b", nativeRowReplacement(before, listOf("hero", "b", "c"), "a"))
        assertEquals("b", nativeRowReplacement(before, listOf("hero", "a", "b"), "c"))
        assertEquals("b", nativeRowReplacement(before, listOf("new", "hero", "a", "b", "c"), "b"))
        assertNull(nativeRowReplacement(before, emptyList(), "b"))
    }
}
