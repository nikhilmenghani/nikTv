package com.nikhil.niktv.model

import kotlinx.coroutines.CancellationException

/** Session-local cursors. Never assume that three providers' media types share a page number. */
class GlobalSearchPager {
    private data class Cursor(val type: SearchContentType, val category: String, var page: Int = 1,
        val seen: MutableSet<String> = mutableSetOf())
    private val pending = ArrayDeque<Cursor>().apply {
        globalSearchTypes.forEach { add(Cursor(it, "*")) }
    }
    val hasMore: Boolean get() = pending.isNotEmpty()

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
            try {
                val result = search(cursor.type, cursor.category, cursor.page)
                if (cursor.category == "*" && cursor.page == 1 && result.items.isEmpty()) {
                    // Some Stalker portals silently reject wildcard search. Keep category
                    // cursors so subsequent activations can visit every category and page.
                    categories(cursor.type).filter { it != "*" }.distinct().forEach {
                        pending.addLast(Cursor(cursor.type, it))
                    }
                } else {
                    val fresh = result.items.filter { cursor.seen.add(it.id) }
                    discovered = fresh.map { it.copy(searchResultType = cursor.type) }
                    items += discovered
                    if (result.hasMore && fresh.isNotEmpty()) {
                        cursor.page++
                        pending.addLast(cursor)
                    }
                }
            } catch (error: Exception) {
                pending.addLast(cursor) // Retry exactly the failed page, without losing other types.
                if (error is CancellationException) throw error
                failures++
            }
            // Publish each completed page before requesting the next type/category.
            if (discovered.isNotEmpty()) onItems(discovered)
        }
        return Batch(items, failures)
    }
}
