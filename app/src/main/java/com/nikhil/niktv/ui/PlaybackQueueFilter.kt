package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import com.nikhil.niktv.model.CategoryOrganization

internal fun PlaybackQueueFilter.includes(item: MediaItem, type: CatalogType): Boolean {
    if (type == CatalogType.LIVE_TV && item.id in excludedIds) return false
    if (fixedIds != null && item.id !in fixedIds) return false
    if (sourceIds != null && item.portalCategoryId !in sourceIds) return false
    if (query.isBlank()) return true
    return if (type == CatalogType.LIVE_TV) item.matchesLiveChannelQuery(query, System.currentTimeMillis())
        else CategoryOrganization.titleMatcher(query)(item.title)
}
