package com.nikhil.niktv.model

val globalSearchTypes = listOf(SearchContentType.SERIES, SearchContentType.MOVIES, SearchContentType.LIVE_TV)

data class SearchIndexCoverage(val items: Int, val scannedCategories: Int, val cachedAtMillis: Long)

fun MediaItem.searchIdentity(fallback: SearchContentType): String = "${searchResultType ?: fallback}:$id"

/** Provider IDs are only unique within a media type. Exact titles win within each group. */
fun List<MediaItem>.rankSearchResults(query: String, fallback: SearchContentType): List<MediaItem> =
    distinctBy { it.searchIdentity(fallback) }.sortedWith(
        compareByDescending<MediaItem> { it.title.normalizedSearchQuery() == query.normalizedSearchQuery() }
            .thenByDescending { it.title.titleKeywordScore(query) }
            .thenBy { it.title.lowercase() }
            .thenBy { it.searchIdentity(fallback) }
    )
