package com.nikhil.niktv.discovery.components

import com.nikhil.niktv.ui.*

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.model.*

@Composable
internal fun DiscoveryCatalogBrowser(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    play: (MediaItem) -> Unit,
    openTrendingMovie: (TrendingMovie) -> Unit,
    openTrendingSeries: (TrendingSeries) -> Unit,
    openHome: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    openSeriesFromHome: (MediaItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    dismissRecommendation: (com.nikhil.niktv.data.HistoryRecommendationRow) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    markEpisodeWatched: (WatchedSeries, MediaItem) -> Unit,
    clearRecent: (FavoriteKind) -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    refreshCatalog: () -> Unit,
    openCategoryManager: (CatalogType) -> Unit,
    removeCategoryFilter: (CatalogType, String) -> Unit,
    setTmdbSections: (DashboardSurface, List<TmdbHomeSection>) -> Unit,
    resetScreenConfiguration: (DashboardSurface) -> Unit,
    loadMoreCatalog: () -> Unit,
    openModernTmdbSection: (TmdbHomeSection) -> Unit,
    openModernIptvCategory: (Category) -> Unit,
    closeModernSection: () -> Unit,
    loadMoreModernTmdbSection: () -> Unit,
    enrichFocusedCatalogMetadata: suspend (MediaItem, CatalogType) -> Unit,
    enrichVisibleLiveGuides: (List<MediaItem>) -> Unit,
    refreshLiveProgramme: (MediaItem) -> Unit,
    refreshTmdbMatches: (Int, CatalogType) -> Unit,
    findChannelsInCategory: (String) -> Unit,
    cancelCategoryFind: () -> Unit,
    playInContext: ((MediaItem, List<MediaItem>, com.nikhil.niktv.model.PlaybackQueueFilter) -> Unit)? = null
) {
    val home = state.homeOpen
    var tmdbSetupOpen by rememberSaveable { mutableStateOf(false) }
    var resetConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    val dashboardSurface = if (home) DashboardSurface.HOME else when (state.selectedType) {
        CatalogType.LIVE_TV -> DashboardSurface.LIVE_TV
        CatalogType.MOVIES -> DashboardSurface.MOVIES
        CatalogType.SERIES -> DashboardSurface.SERIES
        CatalogType.RADIO -> DashboardSurface.LIVE_TV
    }
    val selectedTmdbSections = state.tmdbSectionsBySurface[dashboardSurface].orEmpty()

    // Tile-first sections are the sole browse interface for every destination.
    DiscoveryCatalogContent(
        state = state,
        playInContext = playInContext,
        dashboardSurface = dashboardSurface,
        openHome = openHome,
        selectType = selectType,
        openFavorites = openFavorites,
        openSearch = openSearch,
        openSettings = openSettings,
        openProfileSwitcher = openProfileSwitcher,
        openRecent = openRecent,
        removeRecent = removeRecent,
        openSeries = openSeriesFromHome,
        clearRecentChannels = { clearRecent(FavoriteKind.CHANNEL) },
        openWatchedEpisode = openWatchedEpisode,
        dismissRecommendation = dismissRecommendation,
        dismissWatchedEpisode = dismissWatchedEpisode,
        markEpisodeWatched = markEpisodeWatched,
        openTmdbSection = openModernTmdbSection,
        openIptvCategory = openModernIptvCategory,
        closeSection = closeModernSection,
        openTmdbMovie = openTrendingMovie,
        openTmdbSeries = openTrendingSeries,
        openIptvItem = play,
        toggleFavorite = toggleFavoriteEntry,
        loadMoreTmdb = loadMoreModernTmdbSection,
        loadMoreIptv = loadMoreCatalog,
        refreshIptv = refreshCatalog,
        enrichFocusedCatalogMetadata = enrichFocusedCatalogMetadata,
        enrichVisibleLiveGuides = enrichVisibleLiveGuides,
        refreshLiveProgramme = refreshLiveProgramme,
        refreshTmdbMatches = refreshTmdbMatches,
        findChannelsInCategory = findChannelsInCategory,
        cancelCategoryFind = cancelCategoryFind,
        configureTmdb = { tmdbSetupOpen = true },
        configureIptv = openCategoryManager,
        removeIptvCategory = removeCategoryFilter,
        resetSurface = { resetConfirmationOpen = true }
    )

    if (tmdbSetupOpen) {
        TmdbHomeSectionsDialog(
            selected = selectedTmdbSections,
            surface = dashboardSurface,
            close = { tmdbSetupOpen = false },
            save = {
                setTmdbSections(dashboardSurface, it)
                tmdbSetupOpen = false
            }
        )
    }
    if (resetConfirmationOpen) {
        ProjectCardConfirmationDialog(
            title = "Reset ${dashboardSurface.displayTitle()}?",
            message = "This clears every IPTV and TMDB selection for this screen so you can configure it again from nothing.",
            confirmLabel = "Reset screen",
            close = { resetConfirmationOpen = false },
            confirm = {
                resetScreenConfiguration(dashboardSurface)
                resetConfirmationOpen = false
            }
        )
    }
}
