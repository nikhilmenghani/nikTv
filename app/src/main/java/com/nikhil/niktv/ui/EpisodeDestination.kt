package com.nikhil.niktv.ui

import com.nikhil.niktv.model.EpisodeSeasonResult
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class EpisodeBrowseKey(val profile: String, val series: String, val season: Int?)

/** A partial head refresh is not an authoritative replacement of cached later pages. */
internal fun EpisodeSeasonResult.retainLoadedEpisodes(
    previous: EpisodeSeasonCache?, profile: String, series: String, previousEpisodes: List<MediaItem>
): EpisodeSeasonResult {
    if (!hasMore || previous == null || previous.profileKey != profile || previous.seriesId != series ||
        previous.season != selectedSeason || previousEpisodes.isEmpty()) return this
    return copy(episodes = (episodes + previousEpisodes).distinctBy { it.id },
        availableSeasons = (availableSeasons + previous.availableSeasons).distinct().sorted(),
        page = maxOf(page, previous.page), hasMore = previous.hasMore)
}

data class EpisodeDestinationState(
    val profile: String,
    val series: String,
    val season: Int?,
    val request: Long,
    val number: Int?, // null means the true earliest episode, after all pages are known
    val loaded: Int,
    val page: Int,
    val running: Boolean = true,
    val focusId: String? = null,
    val message: String? = null,
)

internal fun MediaItem.destinationEpisodeNumber(): Int? = episodeNumber ?: title.episodeNumberFromTitle()

/** Sequential metadata requests only. No playback, TMDB enrichment, or arbitrary page/episode cap. */
internal suspend fun loadEpisodeDestination(
    initial: EpisodeSeasonResult,
    number: Int?,
    nextPage: suspend (EpisodeSeasonResult) -> EpisodeSeasonResult,
    onPage: suspend (EpisodeSeasonResult) -> Unit,
): Pair<EpisodeSeasonResult, MediaItem?> {
    var loaded = initial.copy(episodes = initial.episodes.distinctBy { it.id })
    fun target() = if (number == null) {
        loaded.episodes.minWithOrNull(episodeComparator(false))
    } else loaded.episodes.firstOrNull { it.destinationEpisodeNumber() == number }
    while (loaded.hasMore && (number == null || target() == null)) {
        currentCoroutineContext().ensureActive()
        val next = nextPage(loaded)
        currentCoroutineContext().ensureActive()
        check(next.selectedSeason == loaded.selectedSeason) { "The provider returned a different season. Please select the season again." }
        check(next.page > loaded.page) { "The provider did not advance to the next episode page. Please retry." }
        val combined = (loaded.episodes + next.episodes).distinctBy { it.id }
        // A repeated/empty page is not proof that Episode 1 exists, or that the season is complete.
        check(combined.size > loaded.episodes.size || !next.hasMore) { "The provider repeated an episode page. Please retry." }
        loaded = next.copy(episodes = combined)
        onPage(loaded)
    }
    return loaded to target()
}
