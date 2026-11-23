package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DisplayOrderingTest {
    @Test fun orderingNeverAddsHiddenItemsAndAppendsNewItemsInProviderOrder() {
        assertEquals(listOf("c", "a", "new-2", "new-1"), orderedForDisplay(
            listOf("a", "new-2", "c", "new-1"), listOf("hidden", "c", "c", "a")) { it })
        assertEquals(listOf("a", "b"), orderedForDisplay(listOf("a", "b"), emptyList()) { it })
        assertEquals(listOf("c", "hidden", "a", "new"), mergeDisplayOrder(listOf("a", "hidden", "c"), listOf("c", "a", "new")))
    }

    @Test fun categoryMembershipChangesDoNotResetItsDisplayOrder() {
        val before = Category("@collection:LANGUAGE:Hindi:0123456789abcdef", "Hindi", CatalogType.SERIES)
        val after = before.copy(id = "@collection:LANGUAGE:Hindi:fedcba9876543210")
        assertEquals(categoryDisplayOrderId(before), categoryDisplayOrderId(after))
        val custom = before.copy(id = "@custom:fixed-id:0123456789abcdef")
        assertEquals("@custom:fixed-id", categoryDisplayOrderId(custom))
        assertEquals("1234", categoryDisplayOrderId(before.copy(id = "1234")))
    }
}
