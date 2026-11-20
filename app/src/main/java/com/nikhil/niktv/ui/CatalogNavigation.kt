package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface

/** Replace an unavailable Home route with the IPTV landing page while retaining overlays. */
internal fun NikTvState.withHomeTab(enabled: Boolean = showHomeTab): NikTvState {
    if (enabled || (discoverySurface != DashboardSurface.HOME && !(homeOpen && discoverySurface == null))) {
        return copy(showHomeTab = enabled)
    }
    val live = browseCachesByType[CatalogType.LIVE_TV]
    return copy(
        showHomeTab = false, homeOpen = false, discoverySurface = null, watchProvidersOpen = false,
        modernSectionOriginHome = false, selectedType = CatalogType.LIVE_TV,
        categories = if (selectedType == CatalogType.LIVE_TV) categories else live?.categories.orEmpty(),
        selectedCategory = selectedCategory?.takeIf { it.type == CatalogType.LIVE_TV },
        browseCache = live,
        items = if (selectedType == CatalogType.LIVE_TV || selectedSeries != null) items else emptyList()
    )
}

internal fun NikTvState.isIptvLanding() =
    !showHomeTab && !homeOpen && discoverySurface == null && selectedType == CatalogType.LIVE_TV

/** A menu destination takes ownership even when its catalogue type is already active underneath an overlay. */
internal fun NikTvState.enterCatalogRoot(type: CatalogType): NikTvState = withoutContentOverlays().copy(
    favoritesOpen = false, homeOpen = false, settingsOpen = false, searchOpen = false,
    selectedSeries = null, selectedType = type, discoverySurface = null,
    offlineDownloadsOpen = false
)

/** Choosing a destination replaces content routes instead of leaving them above that destination. */
internal fun NikTvState.withoutContentOverlays(): NikTvState = copy(
    providerMovieDetails = null,
    movieMatchSelection = null, movieMatchCandidates = emptyList(), movieMatchLoadingMore = false,
    seriesMatchSelection = null, seriesMatchCandidates = emptyList(),
    selectedSeries = null, seriesOpenedFromHome = false, seriesOpenedFromFavorites = false,
    seriesOpenedFromModernSection = false, episodeTmdbRefresh = null, tmdbIptvLookupMiss = null
)
