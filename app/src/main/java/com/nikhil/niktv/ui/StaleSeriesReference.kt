package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.data.tmdbLookupTitle

/** Only a unique exact title can repair an empty provider reference. Never guess among versions. */
internal fun staleSeriesReplacement(original: MediaItem, candidates: List<MediaItem>): MediaItem? {
    val wanted = providerSeriesTmdbTitle(original.title).tmdbLookupTitle()
    if (wanted.isBlank()) return null
    val year = Regex("\\b(?:19|20)\\d{2}\\b")
    val requestedYear = year.find(providerSeriesTmdbTitle(original.title))?.value
    return candidates.filter {
        (it.id != original.id || it.command != original.command) && !it.command.isNullOrBlank() &&
            providerSeriesTmdbTitle(it.title).tmdbLookupTitle() == wanted &&
            (requestedYear == null || year.find(providerSeriesTmdbTitle(it.title))?.value == requestedYear) &&
            (original.externalTmdbId == null || it.externalTmdbId == null || it.externalTmdbId == original.externalTmdbId)
    }.distinctBy { it.id }.singleOrNull()
}
