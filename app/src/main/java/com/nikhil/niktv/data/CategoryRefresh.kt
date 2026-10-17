package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalCatalogPage

/** Manual refresh replaces the loaded window; background refresh keeps later pages. */
internal fun refreshedCategoryWindow(
    cachedItems: List<MediaItem>,
    cachedPage: Int,
    cachedHasMore: Boolean?,
    fresh: PortalCatalogPage,
    userRequested: Boolean
): PortalCatalogPage {
    if (userRequested || cachedPage <= fresh.page) return fresh
    return fresh.copy(
        items = (fresh.items + cachedItems).distinctBy { it.id },
        page = cachedPage,
        hasMore = fresh.totalPages?.let { cachedPage < it } ?: (cachedHasMore ?: fresh.hasMore)
    )
}
