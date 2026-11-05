package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.DashboardSurface
import org.junit.Assert.*
import org.junit.Test

class DiscoverySurfaceIsolationTest {
    @Test fun homeKeysRemainCompatibleAndMoviesCannotChangeThem() {
        assertEquals("presenter:p:tv", discoveryPresenterKey("p", DashboardSurface.HOME, true))
        assertEquals("title_metadata", discoveryPresentationOptionKey("title_metadata", "p", DashboardSurface.HOME, true))
        assertNotEquals(discoveryPresenterKey("p", DashboardSurface.HOME, true), discoveryPresenterKey("p", DashboardSurface.MOVIES, true))
        assertNotEquals(discoveryPresenterKey("p", DashboardSurface.MOVIES, true), discoveryPresenterKey("q", DashboardSurface.MOVIES, true))
        assertNotEquals(discoveryPresentationOptionKey("title_metadata", "p", DashboardSurface.MOVIES, true),
            discoveryPresentationOptionKey("title_metadata", "p", DashboardSurface.MOVIES, false))
    }
    @Test fun catalogViewModelsCannotRebindHomeOrEachOther() {
        assertEquals("discovery2:p:true", discoverySurfaceVmKey("p", DashboardSurface.HOME, true))
        assertNotEquals(discoverySurfaceVmKey("p", DashboardSurface.HOME, true), discoverySurfaceVmKey("p", DashboardSurface.MOVIES, true))
        assertEquals("discovery2:p:false", discoverySurfaceVmKey("p", DashboardSurface.SERIES, false))
        assertTrue(optimizedDiscoverySurface(DashboardSurface.SERIES))
        assertNotEquals(discoverySurfaceVmKey("p", DashboardSurface.MOVIES, true), discoverySurfaceVmKey("p", DashboardSurface.SERIES, true))
        assertNotEquals(discoverySurfaceVmKey("p", DashboardSurface.SERIES, false), discoverySurfaceVmKey("p", DashboardSurface.SERIES, true))
        assertTrue(optimizedDiscoverySurface(DashboardSurface.MOVIES))
    }
    @Test fun providerAdaptersAndSeriesPreferencesCannotEnterHomeOrMovies() {
        assertNull(discoveryProviderCatalog(DashboardSurface.HOME))
        assertEquals(com.nikhil.niktv.model.CatalogType.MOVIES, discoveryProviderCatalog(DashboardSurface.MOVIES))
        assertEquals(com.nikhil.niktv.model.CatalogType.SERIES, discoveryProviderCatalog(DashboardSurface.SERIES))
        for (tv in listOf(false, true)) {
            assertNotEquals(discoveryPresenterKey("p", DashboardSurface.MOVIES, tv), discoveryPresenterKey("p", DashboardSurface.SERIES, tv))
            assertNotEquals(discoveryPresentationOptionKey("tile_skeletons", "p", DashboardSurface.HOME, tv),
                discoveryPresentationOptionKey("tile_skeletons", "p", DashboardSurface.SERIES, tv))
            assertNotEquals(discoveryPresentationOptionKey("home_hero_framing", "p", DashboardSurface.MOVIES, tv),
                discoveryPresentationOptionKey("home_hero_framing", "p", DashboardSurface.SERIES, tv))
        }
    }
}
