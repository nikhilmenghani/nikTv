package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.CatalogType
import org.junit.Assert.*
import org.junit.Test

class DiscoveryLibraryRoutingTest {
    @Test fun liveAndSavedMediaUseDiscoveryWithoutChangingPersistedCatalogueTypes() {
        assertEquals(DiscoveryLibraryDestination.LIVE_TV, destination(type = CatalogType.LIVE_TV))
        assertEquals(DiscoveryLibraryDestination.MY_LIST, destination(favorites = true))
        assertNull(destination(home = true))
        assertNull(destination(type = CatalogType.MOVIES))
        assertNull(destination(type = CatalogType.SERIES))
    }
    @Test fun secondaryRoutesKeepTheirOwners() {
        assertNull(destination(enabled = false))
        assertNull(destination(overlay = true))
        assertNull(destination(detail = true))
        assertNull(destination(favorites = true, overlay = true))
    }
    @Test fun catalogueMovieAndSeriesRootsAlwaysResolveToModernSurfaces() {
        assertEquals(com.nikhil.niktv.model.DashboardSurface.MOVIES, modernCatalogSurface(CatalogType.MOVIES))
        assertEquals(com.nikhil.niktv.model.DashboardSurface.SERIES, modernCatalogSurface(CatalogType.SERIES))
        assertNull(modernCatalogSurface(CatalogType.LIVE_TV))
        assertNull(modernCatalogSurface(CatalogType.RADIO))
    }
    private fun destination(enabled: Boolean = true, home: Boolean = false, type: CatalogType = CatalogType.LIVE_TV,
        favorites: Boolean = false, overlay: Boolean = false, detail: Boolean = false) =
        discoveryLibraryDestination(enabled, home, type, favorites, overlay, detail)
}
