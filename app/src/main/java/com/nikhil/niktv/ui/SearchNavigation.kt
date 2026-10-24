package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem

/** Search overlays the modern destination, so Back can reveal the same My List. */
internal fun NikTvState.openSearchNavigation(): NikTvState = copy(
    searchOpen = true,
    settingsOpen = false,
    favoritesOpen = discoveryExperienceEnabled && favoritesOpen
)

/**
 * An in-memory return route, not a replacement app-state snapshot. In particular,
 * returning to results must retain fresh progress, history and provider caches.
 */
internal class SearchSeriesReturn private constructor(
    private val profileKey: String,
    private val seriesId: String,
    private val restoreBrowse: (NikTvState) -> NikTvState
) {
    fun restore(current: NikTvState): NikTvState? =
        if (current.searchProfileKey() == profileKey && current.selectedSeries?.id == seriesId) {
            restoreBrowse(current).copy(searchOpen = true, searchLocalLoading = false, searchServerLoading = false)
        } else null

    fun resolved(profile: String, requested: String, resolved: String): SearchSeriesReturn =
        if (profile == profileKey && requested == seriesId) SearchSeriesReturn(profileKey, resolved, restoreBrowse) else this

    companion object {
        fun capture(origin: NikTvState, selected: MediaItem): SearchSeriesReturn? {
            if (!origin.discoveryExperienceEnabled || !origin.searchOpen) return null
            val profile = origin.searchProfileKey() ?: return null
            // Capture only the fields owned by the replaced browse/detail page.
            val home = origin.homeOpen
            val favorites = origin.favoritesOpen
            val type = origin.selectedType
            val previousSeries = origin.selectedSeries
            val items = origin.items
            val focus = origin.playbackReturnFocusId
            val fromHome = origin.seriesOpenedFromHome
            val fromFavorites = origin.seriesOpenedFromFavorites
            val fromSection = origin.seriesOpenedFromModernSection
            val tmdbMetadata = origin.useTmdbEpisodeMetadata
            val seasons = origin.availableSeriesSeasons
            val season = origin.selectedSeriesSeason
            val seasonCounts = origin.seriesSeasonEpisodeCounts
            val seasonCountsKey = origin.seriesSeasonEpisodeCountsKey
            val page = origin.episodePage
            val hasMore = origin.episodeHasMore
            return SearchSeriesReturn(profile, selected.id) { current -> current.copy(
                homeOpen = home, favoritesOpen = favorites, selectedType = type,
                selectedSeries = previousSeries, items = items, playbackReturnFocusId = focus,
                seriesOpenedFromHome = fromHome, seriesOpenedFromFavorites = fromFavorites,
                seriesOpenedFromModernSection = fromSection, useTmdbEpisodeMetadata = tmdbMetadata,
                availableSeriesSeasons = seasons, selectedSeriesSeason = season,
                seriesSeasonEpisodeCounts = seasonCounts, seriesSeasonEpisodeCountsKey = seasonCountsKey,
                episodePage = page, episodeHasMore = hasMore, episodeLoadingMore = false
            ) }
        }
    }
}

private fun NikTvState.searchProfileKey(): String? = session?.profile?.cacheKey() ?: savedProfile?.cacheKey()
