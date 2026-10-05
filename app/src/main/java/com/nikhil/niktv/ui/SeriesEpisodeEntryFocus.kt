package com.nikhil.niktv.ui

internal sealed interface SeriesEpisodeEntryFocus {
    data object Loading : SeriesEpisodeEntryFocus
    data object Back : SeriesEpisodeEntryFocus
    data class Episode(val index: Int, val id: String) : SeriesEpisodeEntryFocus
}

/** Select once on entry, or restore the played episode after asynchronous loading. */
internal fun seriesEpisodeEntryFocusTarget(
    episodeIds: List<String>,
    loading: Boolean,
    returningEpisodeId: String?,
    initialEntryPending: Boolean
): SeriesEpisodeEntryFocus? {
    if (returningEpisodeId == null && !initialEntryPending) return null
    val returningIndex = returningEpisodeId?.let(episodeIds::indexOf) ?: -1
    if (returningIndex >= 0) {
        return SeriesEpisodeEntryFocus.Episode(returningIndex, episodeIds[returningIndex])
    }
    // An absent returning episode may still be in the provider response being loaded.
    if (loading && (episodeIds.isEmpty() || returningEpisodeId != null)) {
        return SeriesEpisodeEntryFocus.Loading
    }
    return episodeIds.firstOrNull()?.let { SeriesEpisodeEntryFocus.Episode(0, it) }
        ?: SeriesEpisodeEntryFocus.Back
}
