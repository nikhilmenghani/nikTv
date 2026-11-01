package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class SearchNavigationTest {
    @Test fun movieContainerBackRestoresMovieCategoryAndFreshProgressWithoutOpeningSearch() {
        val movie = MediaItem("event", "Event", null, "container")
        val category = Category("sports", "Sports", CatalogType.MOVIES)
        val origin = NikTvState(savedProfile = profile, selectedType = CatalogType.MOVIES,
            selectedCategory = category, items = listOf(movie), homeOpen = false, searchOpen = false)
        val route = requireNotNull(SearchSeriesReturn.capture(origin, movie, movieContainer = true))
        val progress = PlaybackProgress("SERIES:event:1:1", 30000, 120000)
        val current = origin.copy(selectedType = CatalogType.SERIES, selectedSeries = movie.copy(vodSeriesContainer = true),
            items = emptyList(), playbackProgress = listOf(progress))
        val restored = requireNotNull(route.restore(current))
        assertEquals(CatalogType.MOVIES, restored.selectedType)
        assertEquals(category, restored.selectedCategory)
        assertEquals(origin.items, restored.items)
        assertEquals(movie.id, restored.playbackReturnFocusId)
        assertFalse(restored.searchOpen)
        assertNull(restored.selectedSeries)
        assertEquals(listOf(progress), restored.playbackProgress)
        assertNull(route.restore(current.copy(savedProfile = profile.copy(name = "Other", portalUrl = "https://other.invalid"))))
    }
    private val profile = PortalProfile("Fixture", "https://provider.invalid", "00:00:00:00:00:00")
    private val selected = MediaItem("series:1", "Selected series", null, null, searchResultType = SearchContentType.SERIES)
    private val saved = MediaItem("saved:1", "Saved movie", null, null)
    private val result = MediaItem("result:2", "Another result", null, null)

    @Test fun modernSearchOverlaysMyListWithoutLosingItsOrigin() {
        val origin = NikTvState(savedProfile = profile, favoritesOpen = true, homeOpen = false,
            selectedType = CatalogType.LIVE_TV, items = listOf(saved))
        val search = origin.openSearchNavigation()
        assertTrue(search.searchOpen)
        assertTrue(search.favoritesOpen)
        assertFalse(search.homeOpen)
        assertEquals(origin.items, search.items)
        assertEquals(origin.selectedType, search.selectedType)
        assertTrue(search.copy(searchOpen = false).favoritesOpen)
    }

    @Test fun seriesBackRestoresSearchAndParentWithoutFetchingACategoryOrLosingNewProgress() {
        val origin = NikTvState(savedProfile = profile, favoritesOpen = true, homeOpen = false,
            selectedType = CatalogType.LIVE_TV, selectedCategory = null, items = listOf(saved),
            searchQuery = "series", searchResults = listOf(selected, result)).openSearchNavigation()
        val route = requireNotNull(SearchSeriesReturn.capture(origin, selected))
        val progress = PlaybackProgress("SERIES:episode", 30_000, 120_000)
        val detail = origin.copy(searchOpen = false, homeOpen = false, favoritesOpen = false,
            selectedType = CatalogType.SERIES, selectedSeries = selected, items = listOf(result),
            searchServerLoading = true, playbackProgress = listOf(progress))
        val restored = requireNotNull(route.restore(detail))
        assertTrue(restored.searchOpen)
        assertTrue(restored.favoritesOpen)
        assertEquals(CatalogType.LIVE_TV, restored.selectedType)
        assertNull(restored.selectedCategory)
        assertNull(restored.selectedSeries)
        assertEquals(listOf(saved), restored.items)
        assertEquals(origin.searchResults, restored.searchResults)
        assertEquals(listOf(progress), restored.playbackProgress)
        assertFalse(restored.searchServerLoading)
        assertFalse(restored.searchLocalLoading)
    }

    @Test fun obsoleteProfileOrDifferentSeriesCannotUseAReturnRoute() {
        val origin = NikTvState(savedProfile = profile).openSearchNavigation()
        val route = requireNotNull(SearchSeriesReturn.capture(origin, selected))
        val detail = origin.copy(selectedSeries = selected)
        assertNull(route.restore(detail.copy(savedProfile = profile.copy(portalUrl = "https://other.invalid"))))
        assertNull(route.restore(detail.copy(selectedSeries = selected.copy(id = "another-series"))))
        assertNull(route.restore(detail.copy(selectedSeries = null)))
    }

    @Test fun resolvingAProviderContainerKeepsTheSameSearchReturnRoute() {
        val origin = NikTvState(savedProfile = profile).openSearchNavigation()
        val route = requireNotNull(SearchSeriesReturn.capture(origin, selected))
        val resolved = selected.copy(id = "container:1")
        val rebound = route.resolved(profile.cacheKey(), selected.id, resolved.id)
        assertNotNull(rebound.restore(origin.copy(selectedSeries = resolved)))
        assertNull(route.resolved("other-profile", selected.id, resolved.id).restore(origin.copy(selectedSeries = resolved)))
        assertNull(route.resolved(profile.cacheKey(), "other-series", resolved.id).restore(origin.copy(selectedSeries = resolved)))
    }

    @Test fun searchFromAnExistingSeriesRestoresItsEpisodeState() {
        val previous = selected.copy(id = "previous-series")
        val origin = NikTvState(savedProfile = profile, selectedSeries = previous,
            selectedType = CatalogType.SERIES, items = listOf(saved), seriesOpenedFromHome = true,
            selectedSeriesSeason = 3, availableSeriesSeasons = listOf(1, 2, 3),
            seriesSeasonEpisodeCounts = mapOf(3 to 24), seriesSeasonEpisodeCountsKey = "previous-series",
            episodePage = 2, episodeHasMore = true).openSearchNavigation()
        val route = requireNotNull(SearchSeriesReturn.capture(origin, selected))
        val restored = requireNotNull(route.restore(origin.copy(selectedSeries = selected, items = emptyList(),
            selectedSeriesSeason = 1, availableSeriesSeasons = listOf(1), episodePage = 1, episodeHasMore = false)))
        assertEquals(previous, restored.selectedSeries)
        assertEquals(listOf(saved), restored.items)
        assertTrue(restored.seriesOpenedFromHome)
        assertEquals(3, restored.selectedSeriesSeason)
        assertEquals(listOf(1, 2, 3), restored.availableSeriesSeasons)
        assertEquals(mapOf(3 to 24), restored.seriesSeasonEpisodeCounts)
        assertEquals(2, restored.episodePage)
        assertTrue(restored.episodeHasMore)
    }

    @Test fun returnRoutesAreOnlyCapturedForOpenModernSearchWithAProfile() {
        assertNull(SearchSeriesReturn.capture(NikTvState(savedProfile = profile), selected))
        assertNull(SearchSeriesReturn.capture(NikTvState(searchOpen = true), selected))
    }
}
