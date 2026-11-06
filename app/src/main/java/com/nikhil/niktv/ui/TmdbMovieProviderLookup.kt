package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.rankTmdbMovieMatches
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalSearchPage
import com.nikhil.niktv.model.matchesTitleKeywords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class MovieMatchLookup(
    val matches: List<MediaItem>,
    val rawCandidates: List<MediaItem>,
    val pendingRequests: List<MovieMatchRequest>,
    val reviewCandidates: List<MediaItem> = emptyList()
) {
    val selectionCandidates: List<MediaItem> get() = matches.ifEmpty { reviewCandidates }
    val requiresConfirmation: Boolean get() = matches.isEmpty() && reviewCandidates.isNotEmpty()
}

/** Provider labels/year differences can defeat TMDB scoring without making a title search empty. */
internal fun stalkerMovieReviewCandidates(movie: TmdbMovie, candidates: List<MediaItem>): List<MediaItem> =
    candidates.filter { item ->
        (item.externalTmdbId == null || item.externalTmdbId == movie.id) &&
            (item.externalTmdbId == movie.id || listOf(movie.title, movie.originalTitle)
                .filter(String::isNotBlank).any { item.title.matchesTitleKeywords(it) })
    }.distinctBy { it.id }

/** A miss is final only after title variants, wildcard pages and concrete category pages finish. */
internal suspend fun lookupProviderMovie(
    movie: TmdbMovie,
    localCandidates: List<MediaItem>,
    categories: suspend () -> List<String>,
    search: suspend (MovieMatchRequest) -> PortalSearchPage
): MovieMatchLookup {
    val queries = listOf(movie.title, movie.originalTitle).map(String::trim)
        .filter(String::isNotBlank).distinct()
    val candidates = localCandidates.filter { it.externalTmdbId == null || it.externalTmdbId == movie.id }.toMutableList()
    val reviewOnly = mutableListOf<MediaItem>()
    suspend fun ranked() = withContext(Dispatchers.Default) {
        rankTmdbMovieMatches(movie, candidates.distinctBy { it.id })
    }
    suspend fun collect(pages: TmdbMovieMatchPaging, initialQueries: Int = 0): MovieMatchLookup {
        var firstPagesLeft = initialQueries
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = pages.next() ?: break
            val result = search(request) // Errors and cancellation must never become "not found".
            currentCoroutineContext().ensureActive()
            candidates += result.items.filter { it.externalTmdbId == null || it.externalTmdbId == movie.id }
            reviewOnly += result.movieReviewCandidates
            pages.completed(request, result.providerItemIds, result.hasMore)
            if (request.page == 1 && firstPagesLeft > 0) firstPagesLeft--
            val matches = ranked()
            // Keep the existing quick multi-version preview. If it is empty, keep looking.
            if (matches.size >= 5 || firstPagesLeft == 0 && matches.isNotEmpty()) {
                return MovieMatchLookup(matches, candidates.distinctBy { it.id }, pages.remaining())
            }
        }
        return MovieMatchLookup(ranked(), candidates.distinctBy { it.id }, emptyList())
    }
    val wildcard = collect(TmdbMovieMatchPaging(queries.map { MovieMatchRequest(it) }), queries.size)
    if (wildcard.matches.isNotEmpty()) return wildcard

    // Some Stalker portals silently return no rows for the synthetic wildcard. Search uses
    // concrete categories in that case; Play and explicit match refresh must do the same.
    val requests = categories().filter { it != "*" }.distinct().flatMap { category ->
        queries.map { MovieMatchRequest(it, categoryId = category) }
    }
    val scoped = collect(TmdbMovieMatchPaging(requests))
    if (scoped.matches.isNotEmpty()) return scoped
    return scoped.copy(reviewCandidates = withContext(Dispatchers.Default) {
        stalkerMovieReviewCandidates(movie, candidates + reviewOnly)
    })
}
