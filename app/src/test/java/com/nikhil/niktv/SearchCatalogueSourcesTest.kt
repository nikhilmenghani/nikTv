package com.nikhil.niktv

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class SearchCatalogueSourcesTest {
    @Test fun overlappingProviderIdsCannotLeakAcrossProfiles() {
        val nikhil = MediaItem("1", "Nikhil film", null, "nikhil-command")
        val wio = nikhil.copy(title = "Wio channel", command = "wio-command")
        val cache = BrowseCatalogCache("wio", CatalogType.MOVIES, 1, emptyList(), mapOf("same-category" to listOf(wio)))
        assertTrue(cache.searchItemsForProfile("nikhil", "*").isEmpty())
        assertEquals(listOf(wio), cache.searchItemsForProfile("wio", "same-category"))
        assertTrue(cache.searchItemsForProfile(null, "*").isEmpty())
    }
    @Test fun matchingProfileStillHonorsCategoryScope() {
        val first = MediaItem("1", "Drishyam", null, null)
        val second = first.copy(id = "2", title = "Other")
        val cache = BrowseCatalogCache("nikhil", CatalogType.MOVIES, 1, emptyList(), mapOf("hindi" to listOf(first), "english" to listOf(second)))
        assertEquals(listOf(first), cache.searchItemsForProfile("nikhil", "hindi"))
        assertEquals(listOf(first, second), cache.searchItemsForProfile("nikhil", "*"))
    }
}
