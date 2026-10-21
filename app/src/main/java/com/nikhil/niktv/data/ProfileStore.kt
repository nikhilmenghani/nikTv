package com.nikhil.niktv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.SearchCatalogCache
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.BrowseCatalogMetadataCache
import com.nikhil.niktv.model.PlaybackUrl
import com.nikhil.niktv.model.RecentSearch
import com.nikhil.niktv.model.SearchResultCache
import com.nikhil.niktv.model.SeriesStartSeason
import com.nikhil.niktv.model.WatchedSeries
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.BrowseLayout
import com.nikhil.niktv.model.TmdbIptvMapping
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.OfflineMediaDownload
import com.nikhil.niktv.model.canonicalSearchQuery
import com.nikhil.niktv.model.deduplicatedRecentSearches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

@Serializable
private data class NikTvBackup(
    val formatVersion: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val strings: Map<String, String>,
    val integers: Map<String, Int>
)

class ProfileStore @JvmOverloads constructor(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.dataStore,
) {
    private val key = stringPreferencesKey("active_profile")
    private val sessionKey = stringPreferencesKey("active_session")
    private val profilesKey = stringPreferencesKey("saved_profiles")
    private val sessionsKey = stringPreferencesKey("saved_sessions")
    private val activeProfileKey = stringPreferencesKey("active_profile_identity")
    private val favoritesKey = stringPreferencesKey("favorites")
    private val recentKey = stringPreferencesKey("recently_played")
    private val progressKey = stringPreferencesKey("playback_progress")
    private val playbackUrlsKey = stringPreferencesKey("playback_urls")
    private val cacheIntervalKey = intPreferencesKey("catalog_cache_interval_minutes")
    private val initialCatalogItemsKey = intPreferencesKey("initial_catalog_items")
    private val playerControlsTimeoutKey = intPreferencesKey("player_controls_timeout_seconds")
    private val keepAwakeOnlyDuringPlaybackKey = intPreferencesKey("keep_awake_only_during_playback")
    private val automaticReauthenticationKey = intPreferencesKey("automatic_reauthentication")
    private val modernUiEnabledKey = intPreferencesKey("modern_ui_enabled")
    private val playbackEngineKey = stringPreferencesKey("playback_engine")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val pagedSearchesKey = stringPreferencesKey("paged_search_results")
    private val categoryFiltersKey = stringPreferencesKey("category_filters")
    private val seriesStartSeasonKey = stringPreferencesKey("series_start_season")
    private val watchedSeriesKey = stringPreferencesKey("watched_series")
    private val rememberedSeriesSeasonsKey = stringPreferencesKey("remembered_series_seasons")
    private val episodeSeasonCachesKey = stringPreferencesKey("episode_season_caches")
    private val browseLayoutsKey = stringPreferencesKey("browse_layouts")
    private val tmdbDashboardSectionsKey = stringPreferencesKey("tmdb_dashboard_sections")
    private val tmdbMappingsKey = stringPreferencesKey("tmdb_iptv_mappings")
    private val offlineDownloadsKey = stringPreferencesKey("offline_media_downloads")
    val activeProfile: Flow<PortalProfile?> = preferences.data.decodedProfilePreference(
        select = { Triple(it[profilesKey], it[key], it[activeProfileKey]) },
        decode = { (raw, legacy, identity) ->
            val profiles = decodeProfiles(raw, legacy)
            profiles.firstOrNull { it.identity() == identity } ?: profiles.firstOrNull()
        },
    )
    val activeSession: Flow<PortalSession?> = preferences.data.decodedProfilePreference(
        select = { Triple(it[sessionsKey], it[sessionKey], it[activeProfileKey]) },
        decode = { (raw, legacy, active) ->
            decodeSessions(raw, legacy).firstOrNull { it.profile.identity() == active }
                ?: legacy?.let { runCatching { Json.decodeFromString<PortalSession>(it) }.getOrNull() }
        },
    )
    val profiles: Flow<List<PortalProfile>> = preferences.data.decodedProfilePreference(
        select = { it[profilesKey] to it[key] }, decode = { (raw, legacy) -> decodeProfiles(raw, legacy) },
    )
    val favorites: Flow<List<FavoriteItem>> = preferences.data.decodedProfilePreference(select = { it[favoritesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<FavoriteItem>>(it) }.getOrNull() }.orEmpty()
    })
    val recentlyPlayed: Flow<List<RecentItem>> = preferences.data.decodedProfilePreference(select = { it[recentKey] }, decode = { raw ->
        val stored = raw?.let { runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull() }.orEmpty()
        stored.filterNot { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE }.map { recent ->
            if (recent.kind != com.nikhil.niktv.model.FavoriteKind.SERIES || recent.lastPlayed != null) recent
            else stored.firstOrNull { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE && it.series?.id == recent.media.id }
                ?.let { recent.copy(lastPlayed = it.media, playedAtMillis = maxOf(recent.playedAtMillis, it.playedAtMillis)) }
                ?: recent
        }
    })
    private val requestedRecommendationsKey = stringPreferencesKey("requested_recommendations_v1")
    val requestedRecommendations: Flow<List<RecentItem>> = preferences.data.decodedProfilePreference(select = { it[requestedRecommendationsKey] }, decode = { raw ->
        raw?.let {
            runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull()
        }.orEmpty()
    })
    suspend fun requestRecommendations(seed: RecentItem, category: String) = preferences.editProfileMetadata { prefs ->
        val seeds = prefs[requestedRecommendationsKey]?.let {
            runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull()
        }.orEmpty()
        prefs[requestedRecommendationsKey] = Json.encodeToString(listOf(seed) + seeds.filterNot {
            it.profileKey == seed.profileKey && it.kind == seed.kind && it.media.id == seed.media.id
        })
        val dismissed = prefs[dismissedRecommendationsKey]?.let {
            runCatching { Json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
        }.orEmpty()
        prefs[dismissedRecommendationsKey] = Json.encodeToString(dismissed +
            (seed.profileKey.orEmpty() to (dismissed[seed.profileKey].orEmpty() - category)))
    }

    private val dismissedRecommendationsKey = stringPreferencesKey("dismissed_recommendations_v1")
    val dismissedRecommendations: Flow<Map<String, Set<String>>> = preferences.data.decodedProfilePreference(select = { it[dismissedRecommendationsKey] }, decode = { raw ->
        raw?.let {
            runCatching { Json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
        }.orEmpty()
    })
    suspend fun dismissRecommendation(profile: String, category: String) = preferences.editProfileMetadata { prefs ->
        val current = prefs[dismissedRecommendationsKey]?.let {
            runCatching { Json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
        }.orEmpty()
        prefs[dismissedRecommendationsKey] = Json.encodeToString(
            current + (profile to (current[profile].orEmpty() + category)))
    }

    suspend fun restoreRecommendation(profile: String, category: String) = preferences.editProfileMetadata { prefs ->
        val current = prefs[dismissedRecommendationsKey]?.let {
            runCatching { Json.decodeFromString<Map<String, Set<String>>>(it) }.getOrNull()
        }.orEmpty()
        if (category in current[profile].orEmpty()) {
            prefs[dismissedRecommendationsKey] = Json.encodeToString(
                current + (profile to (current[profile].orEmpty() - category)))
        }
    }

    private val recommendationProgressKey = stringPreferencesKey("recommendation_progress_v1")
    val recommendationProgress: Flow<List<PlaybackProgress>> = preferences.data.decodedProfilePreference(select = { it[recommendationProgressKey] }, decode = { raw ->
        raw?.let {
            runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull()
        }.orEmpty()
    })
    suspend fun saveRecommendationProgress(progress: PlaybackProgress) = preferences.editProfileMetadata { prefs ->
        val current = prefs[recommendationProgressKey]?.let {
            runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull()
        }.orEmpty()
        prefs[recommendationProgressKey] = Json.encodeToString(
            (listOf(progress) + current.filterNot { it.key == progress.key }).take(500))
    }

    val playbackProgress: Flow<List<PlaybackProgress>> = preferences.data.decodedProfilePreference(select = { it[progressKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull() }.orEmpty()
    })
    val playbackUrls: Flow<List<PlaybackUrl>> = preferences.data.decodedProfilePreference(select = { it[playbackUrlsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<PlaybackUrl>>(it) }.getOrNull() }.orEmpty()
    })
    val cacheIntervalMinutes: Flow<Int> = preferences.data.map { it[cacheIntervalKey] ?: 60 }
    val initialCatalogItems: Flow<Int> = preferences.data.map { prefs ->
        prefs[initialCatalogItemsKey]?.takeIf { it in INITIAL_CATALOG_OPTIONS } ?: 14
    }
    val playerControlsTimeoutSeconds: Flow<Int> = preferences.data.map { it[playerControlsTimeoutKey] ?: 3 }
    val keepAwakeOnlyDuringPlayback: Flow<Boolean> = preferences.data.map {
        (it[keepAwakeOnlyDuringPlaybackKey] ?: 0) == 1
    }
    val automaticReauthentication: Flow<Boolean> = preferences.data.map {
        (it[automaticReauthenticationKey] ?: 1) == 1
    }
    val modernUiEnabled: Flow<Boolean> = preferences.data.map {
        // Existing and new installs both default to the tile-first interface.
        (it[modernUiEnabledKey] ?: 1) == 1
    }
    val playbackEngine: Flow<PlaybackEngine> = preferences.data.map { prefs ->
        prefs[playbackEngineKey]?.let { runCatching { PlaybackEngine.valueOf(it) }.getOrNull() }
            ?.let { if (it == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else it }
            ?: PlaybackEngine.AUTO
    }
    val recentSearches: Flow<List<RecentSearch>> = preferences.data.decodedProfilePreference(select = { it[recentSearchesKey] }, decode = { raw ->
        decodeRecentSearches(raw).deduplicatedRecentSearches()
    })
    val pagedSearches: Flow<List<SearchResultCache>> = preferences.data.decodedProfilePreference(select = { it[pagedSearchesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
    })
    val categoryFilters: Flow<Map<String, List<String>>> = preferences.data.decodedProfilePreference(select = { it[categoryFiltersKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
    })
    val seriesStartSeason: Flow<SeriesStartSeason> = preferences.data.map { prefs ->
        prefs[seriesStartSeasonKey]?.let { runCatching { SeriesStartSeason.valueOf(it) }.getOrNull() }
            ?: SeriesStartSeason.LAST
    }
    val watchedSeries: Flow<List<WatchedSeries>> = preferences.data.decodedProfilePreference(select = { it[watchedSeriesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<WatchedSeries>>(it) }.getOrNull() }.orEmpty()
    })
    val rememberedSeriesSeasons: Flow<Map<String, Int>> = preferences.data.decodedProfilePreference(select = { it[rememberedSeriesSeasonsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
    })
    val episodeSeasonCaches: Flow<List<EpisodeSeasonCache>> = preferences.data.decodedProfilePreference(select = { it[episodeSeasonCachesKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<EpisodeSeasonCache>>(it) }.getOrNull() }
            .orEmpty()
    })
    val browseLayouts: Flow<Map<String, BrowseLayout>> = preferences.data.decodedProfilePreference(select = { it[browseLayoutsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, BrowseLayout>>(it) }.getOrNull() }.orEmpty()
    })
    val tmdbDashboardSections: Flow<Map<String, List<TmdbHomeSection>>> = preferences.data.decodedProfilePreference(select = { it[tmdbDashboardSectionsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<Map<String, List<TmdbHomeSection>>>(it) }.getOrNull() }
            .orEmpty()
    })
    val tmdbMappings: Flow<List<TmdbIptvMapping>> = preferences.data.decodedProfilePreference(select = { it[tmdbMappingsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<TmdbIptvMapping>>(it) }.getOrNull() }
            .orEmpty()
    })
    suspend fun save(session: PortalSession) = preferences.editProfileMetadata {
        val profiles = decodeProfiles(it[profilesKey], it[key])
        val sessions = decodeSessions(it[sessionsKey], it[sessionKey])
        it[profilesKey] = Json.encodeToString((profiles.filterNot { saved -> saved.identity() == session.profile.identity() } + session.profile))
        it[sessionsKey] = Json.encodeToString((sessions.filterNot { saved -> saved.profile.identity() == session.profile.identity() } + session))
        it[activeProfileKey] = session.profile.identity()
        it[key] = Json.encodeToString(session.profile)
        it[sessionKey] = Json.encodeToString(session)
    }
    suspend fun addProfile(profile: PortalProfile) = preferences.editProfileMetadata { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key])
        if (profiles.none { it.identity() == profile.identity() }) {
            prefs[profilesKey] = Json.encodeToString(profiles + profile)
        }
    }
    suspend fun activate(profile: PortalProfile) = preferences.editProfileMetadata { prefs ->
        prefs[activeProfileKey] = profile.identity()
        prefs[key] = Json.encodeToString(profile)
        val session = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
        if (session != null) prefs[sessionKey] = Json.encodeToString(session) else prefs.remove(sessionKey)
    }
    suspend fun sessionFor(profile: PortalProfile): PortalSession? = withContext(profileMetadataDispatcher) {
        preferences.data.first().let { prefs ->
            decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
        }
    }
    suspend fun removeProfile(profile: PortalProfile) = preferences.editProfileMetadata { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key]).filterNot { it.identity() == profile.identity() }
        val sessions = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).filterNot { it.profile.identity() == profile.identity() }
        prefs[profilesKey] = Json.encodeToString(profiles)
        prefs[sessionsKey] = Json.encodeToString(sessions)
        if (prefs[activeProfileKey] == profile.identity()) {
            prefs.remove(activeProfileKey); prefs.remove(key); prefs.remove(sessionKey)
        }
    }
    suspend fun clearSession() = preferences.edit { it.remove(sessionKey) }
    fun searchCatalog(type: CatalogType, profileKey: String? = null): Flow<SearchCatalogCache?> = kotlinx.coroutines.flow.flow {
        emit(profileKey?.let { CatalogDiskCache.read<SearchCatalogCache>(context, "search:$it:${type.name}") })
    }
    suspend fun saveSearchCatalog(cache: SearchCatalogCache, scheduleMetadataSync: Boolean = true) {
        discardLegacyBrowseCatalogs()
        CatalogDiskCache.write(context, "search:${cache.profileKey}:${cache.type.name}", cache)
        if (scheduleMetadataSync) SearchMetadataSyncScheduler.request(context)
    }
    suspend fun saveFavorites(items: List<FavoriteItem>) = preferences.editProfileMetadata {
        it[favoritesKey] = Json.encodeToString(items)
    }
    suspend fun saveRecentlyPlayed(items: List<RecentItem>) = preferences.editProfileMetadata {
        it[recentKey] = Json.encodeToString(items)
    }
    suspend fun savePlaybackProgress(items: List<PlaybackProgress>) = preferences.editProfileMetadata {
        it[progressKey] = Json.encodeToString(items)
    }
    suspend fun savePlaybackUrls(items: List<PlaybackUrl>) = preferences.editProfileMetadata {
        it[playbackUrlsKey] = Json.encodeToString(items)
    }
    fun browseCatalog(type: CatalogType, profileKey: String? = null): Flow<BrowseCatalogCache?> = kotlinx.coroutines.flow.flow {
        emit(profileKey?.let { CatalogDiskCache.read<BrowseCatalogCache>(context, "browse:$it:${type.name}") })
    }
    fun browseCatalogMetadata(type: CatalogType, profileKey: String? = null): Flow<BrowseCatalogMetadataCache?> = kotlinx.coroutines.flow.flow {
        emit(profileKey?.let { CatalogDiskCache.read<BrowseCatalogMetadataCache>(context, "browse-metadata:$it:${type.name}") })
    }
    suspend fun saveBrowseCatalogMetadata(cache: BrowseCatalogMetadataCache) {
        CatalogDiskCache.write(context, "browse-metadata:${cache.profileKey}:${cache.type.name}", cache)
    }
    suspend fun saveBrowseCatalog(cache: BrowseCatalogCache, scheduleMetadataSync: Boolean = true) {
        discardLegacyBrowseCatalogs()
        CatalogDiskCache.write(context, "browse:${cache.profileKey}:${cache.type.name}", cache)
        saveBrowseCatalogMetadata(
            BrowseCatalogMetadataCache(
                profileKey = cache.profileKey,
                type = cache.type,
                cachedAtMillis = cache.cachedAtMillis,
                categories = cache.categories
            )
        )
        if (scheduleMetadataSync) SearchMetadataSyncScheduler.request(context)
    }

    suspend fun mergeSearchMetadata(cache: SearchCatalogCache) {
        val current = searchCatalog(cache.type, cache.profileKey).first()
        val merged = if (current == null) {
            cache
        } else {
            current.copy(
                cachedAtMillis = maxOf(current.cachedAtMillis, cache.cachedAtMillis),
                items = (current.items + cache.items).distinctBy { it.id }
            )
        }
        saveSearchCatalog(merged, scheduleMetadataSync = false)
    }

    suspend fun discardLegacyBrowseCatalogs() = preferences.edit { prefs ->
        // These are rebuildable caches, not user settings. Drop the legacy
        // strings without decoding their potentially enormous object graphs.
        prefs.asMap().keys.filter {
            it.name.startsWith("browse_catalog_") || it.name.startsWith("search_catalog_")
        }.forEach {
            prefs.remove(stringPreferencesKey(it.name))
        }
    }

    private fun browseCatalogKey(type: CatalogType, profileKey: String): androidx.datastore.preferences.core.Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("browse_catalog_v2_${type.name.lowercase()}_$digest")
    }
    suspend fun saveTmdbMapping(mapping: TmdbIptvMapping) = preferences.editProfileMetadata { prefs ->
        val current = prefs[tmdbMappingsKey]
            ?.let { runCatching { Json.decodeFromString<List<TmdbIptvMapping>>(it) }.getOrNull() }
            .orEmpty()
        prefs[tmdbMappingsKey] = Json.encodeToString(
            (listOf(mapping) + current.filterNot { it.key == mapping.key }).take(500)
        )
    }
    suspend fun setCacheIntervalMinutes(minutes: Int) = preferences.edit { it[cacheIntervalKey] = minutes }
    suspend fun setInitialCatalogItems(items: Int) = preferences.edit {
        it[initialCatalogItemsKey] =
            items.takeIf { value -> value in INITIAL_CATALOG_OPTIONS } ?: 14
    }
    suspend fun setPlayerControlsTimeoutSeconds(seconds: Int) = preferences.edit {
        it[playerControlsTimeoutKey] = if (seconds <= 0) 0 else seconds.coerceIn(1, 30)
    }
    suspend fun setKeepAwakeOnlyDuringPlayback(enabled: Boolean) = preferences.edit {
        it[keepAwakeOnlyDuringPlaybackKey] = if (enabled) 1 else 0
    }
    val offlineDownloads: Flow<List<OfflineMediaDownload>> = preferences.data.decodedProfilePreference(select = { it[offlineDownloadsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<OfflineMediaDownload>>(it) }.getOrNull() }
            .orEmpty()
    })
    suspend fun setAutomaticReauthentication(enabled: Boolean) = preferences.edit {
        it[automaticReauthenticationKey] = if (enabled) 1 else 0
    }
    suspend fun setModernUiEnabled(enabled: Boolean) = preferences.edit {
        it[modernUiEnabledKey] = if (enabled) 1 else 0
    }
    suspend fun setPlaybackEngine(engine: PlaybackEngine) = preferences.edit {
        it[playbackEngineKey] = if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3.name else engine.name
    }
    suspend fun setSeriesStartSeason(value: SeriesStartSeason) = preferences.edit { it[seriesStartSeasonKey] = value.name }
    suspend fun saveWatchedSeries(items: List<WatchedSeries>) = preferences.editProfileMetadata {
        it[watchedSeriesKey] = Json.encodeToString(items)
    }
    // Merge into the latest persisted subscription, preserving concurrent watched/
    // dismissed actions and never reviving an unsubscribed series.
    internal suspend fun recordEpisodeDiscovery(seriesKey: String, episodes: List<com.nikhil.niktv.model.MediaItem>, now: Long): EpisodeDiscovery? {
        var result: EpisodeDiscovery? = null
        preferences.editProfileMetadata { prefs ->
            val current = prefs[watchedSeriesKey]?.let { Json.decodeFromString<List<WatchedSeries>>(it) }.orEmpty()
            val watched = current.firstOrNull { it.key == seriesKey } ?: return@editProfileMetadata
            val discovery = discoverNewEpisodes(watched, episodes, now)
            prefs[watchedSeriesKey] = Json.encodeToString(current.map { if (it.key == seriesKey) discovery.updated else it })
            result = discovery
        }
        return result
    }
    suspend fun rememberSeriesSeason(profileKey: String, seriesId: String, season: Int) = preferences.editProfileMetadata { prefs ->
        val current = prefs[rememberedSeriesSeasonsKey]?.let { runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
        prefs[rememberedSeriesSeasonsKey] = Json.encodeToString(current + ("$profileKey|$seriesId" to season))
    }
    suspend fun saveEpisodeSeasonCache(cache: EpisodeSeasonCache) = preferences.editProfileMetadata { prefs ->
        val current = prefs[episodeSeasonCachesKey]
            ?.let { runCatching { Json.decodeFromString<List<EpisodeSeasonCache>>(it) }.getOrNull() }
            .orEmpty()
        prefs[episodeSeasonCachesKey] = Json.encodeToString(
            (listOf(cache) + current.filterNot { it.key == cache.key }).take(40)
        )
    }
    suspend fun saveOfflineDownloads(items: List<OfflineMediaDownload>) = preferences.editProfileMetadata { prefs ->
        prefs[offlineDownloadsKey] = Json.encodeToString(items)
    }
    suspend fun setBrowseLayout(profileKey: String, layout: BrowseLayout) = preferences.editProfileMetadata { prefs ->
        val current = prefs[browseLayoutsKey]?.let { runCatching { Json.decodeFromString<Map<String, BrowseLayout>>(it) }.getOrNull() }.orEmpty()
        prefs[browseLayoutsKey] = Json.encodeToString(current + (profileKey to layout))
    }
    suspend fun setTmdbDashboardSections(profileKey: String, surface: DashboardSurface, sections: List<TmdbHomeSection>) = preferences.editProfileMetadata { prefs ->
        val current = prefs[tmdbDashboardSectionsKey]
            ?.let { runCatching { Json.decodeFromString<Map<String, List<TmdbHomeSection>>>(it) }.getOrNull() }
            .orEmpty()
        prefs[tmdbDashboardSectionsKey] = Json.encodeToString(current + ("$profileKey|${surface.name}" to sections.distinct()))
    }
    suspend fun addRecentSearch(search: RecentSearch) = preferences.editProfileMetadata { prefs ->
        val entry = search.copy(query = search.query.canonicalSearchQuery())
        val current = decodeRecentSearches(prefs[recentSearchesKey])
        prefs[recentSearchesKey] = Json.encodeToString(
            (listOf(entry) + current.filterNot { it.key == entry.key }).deduplicatedRecentSearches()
        )
    }
    suspend fun removeRecentSearch(search: RecentSearch) = preferences.editProfileMetadata { prefs ->
        val current = decodeRecentSearches(prefs[recentSearchesKey])
        prefs[recentSearchesKey] = Json.encodeToString(
            current.filterNot { it.key == search.key }.deduplicatedRecentSearches()
        )
    }
    suspend fun savePagedSearch(cache: SearchResultCache) = preferences.editProfileMetadata { prefs ->
        val current = prefs[pagedSearchesKey]?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
        prefs[pagedSearchesKey] = Json.encodeToString((listOf(cache) + current.filterNot { it.key == cache.key }).take(40))
    }
    suspend fun saveCategoryFilter(profileKey: String, type: CatalogType, categoryIds: List<String>) = preferences.editProfileMetadata { prefs ->
        val existing = prefs[categoryFiltersKey]?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
        val filterKey = "$profileKey|${type.name}"
        prefs[categoryFiltersKey] = Json.encodeToString(existing + (filterKey to categoryIds))
    }
    suspend fun clearCategoryFilter(profileKey: String, type: CatalogType) = preferences.editProfileMetadata { prefs ->
        val existing = prefs[categoryFiltersKey]?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
        val filterKey = "$profileKey|${type.name}"
        prefs[categoryFiltersKey] = Json.encodeToString(existing - filterKey)
    }

    suspend fun backupFingerprint(): String {
        val values = preferences.data.first().asMap()
        val canonical = buildString {
            BACKUP_STRING_KEYS.sorted().forEach { name ->
                val value =
                    values.entries
                        .firstOrNull { it.key.name == name }
                        ?.value as? String
                append("S:")
                append(name.length)
                append(':')
                append(name)
                append(':')
                if (value == null) {
                    append("-1:")
                } else {
                    append(value.length)
                    append(':')
                    append(value)
                }
                append('\n')
            }
            BACKUP_INT_KEYS.sorted().forEach { name ->
                val value =
                    values.entries
                        .firstOrNull { it.key.name == name }
                        ?.value as? Int
                append("I:")
                append(name.length)
                append(':')
                append(name)
                append(':')
                append(value?.toString() ?: "<missing>")
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun exportBackup(): String {
        val values = preferences.data.first().asMap()
        val strings = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_STRING_KEYS && value is String) key.name to value else null
        }.toMap()
        val integers = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_INT_KEYS && value is Int) key.name to value else null
        }.toMap()
        return backupExportJson.encodeToString(
            NikTvBackup(strings = strings, integers = integers)
        )
    }

    suspend fun importBackup(content: String) {
        val backup = backupImportJson.decodeFromString<NikTvBackup>(content)
        require(backup.formatVersion == 1) { "Unsupported NikTV backup version ${backup.formatVersion}" }
        backup.strings[profilesKey.name]?.let {
            require(runCatching { Json.decodeFromString<List<PortalProfile>>(it) }.getOrNull() != null) {
                "The backup contains invalid profile data"
            }
        }
        preferences.edit { prefs ->
            BACKUP_STRING_KEYS.forEach { prefs.remove(stringPreferencesKey(it)) }
            BACKUP_INT_KEYS.forEach { prefs.remove(intPreferencesKey(it)) }
            backup.strings.filterKeys { it in BACKUP_STRING_KEYS }.forEach { (name, value) ->
                prefs[stringPreferencesKey(name)] = value
            }
            backup.integers.filterKeys { it in BACKUP_INT_KEYS }.forEach { (name, value) ->
                prefs[intPreferencesKey(name)] = value
            }
            // Portal tokens are intentionally device-local and must be refreshed.
            prefs.remove(sessionKey)
            prefs.remove(sessionsKey)
        }
    }
    suspend fun clear() = preferences.edit {
        CatalogDiskCache.clear(context)
        CatalogRepository(context).clear()
        it.remove(key)
        it.remove(sessionKey)
        it.remove(profilesKey)
        it.remove(sessionsKey)
        it.remove(activeProfileKey)
        CatalogType.entries.forEach { type -> it.remove(stringPreferencesKey("search_catalog_${type.name.lowercase()}")) }
        CatalogType.entries.forEach { type -> it.remove(stringPreferencesKey("browse_catalog_${type.name.lowercase()}")) }
        it.remove(favoritesKey)
        it.remove(recentKey)
        it.remove(progressKey)
        it.remove(playbackUrlsKey)
        it.remove(recentSearchesKey)
        it.remove(pagedSearchesKey)
        it.remove(categoryFiltersKey)
        it.remove(seriesStartSeasonKey)
        it.remove(watchedSeriesKey)
        it.remove(rememberedSeriesSeasonsKey)
        it.remove(episodeSeasonCachesKey)
        it.remove(browseLayoutsKey)
        it.remove(modernUiEnabledKey)
        it.remove(tmdbMappingsKey)
        it.remove(recommendationProgressKey)
    }

    private fun decodeProfiles(raw: String?, legacy: String?): List<PortalProfile> =
        raw?.let { runCatching { Json.decodeFromString<List<PortalProfile>>(it) }.getOrNull() }
            ?: legacy?.let { runCatching { listOf(Json.decodeFromString<PortalProfile>(it)) }.getOrNull() }.orEmpty()
    private fun decodeSessions(raw: String?, legacy: String?): List<PortalSession> =
        raw?.let { runCatching { Json.decodeFromString<List<PortalSession>>(it) }.getOrNull() }
            ?: legacy?.let { runCatching { listOf(Json.decodeFromString<PortalSession>(it)) }.getOrNull() }.orEmpty()
    private fun decodeRecentSearches(raw: String?): List<RecentSearch> =
        raw?.let { runCatching { Json.decodeFromString<List<RecentSearch>>(it) }.getOrNull() }.orEmpty()
    private fun PortalProfile.identity() = "$portalType|${portalUrl.trimEnd('/').lowercase()}|${username.ifBlank { macAddress }.lowercase()}"

    companion object {
        private val backupExportJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        private val backupImportJson = Json { ignoreUnknownKeys = true }
        private val BACKUP_STRING_KEYS = setOf(
            "active_profile", "saved_profiles", "active_profile_identity", "favorites",
            "recently_played", "playback_progress", "recent_searches", "category_filters",
            "series_start_season", "watched_series", "remembered_series_seasons", "browse_layouts",
            "tmdb_iptv_mappings", "tmdb_dashboard_sections"
        )
        private val BACKUP_INT_KEYS = setOf(
            "catalog_cache_interval_minutes",
            "initial_catalog_items",
            "player_controls_timeout_seconds",
            "automatic_reauthentication",
            "modern_ui_enabled"
        )
        private val INITIAL_CATALOG_OPTIONS = setOf(14, 28, 42, 56)
    }
}
