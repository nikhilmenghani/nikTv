package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType

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
