package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class PortalSwitchNavigationTest {
    private val movie = MediaItem("old-provider-id", "Old provider title", null, null)
    private val profile = PortalProfile("Active portal", "https://active.invalid", "fixture")
    private val session = PortalSession(profile, "fixture-token", "https://active.invalid/portal.php", "", "", "")

    @Test fun eachDashboardStaysOnItsCurrentSurfaceWhenThePortalChanges() {
        for (surface in DashboardSurface.entries) {
            val origin = NikTvState(session = session, savedProfile = profile,
                discoverySurface = surface, homeOpen = true, discoveryExperiment = true)
            val result = PortalSwitchDestination.capture(origin).restore(origin)
            assertEquals(surface, result.discoverySurface)
            assertTrue(result.homeOpen)
            assertFalse(result.searchOpen)
            assertFalse(result.favoritesOpen)
            assertTrue(result.discoveryNavigationId > origin.discoveryNavigationId)
        }
    }

    @Test fun liveCategorySwitchReturnsToLiveRootWithoutCarryingProviderCategoryIds() {
        val category = Category("old-category", "Old live category", CatalogType.LIVE_TV)
        val origin = NikTvState(session = session, savedProfile = profile, homeOpen = false, selectedType = CatalogType.LIVE_TV,
            selectedCategory = category, modernIptvCategory = category)
        val result = PortalSwitchDestination.capture(origin).restore(origin)
        assertFalse(result.homeOpen)
        assertEquals(CatalogType.LIVE_TV, result.selectedType)
        assertNull(result.discoverySurface)
        assertNull(result.selectedCategory)
        assertNull(result.modernIptvCategory)
        assertEquals(CatalogBackAction.OPEN_HOME, backAction(result))
    }

    @Test fun categorySearchRetainsTitleAndMediaTypeAndSearchesAllCategoriesOfTheNewPortal() {
        val origin = NikTvState(session = session, savedProfile = profile, homeOpen = true, discoverySurface = DashboardSurface.SERIES,
            searchOpen = true, searchQuery = "Friends", searchType = SearchContentType.SERIES,
            searchScopeLocked = true, searchCategoryId = "old-english-series", searchResults = listOf(movie),
            searchCategories = listOf(Category("old-english-series", "English", CatalogType.SERIES)),
            searchPage = 12, searchUsedServer = true, searchHasMore = true, searchServerLoading = true)
        val result = PortalSwitchDestination.capture(origin).restore(origin)
        assertTrue(result.searchOpen)
        assertEquals("Friends", result.searchQuery)
        assertEquals(SearchContentType.SERIES, result.searchType)
        assertEquals("*", result.searchCategoryId)
        assertFalse(result.searchScopeLocked)
        assertTrue(result.searchCategories.isEmpty())
        assertTrue(result.searchResults.isEmpty())
        assertFalse(result.searchHasMore)
        assertFalse(result.searchUsedServer)
        assertEquals(0, result.searchPage)
        assertFalse(result.searchServerLoading)
        assertEquals(CatalogBackAction.CLOSE_SEARCH, backAction(result))
        val afterBack = result.copy(searchOpen = false)
        assertEquals(DashboardSurface.SERIES, afterBack.discoverySurface)
        assertEquals(CatalogBackAction.SHOW_EXIT_CONFIRMATION, backAction(afterBack))
    }

    @Test fun libraryAndItsSearchOverlaySurviveWithoutRestoringAnOldSeriesOnBack() {
        val origin = NikTvState(session = session, savedProfile = profile, homeOpen = false, favoritesOpen = true, searchOpen = true,
            discoverySurface = DashboardSurface.MOVIES, selectedSeries = movie,
            seriesOpenedFromFavorites = true, seriesOpenedFromHome = true, seriesOpenedFromModernSection = true,
            selectedSeriesSeason = 8, availableSeriesSeasons = listOf(7, 8), playbackReturnFocusId = movie.id,
            providerMovieDetails = com.nikhil.niktv.discovery.ProviderMovieDetails("old", movie, listOf(movie)))
        val result = PortalSwitchDestination.capture(origin).restore(origin)
        assertTrue(result.searchOpen)
        assertTrue(result.favoritesOpen)
        assertFalse(result.homeOpen)
        assertNull(result.selectedSeries)
        assertNull(result.providerMovieDetails)
        assertNull(result.playbackReturnFocusId)
        assertNull(result.selectedSeriesSeason)
        assertTrue(result.availableSeriesSeasons.isEmpty())
        assertFalse(result.seriesOpenedFromFavorites)
        assertFalse(result.seriesOpenedFromHome)
        assertFalse(result.seriesOpenedFromModernSection)
        assertEquals(CatalogBackAction.CLOSE_SEARCH, backAction(result))
        assertEquals(CatalogBackAction.CLOSE_FAVORITES, backAction(result.copy(searchOpen = false)))
    }

    @Test fun providerMovieAndSeriesCategoriesReturnToTheMatchingDashboard() {
        for ((type, surface) in listOf(CatalogType.MOVIES to DashboardSurface.MOVIES, CatalogType.SERIES to DashboardSurface.SERIES)) {
            val origin = NikTvState(session = session, savedProfile = profile, homeOpen = false, selectedType = type,
                selectedSeries = movie, modernIptvCategory = Category("old-category", "Old category", type))
            val result = PortalSwitchDestination.capture(origin).restore(origin)
            assertEquals(surface, result.discoverySurface)
            assertTrue(result.homeOpen)
            assertNull(result.selectedSeries)
            assertNull(result.modernIptvCategory)
        }
    }

    @Test fun destinationRestorationKeepsNewPortalIdentityAndFreshLibraryData() {
        val origin = NikTvState(session = session, savedProfile = profile, discoverySurface = DashboardSurface.MOVIES)
        val newProfile = PortalProfile("New portal", "https://new.invalid", "fixture")
        val freshProgress = PlaybackProgress("fresh-progress", 120, 240)
        val current = origin.copy(savedProfile = newProfile, session = session.copy(profile = newProfile), playbackProgress = listOf(freshProgress))
        val result = PortalSwitchDestination.capture(origin).restore(current)
        assertEquals(newProfile, result.savedProfile)
        assertEquals(listOf(freshProgress), result.playbackProgress)
        assertEquals(DashboardSurface.MOVIES, result.discoverySurface)
    }

    @Test fun chooserOrNewPersonStartsOnHomeInsteadOfInheritingPreviousViewingRoutes() {
        val origin = NikTvState(session = null, savedProfile = profile,
            homeOpen = false, discoverySurface = DashboardSurface.SERIES, selectedType = CatalogType.SERIES,
            discoveryExperiment = false, favoritesOpen = true, searchOpen = true,
            searchQuery = "Previous viewer's search", searchType = SearchContentType.SERIES,
            searchCategoryId = "old-category", selectedSeries = movie, seriesOpenedFromFavorites = true)
        val result = PortalSwitchDestination.capture(origin).restore(origin)
        assertEquals(DashboardSurface.HOME, result.discoverySurface)
        assertTrue(result.discoveryExperiment)
        assertTrue(result.homeOpen)
        assertFalse(result.favoritesOpen)
        assertFalse(result.searchOpen)
        assertEquals("", result.searchQuery)
        assertEquals(SearchContentType.ALL, result.searchType)
        assertEquals("*", result.searchCategoryId)
        assertNull(result.selectedSeries)
        assertFalse(result.seriesOpenedFromFavorites)
        assertEquals(CatalogBackAction.SHOW_EXIT_CONFIRMATION, backAction(result))
    }

    private fun backAction(state: NikTvState) = catalogBackAction(searchOpen = state.searchOpen,
        favoritesOpen = state.favoritesOpen, seriesOpen = state.selectedSeries != null,
        modernSectionOpen = state.modernIptvCategory != null || state.modernTmdbSection != null, homeOpen = state.homeOpen)
}
