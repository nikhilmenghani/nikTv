package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoverySkeletonTest {
    private val section = TmdbHomeSection.TRENDING_MOVIES
    @Test fun deferredFeedLoadsUntilResponseOrFailureWithoutCoveringCachedContent() {
        assertTrue(discoverySectionSkeleton(section, DiscoveryState()))
        assertFalse(discoverySectionSkeleton(section, DiscoveryState(feeds = mapOf(section to emptyList()))))
        assertFalse(discoverySectionSkeleton(section, DiscoveryState(errors = mapOf("feed:${section.name}" to "Offline"))))
        val cached = DiscoveryState(feeds = mapOf(section to listOf(DiscoveryCard(1, false, "Cached"))), loading = setOf("feed:${section.name}"))
        assertFalse(discoverySectionSkeleton(section, cached))
    }
    @Test fun providerPlaceholderIsProfileScopedAndStopsOnKnownEmptyOrFailure() {
        val category = Category("one", "Movies", CatalogType.MOVIES)
        val initial = ProviderDiscoveryState(profile = "current", ready = true)
        assertTrue(discoveryProviderSkeleton("current", category, initial))
        val empty = initial.copy(pages = mapOf(category.type to mapOf(category.id to ProviderDiscoveryPage(page = 1, hasMore = false))))
        assertFalse(discoveryProviderSkeleton("current", category, empty))
        assertFalse(discoveryProviderSkeleton("current", category, initial.copy(errors = mapOf(category.type to "Offline"))))
        val cached = empty.copy(pages = mapOf(category.type to mapOf(category.id to ProviderDiscoveryPage(listOf(MediaItem("1", "Cached", null, null)), page = 1))), loading = setOf(category.type))
        assertFalse(discoveryProviderSkeleton("current", category, cached))
        assertTrue("Another profile's cache must not finish this loading state", discoveryProviderSkeleton("new-profile", category, cached))
    }
    @Test fun mergedProviderCollectionWaitsForItsOwnSourcesOnly() {
        val merged = Category("merged", "Collection", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b"))
        val state = ProviderDiscoveryState(profile = "p", ready = true, pages = mapOf(merged.type to mapOf(
            "unrelated" to ProviderDiscoveryPage(listOf(MediaItem("x", "Other", null, null)), page = 1),
            "a" to ProviderDiscoveryPage(page = 1))))
        assertTrue(discoveryProviderSkeleton("p", merged, state))
        assertFalse(discoveryProviderSkeleton("p", merged, state.copy(errors = mapOf(merged.type to "Failed"))))
    }
    @Test fun drawingsAreBoundedToViewportAndNeverMaterializeTheCatalogue() {
        assertEquals(0, discoverySkeletonSlots(0, 104, 12))
        assertEquals(8, discoverySkeletonSlots(900, 104, 12))
        assertEquals(12, discoverySkeletonSlots(10_000, 50, 12))
    }
}
