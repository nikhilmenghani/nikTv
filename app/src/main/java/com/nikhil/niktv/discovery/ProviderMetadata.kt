package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.providerDisplayTitle
import com.nikhil.niktv.data.providerTmdbTitle
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val key = providerPresentationCacheKey(item, type)
    // Only public metadata is stored. Provider IDs, credentials and commands never enter this cache.
    return cache.load(key, DiscoveryCache.FEED_TTL, ListSerializer(DiscoveryCard.serializer())) {
        listOfNotNull(lookup())
    }.firstOrNull()
}

private fun providerPresentationCacheKey(item: MediaItem, type: CatalogType): String {
    val title = if (type == CatalogType.SERIES) providerSeriesTmdbTitle(item.title) else providerTmdbTitle(item.title)
    return "provider-presentation-v1:${type.name}:${item.externalTmdbId}:$title"
}

/** Read-only presentation: misses and expired metadata never initiate matching or a refresh. */
internal suspend fun readProviderHeroMetadata(cache: DiscoveryCache, item: MediaItem, type: CatalogType): DiscoveryCard? =
    withContext(Dispatchers.IO) {
        val json = Json { ignoreUnknownKeys = true }
        val known = item.externalTmdbId?.takeIf { it > 0 }
        val matched = cache.read(providerPresentationCacheKey(item, type))?.let { entry ->
            runCatching { json.decodeFromString(ListSerializer(DiscoveryCard.serializer()), entry.payload).firstOrNull() }.getOrNull()
        }
        val seed = matched?.takeIf { it.series == (type == CatalogType.SERIES) && it.id > 0 && (known == null || it.id == known) }
            ?: known?.let { DiscoveryCard(it, type == CatalogType.SERIES, "") } ?: return@withContext null
        cache.read("detail-v4:${seed.key}")?.let { entry ->
            runCatching { json.decodeFromString(DiscoveryDetails.serializer(), entry.payload).card }.getOrNull()
        }?.takeIf { it.key == seed.key } ?: seed.takeIf { it.title.isNotBlank() }
    }
