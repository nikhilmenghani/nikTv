package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalCatalogPage
import org.junit.Assert.*
import org.junit.Test

class CategoryRefreshTest {
    private fun item(id: String) = MediaItem(id, id, null, null)

    @Test fun manualRefreshReopensPaginationAndRemovesDeletedChannels() {
        val fresh = PortalCatalogPage(listOf(item("new")), 5, true, 13, 177, 14)
        val result = refreshedCategoryWindow(listOf(item("deleted")), 5, false, fresh, true)
        assertEquals(listOf("new"), result.items.map { it.id })
        assertEquals(5, result.page)
        assertTrue(result.hasMore)
    }

    @Test fun backgroundRefreshRepairsPrematureEndUsingProviderTotal() {
        val result = refreshedCategoryWindow(listOf(item("later")), 5, false,
            PortalCatalogPage(listOf(item("first")), 1, true, 13, 177, 14), false)
        assertEquals(listOf("first", "later"), result.items.map { it.id })
        assertEquals(5, result.page)
        assertTrue(result.hasMore)
    }

    @Test fun manualRefreshCanShrinkCategoryAndResetItsCursor() {
        val result = refreshedCategoryWindow(listOf(item("old")), 5, true,
            PortalCatalogPage(emptyList(), 1, false, 1, 0), true)
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.page)
        assertFalse(result.hasMore)
    }
}
