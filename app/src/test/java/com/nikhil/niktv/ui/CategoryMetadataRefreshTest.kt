package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CategoryMetadataRefreshTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://categories.invalid", "")
    private val old = Category("old", "Hindi TV", CatalogType.LIVE_TV)
    private val added = Category("new", "Hindi 24x7 classic shows", CatalogType.LIVE_TV)
    private val channel = MediaItem("channel", "Fixture channel", null, null)
    private val cache = BrowseCatalogCache(profile.cacheKey(), CatalogType.LIVE_TV, 100,
        listOf(old), mapOf(old.id to listOf(channel)), mapOf(old.id to 4), mapOf(old.id to true), mapOf(old.id to 90))
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren(); scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val mutable = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        state = mutable
        state.value = NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://categories.invalid", "", "", ""),
            categories = listOf(old), rawCategoriesByType = mapOf(CatalogType.LIVE_TV to listOf(old)),
            selectedCategory = old, items = listOf(channel), browseCache = cache,
            browseCachesByType = mapOf(CatalogType.LIVE_TV to cache))
        vm.categoryMetadataLookup = { _, _, _ -> error("Unexpected provider lookup") }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun awaitFinished() {
        val end = System.nanoTime() + 6_000_000_000L
        do { scheduler.runCurrent(); if (vm.state.value.categoryRefreshes.isEmpty()) return; Thread.sleep(10) }
        while (System.nanoTime() < end)
        fail("Refresh did not complete")
    }
    @Test fun refreshAddsCategoriesWithoutLosingMediaPagesSelectionOrMediaFreshness() {
        vm.categoryMetadataLookup = { _, _, background -> assertFalse(background); listOf(old, added) }
        vm.refreshProviderCategories(CatalogType.LIVE_TV); awaitFinished()
        val current = vm.state.value
        assertEquals(listOf(old, added), current.categories)
        assertEquals(old, current.selectedCategory)
        assertEquals(listOf(channel), current.items)
        val updated = current.browseCachesByType.getValue(CatalogType.LIVE_TV)
        assertEquals(cache.itemsByCategory, updated.itemsByCategory)
        assertEquals(cache.pagesByCategory, updated.pagesByCategory)
        assertEquals(cache.hasMoreByCategory, updated.hasMoreByCategory)
        assertEquals(cache.cachedAtMillis, updated.cachedAtMillis)
        assertEquals(cache.categoryCachedAtMillis, updated.categoryCachedAtMillis)
        assertTrue(updated.categoriesCachedAtMillis > cache.categoriesCachedAtMillis)
    }
    @Test fun emptyProviderResponseRetainsExistingCategoriesAndReportsTheFailure() {
        vm.categoryMetadataLookup = { _, _, _ -> emptyList() }
        vm.refreshProviderCategories(CatalogType.LIVE_TV); awaitFinished()
        assertEquals(listOf(old), vm.state.value.categories)
        assertEquals(cache, vm.state.value.browseCachesByType[CatalogType.LIVE_TV])
        assertNotNull(vm.state.value.error)
    }
    @Test fun refreshRespectsExplicitHiddenCategorySelections() {
        state.value = state.value.copy(categoryFilters = mapOf("${profile.cacheKey()}|LIVE_TV" to listOf(old.id)))
        vm.categoryMetadataLookup = { _, _, _ -> listOf(old, added) }
        vm.refreshProviderCategories(CatalogType.LIVE_TV); awaitFinished()
        assertEquals(listOf(old), vm.state.value.categories)
        assertEquals(listOf(old, added), vm.state.value.rawCategoriesByType[CatalogType.LIVE_TV])
    }
    @Test fun concurrentRefreshesAreDeduplicatedAndOldProfilesCannotPublish() {
        val response = CompletableDeferred<List<Category>>(); var calls = 0
        vm.categoryMetadataLookup = { _, _, _ -> calls++; response.await() }
        vm.refreshProviderCategories(CatalogType.LIVE_TV); scheduler.runCurrent()
        vm.refreshProviderCategories(CatalogType.LIVE_TV); scheduler.runCurrent()
        assertEquals(1, calls)
        val other = profile.copy(portalUrl = "https://other.invalid")
        state.value = state.value.copy(savedProfile = other, session = PortalSession(other, "fixture", "https://other.invalid", "", "", ""))
        response.complete(listOf(added)); awaitFinished()
        assertEquals(listOf(old), vm.state.value.categories)
        assertEquals(listOf(old), vm.state.value.rawCategoriesByType[CatalogType.LIVE_TV])
    }
    @Test fun automaticRefreshIsBackgroundAndCannotRunDuringPlayback() {
        var calls = 0
        vm.categoryMetadataLookup = { _, _, background -> assertTrue(background); calls++; listOf(old, added) }
        state.value = state.value.copy(nowPlaying = PlayingMedia(channel, "https://fixture.invalid/live", CatalogType.LIVE_TV))
        vm.refreshProviderCategories(CatalogType.LIVE_TV, userRequested = false); scheduler.runCurrent()
        assertEquals(0, calls)
        state.value = state.value.copy(nowPlaying = null)
        vm.refreshProviderCategories(CatalogType.LIVE_TV, userRequested = false); awaitFinished()
        assertEquals(1, calls)
        vm.refreshProviderCategories(CatalogType.LIVE_TV, userRequested = false); scheduler.runCurrent()
        assertEquals("Automatic retry backoff prevents repeated tab-entry requests", 1, calls)
    }
}
