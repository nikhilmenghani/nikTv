package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.SearchContentType

/** Only portable destinations cross portals; provider categories and detail routes never do. */
internal data class PortalSwitchDestination private constructor(
    private val surface: DashboardSurface?,
    private val library: Boolean,
    private val catalogType: CatalogType,
    private val experiment: Boolean,
    val searchOpen: Boolean,
    val searchType: SearchContentType,
    private val query: String,
) {
    fun restore(current: NikTvState): NikTvState = current.withoutContentOverlays().copy(
        discoverySurface = surface,
        discoveryExperiment = experiment,
        discoveryMatchCounts = emptyMap(),
        discoveryNavigationId = current.discoveryNavigationId + 1,
        homeOpen = !library && surface != null,
        favoritesOpen = library,
        selectedType = catalogType,
        settingsOpen = false,
        offlineDownloadsOpen = false,
        watchProvidersOpen = false,
        modernIptvCategory = null,
        modernTmdbSection = null,
        modernSectionOriginHome = false,
        modernTmdbMovies = emptyList(), modernTmdbSeries = emptyList(),
        modernTmdbMovieMatches = emptyMap(), modernTmdbSeriesMatches = emptyMap(),
        modernTmdbLoading = false, modernTmdbPage = 0, modernTmdbHasMore = false, modernTmdbError = null,
        selectedCategory = null,
        categoryManagerOpen = false,
        categoryFindQuery = "", categoryFindSearching = false, categoryFindPage = 0,
        categoryFindTotalPages = null, categoryFindMessage = null, categoryFindCheckingGuides = false, categoryFindGuideChecked = 0,
        playbackReturnFocusId = null,
        availableSeriesSeasons = emptyList(), selectedSeriesSeason = null,
        seriesSeasonEpisodeCounts = emptyMap(), seriesSeasonEpisodeCountsKey = null,
        episodePage = 1, episodeHasMore = false, episodeLoadingMore = false, episodeDestination = null,
        searchOpen = searchOpen,
        searchType = searchType,
        searchQuery = if (searchOpen) query else "",
        searchScopeLocked = false,
        searchCategoryId = "*", searchCategories = emptyList(), searchResults = emptyList(),
        searchLocalLoading = false, searchServerLoading = false,
        searchProviderMessage = null, searchActivityTitle = null, searchActivityDetail = null, searchActivityProgress = null,
        searchUsedServer = false, searchPage = 0, searchHasMore = false,
        searchPaginationGeneration = current.searchPaginationGeneration + 1,
    )

    companion object {
        fun capture(origin: NikTvState): PortalSwitchDestination {
            // The chooser and a new person's startup also call switchProfile.
            // Their stale browse fields belong to no active portal destination.
            if (origin.session == null) return PortalSwitchDestination(DashboardSurface.HOME,
                false, CatalogType.LIVE_TV, true, false, SearchContentType.ALL, "")
            val library = origin.favoritesOpen || origin.seriesOpenedFromFavorites || origin.offlineDownloadsOpen
            val surface = when {
                library || origin.homeOpen || origin.seriesOpenedFromHome -> origin.discoverySurface ?: DashboardSurface.HOME
                origin.selectedType == CatalogType.MOVIES -> DashboardSurface.MOVIES
                origin.selectedType == CatalogType.SERIES -> DashboardSurface.SERIES
                else -> null
            }
            val type = when (surface) {
                DashboardSurface.MOVIES -> CatalogType.MOVIES
                DashboardSurface.SERIES -> CatalogType.SERIES
                DashboardSurface.LIVE_TV -> CatalogType.LIVE_TV
                else -> origin.selectedType
            }
            return PortalSwitchDestination(surface, library, type,
                if (origin.discoverySurface == surface) origin.discoveryExperiment else true,
                origin.searchOpen, origin.searchType, origin.searchQuery)
        }
    }
}
