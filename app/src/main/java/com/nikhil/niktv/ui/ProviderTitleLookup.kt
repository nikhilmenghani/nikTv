package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalSearchPage
import com.nikhil.niktv.model.SearchPageProgress
import kotlinx.coroutines.*

/** Title aliases and categories have independent page cursors; rejected rows still advance them. */
internal suspend fun <T> lookupProviderTitle(
    local: List<MediaItem>, queries: List<String>, categories: suspend () -> List<String>,
    rank: (List<MediaItem>) -> List<T>,
    search: suspend (query: String, category: String, page: Int) -> PortalSearchPage
): List<T> {
    val candidates = local.toMutableList()
    suspend fun ranked() = withContext(Dispatchers.Default) { rank(candidates.distinctBy { it.id }) }
    ranked().takeIf { it.isNotEmpty() }?.let { return it }
    val titles = queries.map(String::trim).filter(String::isNotBlank).distinct()
    suspend fun collect(scopes: List<String>): List<T> {
        val pending = ArrayDeque(scopes.flatMap { category -> titles.map { Triple(it, category, 1) } })
        val progress = mutableMapOf<Pair<String, String>, SearchPageProgress>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (query, category, page) = pending.removeFirst()
            val result = search(query, category, page)
            currentCoroutineContext().ensureActive()
            candidates += result.items
            ranked().takeIf { it.isNotEmpty() }?.let { return it }
            if (progress.getOrPut(query to category) { SearchPageProgress() }.hasNext(result))
                pending.addLast(Triple(query, category, page + 1))
        }
        return emptyList()
    }
    collect(listOf("*")).takeIf { it.isNotEmpty() }?.let { return it }
    return collect(categories().filter { it != "*" }.distinct())
}
