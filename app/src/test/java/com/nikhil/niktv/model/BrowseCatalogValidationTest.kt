package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class BrowseCatalogValidationTest {
    private fun cache() = BrowseCatalogCache(
        profileKey = "profile", type = CatalogType.LIVE_TV, cachedAtMillis = 1L,
        categories = emptyList(),
        itemsByCategory = mapOf(
            "movies" to listOf(MediaItem("1", "Movie channel", null, null, portalCategoryId = "movies")),
            "actors" to listOf(MediaItem("2", "Actor channel", null, null, portalCategoryId = "actors"))
        ),
        pagesByCategory = mapOf("movies" to 6, "actors" to 3),
        hasMoreByCategory = mapOf("movies" to true, "actors" to true),
        categoryCachedAtMillis = mapOf("movies" to 1L, "actors" to 2L)
    )

    @Test fun mixedCategoryInvalidatesItsPaginationButPreservesOtherCaches() {
        val original = cache()
        val mixed = original.copy(itemsByCategory = original.itemsByCategory +
            ("movies" to (original.itemsByCategory.getValue("movies") + original.itemsByCategory.getValue("actors"))))
        val repaired = mixed.validateCategory("movies")
        assertFalse(repaired.itemsByCategory.containsKey("movies"))
        assertFalse(repaired.pagesByCategory.containsKey("movies"))
        assertFalse(repaired.hasMoreByCategory.containsKey("movies"))
        assertFalse(repaired.categoryCachedAtMillis.containsKey("movies"))
        assertEquals(original.itemsByCategory["actors"], repaired.itemsByCategory["actors"])
        assertEquals(3, repaired.pagesByCategory["actors"])
    }

    @Test fun validAndLegacyItemsRetainTheirLoadedPages() {
        val original = cache()
        assertSame(original, original.validateCategory("movies"))
        val legacy = original.copy(itemsByCategory = original.itemsByCategory +
            ("movies" to listOf(MediaItem("1", "Legacy channel", null, null))))
        assertSame(legacy, legacy.validateCategory("movies"))
    }
}
