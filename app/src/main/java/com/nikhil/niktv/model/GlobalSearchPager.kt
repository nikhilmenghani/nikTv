package com.nikhil.niktv.model

import kotlinx.coroutines.CancellationException

/** Session-local cursors. Never assume that three providers' media types share a page number. */
class GlobalSearchPager(private val exhaustiveCategories: Boolean = false) {
    private data class Cursor(val type: SearchContentType, val category: String, var page: Int = 1,
        val seen: MutableSet<String> = mutableSetOf(), val progress: SearchPageProgress = SearchPageProgress(),
        var retry: Boolean = false, val discoveryOnly: Boolean = false)
    private val pending = ArrayDeque<Cursor>().apply {
        globalSearchTypes.forEach { add(Cursor(it, "*")) }
    }
    val hasMore: Boolean get() = pending.isNotEmpty()
    val hasInitialPages: Boolean get() = pending.any { it.page == 1 && !it.retry }
    private val expanded = mutableSetOf<SearchContentType>()

    data class Batch(val items: List<MediaItem>, val failures: Int)

    /** At most three page requests per activation, plus category discovery for rejected wildcards. */
    suspend fun next(
        search: suspend (SearchContentType, String, Int) -> PortalSearchPage,
        onItems: suspend (List<MediaItem>) -> Unit = {},
        categories: suspend (SearchContentType) -> List<String>
    ): Batch {
        val items = mutableListOf<MediaItem>()
        var failures = 0
        repeat(minOf(3, pending.size)) {
            val cursor = pending.removeFirst()
            var discovered = emptyList<MediaItem>()
            var result: PortalSearchPage? = null
            if (!cursor.discoveryOnly) try {
                result = search(cursor.type, cursor.category, cursor.page)
            } catch (error: Exception) {
                if (error is CancellationException) { pending.addFirst(cursor); throw error }
                cursor.retry = true
                pending.addLast(cursor)
                failures++
            }
            if (cursor.type !in expanded && cursor.category == "*" && cursor.page == 1 &&
                (exhaustiveCategories || cursor.discoveryOnly || result?.items?.isEmpty() == true)) {
                try {
                    categories(cursor.type).filter { it != "*" }.distinct().forEach {
                        pending.addLast(Cursor(cursor.type, it))
                    }
                    expanded += cursor.type
                } catch (error: Exception) {
                    if (error is CancellationException) { pending.addFirst(cursor); throw error }
                    pending.addLast(Cursor(cursor.type, "*", retry = true, discoveryOnly = true))
                    failures++
                }
            }
            result?.let { page ->
                    val fresh = page.items.filter { cursor.seen.add(it.id) }
                    discovered = fresh.map { it.copy(searchResultType = cursor.type) }
                    items += discovered
                    if (cursor.progress.hasNext(page)) {
                        cursor.page++
                        cursor.retry = false
                        pending.addLast(cursor)
                    }
            }
            // Publish each completed page before requesting the next type/category.
            if (discovered.isNotEmpty()) onItems(discovered)
        }
        return Batch(items, failures)
    }
}
