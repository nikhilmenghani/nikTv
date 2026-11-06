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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import androidx.room.withTransaction
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

@Serializable
private data class NikTvBackup(
    val formatVersion: Int = 3,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val strings: Map<String, String>,
    val integers: Map<String, Int>,
    val devicePreferences: Map<String, Map<String, DevicePreferenceValue>> = emptyMap(),
    val remoteConfiguration: RemoteConfigurationBackup? = null,
    val backupConnection: BackupConnectionConfiguration? = null,
    val backupMetadata: DeviceBackupMetadata? = null,
    val userRecords: List<SyncRecord> = emptyList(),
    val userLocalPreferences: List<UserLocalPreference> = emptyList(),
    val portalMappings: List<LocalPortalMapping> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStore @JvmOverloads constructor(
    private val context: Context,
    preferences: DataStore<Preferences> = context.dataStore,
    private val fixedUserScope: UserScope? = null,
) {
    private val rawPreferences = preferences
    private val basePreferences = RestoreReadyPreferences(context, preferences)
    private val users = UserSyncStore.get(context)
    private val preferences: DataStore<Preferences> = UserScopedPreferences(basePreferences, users, fixedUserScope)
    fun captureScope(): UserScope? = fixedUserScope ?: users.selectedScope.value
    fun scoped(scope: UserScope): ProfileStore = ProfileStore(context, rawPreferences, scope)
    private fun scopedPreferences(scope: UserScope?): DataStore<Preferences> =
        if (scope == null) preferences else UserScopedPreferences(basePreferences, users, scope)
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
    val activeProfile: Flow<PortalProfile?> = this@ProfileStore.preferences.data.decodedProfilePreference(
        select = { Triple(it[profilesKey], it[key], it[activeProfileKey]) },
        decode = { (raw, legacy, identity) ->
            val profiles = decodeProfiles(raw, legacy)
            profiles.firstOrNull { it.identity() == identity } ?: profiles.firstOrNull()
        },
    )
    val activeSession: Flow<PortalSession?> = this@ProfileStore.preferences.data.decodedProfilePreference(
        select = { Triple(it[sessionsKey], it[sessionKey], it[activeProfileKey]) },
        decode = { (raw, legacy, active) ->
            decodeSessions(raw, legacy).firstOrNull { it.profile.identity() == active }
                ?: legacy?.let { runCatching { Json.decodeFromString<PortalSession>(it) }.getOrNull() }
        },
    )
    private val portalRecordChanges = users.selectedAccount.flatMapLatest { accountId ->
        if (accountId == null) flowOf(emptyList()) else users.dao.observeRecords(accountId)
    }.map { records -> records.filter { it.collection == "portals" } }
    val profiles: Flow<List<PortalProfile>> = this@ProfileStore.preferences.data.decodedProfilePreference(
        select = { it[profilesKey] to it[key] }, decode = { (raw, legacy) -> decodeProfiles(raw, legacy) },
    ).combine(users.selectedScope) { portals, selected -> portals to (fixedUserScope ?: selected) }
    .combine(portalRecordChanges) { (portals, owner), _ ->
        if (owner == null) portals else portals.filter { users.hasPortalAssociation(owner, it) }
    }
    val favorites: Flow<List<FavoriteItem>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[favoritesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<FavoriteItem>>(it) }.getOrNull() }.orEmpty()
    })
    val recentlyPlayed: Flow<List<RecentItem>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[recentKey] }, decode = { raw ->
        val stored = raw?.let { runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull() }.orEmpty()
        stored.filterNot { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE }.map { recent ->
            if (recent.kind != com.nikhil.niktv.model.FavoriteKind.SERIES || recent.lastPlayed != null) recent
            else stored.firstOrNull { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE && it.series?.id == recent.media.id }
                ?.let { recent.copy(lastPlayed = it.media, playedAtMillis = maxOf(recent.playedAtMillis, it.playedAtMillis)) }
                ?: recent
        }
    })
    private val requestedRecommendationsKey = stringPreferencesKey("requested_recommendations_v1")
    val requestedRecommendations: Flow<List<RecentItem>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[requestedRecommendationsKey] }, decode = { raw ->
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
    val dismissedRecommendations: Flow<Map<String, Set<String>>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[dismissedRecommendationsKey] }, decode = { raw ->
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
    val recommendationProgress: Flow<List<PlaybackProgress>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[recommendationProgressKey] }, decode = { raw ->
        raw?.let {
            runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull()
        }.orEmpty()
    })
    suspend fun saveRecommendationProgress(progress: PlaybackProgress, scope: UserScope? = captureScope()) = scopedPreferences(scope).editProfileMetadata { prefs ->
        val current = prefs[recommendationProgressKey]?.let {
            runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull()
        }.orEmpty()
        prefs[recommendationProgressKey] = Json.encodeToString(
            (listOf(progress) + current.filterNot { it.key == progress.key }).take(500))
    }

    val playbackProgress: Flow<List<PlaybackProgress>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[progressKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull() }.orEmpty()
    })
    val playbackUrls: Flow<List<PlaybackUrl>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[playbackUrlsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<PlaybackUrl>>(it) }.getOrNull() }.orEmpty()
    })
    val cacheIntervalMinutes: Flow<Int> = this@ProfileStore.preferences.data.map { it[cacheIntervalKey] ?: 60 }
    val initialCatalogItems: Flow<Int> = this@ProfileStore.preferences.data.map { prefs ->
        prefs[initialCatalogItemsKey]?.takeIf { it in INITIAL_CATALOG_OPTIONS } ?: 14
    }
    val playerControlsTimeoutSeconds: Flow<Int> = this@ProfileStore.preferences.data.decodedProfilePreference(
        select = { prefs -> if (captureScope() == null) "legacy:${prefs[playerControlsTimeoutKey] ?: 3}"
            else prefs[stringPreferencesKey("portable_preferences")] ?: "{}" },
        decode = { raw ->
            val value = if (raw?.startsWith("legacy:") == true) raw.removePrefix("legacy:").toIntOrNull() else runCatching {
                val entries = Json.parseToJsonElement(raw ?: "{}") as JsonObject
                val entry = entries["niktv_playback_ui|controls_timeout"] as? JsonObject
                entry?.takeIf { it["type"]?.jsonPrimitive?.content == "int" }?.get("value")?.jsonPrimitive?.intOrNull
            }.getOrNull()
            normalizeControlsTimeout(value ?: 3)
        })
    val keepAwakeOnlyDuringPlayback: Flow<Boolean> = this@ProfileStore.preferences.data.map {
        (it[keepAwakeOnlyDuringPlaybackKey] ?: 0) == 1
    }
    val automaticReauthentication: Flow<Boolean> = this@ProfileStore.preferences.data.map {
        (it[automaticReauthenticationKey] ?: 1) == 1
    }

    val playbackEngine: Flow<PlaybackEngine> = this@ProfileStore.preferences.data.map { prefs ->
        prefs[playbackEngineKey]?.let { runCatching { PlaybackEngine.valueOf(it) }.getOrNull() }
            ?.let { if (it == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else it }
            ?: PlaybackEngine.AUTO
    }
    val recentSearches: Flow<List<RecentSearch>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[recentSearchesKey] }, decode = { raw ->
        decodeRecentSearches(raw).deduplicatedRecentSearches()
    })
    val pagedSearches: Flow<List<SearchResultCache>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[pagedSearchesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
    })
    val categoryFilters: Flow<Map<String, List<String>>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[categoryFiltersKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
    })
    val seriesStartSeason: Flow<SeriesStartSeason> = this@ProfileStore.preferences.data.map { prefs ->
        prefs[seriesStartSeasonKey]?.let { runCatching { SeriesStartSeason.valueOf(it) }.getOrNull() }
            ?: SeriesStartSeason.LAST
    }
    val watchedSeries: Flow<List<WatchedSeries>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[watchedSeriesKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<List<WatchedSeries>>(it) }.getOrNull() }.orEmpty()
    })
    val rememberedSeriesSeasons: Flow<Map<String, Int>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[rememberedSeriesSeasonsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
    })
    val episodeSeasonCaches: Flow<List<EpisodeSeasonCache>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[episodeSeasonCachesKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<EpisodeSeasonCache>>(it) }.getOrNull() }
            .orEmpty()
    })
    val browseLayouts: Flow<Map<String, BrowseLayout>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[browseLayoutsKey] }, decode = { raw ->
        raw?.let { runCatching { Json.decodeFromString<Map<String, BrowseLayout>>(it) }.getOrNull() }.orEmpty()
    })
    val tmdbDashboardSections: Flow<Map<String, List<TmdbHomeSection>>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[tmdbDashboardSectionsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<Map<String, List<TmdbHomeSection>>>(it) }.getOrNull() }
            .orEmpty()
    })
    val tmdbMappings: Flow<List<TmdbIptvMapping>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[tmdbMappingsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<TmdbIptvMapping>>(it) }.getOrNull() }
            .orEmpty()
    })
    suspend fun save(session: PortalSession, owns: () -> Boolean = { true }): Preferences {
        if (!owns()) throw kotlinx.coroutines.CancellationException("Portal changed")
        captureScope()?.let { users.associatePortal(it, session.profile) }
        return preferences.editOwnedProfileMetadata(owns) {
        if (!owns()) throw kotlinx.coroutines.CancellationException("Portal changed")
        val profiles = decodeProfiles(it[profilesKey], it[key])
        val sessions = decodeSessions(it[sessionsKey], it[sessionKey])
        it[profilesKey] = Json.encodeToString((profiles.filterNot { saved -> saved.identity() == session.profile.identity() } + session.profile))
        it[sessionsKey] = Json.encodeToString((sessions.filterNot { saved -> saved.profile.identity() == session.profile.identity() } + session))
        it[activeProfileKey] = session.profile.identity()
        it[key] = Json.encodeToString(session.profile)
        it[sessionKey] = Json.encodeToString(session)
        }
    }
    suspend fun addProfile(profile: PortalProfile): Preferences {
        captureScope()?.let { users.associatePortal(it, profile) }
        return preferences.editProfileMetadata { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key])
        if (profiles.none { it.identity() == profile.identity() }) {
            prefs[profilesKey] = Json.encodeToString(profiles + profile)
        }
        }
    }
    suspend fun activate(profile: PortalProfile) = preferences.editProfileMetadata { prefs ->
        prefs[activeProfileKey] = profile.identity()
        prefs[key] = Json.encodeToString(profile)
        val session = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
        if (session != null) prefs[sessionKey] = Json.encodeToString(session) else prefs.remove(sessionKey)
    }
    suspend fun sessionFor(profile: PortalProfile): PortalSession? = withContext(profileMetadataDispatcher) {
        this@ProfileStore.preferences.data.first().let { prefs ->
            decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
        }
    }
    suspend fun removeProfile(profile: PortalProfile): Preferences {
        captureScope()?.let { owner ->
            val portalId = users.registerPortal(owner.accountId, profile)
            users.removePortal(owner, profile)
            val usedElsewhere = users.snapshot(owner.accountId).any {
                it.collection == "portals" && it.recordId == portalId && it.profileId != owner.profileId && !it.deleted
            }
            if (usedElsewhere) return this@ProfileStore.preferences.data.first()
        }
        return preferences.editProfileMetadata { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key]).filterNot { it.identity() == profile.identity() }
        val sessions = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).filterNot { it.profile.identity() == profile.identity() }
        prefs[profilesKey] = Json.encodeToString(profiles)
        prefs[sessionsKey] = Json.encodeToString(sessions)
        if (prefs[activeProfileKey] == profile.identity()) {
            prefs.remove(activeProfileKey); prefs.remove(key); prefs.remove(sessionKey)
        }
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
    suspend fun saveFavorites(items: List<FavoriteItem>, scope: UserScope? = captureScope()) = scopedPreferences(scope).editProfileMetadata {
        it[favoritesKey] = Json.encodeToString(items)
    }
    suspend fun saveRecentlyPlayed(items: List<RecentItem>, scope: UserScope? = captureScope()) = scopedPreferences(scope).editProfileMetadata {
        it[recentKey] = Json.encodeToString(items)
    }
    suspend fun savePlaybackProgress(items: List<PlaybackProgress>, scope: UserScope? = captureScope()) = scopedPreferences(scope).editProfileMetadata {
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
                categories = cache.categories,
                categoriesCachedAtMillis = cache.categoriesCachedAtMillis
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
    suspend fun setPlayerControlsTimeoutSeconds(seconds: Int) {
        val owner = captureScope()
        val value = normalizeControlsTimeout(seconds)
        if (owner == null) preferences.edit { it[playerControlsTimeoutKey] = value }
        else withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(UserProfilePreferences.get(context, "niktv_playback_ui", owner = owner)
                .edit().putInt("controls_timeout", value).commit())
        }
    }
    suspend fun setKeepAwakeOnlyDuringPlayback(enabled: Boolean) = preferences.edit {
        it[keepAwakeOnlyDuringPlaybackKey] = if (enabled) 1 else 0
    }
    val offlineDownloads: Flow<List<OfflineMediaDownload>> = this@ProfileStore.preferences.data.decodedProfilePreference(select = { it[offlineDownloadsKey] }, decode = { raw ->
        raw
            ?.let { runCatching { Json.decodeFromString<List<OfflineMediaDownload>>(it) }.getOrNull() }
            .orEmpty()
    })
    suspend fun setAutomaticReauthentication(enabled: Boolean) = preferences.edit {
        it[automaticReauthenticationKey] = if (enabled) 1 else 0
    }

    suspend fun setPlaybackEngine(engine: PlaybackEngine) = preferences.edit {
        it[playbackEngineKey] = if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3.name else engine.name
    }
    suspend fun setSeriesStartSeason(value: SeriesStartSeason) = preferences.edit { it[seriesStartSeasonKey] = value.name }
    suspend fun saveWatchedSeries(items: List<WatchedSeries>, scope: UserScope? = captureScope()) = scopedPreferences(scope).editProfileMetadata {
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

    private suspend fun backupSnapshot(now: Long, metadata: DeviceBackupMetadata? = null): NikTvBackup {
        val values = this@ProfileStore.preferences.data.first().asMap()
        val strings = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_STRING_KEYS && value is String) key.name to value else null
        }.toMap().toSortedMap()
        val integers = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_INT_KEYS && value is Int) key.name to value else null
        }.toMap().toSortedMap()
        val connection = GitHubBackupManager(context).loadConfig()
        return NikTvBackup(exportedAtMillis = now, strings = users.portableBackupValues(captureScope(), strings), integers = integers,
            devicePreferences = DevicePreferencesBackup.capture(context),
            remoteConfiguration = RemoteCredentials.exportDeviceConfiguration(context)?.let {
                it.copy(token = "", values = it.values.filterKeys { name -> name != "G_TOKEN" })
            },
            backupConnection = BackupConnectionConfiguration(connection.username, connection.repository, "",
                "", connection.backupMode.name, connection.autoBackupIntervalHours),
            backupMetadata = metadata,
            userRecords = users.selectedAccount.value?.let { users.snapshot(it) }.orEmpty(),
            userLocalPreferences = users.selectedAccount.value?.let { users.backupLocalPreferences(it) }.orEmpty(),
            portalMappings = users.selectedAccount.value?.let { users.backupPortalMappings(it) }.orEmpty())
    }

    suspend fun backupFingerprint(): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val canonical = backupExportJson.encodeToString(backupSnapshot(0))
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun exportBackup(): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        backupExportJson.encodeToString(backupSnapshot(now, DeviceBackupIdentity.create(context, now)))
    }

    /** Room rolls back an interrupted transaction. Compensate the other stores before readers resume. */
    internal suspend fun recoverPendingRestore() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        users.withEditLock {
            val journal = SetupRestoreJournal(context)
            val snapshot = journal.read() ?: return@withEditLock
            val allowed = BACKUP_STRING_KEYS + BACKUP_INT_KEYS + setOf("active_session", "saved_sessions")
            require(snapshot.keys.all { name -> name in allowed || name.removePrefix("account_").let { scoped ->
                name.startsWith("account_") && scoped.substringBefore('_').toLongOrNull() != null && scoped.substringAfter('_') in allowed
            } }) { "Invalid restore recovery keys; data was not changed." }
            val committed = users.dao.preferences(SetupRestoreJournal.MARKER_ACCOUNT, "")
                .any { it.name == SetupRestoreJournal.MARKER_NAME && it.value == snapshot.id }
            if (!committed) {
                val shared = BackupPreferenceRollback.fromJournal(context, snapshot.shared)
                rawPreferences.updateData { current -> current.toMutablePreferences().apply {
                    snapshot.keys.forEach { remove(stringPreferencesKey(it)) }
                    snapshot.preferences.forEach { (name, value) -> putRestoreValue(name, value) }
                } }
                shared.restore()
            }
            journal.clear()
        }
    }

    internal var backupWriteCheckpoint: (String) -> Unit = {}

    suspend fun importBackup(content: String, scope: UserScope? = captureScope()) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val backup = backupImportJson.decodeFromString<NikTvBackup>(content)
        val importAccount = scope?.accountId ?: users.selectedAccount.value
        require(backup.formatVersion in 1..3) { "Unsupported NikTV backup version ${backup.formatVersion}" }
        if (backup.userRecords.isNotEmpty() || backup.userLocalPreferences.isNotEmpty() || backup.portalMappings.isNotEmpty()) {
            val accountId = requireNotNull(importAccount) { "Connect the backup's GitHub account before importing user data." }
            require(backup.userRecords.all { it.accountId == accountId }) { "This backup belongs to a different GitHub account." }
            require(backup.userLocalPreferences.all { it.accountId == accountId } && backup.portalMappings.all { it.accountId == accountId }) { "This backup belongs to a different GitHub account." }
        }
        backup.strings[profilesKey.name]?.let {
            require(runCatching { Json.decodeFromString<List<PortalProfile>>(it) }.getOrNull() != null) {
                "The backup contains invalid profile data"
            }
        }
        if (backup.formatVersion >= 2) {
            DevicePreferencesBackup.validate(backup.devicePreferences)
            RemoteCredentials.validateDeviceConfiguration(backup.remoteConfiguration)
            backup.backupConnection?.let {
                require(it.intervalHours in setOf(0, 6, 12, 24)) { "Invalid backup interval" }
                BackupMode.valueOf(it.mode)
            }
        }
        if (scope != null) require(users.snapshot(scope.accountId).any { it.collection == "profile" && it.recordId == scope.profileId && !it.deleted }) { "Select a user profile before assigning this backup." }
        users.validateBackup(backup.userRecords, backup.userLocalPreferences, backup.portalMappings)
        users.withEditLock {
            recoverPendingRestore()
            val originalPreferences = basePreferences.data.first()
            val deviceRollback = BackupPreferenceRollback(context, importAccount)
            val journal = SetupRestoreJournal(context)
            val restoreId = java.util.UUID.randomUUID().toString()
            val names = BACKUP_STRING_KEYS + BACKUP_INT_KEYS + setOf("active_session", "saved_sessions")
            val keys = names + names.filter { it in UserSyncStore.ACCOUNT_KEYS }.mapNotNull { name -> importAccount?.let { "account_${it}_$name" } }
            val snapshot = SetupRestoreSnapshot(restoreId, keys, originalPreferences.asMap().entries
                .filter { it.key.name in keys }.associate { it.key.name to encodeRestorePreference(it.value) }, deviceRollback.journalSnapshot())
            journal.write(snapshot)
            try { users.backupTransaction {
            val legacyBackup = backup.formatVersion < 3 || backup.userRecords.isEmpty()
            val restoredPortals = decodeProfiles(backup.strings[profilesKey.name], backup.strings[key.name])
            if (scope != null && legacyBackup) restoredPortals.forEach { users.associatePortal(scope, it) }
            // With no chosen person, old snapshot fields remain unassigned on-device until the
            // user explicitly imports them from the chooser. Never pick an arbitrary remote UUID.
            val targetPreferences = when {
                scope != null -> scopedPreferences(scope)
                legacyBackup -> basePreferences
                else -> preferences
            }
            targetPreferences.edit { prefs ->
                BACKUP_STRING_KEYS.filter { legacyBackup || it !in UserSyncStore.SCOPED_KEYS }.forEach { prefs.remove(stringPreferencesKey(it)) }
                BACKUP_INT_KEYS.forEach { prefs.remove(intPreferencesKey(it)) }
                backup.strings.filterKeys { it in BACKUP_STRING_KEYS && (legacyBackup || it !in UserSyncStore.SCOPED_KEYS) }.forEach { (name, value) ->
                    prefs[stringPreferencesKey(name)] = value
                }
                backup.integers.filterKeys { it in BACKUP_INT_KEYS }.forEach { (name, value) ->
                    prefs[intPreferencesKey(name)] = value
                }
                // Portal tokens are intentionally device-local and must be refreshed.
                prefs.remove(sessionKey)
                prefs.remove(sessionsKey)
            }
            backupWriteCheckpoint("settings")
            if (backup.formatVersion >= 2) {
                DevicePreferencesBackup.restore(context, backup.devicePreferences)
                RemoteCredentials.restoreDeviceConfiguration(context, backup.remoteConfiguration)
                backup.backupConnection?.let {
                    GitHubBackupManager(context).saveConfig(GitHubBackupConfig(username = it.username,
                        repository = it.repository, token = it.token, passphrase = it.password,
                        rememberPassphrase = it.password.isNotBlank(), backupMode = BackupMode.valueOf(it.mode),
                        autoBackupIntervalHours = it.intervalHours), reschedule = false)
                }
            }
            backupWriteCheckpoint("device")
            if (backup.formatVersion >= 3 && backup.userRecords.isNotEmpty()) {
                val accountId = requireNotNull(importAccount) { "Connect the backup's GitHub account before importing user data." }
                require(backup.userRecords.all { it.accountId == accountId }) { "This backup belongs to a different GitHub account." }
                users.restoreBackupRecords(accountId, backup.userRecords)
                users.restoreLocalBackup(accountId, backup.userLocalPreferences, backup.portalMappings)
            }
            backupWriteCheckpoint("records")
            val migrationId = "backup-import:${java.util.UUID.randomUUID()}"
            if (scope != null && legacyBackup) UserProfilePreferences.importLegacy(context, scope, migrationId = migrationId, overrideExisting = true, portalKeysOverride = restoredPortals.map { it.cacheKey() })
            if (scope == null && legacyBackup) context.getSharedPreferences("niktv_legacy_assignment", Context.MODE_PRIVATE)
                .edit().remove("assigned").putString("pending_import_id", migrationId).commit()
            users.dao.putPreference(UserLocalPreference(SetupRestoreJournal.MARKER_ACCOUNT, "", SetupRestoreJournal.MARKER_NAME, restoreId))
            } }
            catch (failure: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        recoverPendingRestore()
                        RemoteCredentials.reloadAfterRestoreRollback(context)
                    } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
            journal.clear()
        }
    }

    suspend fun hasLegacyViewingData(): Boolean {
        if (context.getSharedPreferences("niktv_legacy_assignment", Context.MODE_PRIVATE).getBoolean("assigned", false)) return false
        val source = basePreferences.data.first()
        return UserSyncStore.SCOPED_KEYS.filterNot { it in UserSyncStore.LOCAL_ONLY_KEYS }.any {
            source[stringPreferencesKey(it)]?.let { raw -> raw != "[]" && raw != "{}" && raw.isNotBlank() } == true
        } || UserProfilePreferences.hasLegacy(context)
    }

    suspend fun migrateLegacy(accountId: String, assignToFamily: Boolean): Boolean {
        val legacyAssignment = context.getSharedPreferences("niktv_legacy_assignment", Context.MODE_PRIVATE)
        val originalAccount = legacyAssignment.getString("portal_account", null)
        if (originalAccount == null || originalAccount == accountId) {
            basePreferences.edit { prefs ->
                UserSyncStore.ACCOUNT_KEYS.forEach { name ->
                    val destination = stringPreferencesKey("account_${accountId}_$name")
                    if (prefs[destination] == null) prefs[stringPreferencesKey(name)]?.let { prefs[destination] = it }
                }
            }
            legacyAssignment.edit().putString("portal_account", accountId).commit()
            val raw = basePreferences.data.first()
            decodeProfiles(raw[profilesKey], raw[key]).forEach { portal ->
                users.registerPortal(accountId, portal)
                if (assignToFamily) listOf(UserProfileType.GENERAL, UserProfileType.KIDS).forEach { type ->
                    users.associatePortal(UserScope(accountId, UserSyncStore.defaultProfileId(accountId, type)), portal)
                }
            }
        }
        if (!assignToFamily || !hasLegacyViewingData()) return false
        val family = UserSyncStore.defaultProfileId(accountId, UserProfileType.GENERAL)
        if (users.snapshot(accountId).none { it.collection == "profile" && it.recordId == family && !it.deleted }) return false
        importLegacyToProfile(UserScope(accountId, family))
        return true
    }

    suspend fun importLegacyToProfile(scope: UserScope) {
        require(users.snapshot(scope.accountId).any { it.collection == "profile" && it.recordId == scope.profileId && !it.deleted }) { "Select a user profile for the import." }
        val source = basePreferences.data.first()
        decodeProfiles(source[profilesKey], source[key]).forEach { users.associatePortal(scope, it) }
        basePreferences.edit { prefs -> UserSyncStore.ACCOUNT_KEYS.forEach { name ->
            source[stringPreferencesKey(name)]?.let { prefs[stringPreferencesKey("account_${scope.accountId}_$name")] = it }
        } }
        val assignment = context.getSharedPreferences("niktv_legacy_assignment", Context.MODE_PRIVATE)
        val pendingImport = assignment.getString("pending_import_id", null)
        users.importPreferences(scope, source, pendingImport ?: "legacy-import")
        UserProfilePreferences.importLegacy(context, scope, migrationId = pendingImport?.let { "$it:browsing" } ?: "legacy-browsing-import", overrideExisting = pendingImport != null)
        source[playerControlsTimeoutKey]?.let { timeout ->
            importPlaybackTiming(scope, mapOf("niktv_playback_ui|controls_timeout" to timingEntry(normalizeControlsTimeout(timeout))),
                "legacy-controls-timeout-import:${scope.profileId}:${pendingImport ?: "v1"}")
        }
        assignment.edit().putBoolean("assigned", true).remove("pending_import_id").commit()
    }

    /** Only the account already assigned this device's legacy data may inherit its timing.
     * Other accounts and arbitrary remote people always start with their own defaults. */
    suspend fun migrateLegacyPlaybackTiming(accountId: String): Boolean {
        val assignment = context.getSharedPreferences("niktv_legacy_assignment", Context.MODE_PRIVATE)
        if (assignment.getString("portal_account", null) != accountId) return false
        val owner = UserScope(accountId, UserSyncStore.defaultProfileId(accountId, UserProfileType.GENERAL))
        val legacy = context.getSharedPreferences("niktv_playback_ui", Context.MODE_PRIVATE).all
        val entries = linkedMapOf<String, JsonObject>()
        (basePreferences.data.first()[playerControlsTimeoutKey] ?: legacy["controls_timeout"] as? Int)?.let {
            entries["niktv_playback_ui|controls_timeout"] = timingEntry(normalizeControlsTimeout(it))
        }
        (legacy["watching_check_enabled"] as? Boolean)?.let { entries["niktv_playback_ui|watching_check_enabled"] = timingEntry(it) }
        (legacy["watching_check_minutes"] as? Int)?.let { entries["niktv_playback_ui|watching_check_minutes"] = timingEntry(it.coerceIn(15, 480)) }
        (legacy["watching_check_episodes"] as? Int)?.let { entries["niktv_playback_ui|watching_check_episodes"] = timingEntry(it.coerceIn(1, 12)) }
        (context.getSharedPreferences("sports_playback_settings", Context.MODE_PRIVATE).all["controls_timeout"] as? Int)?.let {
            entries["niktv_playback_ui|sports_controls_timeout"] = timingEntry(normalizeControlsTimeout(it))
        }
        return importPlaybackTiming(owner, entries, "legacy-playback-timing-import-v1")
    }

    private fun timingEntry(value: Int): JsonObject = buildJsonObject { put("type", "int"); put("value", value) }
    private fun timingEntry(value: Boolean): JsonObject = buildJsonObject { put("type", "boolean"); put("value", value) }
    private suspend fun importPlaybackTiming(owner: UserScope, entries: Map<String, JsonObject>, marker: String): Boolean =
        users.withEditLock { users.db.withTransaction {
            val records = users.dao.records(owner.accountId)
            if (records.none { it.collection == "profile" && it.recordId == owner.profileId && !it.deleted } ||
                records.any { it.collection == "account" && it.recordId == marker }) return@withTransaction false
            val key = stringPreferencesKey("portable_preferences")
            val raw = users.dao.preferences(owner.accountId, owner.profileId).firstOrNull { it.name == key.name }?.value
            val old = raw?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }.orEmpty()
            val next = old.toMutableMap()
            entries.forEach { (name, entry) ->
                // A retained deletion is also a remote choice; migration cannot resurrect it.
                if (name !in next && records.none { it.profileId == owner.profileId && it.collection == "preferences" && it.recordId == "portable_preferences:$name" }) {
                    next[name] = entry
                }
            }
            if (next != old) {
                val before = mutablePreferencesOf().apply { if (raw != null) this[key] = raw }
                val after = mutablePreferencesOf(key to JsonObject(next).toString())
                users.persist(owner, before, after)
            }
            users.mutate(UserScope(owner.accountId, ""), "account", marker, "{\"imported\":true}")
            true
        } }
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
        private fun normalizeControlsTimeout(value: Int): Int = if (value <= 0) 0 else value.coerceIn(1, 30)
        private val backupExportJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        private val backupImportJson = Json { ignoreUnknownKeys = true }
        private val BACKUP_STRING_KEYS = setOf(
            "active_profile", "saved_profiles", "active_profile_identity", "favorites",
            "recently_played", "playback_progress", "recent_searches", "category_filters",
            "series_start_season", "watched_series", "remembered_series_seasons", "browse_layouts",
            "tmdb_iptv_mappings", "tmdb_dashboard_sections", "playback_engine",
            "requested_recommendations_v1", "dismissed_recommendations_v1", "recommendation_progress_v1"
        )
        private val BACKUP_INT_KEYS = setOf(
            "catalog_cache_interval_minutes",
            "initial_catalog_items",
            "player_controls_timeout_seconds",
            "automatic_reauthentication",
            "modern_ui_enabled", "keep_awake_only_during_playback"
        )
        private val INITIAL_CATALOG_OPTIONS = setOf(14, 28, 42, 56)
    }
}
