package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.WatchedSeries

internal data class EpisodeDiscovery(val updated: WatchedSeries, val added: List<MediaItem>)

internal fun discoverNewEpisodes(watched: WatchedSeries, episodes: List<MediaItem>, now: Long): EpisodeDiscovery {
    // A subscription without a baseline must not announce every existing episode.
    val added = if (watched.checkedAtMillis == 0L && watched.knownEpisodeIds.isEmpty()) emptyList()
        else episodes.distinctBy { it.id }.filterNot { it.id in watched.knownEpisodeIds }
    return EpisodeDiscovery(watched.copy(
        knownEpisodeIds = watched.knownEpisodeIds + episodes.map { it.id },
        newEpisodes = (added + watched.newEpisodes).distinctBy { it.id }
            .sortedWith(compareByDescending<MediaItem> { it.seasonNumber ?: 0 }.thenByDescending { it.episodeNumber ?: 0 }),
        checkedAtMillis = now
    ), added)
}
