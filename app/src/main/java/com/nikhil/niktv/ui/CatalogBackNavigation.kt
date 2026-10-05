package com.nikhil.niktv.ui

internal enum class CatalogBackAction {
    CLOSE_MOVIE_MATCHES,
    CLOSE_SERIES_MATCHES,
    CLOSE_DOWNLOADS,
    CLOSE_SETTINGS,
    CLOSE_SEARCH,
    CLOSE_FAVORITES,
    CLOSE_SERIES,
    CLOSE_MODERN_SECTION,
    OPEN_HOME,
    SHOW_EXIT_CONFIRMATION
}

/** Match the visible main pane even when an underlying destination remains open. */
internal fun catalogBackAction(
    movieMatchesOpen: Boolean = false,
    seriesMatchesOpen: Boolean = false,
    downloadsOpen: Boolean = false,
    settingsOpen: Boolean = false,
    searchOpen: Boolean = false,
    favoritesOpen: Boolean = false,
    seriesOpen: Boolean = false,
    modernSectionOpen: Boolean = false,
    homeOpen: Boolean = false
): CatalogBackAction = when {
    movieMatchesOpen -> CatalogBackAction.CLOSE_MOVIE_MATCHES
    seriesMatchesOpen -> CatalogBackAction.CLOSE_SERIES_MATCHES
    downloadsOpen -> CatalogBackAction.CLOSE_DOWNLOADS
    settingsOpen -> CatalogBackAction.CLOSE_SETTINGS
    searchOpen -> CatalogBackAction.CLOSE_SEARCH
    favoritesOpen -> CatalogBackAction.CLOSE_FAVORITES
    seriesOpen -> CatalogBackAction.CLOSE_SERIES
    modernSectionOpen -> CatalogBackAction.CLOSE_MODERN_SECTION
    homeOpen -> CatalogBackAction.SHOW_EXIT_CONFIRMATION
    else -> CatalogBackAction.OPEN_HOME
}
