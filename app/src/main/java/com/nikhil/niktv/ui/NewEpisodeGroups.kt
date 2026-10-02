package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.WatchedSeries

internal data class NewEpisodeGroup(val watched: WatchedSeries, val episodes: List<MediaItem>) {
    val key: String get() = watched.key
    val latest: MediaItem get() = episodes.last()
}

/** Keep the full backlog locally; only the dashboard preview is capped. */
internal fun newEpisodeGroups(watched: List<WatchedSeries>, profileKey: String): List<NewEpisodeGroup> =
    watched.filter { it.profileKey == profileKey }.groupBy { it.key }.values.mapNotNull { series ->
        val episodes = series.flatMap { it.newEpisodes }.distinctBy { it.id }
            .sortedWith(episodeComparator(descending = false))
        episodes.takeIf { it.isNotEmpty() }?.let { NewEpisodeGroup(series.first(), it) }
    }
