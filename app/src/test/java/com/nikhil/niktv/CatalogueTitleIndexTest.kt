package com.nikhil.niktv

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class CatalogueTitleIndexTest {
    @Test fun accentAndPunctuationMatchesPreserveFullMediaAndCategoryScope() {
        val first = MediaItem("1", "Dríshyam (2013) Hindi", "logo", "original-command", portalCategoryId = "hindi", description = "Keep metadata")
        val second = first.copy(id = "2", title = "Drishyam 2 Malayalam", portalCategoryId = "malayalam")
        val items = listOf(first, second, first.copy(id = "3", title = "Another movie"))
        val index = CatalogueTitleIndex(items)
        assertEquals(listOf(first, second), index.find(items, "drishyam", "*"))
        assertSame(first, index.find(items, "drishyam hindi", "hindi").single())
        assertTrue(index.find(items, "drishyam", "unrelated").isEmpty())
    }
    @Test fun replacedCatalogueCannotReuseAnotherProfilesTitleIndex() {
        val items = listOf(MediaItem("1", "Drishyam", null, null))
        val index = CatalogueTitleIndex(items)
        assertTrue(index.isFor(items))
        assertFalse(index.isFor(listOf(items.single().copy(title = "Other profile title"))))
        assertTrue(index.find(items, " -- ", "*").isEmpty())
    }
    @Test fun reuseNeverDropsNewTitlesOrBorrowsOldMetadata() {
        val original = MediaItem("1", "Drishyam", null, "old")
        val index = CatalogueTitleIndex(listOf(original), "nikhil", CatalogType.MOVIES)
        val richer = original.copy(command = "new", description = "New metadata")
        val added = original.copy(id = "2", title = "Drishyam 2")
        assertSame(richer, index.findCandidates(listOf(richer), "drishyam").single())
        assertEquals(listOf(added), index.findCandidates(listOf(added), "drishyam"))
        assertTrue(index.isScopedTo("nikhil", CatalogType.MOVIES))
        assertFalse(index.isScopedTo("wio", CatalogType.MOVIES))
    }
}
