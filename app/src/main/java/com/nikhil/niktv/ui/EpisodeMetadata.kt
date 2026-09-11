package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.model.MediaItem

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
