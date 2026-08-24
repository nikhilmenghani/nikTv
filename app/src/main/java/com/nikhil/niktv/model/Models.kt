package com.nikhil.niktv.model

import kotlinx.serialization.Serializable
import java.text.Normalizer

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
    val searchedAtMillis: Long = System.currentTimeMillis(),
    val categoryId: String = "*",
    val categoryTitle: String = "All categories"
) { val key: String get() = "${type.name}:$categoryId:${query.normalizedSearchQuery()}" }

private val searchWhitespace = Regex("\\s+")

fun String.canonicalSearchQuery(): String = trim().replace(searchWhitespace, " ")

fun String.normalizedSearchQuery(): String = canonicalSearchQuery().lowercase()

private fun String.titleSearchTokens(): List<String> =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)

/** Case-insensitive title matching where every query word may be separated by punctuation. */
fun String.matchesTitleKeywords(query: String): Boolean {
    val titleTokens = titleSearchTokens()
    val queryTokens = query.titleSearchTokens()
    return queryTokens.isNotEmpty() && queryTokens.all { key -> titleTokens.any { word -> word.contains(key) } }
}

fun String.titleKeywordScore(query: String): Int {
    if (!matchesTitleKeywords(query)) return Int.MIN_VALUE
    val titleTokens = titleSearchTokens()
    val queryTokens = query.titleSearchTokens()
    val exactWords = queryTokens.count { it in titleTokens }
    val orderedPhrase = queryTokens.joinToString(" ") in titleTokens.joinToString(" ")
    return (if (orderedPhrase) 1_000 else 0) + exactWords * 100 - (titleTokens.size - queryTokens.size).coerceAtLeast(0)
}

fun List<RecentSearch>.deduplicatedRecentSearches(maxItems: Int = 20): List<RecentSearch> =
    sortedByDescending { it.searchedAtMillis }.distinctBy { it.key }.take(maxItems)

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

data class PortalCatalogPage(
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
    val portalEpisodeId: String? = null,
    val liveProgramme: LiveProgramme? = null,
    /** Provider-supplied TMDB identity, when available (not a guessed match). */
    val externalTmdbId: Int? = null
)

@Serializable
data class TmdbIptvMapping(
    val profileKey: String,
    val type: CatalogType,
    val tmdbId: Int,
    val media: MediaItem,
    val cachedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "$profileKey|${type.name}|$tmdbId"
}

@Serializable
data class LiveProgramme(
    val title: String,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null
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
    val addedAtMillis: Long = System.currentTimeMillis(),
    val profileKey: String = "",
    val categoryTitle: String? = null
) {
    val key: String get() = "$profileKey:${kind.name}:${media.id}"
}

@Serializable
enum class SeriesStartSeason { FIRST, LAST }

@Serializable
enum class BrowseLayout { GRID, LIST }

@Serializable
data class WatchedSeries(
    val profileKey: String,
    val series: MediaItem,
    val categoryTitle: String? = null,
    val knownEpisodeIds: Set<String> = emptySet(),
    val newEpisodes: List<MediaItem> = emptyList(),
    val checkedAtMillis: Long = 0L
) {
    val key: String get() = "$profileKey:${series.id}"
}

data class EpisodeSeasonResult(
    val episodes: List<MediaItem>,
    val availableSeasons: List<Int>,
    val selectedSeason: Int?,
    val page: Int = 1,
    val hasMore: Boolean = false
)

@Serializable
data class EpisodeSeasonCache(
    val profileKey: String,
    val seriesId: String,
    val season: Int?,
    val availableSeasons: List<Int>,
    val episodes: List<MediaItem>,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val cachedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "$profileKey|$seriesId|${season ?: -1}"
}

@Serializable
data class RecentItem(
    val kind: FavoriteKind,
    val media: MediaItem,
    val series: MediaItem? = null,
    val lastPlayed: MediaItem? = null,
    val playedAtMillis: Long = System.currentTimeMillis(),
    val profileKey: String = ""
) {
    val key: String get() = "$profileKey:${kind.name}:${media.id}"
}

data class PlayingMedia(
    val media: MediaItem,
    val url: String,
    val catalogType: CatalogType,
    val previousEpisode: MediaItem? = null,
    val nextEpisode: MediaItem? = null,
    val series: MediaItem? = null,
    val episodeQueue: List<MediaItem> = emptyList(),
    val resumePositionMillis: Long = 0L,
    val progressKey: String = "",
    val authorizationRetryCount: Int = 0
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
