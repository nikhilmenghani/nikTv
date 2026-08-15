package com.nikhil.niktv

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFilterTest {

    private fun filterKey(profileKey: String, type: CatalogType): String = "$profileKey|${type.name}"

    private fun filterCategories(
        raw: List<Category>,
        profileKey: String?,
        type: CatalogType,
        filters: Map<String, List<String>>
    ): List<Category> {
        if (profileKey == null) return raw
        val key = filterKey(profileKey, type)
        val enabledIds = filters[key] ?: return raw
        val enabledSet = enabledIds.toSet()
        return raw.filter { it.id in enabledSet }
    }

    private val profile = PortalProfile(
        name = "Test Service",
        portalUrl = "http://example.com/c/",
        macAddress = "00:1A:79:00:00:01",
        portalType = PortalType.STALKER
    )

    private val rawLiveCategories = listOf(
        Category(id = "1", title = "English | Canada", type = CatalogType.LIVE_TV),
        Category(id = "2", title = "English | USA", type = CatalogType.LIVE_TV),
        Category(id = "3", title = "Sports | 4K", type = CatalogType.LIVE_TV),
        Category(id = "4", title = "Kids & Family", type = CatalogType.LIVE_TV),
        Category(id = "5", title = "Documentary", type = CatalogType.LIVE_TV)
    )

    @Test
    fun testDefaultReturnsAllCategories() {
        val filters = emptyMap<String, List<String>>()
        val result = filterCategories(rawLiveCategories, profile.cacheKey(), CatalogType.LIVE_TV, filters)
        assertEquals(5, result.size)
        assertEquals(rawLiveCategories, result)
    }

    @Test
    fun testFilteredCategoriesOnlyReturnSelected() {
        val profileKey = profile.cacheKey()
        val key = filterKey(profileKey, CatalogType.LIVE_TV)
        val filters = mapOf(key to listOf("1", "2"))

        val result = filterCategories(rawLiveCategories, profileKey, CatalogType.LIVE_TV, filters)
        assertEquals(2, result.size)
        assertEquals(listOf("1", "2"), result.map { it.id })
        assertEquals(listOf("English | Canada", "English | USA"), result.map { it.title })
    }

    @Test
    fun testEmptyFilterReturnsNoCategories() {
        val profileKey = profile.cacheKey()
        val key = filterKey(profileKey, CatalogType.LIVE_TV)
        val filters = mapOf(key to emptyList<String>())

        val result = filterCategories(rawLiveCategories, profileKey, CatalogType.LIVE_TV, filters)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testFiltersAreIndependentPerCatalogType() {
        val profileKey = profile.cacheKey()
        val liveKey = filterKey(profileKey, CatalogType.LIVE_TV)
        val moviesKey = filterKey(profileKey, CatalogType.MOVIES)

        val filters = mapOf(
            liveKey to listOf("1"),
            moviesKey to listOf("m1", "m2")
        )

        val liveResult = filterCategories(rawLiveCategories, profileKey, CatalogType.LIVE_TV, filters)
        assertEquals(1, liveResult.size)
        assertEquals("1", liveResult.first().id)

        val rawMovieCategories = listOf(
            Category(id = "m1", title = "Action", type = CatalogType.MOVIES),
            Category(id = "m2", title = "Comedy", type = CatalogType.MOVIES),
            Category(id = "m3", title = "Horror", type = CatalogType.MOVIES)
        )

        val movieResult = filterCategories(rawMovieCategories, profileKey, CatalogType.MOVIES, filters)
        assertEquals(2, movieResult.size)
        assertEquals(listOf("m1", "m2"), movieResult.map { it.id })
    }

    @Test
    fun testCategorySearchMatching() {
        val query = "english"
        val matching = rawLiveCategories.filter { it.title.contains(query, ignoreCase = true) }
        assertEquals(2, matching.size)
        assertTrue(matching.any { it.title == "English | Canada" })
        assertTrue(matching.any { it.title == "English | USA" })
    }

    @Test
    fun testProfileCacheKeyFormat() {
        val cacheKey = profile.cacheKey()
        assertTrue(cacheKey.startsWith("catalog-v5|STALKER|http://example.com/c|"))
        assertTrue(cacheKey.contains("00:1A:79:00:00:01"))
    }
}
