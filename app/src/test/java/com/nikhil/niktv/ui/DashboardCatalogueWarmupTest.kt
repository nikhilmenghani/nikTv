package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardCatalogueWarmupTest {
    private val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
    private val home = NikTvState(session = PortalSession(profile, "local", profile.portalUrl, "", "", ""),
        restoring = false, discoverySurface = DashboardSurface.HOME)

    @Test fun prioritizesTheVisibleType() {
        assertEquals(listOf(CatalogType.MOVIES, CatalogType.LIVE_TV, CatalogType.SERIES),
            dashboardCatalogueOrder(home.copy(discoverySurface = DashboardSurface.MOVIES).dashboardCatalogueType()))
        assertEquals(CatalogType.LIVE_TV, home.dashboardCatalogueWarmupRoute(profile.cacheKey())?.type)
    }

    @Test fun usesRouteIdentityInsteadOfUnrelatedStateRevisions() {
        val route = home.dashboardCatalogueWarmupRoute(profile.cacheKey())
        assertEquals(route, home.copy(error = "Background guide unavailable",
            items = listOf(MediaItem("new", "New title", null, null))).dashboardCatalogueWarmupRoute(profile.cacheKey()))
        assertNotEquals(route, home.copy(discoverySurface = DashboardSurface.SERIES,
            discoveryNavigationId = 1).dashboardCatalogueWarmupRoute(profile.cacheKey()))
    }

    @Test fun suspendsWarmupDuringForegroundWorkAndPortalChanges() {
        for (state in listOf(home.copy(loading = true), home.copy(searchOpen = true),
            home.copy(settingsOpen = true), home.copy(favoritesOpen = true), home.copy(session = null))) {
            assertNull(state.dashboardCatalogueWarmupRoute(profile.cacheKey()))
        }
        assertNull(home.dashboardCatalogueWarmupRoute("another-portal"))
    }
}
