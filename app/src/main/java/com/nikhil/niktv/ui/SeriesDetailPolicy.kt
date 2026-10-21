package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.EpisodeSeasonCache

/** A page knows its own season, not the entire series season index. */
internal fun mergeSeriesSeasons(known: List<Int>, page: List<Int>, selected: Int?): List<Int> =
    (known + page + listOfNotNull(selected)).filter { it >= 0 }.distinct().sorted()

internal fun needsSeriesSeasonIndexRepair(cache: EpisodeSeasonCache): Boolean =
    !cache.seasonIndexValidated && cache.page > 1 && cache.availableSeasons.size <= 1

internal fun formatEpisodeAddedTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(millis))

/** Playback's v2 identity contains season/episode and title, not the provider episode ID. */
internal fun seriesEpisodeProgress(records: List<PlaybackProgress>, profile: String,
    series: MediaItem, episode: MediaItem): PlaybackProgress? {
    val title = episode.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
    val prefix = "progress-v2|$profile|SERIES|series:${series.id}|s:${episode.seasonNumber ?: -1}|e:${episode.episodeNumber ?: -1}|$title|"
    return records.filter { it.key.startsWith(prefix.take(240)) }.maxByOrNull { it.updatedAtMillis }
        ?: records.firstOrNull { it.key == "SERIES:${episode.id}" }
}

internal fun episodeRemainingLabel(progress: PlaybackProgress?): String? {
    if (progress == null || progress.durationMillis <= 0 || progress.positionMillis <= 0) return null
    if (progress.positionMillis >= progress.durationMillis * .9) return "Watched"
    val minutes = ((progress.durationMillis - progress.positionMillis + 59_999) / 60_000).coerceAtLeast(1)
    return "$minutes min left"
}

internal enum class SeriesDetailPanel { SEARCH, CAST, RELATED }

internal const val SERIES_INLINE_DETAILS_INDEX = 2
internal const val SERIES_EPISODE_START_INDEX = 3

internal enum class SeriesDetailFocus { TOP, PLAY, INFO, SEASONS, SORT, TOOLS, EPISODES }
internal fun adjacentSeriesDetailFocus(current: SeriesDetailFocus, delta: Int, hasSeasons: Boolean): SeriesDetailFocus? {
    val path = SeriesDetailFocus.entries.filter { it != SeriesDetailFocus.SEASONS || hasSeasons }
    val index = path.indexOf(current)
    return if (index < 0) null else path.getOrNull(index + delta)
}
internal fun seriesDetailFocusSlot(target: SeriesDetailFocus): Int = when (target) {
    SeriesDetailFocus.TOP, SeriesDetailFocus.PLAY, SeriesDetailFocus.INFO -> 0
    SeriesDetailFocus.SEASONS, SeriesDetailFocus.SORT, SeriesDetailFocus.TOOLS -> 1
    SeriesDetailFocus.EPISODES -> SERIES_EPISODE_START_INDEX
}

internal fun seriesEpisodeVerticalIndex(episodeIndex: Int, horizontal: Boolean): Int =
    SERIES_EPISODE_START_INDEX + if (horizontal) 0 else episodeIndex

internal fun shouldLoadMoreLandscapeEpisodes(count: Int, lastVisible: Int, hasMore: Boolean, loading: Boolean,
    query: String, interacted: Boolean): Boolean = count > 0 && hasMore && !loading && query.isBlank() && interacted && lastVisible >= count - 4

/** The inline details slot keeps its index when collapsed. Do not page while filtering or browsing extras. */
internal fun shouldLoadMoreSeriesEpisodes(count: Int, lastVisible: Int, hasMore: Boolean,
    loading: Boolean, query: String, userScrolled: Boolean): Boolean =
    count > 0 && hasMore && !loading && query.isBlank() && userScrolled &&
        lastVisible in (count + SERIES_EPISODE_START_INDEX - 1 - 4).coerceAtLeast(SERIES_EPISODE_START_INDEX)..(count + SERIES_EPISODE_START_INDEX)

internal fun seriesEpisodeCountLabel(count: Int, hasMore: Boolean, filtered: Boolean): String = when {
    filtered -> "$count ${if (count == 1) "match" else "matches"} in loaded episodes"
    hasMore -> "$count episodes loaded"
    else -> "$count ${if (count == 1) "episode" else "episodes"}"
}
