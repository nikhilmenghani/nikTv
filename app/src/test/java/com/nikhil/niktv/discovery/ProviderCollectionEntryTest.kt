package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCollectionEntryTest {
    private val type = CatalogType.MOVIES
    private val category = Category("a", "Movies", type)
    @Test fun cacheBindingArrivalDoesNotChangeTheSavedVisitIdentity() {
        val loading = ProviderDiscoveryState(profile = "profile", categories = mapOf(type to listOf(category)))
        val ready = loading.copy(identity = "new-cache-binding", ready = true)
        assertEquals(providerCollectionUiIdentity(loading, type), providerCollectionUiIdentity(ready, type))
    }
    @Test fun profileTypeAndOrderedCategoryMembershipRemainIsolated() {
        val state = ProviderDiscoveryState(profile = "one", categories = mapOf(type to listOf(category)))
        val key = providerCollectionUiIdentity(state, type)
        assertNotEquals(key, providerCollectionUiIdentity(state.copy(profile = "two"), type))
        assertNotEquals(key, providerCollectionUiIdentity(state, CatalogType.SERIES))
        assertNotEquals(key, providerCollectionUiIdentity(state.copy(categories = mapOf(type to listOf(category.copy(id = "b")))), type))
        val pair = state.copy(categories = mapOf(type to listOf(category, category.copy(id = "b"))))
        assertNotEquals(providerCollectionUiIdentity(pair, type), providerCollectionUiIdentity(pair.copy(categories = mapOf(type to pair.categories.getValue(type).reversed())), type))
    }
}
