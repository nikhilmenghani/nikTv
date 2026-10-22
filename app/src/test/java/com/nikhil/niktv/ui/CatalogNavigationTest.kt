package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface
import org.junit.Assert.*
import org.junit.Test

class CatalogNavigationTest {
    @Test fun liveTvReplacesMyListEvenWhenLiveCatalogueIsAlreadySelected() {
        val result = NikTvState(selectedType = CatalogType.LIVE_TV, favoritesOpen = true,
            discoverySurface = DashboardSurface.HOME).enterCatalogRoot(CatalogType.LIVE_TV)
        assertFalse(result.favoritesOpen)
        assertFalse(result.homeOpen)
        assertNull(result.discoverySurface)
        assertEquals(CatalogType.LIVE_TV, result.selectedType)
    }
    @Test fun rootSelectionClosesSecondaryDestinationsWithoutDiscardingSavedMedia() {
        val original = NikTvState(settingsOpen = true, searchOpen = true, offlineDownloadsOpen = true)
        val result = original.enterCatalogRoot(CatalogType.SERIES)
        assertFalse(result.settingsOpen)
        assertFalse(result.searchOpen)
        assertFalse(result.offlineDownloadsOpen)
        assertEquals(original.favorites, result.favorites)
        assertEquals(original.recentlyPlayed, result.recentlyPlayed)
        assertEquals(CatalogType.SERIES, result.selectedType)
    }
}
