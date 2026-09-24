package com.nikhil.niktv.model

val globalSearchTypes = listOf(SearchContentType.SERIES, SearchContentType.MOVIES, SearchContentType.LIVE_TV)

data class SearchIndexCoverage(val items: Int, val scannedCategories: Int, val cachedAtMillis: Long)

fun MediaItem.searchIdentity(fallback: SearchContentType): String = "${searchResultType ?: fallback}:$id"

/** Reuse downloaded search rows without borrowing another query's page cursor. */
fun List<SearchResultCache>.cachedSearchItems(
    profileKey: String?, type: SearchContentType, categoryId: String
): List<MediaItem> = asSequence()
    .filter { it.profileKey == profileKey && it.type == type }
    .sortedByDescending { it.cachedAtMillis }
    .flatMap { cache ->
        cache.items.asSequence().filter { item ->
            categoryId == "*" || item.portalCategoryId == categoryId ||
                (item.portalCategoryId.isNullOrBlank() && cache.categoryId == categoryId)
        }
    }
    .distinctBy { it.id }
    .toList()

/** Provider IDs are only unique within a media type. Exact titles win within each group. */
fun List<MediaItem>.rankSearchResults(query: String, fallback: SearchContentType): List<MediaItem> =
    distinctBy { it.searchIdentity(fallback) }.sortedWith(
        compareByDescending<MediaItem> { it.title.normalizedSearchQuery() == query.normalizedSearchQuery() }
            .thenByDescending { it.title.titleKeywordScore(query) }
            .thenBy { it.title.lowercase() }
            .thenBy { it.searchIdentity(fallback) }
    )
