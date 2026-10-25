package com.nikhil.niktv.ui

/** No page-count cap. Stop a broken provider loop when a query repeats a page. */
internal class TmdbMovieMatchPaging(initial: List<Pair<String, Int>>) {
    private val pending = ArrayDeque(initial.distinct())
    private val seen = mutableMapOf<String, MutableSet<List<String>>>()
    fun next(): Pair<String, Int>? = pending.removeFirstOrNull()
    fun completed(query: String, page: Int, ids: List<String>, hasMore: Boolean) {
        if (hasMore && ids.isNotEmpty() && seen.getOrPut(query) { mutableSetOf() }.add(ids.toList())) {
            pending.addLast(query to page + 1)
        }
    }
}
