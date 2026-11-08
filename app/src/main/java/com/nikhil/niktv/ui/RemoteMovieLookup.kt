package com.nikhil.niktv.ui

import com.nikhil.niktv.data.RemoteMovie
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalSearchPage

/** Provider labels are useful for display but often fail the portal's literal title search. */
internal fun remoteMovieQueries(title: String): List<String> {
    val withoutCategory = title.substringBefore(" - ").trim()
    val withoutLabels = withoutCategory.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("\\s+"), " ").trim()
    return listOf(withoutLabels, withoutCategory, title.trim()).filter(String::isNotBlank).distinct()
}

/** Broaden only the query; never silently substitute another language, quality or provider ID. */
internal suspend fun lookupRemoteMovie(movie: RemoteMovie, local: List<MediaItem>,
    categories: suspend () -> List<String>,
    search: suspend (String, String, Int) -> PortalSearchPage): MediaItem? {
    local.firstOrNull { it.id == movie.id }?.let { return it }
    val queries = remoteMovieQueries(movie.title)
    val rank: (List<MediaItem>) -> List<MediaItem> = { rows -> rows.filter { it.id == movie.id } }
    val preferred = movie.categoryId?.takeUnless { it == "*" }
    if (preferred != null) {
        // Reuse independent page cursors, but direct this first pass to the known category.
        lookupProviderTitle(emptyList(), queries, { emptyList() }, rank) { query, _, page ->
            search(query, preferred, page)
        }.firstOrNull()?.let { return it }
    }
    return lookupProviderTitle(emptyList(), queries,
        { categories().filterNot { it == preferred } }, rank, search).firstOrNull()
}
