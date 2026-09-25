package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem

/** A guide response changes guide fields only, never stream commands or the active selection. */
internal fun NikTvState.withLiveChannelGuide(profileKey: String, enriched: MediaItem): NikTvState {
    if (session?.profile?.cacheKey() != profileKey) return this
    fun MediaItem.merge(): MediaItem = if (id != enriched.id ||
        (liveProgramme == enriched.liveProgramme && liveSchedule == enriched.liveSchedule)) this else copy(
        liveProgramme = enriched.liveProgramme,
        liveSchedule = enriched.liveSchedule
    )
    fun List<MediaItem>.mergeGuide(): List<MediaItem> {
        var changed: MutableList<MediaItem>? = null
        forEachIndexed { index, item ->
            val merged = item.merge()
            if (merged !== item) {
                if (changed == null) changed = toMutableList()
                changed!![index] = merged
            }
        }
        return changed ?: this
    }
    fun com.nikhil.niktv.model.BrowseCatalogCache.merge(): com.nikhil.niktv.model.BrowseCatalogCache {
        if (this.profileKey != profileKey || type != CatalogType.LIVE_TV) return this
        var changed: MutableMap<String, List<MediaItem>>? = null
        itemsByCategory.forEach { (key, items) ->
            val merged = items.mergeGuide()
            if (merged !== items) {
                if (changed == null) changed = itemsByCategory.toMutableMap()
                changed!![key] = merged
            }
        }
        return changed?.let { copy(itemsByCategory = it) } ?: this
    }
    return copy(
        items = if (selectedType == CatalogType.LIVE_TV) items.mergeGuide() else items,
        browseCache = browseCache?.merge(),
        browseCachesByType = browseCachesByType.mapValues { (_, cache) -> cache.merge() },
        nowPlaying = nowPlaying?.let { playing ->
            if (playing.catalogType != CatalogType.LIVE_TV) playing else playing.copy(
                media = playing.media.merge(),
                episodeQueue = playing.episodeQueue.mergeGuide(),
                previousEpisode = playing.previousEpisode?.merge(),
                nextEpisode = playing.nextEpisode?.merge()
            )
        },
        recentlyPlayed = recentlyPlayed.map {
            if (it.profileKey == profileKey && it.kind == FavoriteKind.CHANNEL) it.copy(media = it.media.merge()) else it
        }
    )
}
