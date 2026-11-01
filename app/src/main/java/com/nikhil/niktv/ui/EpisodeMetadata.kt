package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.EpisodeSeasonCache

data class EpisodeTmdbRefresh(val profile: String, val series: String, val episode: String,
    val running: Boolean = true, val message: String? = null)

internal fun hasDescriptiveEpisodeTitle(title: String?, seriesTitle: String = ""): Boolean {
    var value = title.orEmpty().trim()
    if (seriesTitle.isNotBlank()) value = value.replaceFirst(Regex("^${Regex.escape(seriesTitle)}[ .:|–—-]*", RegexOption.IGNORE_CASE), "")
    value = value.replaceFirst(Regex("(?i)^(?:S\\d+\\s*E\\d+|(?:episode|ep|e)\\s*#?\\s*\\d+)[ .:|–—-]*"), "")
        .replaceFirst(Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}[ .:|–—-]*"), "")
    return value.isNotBlank()
}

internal fun mergeTmdbEpisodePresentation(raw: MediaItem, existing: MediaItem, metadata: TmdbEpisode,
    seriesTitle: String, tmdbId: Int): MediaItem = raw.copy(
    title = when {
        hasDescriptiveEpisodeTitle(metadata.name) -> metadata.name!!
        hasDescriptiveEpisodeTitle(existing.title, seriesTitle) -> existing.title
        hasDescriptiveEpisodeTitle(raw.title, seriesTitle) -> raw.title
        else -> metadata.name ?: existing.title
    },
    logo = metadata.stillUrl ?: existing.logo ?: raw.logo,
    description = metadata.overview ?: existing.description ?: raw.description,
    episodeAirDate = metadata.airDate ?: existing.episodeAirDate ?: raw.episodeAirDate,
    externalTmdbId = tmdbId)

/** Repair presentation left by older builds locally; do not invalidate provider pages or metadata TTLs. */
internal fun repairEpisodePresentationCache(cache: EpisodeSeasonCache, seriesTitle: String): EpisodeSeasonCache {
    if (cache.iptvEpisodes.isEmpty()) return cache
    val rawById = cache.iptvEpisodes.associateBy { it.id }
    val repaired = cache.episodes.map { existing ->
        val raw = rawById[existing.id] ?: return@map existing
        if (!hasDescriptiveEpisodeTitle(existing.title, seriesTitle) && hasDescriptiveEpisodeTitle(raw.title, seriesTitle))
            existing.copy(title = raw.title)
        else existing
    }
    return if (repaired == cache.episodes) cache else cache.copy(episodes = repaired)
}

internal fun episodeTmdbRefreshMessage(raw: MediaItem, before: MediaItem, after: MediaItem,
    metadata: TmdbEpisode, seriesTitle: String): String {
    val fields = listOfNotNull("title".takeIf { hasDescriptiveEpisodeTitle(metadata.name) },
        "description".takeIf { !metadata.overview.isNullOrBlank() }, "artwork".takeIf { !metadata.stillUrl.isNullOrBlank() })
    if (fields.isNotEmpty()) return "Updated TMDB ${fields.joinToString(", ")} for this episode."
    val source = when {
        hasDescriptiveEpisodeTitle(raw.title, seriesTitle) && after.title == raw.title ->
            if (before.title != after.title) "Restored the episode title from your IPTV provider. "
            else "Kept the episode title from your IPTV provider. "
        hasDescriptiveEpisodeTitle(after.title, seriesTitle) -> "Kept the previously cached episode title. "
        else -> ""
    }
    return source + "TMDB has no descriptive title, synopsis or artwork for this episode; it returned only a generic name and date."
}

/** Never guess an offset between two competing multi-season numbering schemes. */
internal fun tmdbSeasonForProviderSeason(providerSeason: Int?, tmdbSeasons: List<Int>): Int? {
    if (providerSeason != null && providerSeason in tmdbSeasons) return providerSeason
    return tmdbSeasons.filter { it > 0 }.distinct().singleOrNull()
}

/** Build once off main for long-running serials, rather than scanning thousands of titles per tile. */
internal class TmdbEpisodeMetadataIndex(episodes: List<TmdbEpisode>) {
    private val dates = episodes.filter { !it.airDate.isNullOrBlank() }.groupBy { it.airDate }
    private val titles = episodes.filter { !it.name.isNullOrBlank() }.groupBy { it.name!!.metadataTitleKey() }
    private val numbers = episodes.groupBy { it.episodeNumber }
    private val specials = episodes.filter { it.name?.specialMetadataKey() != null }.groupBy { it.name!!.specialMetadataKey() }

    fun select(episode: MediaItem): TmdbEpisode? {
        episode.title.specialMetadataKey()?.let { return specials[it]?.singleOrNull() }
        episode.episodeAirDate?.let { dates[it]?.singleOrNull()?.let { match -> return match } }
        val title = episode.title.specificEpisodeTitleKey()
        if (title.isNotBlank()) titles[title]?.singleOrNull()?.let { return it }
        return numbers[episode.episodeNumber]?.singleOrNull()
    }
}

/** Chooses identity evidence before falling back to an episode ordinal. */
internal fun selectTmdbEpisodeMetadata(
    episode: MediaItem,
    seasonEpisodes: List<TmdbEpisode>,
    specialEpisodes: List<TmdbEpisode> = emptyList()
): TmdbEpisode? {
    val specialKey = episode.title.specialMetadataKey()
    if (specialKey != null) {
        return specialEpisodes.singleOrNull { it.name?.specialMetadataKey() == specialKey }
    }

    episode.episodeAirDate?.let { airDate ->
        seasonEpisodes.singleOrNull { it.airDate == airDate }?.let { return it }
    }

    val titleKey = episode.title.specificEpisodeTitleKey()
    if (titleKey.isNotBlank()) {
        seasonEpisodes.singleOrNull { it.name?.metadataTitleKey() == titleKey }?.let { return it }
    }

    val number = episode.episodeNumber ?: return null
    return seasonEpisodes.singleOrNull { it.episodeNumber == number }
}

private fun String.specificEpisodeTitleKey(): String = trim()
    .replaceFirst(
        Regex("^\\s*(?:S\\d+\\s*[:._-]?\\s*E(?:P(?:ISODE)?)?\\s*\\d+|(?:EPISODE|EP|E)\\s*#?\\s*\\d+)\\s*[. :|\\-–—]*\\s*", RegexOption.IGNORE_CASE),
        ""
    )
    .trim(' ', '.', ':', '-', '–', '—', '|')
    .replaceFirst(Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\s*[. :|\\-–—]*\\s*"), "")
    .metadataTitleKey()

private fun String.specialMetadataKey(): String? {
    val match = Regex("(?i)\\b(bonus|discarded|deleted(?: moments)?|special)[ ._:#-]*(?:ep(?:isode)?)?[ ._:#-]*(\\d+)\\b")
        .find(this) ?: return null
    return "${match.groupValues[1].lowercase().replace(" ", "-")}:${match.groupValues[2].toIntOrNull() ?: return null}"
}

private fun String.metadataTitleKey(): String = lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
