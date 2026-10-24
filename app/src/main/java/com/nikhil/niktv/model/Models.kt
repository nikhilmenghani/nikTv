@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nikhil.niktv.model

import kotlinx.serialization.EncodeDefault
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
    ALL("All"),
    LIVE_TV("Live TV"), SERIES("Series"), MOVIES("Movies"),
    /** Retained only so older on-device caches remain readable. */
    EPISODES("Episodes")
}

@Serializable
data class RecentSearch(
    val query: String,
    val type: SearchContentType,
    @EncodeDefault val searchedAtMillis: Long = System.currentTimeMillis(),
    val categoryId: String = "*",
    val categoryTitle: String = "All categories",
    val profileKey: String = ""
) { val key: String get() = "$profileKey:${type.name}:$categoryId:${query.normalizedSearchQuery()}" }

private val searchWhitespace = Regex("\\s+")

fun String.canonicalSearchQuery(): String = trim().replace(searchWhitespace, " ")

fun String.normalizedSearchQuery(): String = canonicalSearchQuery().lowercase()

private val titleAccentMarks = Regex("\\p{M}+")
private val titleWordSeparators = Regex("[^\\p{L}\\p{N}]+")
private fun String.titleSearchTokens(): List<String> =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(titleAccentMarks, "")
        .split(titleWordSeparators)
        .filter(String::isNotBlank)

/** Case-insensitive title matching where every query word may be separated by punctuation. */
fun String.matchesTitleKeywords(query: String): Boolean {
    return titleKeywordMatcher(query)(this)
}

/** Compile the query once when scanning a catalogue, rather than once per title. */
fun titleKeywordMatcher(query: String): (String) -> Boolean {
    val queryTokens = query.titleSearchTokens()
    return { title ->
        val titleTokens = title.titleSearchTokens()
        queryTokens.isNotEmpty() && queryTokens.all { key -> titleTokens.any { word -> word.contains(key) } }
    }
}

internal fun String.normalizedTitleKeywords(): String = titleSearchTokens().joinToString(" ")
internal fun normalizedTitleKeywordMatcher(query: String): (String) -> Boolean {
    val words = query.titleSearchTokens()
    return { title -> words.isNotEmpty() && words.all(title::contains) }
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
    @EncodeDefault val cachedAtMillis: Long = System.currentTimeMillis()
) { val key: String get() = "${profileKey}|${type.name}|$categoryId|${query.trim().lowercase()}" }

data class PortalSearchPage(
    val items: List<MediaItem>,
    val page: Int,
    val hasMore: Boolean
)

data class PortalCatalogPage(
    val items: List<MediaItem>,
    val page: Int,
    val hasMore: Boolean,
    /** Exact provider-reported page count, or one derived from totalItems/pageSize. */
    val totalPages: Int? = null,
    val totalItems: Int? = null,
    /** Provider-reported page size when available; used to qualify page-count comparisons. */
    val pageSize: Int? = null
)

@Serializable
data class Category(
    val id: String, val title: String, val type: CatalogType, val isAdult: Boolean = false,
    /** Local collection membership; provider IDs on media are never rewritten. */
    val sourceCategoryIds: List<String> = emptyList()
)
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
    val liveSchedule: List<LiveProgramme> = emptyList(),
    val channelNumber: Int? = null,
    val epgChannelId: String? = null,
    val streamType: String? = null,
    val catchupAvailable: Boolean? = null,
    /** Provider-supplied TMDB identity, when available (not a guessed match). */
    val externalTmdbId: Int? = null,
    /** Episode air date in ISO-8601 form when supplied by IPTV or TMDB. */
    val episodeAirDate: String? = null,
    /** TMDB principal cast, populated lazily for focused movie and series titles. */
    val cast: List<String> = emptyList(),
    /** Search-only routing identity; never persisted in favourites or provider caches. */
    @kotlinx.serialization.Transient val searchResultType: SearchContentType? = null,
    val isAdult: Boolean = false,
    /** Provider upload/add timestamp, independent of the episode's original air date. */
    val episodeAddedAtMillis: Long? = null
)

@Serializable
data class TmdbIptvMapping(
    val profileKey: String,
    val type: CatalogType,
    val tmdbId: Int,
    val media: MediaItem,
    /** True when the user explicitly chose this provider result. */
    val confirmedByUser: Boolean = false,
    /** True only after the provider successfully created a playback URL. */
    val playbackVerified: Boolean = false,
    @EncodeDefault val cachedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "$profileKey|${type.name}|$tmdbId"
}

@Serializable
data class LiveProgramme(
    val title: String,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
    /** Freshness of provider "now playing" text when broadcast times are absent. */
    val observedAtMillis: Long? = null
)

/** Provider placeholder rows can include timestamps but are not programmes. */
fun isMissingLiveProgrammeTitle(title: String): Boolean {
    val normalized = title.replace(Regex("^\\s*H:i\\s+", RegexOption.IGNORE_CASE), "")
        .removeSurrounding("[", "]")
        .trim()
        .lowercase(java.util.Locale.ROOT)
    return normalized.isBlank() || normalized in setOf(
        "no channel info",
        "no content available",
        "no programme information",
        "no program information",
        "no information",
        "no epg",
        "no guide",
        "no guide available",
        "guide unavailable",
        "this channel has no guide"
    )
}

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
    val itemsByCategory: Map<String, List<MediaItem>>,
    val pagesByCategory: Map<String, Int> = emptyMap(),
    val hasMoreByCategory: Map<String, Boolean> = emptyMap(),
    val categoryCachedAtMillis: Map<String, Long> = emptyMap()
)

@Serializable
data class BrowseCatalogMetadataCache(
    val profileKey: String,
    val type: CatalogType,
    val cachedAtMillis: Long,
    val categories: List<Category>
)

@Serializable
enum class FavoriteKind { CHANNEL, MOVIE, SERIES, EPISODE }

@Serializable
enum class FavoriteSource { IPTV, TMDB }

@Serializable
data class FavoriteItem(
    val kind: FavoriteKind,
    val media: MediaItem,
    val series: MediaItem? = null,
    @EncodeDefault val addedAtMillis: Long = System.currentTimeMillis(),
    val profileKey: String = "",
    val categoryTitle: String? = null,
    val source: FavoriteSource = FavoriteSource.IPTV
) {
    val key: String get() = "$profileKey:${kind.name}:${media.id}"
}

@Serializable
enum class SeriesStartSeason { FIRST, LAST }

@Serializable
enum class BrowseLayout { SECTIONS, GRID, LIST }

enum class DashboardSurface { HOME, LIVE_TV, MOVIES, SERIES }

@Serializable
enum class TmdbHomeSection(val title: String, val series: Boolean = false) {
    TRENDING_MOVIES("Hollywood Trending Movies"),
    TRENDING_SERIES("Hollywood Trending Series", series = true),
    TOP_RATED_MOVIES("Hollywood Top Rated Movies"),
    NEW_HOLLYWOOD_MOVIES("New Hollywood Movies"),
    NEW_BOLLYWOOD_MOVIES("New Bollywood Movies"),
    HOLLYWOOD("Hollywood"),
    BOLLYWOOD("Bollywood"),
    ACTION("Hollywood Action"),
    COMEDY("Hollywood Comedy"),
    HORROR("Hollywood Horror"),
    THRILLER("Hollywood Thriller"),
    FAMILY("Hollywood Family"),
    DOCUMENTARY("Hollywood Documentary"),
    TOP_RATED_SERIES("Hollywood Top Rated Series", series = true),
    NEW_HOLLYWOOD_SERIES("New Hollywood Series", series = true),
    NEW_BOLLYWOOD_SERIES("New Bollywood Series", series = true),
    HOLLYWOOD_SERIES("Hollywood Series", series = true),
    BOLLYWOOD_SERIES("Bollywood Series", series = true),
    ACTION_SERIES("Hollywood Action & Adventure Series", series = true),
    COMEDY_SERIES("Hollywood Comedy Series", series = true),
    MYSTERY_SERIES("Hollywood Mystery Series", series = true),
    FAMILY_SERIES("Hollywood Family Series", series = true),
    DOCUMENTARY_SERIES("Hollywood Documentary Series", series = true),
    BOLLYWOOD_TRENDING_MOVIES("Bollywood Trending Movies"),
    BOLLYWOOD_TOP_RATED_MOVIES("Bollywood Top Rated Movies"),
    BOLLYWOOD_ACTION("Bollywood Action"),
    BOLLYWOOD_COMEDY("Bollywood Comedy"),
    BOLLYWOOD_HORROR("Bollywood Horror"),
    BOLLYWOOD_THRILLER("Bollywood Thriller"),
    BOLLYWOOD_FAMILY("Bollywood Family"),
    BOLLYWOOD_DOCUMENTARY("Bollywood Documentary"),
    BOLLYWOOD_TRENDING_SERIES("Bollywood Trending Series", series = true),
    BOLLYWOOD_TOP_RATED_SERIES("Bollywood Top Rated Series", series = true),
    BOLLYWOOD_ACTION_SERIES("Bollywood Action & Adventure Series", series = true),
    BOLLYWOOD_COMEDY_SERIES("Bollywood Comedy Series", series = true),
    BOLLYWOOD_MYSTERY_SERIES("Bollywood Mystery Series", series = true),
    BOLLYWOOD_FAMILY_SERIES("Bollywood Family Series", series = true),
    BOLLYWOOD_DOCUMENTARY_SERIES("Bollywood Documentary Series", series = true);

}

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
    val metadataVersion: Int = 0,
    @EncodeDefault val cachedAtMillis: Long = System.currentTimeMillis(),
    /** Unmodified provider episodes, retained so the TMDB switch is local-only. */
    val iptvEpisodes: List<MediaItem> = emptyList(),
    /** IPTV episode identities for which TMDB was checked and cached. */
    val tmdbEpisodeKeys: Set<String> = emptySet(),
    val tmdbSeriesId: Int? = null,
    val tmdbCachedAtMillis: Long = 0L,
    /** True after loading the series index, not merely a subsequent season page. */
    val seasonIndexValidated: Boolean = false
) {
    val key: String get() = "$profileKey|$seriesId|${season ?: -1}"
}

@Serializable
data class RecentItem(
    val kind: FavoriteKind,
    val media: MediaItem,
    val series: MediaItem? = null,
    val lastPlayed: MediaItem? = null,
    @EncodeDefault val playedAtMillis: Long = System.currentTimeMillis(),
    val profileKey: String = "",
    val categoryTitle: String? = null,
    val alternateTarget: com.nikhil.niktv.data.AlternateSourceTarget? = null
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
    // Changes even when resolving a provider returns the same URL.
    val playbackAttemptId: String = java.util.UUID.randomUUID().toString(),
    val authorizationRetryCount: Int = 0,
    val directFullscreen: Boolean = false,
    val offlinePlayback: Boolean = false,
    val playbackFormat: String = "",
    val separateAudioUrl: String? = null,
    val streamRequestHeaders: Map<String, String> = emptyMap(),
    val nativeSabr: com.newtube.sabr.SabrStreamInfo? = null,
    val queueFilter: PlaybackQueueFilter? = null,
    val playWhenReady: Boolean = true,
    val decoderSessionId: String = java.util.UUID.randomUUID().toString(),
    val decoderRecoveryCount: Int = 0,
    /** Explicit alternate playback is ephemeral and never replaces an IPTV mapping. */
    val alternateSource: AlternatePlaybackSource? = null
)

data class AlternatePlaybackSource(
    val target: com.nikhil.niktv.data.AlternateSourceTarget,
    val streams: List<com.nikhil.niktv.data.AlternateStream>,
    val selectedId: String,
    val profileKey: String
)

@Serializable
enum class PlaybackEngine { AUTO, MEDIA3, VLC, EXOPLAYER, MPV, FLOW }

@Serializable
data class PlaybackProgress(
    val key: String,
    val positionMillis: Long,
    val durationMillis: Long,
    @EncodeDefault val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class PlaybackUrl(
    val key: String,
    val url: String,
    @EncodeDefault val resolvedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class OfflineMediaDownload(
    val downloadId: Long = -1L,
    val requestId: String = "",
    val sourceUrl: String = "",
    val profileKey: String,
    val catalogType: CatalogType,
    val media: MediaItem,
    val series: MediaItem? = null,
    val fileType: String = "",
    @EncodeDefault val queuedAtMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "$profileKey:${catalogType.name}:${media.id}"
}

@Serializable
data class PortalSession(
    val profile: PortalProfile,
    val token: String,
    val endpointUrl: String,
    val serialNumber: String,
    val metrics: String,
    val hardwareVersion2: String,
    val random: String? = null,
    @EncodeDefault val authenticatedAtMillis: Long = System.currentTimeMillis()
)

/** A local browse context; never stores credentials or a resolved stream URL. */
data class PlaybackQueueFilter(
    val query: String = "",
    val sourceIds: Set<String>? = null,
    val fixedIds: Set<String>? = null,
    val pinnedIds: List<String>? = null,
    val selectedOrdinal: Int = 0,
    val contextId: String? = null
)
