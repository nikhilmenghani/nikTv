package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.providerDisplayTitle
import com.nikhil.niktv.data.providerTmdbTitle
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.model.*
import kotlinx.serialization.builtins.ListSerializer

/** Playback selection is kept separately from public presentation metadata. */
data class ProviderMovieDetails(val profile: String, val item: MediaItem,
    val queue: List<MediaItem>, val filter: PlaybackQueueFilter? = null)

internal fun providerDetailFallback(item: MediaItem, type: CatalogType) = DiscoveryCard(
    item.externalTmdbId?.takeIf { it > 0 } ?: 0, type == CatalogType.SERIES, providerDisplayTitle(item.title, type),
    item.description, item.logo, adult = item.isAdult)

/** Trusted identity hint only; provider artwork URLs can contain private credentials. */
internal fun providerTmdbSeed(item: MediaItem, type: CatalogType): DiscoveryCard? =
    item.externalTmdbId?.takeIf { it > 0 }?.let { DiscoveryCard(it, type == CatalogType.SERIES, "") }

internal fun providerCategoryQueueFilter(category: Category, items: List<MediaItem>, index: Int, query: String): PlaybackQueueFilter =
    PlaybackQueueFilter(query.trim(), pinnedIds = if (query.isBlank()) null else emptyList(),
        selectedOrdinal = items.take(index).count { it.id == items[index].id }, contextId = category.id)

internal suspend fun cachedProviderMetadata(cache: DiscoveryCache, item: MediaItem, type: CatalogType,
    lookup: suspend () -> DiscoveryCard?): DiscoveryCard? {
    require(type == CatalogType.MOVIES || type == CatalogType.SERIES)
    val title = if (type == CatalogType.SERIES) providerSeriesTmdbTitle(item.title) else providerTmdbTitle(item.title)
    val key = "provider-presentation-v1:${type.name}:${item.externalTmdbId}:$title"
    // Only public metadata is stored. Provider IDs, credentials and commands never enter this cache.
    return cache.load(key, DiscoveryCache.FEED_TTL, ListSerializer(DiscoveryCard.serializer())) {
        listOfNotNull(lookup())
    }.firstOrNull()
}
