package com.nikhil.niktv.model

/** Follow raw provider pages even when every row was filtered out of the visible results. */
internal class SearchPageProgress {
    private val pages = mutableSetOf<Set<String>>()
    fun hasNext(page: PortalSearchPage): Boolean = page.providerItemIds.toSet().let { ids ->
        val fresh = ids.isNotEmpty() && pages.add(ids)
        page.hasMore && fresh
    }
}
