package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class CategoryOrganizationTest {
    private fun category(id: String, title: String, type: CatalogType = CatalogType.MOVIES, sensitive: Boolean = false) =
        Category(id, title, type, sensitive)

    @Test fun languageCombinesYearsQualityAndThemesButDoesNotGuessIndianMeansHindi() {
        val categories = listOf(category("1", "ENGLISH FHD (2026)"), category("2", "ENGLISH 1999-2020"),
            category("3", "ENGLISH (4K)"), category("4", "ENGLISH COMEDY"), category("5", "INDIAN FHD (2025)"))
        val result = CategoryOrganization.organize(categories, CategoryBrowseMode.LANGUAGE)
        assertEquals(listOf("English"), result.map { it.title })
        assertEquals(listOf("1", "2", "3", "4"), result.single().sourceCategoryIds)
        assertEquals(listOf("5"), CategoryOrganization.organize(categories, CategoryBrowseMode.REGION).single().sourceCategoryIds)
    }

    @Test fun universalFacetsCoverLiveRegionsTopicsAndSeriesServices() {
        val live = category("a", "INDIA | KIDS", CatalogType.LIVE_TV)
        assertEquals(setOf("India"), CategoryOrganization.labels(live, CategoryBrowseMode.REGION))
        assertEquals(setOf("Kids"), CategoryOrganization.labels(live, CategoryBrowseMode.TOPIC))
        assertTrue(CategoryOrganization.labels(live, CategoryBrowseMode.LANGUAGE).isEmpty())
        val series = category("s", "NETFLIX (MULTI LANGUAGE)", CatalogType.SERIES)
        assertEquals(setOf("Netflix"), CategoryOrganization.labels(series, CategoryBrowseMode.SERVICE))
        assertEquals(setOf("Multiple languages"), CategoryOrganization.labels(series, CategoryBrowseMode.LANGUAGE))
        assertTrue(CategoryOrganization.labels(category("t", "HINDI | 24x7 Movies"), CategoryBrowseMode.TOPIC).contains("24/7 channels"))
    }

    @Test fun tokenBoundariesDoNotConfuseHdWithFhdOrStarWithStarz() {
        assertEquals(setOf("FHD"), CategoryOrganization.labels(category("1", "ENGLISH FHD"), CategoryBrowseMode.QUALITY))
        assertEquals(setOf("Starz"), CategoryOrganization.labels(category("1", "STARZ"), CategoryBrowseMode.SERVICE))
        assertTrue(CategoryOrganization.labels(category("1", "HISTORY"), CategoryBrowseMode.REGION).isEmpty())
    }

    @Test fun sensitiveCategoriesStayOutOfAggregatesButRemainInProviderView() {
        val rows = listOf(category("1", "ENGLISH"), category("2", "ENGLISH PRIVATE", sensitive = true), category("3", "ADULT"))
        assertTrue(CategoryOrganization.organize(rows, CategoryBrowseMode.COLLECTIONS).all { it.sourceCategoryIds == listOf("1") })
        assertEquals(rows, CategoryOrganization.organize(rows, CategoryBrowseMode.PROVIDER))
    }

    @Test fun unknownCategoriesAreIncludedInAllAndMembershipChangesCacheIdentity() {
        val rows = listOf(category("a", "Unusual provider category"), category("b", "ENGLISH"))
        val all = CategoryOrganization.organize(rows, CategoryBrowseMode.COLLECTIONS).first()
        assertEquals(listOf("a", "b"), all.sourceCategoryIds)
        assertEquals(all.id, CategoryOrganization.organize(rows.reversed(), CategoryBrowseMode.COLLECTIONS).first().id)
        assertNotEquals(all.id, CategoryOrganization.organize(rows.take(1), CategoryBrowseMode.COLLECTIONS).first().id)
    }

    @Test fun selectionDeduplicatesOnlyByProviderIdPreservesVariantsAndRouting() {
        val c = Category("collection", "English", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b"))
        val movie = MediaItem("1", "Movie", null, "play", portalCategoryId = "a")
        val hd = movie.copy(id = "2", title = "Movie HD", portalCategoryId = "b")
        val result = CategoryOrganization.select(listOf(movie, movie, hd, movie.copy(id = "3", portalCategoryId = "c")), c)
        assertEquals(listOf(movie, hd), result)
        assertEquals("a", result.first().portalCategoryId)
        assertEquals("play", result.first().command)
    }

    @Test fun validationAcceptsCollectionMembersAndRejectsForeignRows() {
        val movie = MediaItem("1", "Movie", null, null, portalCategoryId = "a")
        val cache = BrowseCatalogCache("p", CatalogType.MOVIES, 1, emptyList(), mapOf("group" to listOf(movie)))
        assertSame(cache, cache.validateCategory("group", listOf("a", "b")))
        assertFalse(cache.validateCategory("group", listOf("b")).itemsByCategory.containsKey("group"))
    }

    @Test fun localFilterUsesAllWordsIndependentOfCaseAndOrder() {
        assertTrue(CategoryOrganization.matchesTitle("RRR (2022) Multi Language", "language rrr"))
        assertFalse(CategoryOrganization.matchesTitle("RRR", "rrr hindi"))
    }
}
