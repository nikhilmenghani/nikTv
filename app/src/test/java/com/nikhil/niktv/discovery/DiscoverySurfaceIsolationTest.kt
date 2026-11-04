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
    @Test fun movieViewModelsCannotRebindHomeOrLegacySeries() {
        assertEquals("discovery2:p:true", discoverySurfaceVmKey("p", DashboardSurface.HOME, true))
        assertNotEquals(discoverySurfaceVmKey("p", DashboardSurface.HOME, true), discoverySurfaceVmKey("p", DashboardSurface.MOVIES, true))
        assertEquals("discovery2:p:false", discoverySurfaceVmKey("p", DashboardSurface.SERIES, false))
        assertFalse(optimizedDiscoverySurface(DashboardSurface.SERIES))
        assertTrue(optimizedDiscoverySurface(DashboardSurface.MOVIES))
    }
}
