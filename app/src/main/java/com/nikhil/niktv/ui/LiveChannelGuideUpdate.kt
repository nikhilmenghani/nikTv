package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem

/** A guide response changes guide fields only, never stream commands or the active selection. */
internal fun NikTvState.withLiveChannelGuide(profileKey: String, enriched: MediaItem): NikTvState {
    if (session?.profile?.cacheKey() != profileKey) return this
    fun MediaItem.merge(): MediaItem = if (id != enriched.id) this else copy(
        liveProgramme = enriched.liveProgramme,
        liveSchedule = enriched.liveSchedule
    )
    fun com.nikhil.niktv.model.BrowseCatalogCache.merge() =
        if (this.profileKey != profileKey || type != CatalogType.LIVE_TV) this else copy(
            itemsByCategory = itemsByCategory.mapValues { (_, items) -> items.map { it.merge() } }
        )
    return copy(
        items = if (selectedType == CatalogType.LIVE_TV) items.map { it.merge() } else items,
        browseCache = browseCache?.merge(),
        browseCachesByType = browseCachesByType.mapValues { (_, cache) -> cache.merge() },
        nowPlaying = nowPlaying?.let { playing ->
            if (playing.catalogType != CatalogType.LIVE_TV) playing else playing.copy(
                media = playing.media.merge(),
                episodeQueue = playing.episodeQueue.map { it.merge() },
                previousEpisode = playing.previousEpisode?.merge(),
                nextEpisode = playing.nextEpisode?.merge()
            )
        },
        recentlyPlayed = recentlyPlayed.map {
            if (it.profileKey == profileKey && it.kind == FavoriteKind.CHANNEL) it.copy(media = it.media.merge()) else it
        }
    )
}
