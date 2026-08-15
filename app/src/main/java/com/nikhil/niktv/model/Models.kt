package com.nikhil.niktv.model

import kotlinx.serialization.Serializable

@Serializable
data class PortalProfile(
    val name: String,
    val portalUrl: String,
    val macAddress: String,
    val serialNumber: String = "",
    val portalType: PortalType = PortalType.STALKER,
    val username: String = "",
    val password: String = ""
) {
    fun cacheKey(): String = "catalog-v5|$portalType|${portalUrl.trimEnd('/')}|${username.ifBlank { macAddress }}"
}

@Serializable
enum class PortalType { STALKER, XTREAM }




@Serializable
enum class CatalogType(val title: String, val apiType: String) {
    LIVE_TV("Live TV", "itv"), MOVIES("Movies", "vod"), SERIES("Series", "series"), RADIO("Radio", "radio")
}

@Serializable
enum class SearchContentType(val title: String) {
    LIVE_TV("Live TV"), SERIES("Series"), MOVIES("Movies"),
    /** Retained only so older on-device caches remain readable. */
    EPISODES("Episodes")
}

@Serializable
data class RecentSearch(
    val query: String,
    val type: SearchContentType,
    val searchedAtMillis: Long = System.currentTimeMillis()
) { val key: String get() = "${type.name}:${query.lowercase()}" }

@Serializable
data class SearchResultCache(
    val profileKey: String,
    val type: SearchContentType,
    val query: String,
    val categoryId: String = "*",
    val lastPage: Int,
    val hasMore: Boolean,
    val items: List<MediaItem>,
    val cachedAtMillis: Long = System.currentTimeMillis()
) { val key: String get() = "${profileKey}|${type.name}|$categoryId|${query.trim().lowercase()}" }

data class PortalSearchPage(
    val items: List<MediaItem>,
    val page: Int,
    val hasMore: Boolean
)

@Serializable
data class Category(val id: String, val title: String, val type: CatalogType)
@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val logo: String?,
    val command: String?,
    val description: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val portalSeasonId: String? = null,
    val portalCategoryId: String? = null,
    val portalEpisodeId: String? = null
)

@Serializable
data class SearchCatalogCache(
    val profileKey: String,
    val type: CatalogType,
    val cachedAtMillis: Long,
    val items: List<MediaItem>,
    val completedCategoryIds: Set<String> = emptySet()
)

@Serializable
data class BrowseCatalogCache(
    val profileKey: String,
    val type: CatalogType,
    val cachedAtMillis: Long,
    val categories: List<Category>,
    val itemsByCategory: Map<String, List<MediaItem>>
)

@Serializable
enum class FavoriteKind { CHANNEL, MOVIE, SERIES, EPISODE }

@Serializable
data class FavoriteItem(
    val kind: FavoriteKind,
    val media: MediaItem,
    val series: MediaItem? = null,
    val addedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "${kind.name}:${media.id}"
}

@Serializable
data class RecentItem(
    val kind: FavoriteKind,
    val media: MediaItem,
    val series: MediaItem? = null,
    val lastPlayed: MediaItem? = null,
    val playedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "${kind.name}:${media.id}"
}

data class PlayingMedia(
    val media: MediaItem,
    val url: String,
    val nextEpisode: MediaItem? = null,
    val series: MediaItem? = null,
    val episodeQueue: List<MediaItem> = emptyList(),
    val resumePositionMillis: Long = 0L,
    val progressKey: String = ""
)

@Serializable
data class PlaybackProgress(
    val key: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class PlaybackUrl(
    val key: String,
    val url: String,
    val resolvedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class PortalSession(
    val profile: PortalProfile,
    val token: String,
    val endpointUrl: String,
    val serialNumber: String,
    val metrics: String,
    val hardwareVersion2: String,
    val random: String? = null,
    val authenticatedAtMillis: Long = System.currentTimeMillis()
)
