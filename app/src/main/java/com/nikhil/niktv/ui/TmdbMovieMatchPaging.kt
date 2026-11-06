package com.nikhil.niktv.ui

internal data class MovieMatchRequest(val query: String, val page: Int = 1, val categoryId: String = "*")

/** Independent category/query cursors; repeated provider pages cannot create a request loop. */
internal class TmdbMovieMatchPaging(initial: List<MovieMatchRequest>) {
    private val pending = ArrayDeque(initial.distinct())
    private val seen = mutableMapOf<Pair<String, String>, MutableSet<Set<String>>>()
    fun next(): MovieMatchRequest? = pending.removeFirstOrNull()
    fun remaining(): List<MovieMatchRequest> = pending.toList()
    fun completed(request: MovieMatchRequest, ids: List<String>, hasMore: Boolean) {
        val scope = request.query to request.categoryId
        if (hasMore && ids.isNotEmpty() && seen.getOrPut(scope) { mutableSetOf() }.add(ids.toSet())) {
            pending.addLast(request.copy(page = request.page + 1))
        }
    }
}
