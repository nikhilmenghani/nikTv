package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CategoryRefreshPolicyTest {
    @Test fun expiryHonorsTheConfiguredIntervalAndClockChanges() {
        assertFalse(categoryMetadataExpired(1000, 60_999, 1))
        assertTrue(categoryMetadataExpired(1000, 61_000, 1))
        assertTrue(categoryMetadataExpired(0, 1000, 60))
        assertTrue(categoryMetadataExpired(2000, 1000, 60))
    }
    @Test fun oldCacheRecordsRemainReadableAndMediaUpdatesDoNotRenewCategoryAge() {
        val old = Json.decodeFromString<BrowseCatalogCache>("""{"profileKey":"fixture","type":"LIVE_TV","cachedAtMillis":100,"categories":[],"itemsByCategory":{}}""")
        assertEquals(100L, old.categoriesCachedAtMillis)
        val mediaUpdated = old.copy(cachedAtMillis = 1000)
        assertEquals(100L, mediaUpdated.categoriesCachedAtMillis)
        assertEquals(100L, Json.decodeFromString<BrowseCatalogCache>(Json.encodeToString(mediaUpdated)).categoriesCachedAtMillis)
    }
    @Test fun groupedSearchCanFindTheOriginalProviderCategoryWithoutChangingTheDefaultView() {
        val raw = listOf(Category("classic", "Hindi 24x7 classic shows", CatalogType.LIVE_TV))
        val grouped = CategoryOrganization.organize(raw, CategoryBrowseMode.COLLECTIONS)
        assertEquals(grouped, CategoryOrganization.search(grouped, raw, ""))
        assertEquals(raw, CategoryOrganization.search(grouped, raw, "classic shows"))
        assertFalse(CategoryOrganization.search(grouped, raw, "Hindi").map { it.id }.groupingBy { it }.eachCount().any { it.value > 1 })
    }
    @Test fun rawSearchDoesNotReintroduceSensitiveCategoriesIntoSafeGroups() {
        val raw = listOf(Category("adult", "Adult Hindi", CatalogType.LIVE_TV, isAdult = true))
        assertTrue(CategoryOrganization.search(emptyList(), raw, "Hindi").isEmpty())
    }
}
