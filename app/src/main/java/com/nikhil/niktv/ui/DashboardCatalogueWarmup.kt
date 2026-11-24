package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface

internal const val CatalogueWarmupIdleMillis = 1_500L

internal fun NikTvState.dashboardCatalogueType(): CatalogType = when (discoverySurface) {
    DashboardSurface.MOVIES -> CatalogType.MOVIES
    DashboardSurface.SERIES -> CatalogType.SERIES
    DashboardSurface.HOME, DashboardSurface.LIVE_TV -> CatalogType.LIVE_TV
    null -> selectedType
}

internal fun dashboardCatalogueOrder(active: CatalogType): List<CatalogType> {
    val types = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
    return if (active in types) listOf(active) + types.filterNot { it == active } else types
}

/** Cheap route identity. Catalogue pages, guide ticks and artwork updates do not reset the quiet period. */
internal data class DashboardCatalogueWarmupRoute(
    val type: CatalogType,
    val surface: DashboardSurface?,
    val category: String?,
    val navigationId: Long,
)

internal fun NikTvState.dashboardCatalogueWarmupRoute(profileKey: String): DashboardCatalogueWarmupRoute? {
    if (session?.profile?.cacheKey() != profileKey || restoring || loading || reauthenticating ||
        searchOpen || settingsOpen || favoritesOpen || offlineDownloadsOpen || nowPlaying != null ||
        profileSwitcherOpen || profileEditorOpen || categoryManagerOpen || parentalPrompt != null ||
        selectedSeries != null || providerMovieDetails != null || modernIptvCategory != null ||
        modernTmdbSection != null || watchProvidersOpen || movieMatchSelection != null || seriesMatchSelection != null) return null
    return DashboardCatalogueWarmupRoute(dashboardCatalogueType(), discoverySurface,
        selectedCategory?.id.takeIf { discoverySurface == null }, discoveryNavigationId)
}
