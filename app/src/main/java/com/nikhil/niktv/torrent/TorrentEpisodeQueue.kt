package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import java.time.LocalDate
import java.time.ZoneOffset

/** The queue carries stable metadata identity, never a short-lived playback URL. */
internal data class TorrentEpisodeTarget(val card: DiscoveryCard, val episode: TmdbEpisode)

/**
 * Resolves one successor without walking every intervening episode or assuming
 * contiguous numbering. All I/O is supplied by the caller so background prefetch
 * can be cancelled independently of the currently playing torrent.
 */
internal suspend fun findNextTorrentEpisode(
    card: DiscoveryCard,
    currentEpisode: TmdbEpisode,
    currentSeasons: List<Int>,
    loadedEpisodes: List<TmdbEpisode>,
    loadSeasons: suspend () -> List<Int>,
    loadEpisodes: suspend (Int) -> List<TmdbEpisode>,
    today: String = LocalDate.now(ZoneOffset.UTC).toString()
): TorrentEpisodeTarget? {
    if (!card.series || currentEpisode.seasonNumber <= 0 || currentEpisode.episodeNumber <= 0) return null
    fun available(episodes: List<TmdbEpisode>, season: Int) = episodes.asSequence()
        .filter { it.seasonNumber == season && it.episodeNumber > 0 }
        .filter { it.airDate.isNullOrBlank() || it.airDate <= today }
        .distinctBy(TmdbEpisode::episodeNumber).sortedBy(TmdbEpisode::episodeNumber).toList()

    val current = loadedEpisodes.takeIf { rows -> rows.any { it.seasonNumber == currentEpisode.seasonNumber } }
        ?: loadEpisodes(currentEpisode.seasonNumber)
    available(current, currentEpisode.seasonNumber).firstOrNull { it.episodeNumber > currentEpisode.episodeNumber }
        ?.let { return TorrentEpisodeTarget(card, it) }

    val seasons = currentSeasons.ifEmpty { loadSeasons() }.asSequence()
        .filter { it > currentEpisode.seasonNumber }.distinct().sorted().toList()
    for (season in seasons) {
        val episodes = loadedEpisodes.takeIf { rows -> rows.any { it.seasonNumber == season } } ?: loadEpisodes(season)
        available(episodes, season).firstOrNull()?.let { return TorrentEpisodeTarget(card, it) }
    }
    return null
}

/** Wait for the complete source search rather than racing whichever site responds first. */
internal fun selectTorrentPrefetchSource(snapshot: TorrentSearchSnapshot): TorrentSource? =
    if (!snapshot.finished) null else mergeTorrentSources(snapshot.results).firstOrNull()

/** A season pack must identify exactly one requested episode; never guess its largest file. */
internal fun selectTorrentEpisodeFile(files: List<TorrentVideoFile>, episode: TmdbEpisode): TorrentVideoFile? =
    files.asSequence().filter { it.index >= 0 && it.sizeBytes > 0 }
        .filterNot { Regex("(?i)(?<![a-z0-9])(?:sample|trailer|preview)(?![a-z0-9])").containsMatchIn(it.name.substringAfterLast('/').substringAfterLast('\\')) }
        .filter { torrentEpisodeFileMatches(it.name, episode.seasonNumber, episode.episodeNumber) }
        .distinctBy(TorrentVideoFile::index).toList().singleOrNull()
