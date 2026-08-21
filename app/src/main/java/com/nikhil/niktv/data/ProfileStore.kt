package com.nikhil.niktv.data

import android.content.Context
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
import com.nikhil.niktv.model.PlaybackUrl
import com.nikhil.niktv.model.RecentSearch
import com.nikhil.niktv.model.SearchResultCache
import com.nikhil.niktv.model.SeriesStartSeason
import com.nikhil.niktv.model.WatchedSeries
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.BrowseLayout
import com.nikhil.niktv.model.canonicalSearchQuery
import com.nikhil.niktv.model.deduplicatedRecentSearches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

@Serializable
private data class NikTvBackup(
    val formatVersion: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val strings: Map<String, String>,
    val integers: Map<String, Int>
)

class ProfileStore(private val context: Context) {
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
    private val playerControlsTimeoutKey = intPreferencesKey("player_controls_timeout_seconds")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val pagedSearchesKey = stringPreferencesKey("paged_search_results")
    private val categoryFiltersKey = stringPreferencesKey("category_filters")
    private val seriesStartSeasonKey = stringPreferencesKey("series_start_season")
    private val watchedSeriesKey = stringPreferencesKey("watched_series")
    private val rememberedSeriesSeasonsKey = stringPreferencesKey("remembered_series_seasons")
    private val episodeSeasonCachesKey = stringPreferencesKey("episode_season_caches")
    private val browseLayoutsKey = stringPreferencesKey("browse_layouts")
    val activeProfile: Flow<PortalProfile?> = context.dataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key])
        val identity = prefs[activeProfileKey]
        profiles.firstOrNull { it.identity() == identity } ?: profiles.firstOrNull()
    }
    val activeSession: Flow<PortalSession?> = context.dataStore.data.map { prefs ->
        val active = prefs[activeProfileKey]
        decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == active }
            ?: prefs[sessionKey]?.let { runCatching { Json.decodeFromString<PortalSession>(it) }.getOrNull() }
    }
    val profiles: Flow<List<PortalProfile>> = context.dataStore.data.map { decodeProfiles(it[profilesKey], it[key]) }
    val favorites: Flow<List<FavoriteItem>> = context.dataStore.data.map { prefs ->
        prefs[favoritesKey]?.let { runCatching { Json.decodeFromString<List<FavoriteItem>>(it) }.getOrNull() }.orEmpty()
    }
    val recentlyPlayed: Flow<List<RecentItem>> = context.dataStore.data.map { prefs ->
        val stored = prefs[recentKey]?.let { runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull() }.orEmpty()
        stored.filterNot { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE }.map { recent ->
            if (recent.kind != com.nikhil.niktv.model.FavoriteKind.SERIES || recent.lastPlayed != null) recent
            else stored.firstOrNull { it.kind == com.nikhil.niktv.model.FavoriteKind.EPISODE && it.series?.id == recent.media.id }
                ?.let { recent.copy(lastPlayed = it.media, playedAtMillis = maxOf(recent.playedAtMillis, it.playedAtMillis)) }
                ?: recent
        }
    }
    val playbackProgress: Flow<List<PlaybackProgress>> = context.dataStore.data.map { prefs ->
        prefs[progressKey]?.let { runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull() }.orEmpty()
    }
    val playbackUrls: Flow<List<PlaybackUrl>> = context.dataStore.data.map { prefs ->
        prefs[playbackUrlsKey]?.let { runCatching { Json.decodeFromString<List<PlaybackUrl>>(it) }.getOrNull() }.orEmpty()
    }
    val cacheIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[cacheIntervalKey] ?: 60 }
    val playerControlsTimeoutSeconds: Flow<Int> = context.dataStore.data.map { it[playerControlsTimeoutKey] ?: 3 }
    val recentSearches: Flow<List<RecentSearch>> = context.dataStore.data.map { prefs ->
        decodeRecentSearches(prefs[recentSearchesKey]).deduplicatedRecentSearches()
    }
    val pagedSearches: Flow<List<SearchResultCache>> = context.dataStore.data.map { prefs ->
        prefs[pagedSearchesKey]?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
    }
    val categoryFilters: Flow<Map<String, List<String>>> = context.dataStore.data.map { prefs ->
        prefs[categoryFiltersKey]?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
    }
    val seriesStartSeason: Flow<SeriesStartSeason> = context.dataStore.data.map { prefs ->
        prefs[seriesStartSeasonKey]?.let { runCatching { SeriesStartSeason.valueOf(it) }.getOrNull() }
            ?: SeriesStartSeason.FIRST
    }
    val watchedSeries: Flow<List<WatchedSeries>> = context.dataStore.data.map { prefs ->
        prefs[watchedSeriesKey]?.let { runCatching { Json.decodeFromString<List<WatchedSeries>>(it) }.getOrNull() }.orEmpty()
    }
    val rememberedSeriesSeasons: Flow<Map<String, Int>> = context.dataStore.data.map { prefs ->
        prefs[rememberedSeriesSeasonsKey]?.let { runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
    }
    val episodeSeasonCaches: Flow<List<EpisodeSeasonCache>> = context.dataStore.data.map { prefs ->
        prefs[episodeSeasonCachesKey]?.let { runCatching { Json.decodeFromString<List<EpisodeSeasonCache>>(it) }.getOrNull() }.orEmpty()
    }
    val browseLayouts: Flow<Map<String, BrowseLayout>> = context.dataStore.data.map { prefs ->
        prefs[browseLayoutsKey]?.let { runCatching { Json.decodeFromString<Map<String, BrowseLayout>>(it) }.getOrNull() }.orEmpty()
    }
    suspend fun save(session: PortalSession) = context.dataStore.edit {
        val profiles = decodeProfiles(it[profilesKey], it[key])
        val sessions = decodeSessions(it[sessionsKey], it[sessionKey])
        it[profilesKey] = Json.encodeToString((profiles.filterNot { saved -> saved.identity() == session.profile.identity() } + session.profile))
        it[sessionsKey] = Json.encodeToString((sessions.filterNot { saved -> saved.profile.identity() == session.profile.identity() } + session))
        it[activeProfileKey] = session.profile.identity()
        it[key] = Json.encodeToString(session.profile)
        it[sessionKey] = Json.encodeToString(session)
    }
    suspend fun activate(profile: PortalProfile) = context.dataStore.edit { prefs ->
        prefs[activeProfileKey] = profile.identity()
        prefs[key] = Json.encodeToString(profile)
        val session = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
        if (session != null) prefs[sessionKey] = Json.encodeToString(session) else prefs.remove(sessionKey)
    }
    suspend fun sessionFor(profile: PortalProfile): PortalSession? = context.dataStore.data.first().let { prefs ->
        decodeSessions(prefs[sessionsKey], prefs[sessionKey]).firstOrNull { it.profile.identity() == profile.identity() }
    }
    suspend fun removeProfile(profile: PortalProfile) = context.dataStore.edit { prefs ->
        val profiles = decodeProfiles(prefs[profilesKey], prefs[key]).filterNot { it.identity() == profile.identity() }
        val sessions = decodeSessions(prefs[sessionsKey], prefs[sessionKey]).filterNot { it.profile.identity() == profile.identity() }
        prefs[profilesKey] = Json.encodeToString(profiles)
        prefs[sessionsKey] = Json.encodeToString(sessions)
        if (prefs[activeProfileKey] == profile.identity()) {
            prefs.remove(activeProfileKey); prefs.remove(key); prefs.remove(sessionKey)
        }
    }
    suspend fun clearSession() = context.dataStore.edit { it.remove(sessionKey) }
    fun searchCatalog(type: CatalogType, profileKey: String? = null): Flow<SearchCatalogCache?> {
        val cacheKey = stringPreferencesKey("search_catalog_${type.name.lowercase()}")
        return context.dataStore.data.map { prefs ->
            val raw = prefs[cacheKey] ?: return@map null
            val caches = runCatching { Json.decodeFromString<List<SearchCatalogCache>>(raw) }.getOrNull()
                ?: runCatching { listOf(Json.decodeFromString<SearchCatalogCache>(raw)) }.getOrDefault(emptyList())
            caches.firstOrNull { profileKey == null || it.profileKey == profileKey }
        }
    }
    suspend fun saveSearchCatalog(cache: SearchCatalogCache) {
        val cacheKey = stringPreferencesKey("search_catalog_${cache.type.name.lowercase()}")
        context.dataStore.edit { prefs ->
            val existing = prefs[cacheKey]?.let { raw -> runCatching { Json.decodeFromString<List<SearchCatalogCache>>(raw) }.getOrNull() }.orEmpty()
            prefs[cacheKey] = Json.encodeToString(listOf(cache) + existing.filterNot { it.profileKey == cache.profileKey })
        }
    }
    suspend fun saveFavorites(items: List<FavoriteItem>) = context.dataStore.edit {
        it[favoritesKey] = Json.encodeToString(items)
    }
    suspend fun saveRecentlyPlayed(items: List<RecentItem>) = context.dataStore.edit {
        it[recentKey] = Json.encodeToString(items)
    }
    suspend fun savePlaybackProgress(items: List<PlaybackProgress>) = context.dataStore.edit {
        it[progressKey] = Json.encodeToString(items)
    }
    suspend fun savePlaybackUrls(items: List<PlaybackUrl>) = context.dataStore.edit {
        it[playbackUrlsKey] = Json.encodeToString(items)
    }
    fun browseCatalog(type: CatalogType, profileKey: String? = null): Flow<BrowseCatalogCache?> {
        val browseKey = stringPreferencesKey("browse_catalog_${type.name.lowercase()}")
        return context.dataStore.data.map { prefs ->
            val raw = prefs[browseKey] ?: return@map null
            val caches = runCatching { Json.decodeFromString<List<BrowseCatalogCache>>(raw) }.getOrNull()
                ?: runCatching { listOf(Json.decodeFromString<BrowseCatalogCache>(raw)) }.getOrDefault(emptyList())
            caches.firstOrNull { profileKey == null || it.profileKey == profileKey }
        }
    }
    suspend fun saveBrowseCatalog(cache: BrowseCatalogCache) {
        val browseKey = stringPreferencesKey("browse_catalog_${cache.type.name.lowercase()}")
        context.dataStore.edit { prefs ->
            val existing = prefs[browseKey]?.let { raw -> runCatching { Json.decodeFromString<List<BrowseCatalogCache>>(raw) }.getOrNull() }.orEmpty()
            prefs[browseKey] = Json.encodeToString(listOf(cache) + existing.filterNot { it.profileKey == cache.profileKey })
        }
    }
    suspend fun setCacheIntervalMinutes(minutes: Int) = context.dataStore.edit { it[cacheIntervalKey] = minutes }
    suspend fun setPlayerControlsTimeoutSeconds(seconds: Int) = context.dataStore.edit {
        it[playerControlsTimeoutKey] = seconds.coerceIn(1, 30)
    }
    suspend fun setSeriesStartSeason(value: SeriesStartSeason) = context.dataStore.edit { it[seriesStartSeasonKey] = value.name }
    suspend fun saveWatchedSeries(items: List<WatchedSeries>) = context.dataStore.edit {
        it[watchedSeriesKey] = Json.encodeToString(items)
    }
    suspend fun rememberSeriesSeason(profileKey: String, seriesId: String, season: Int) = context.dataStore.edit { prefs ->
        val current = prefs[rememberedSeriesSeasonsKey]?.let { runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
        prefs[rememberedSeriesSeasonsKey] = Json.encodeToString(current + ("$profileKey|$seriesId" to season))
    }
    suspend fun saveEpisodeSeasonCache(cache: EpisodeSeasonCache) = context.dataStore.edit { prefs ->
        val current = prefs[episodeSeasonCachesKey]?.let { runCatching { Json.decodeFromString<List<EpisodeSeasonCache>>(it) }.getOrNull() }.orEmpty()
        prefs[episodeSeasonCachesKey] = Json.encodeToString((listOf(cache) + current.filterNot { it.key == cache.key }).take(40))
    }
    suspend fun setBrowseLayout(profileKey: String, layout: BrowseLayout) = context.dataStore.edit { prefs ->
        val current = prefs[browseLayoutsKey]?.let { runCatching { Json.decodeFromString<Map<String, BrowseLayout>>(it) }.getOrNull() }.orEmpty()
        prefs[browseLayoutsKey] = Json.encodeToString(current + (profileKey to layout))
    }
    suspend fun addRecentSearch(search: RecentSearch) = context.dataStore.edit { prefs ->
        val entry = search.copy(query = search.query.canonicalSearchQuery())
        val current = decodeRecentSearches(prefs[recentSearchesKey])
        prefs[recentSearchesKey] = Json.encodeToString(
            (listOf(entry) + current.filterNot { it.key == entry.key }).deduplicatedRecentSearches()
        )
    }
    suspend fun removeRecentSearch(search: RecentSearch) = context.dataStore.edit { prefs ->
        val current = decodeRecentSearches(prefs[recentSearchesKey])
        prefs[recentSearchesKey] = Json.encodeToString(
            current.filterNot { it.key == search.key }.deduplicatedRecentSearches()
        )
    }
    suspend fun savePagedSearch(cache: SearchResultCache) = context.dataStore.edit { prefs ->
        val current = prefs[pagedSearchesKey]?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
        prefs[pagedSearchesKey] = Json.encodeToString((listOf(cache) + current.filterNot { it.key == cache.key }).take(40))
    }
    suspend fun saveCategoryFilter(profileKey: String, type: CatalogType, categoryIds: List<String>) = context.dataStore.edit { prefs ->
        val existing = prefs[categoryFiltersKey]?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
        val filterKey = "$profileKey|${type.name}"
        prefs[categoryFiltersKey] = Json.encodeToString(existing + (filterKey to categoryIds))
    }
    suspend fun clearCategoryFilter(profileKey: String, type: CatalogType) = context.dataStore.edit { prefs ->
        val existing = prefs[categoryFiltersKey]?.let { runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
        val filterKey = "$profileKey|${type.name}"
        prefs[categoryFiltersKey] = Json.encodeToString(existing - filterKey)
    }
    suspend fun exportBackup(): String {
        val values = context.dataStore.data.first().asMap()
        val strings = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_STRING_KEYS && value is String) key.name to value else null
        }.toMap()
        val integers = values.mapNotNull { (key, value) ->
            if (key.name in BACKUP_INT_KEYS && value is Int) key.name to value else null
        }.toMap()
        return Json { prettyPrint = true; encodeDefaults = true }.encodeToString(
            NikTvBackup(strings = strings, integers = integers)
        )
    }

    suspend fun importBackup(content: String) {
        val backup = Json { ignoreUnknownKeys = true }.decodeFromString<NikTvBackup>(content)
        require(backup.formatVersion == 1) { "Unsupported NikTV backup version ${backup.formatVersion}" }
        backup.strings[profilesKey.name]?.let {
            require(runCatching { Json.decodeFromString<List<PortalProfile>>(it) }.getOrNull() != null) {
                "The backup contains invalid profile data"
            }
        }
        context.dataStore.edit { prefs ->
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
    suspend fun clear() = context.dataStore.edit {
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
        private val BACKUP_STRING_KEYS = setOf(
            "active_profile", "saved_profiles", "active_profile_identity", "favorites",
            "recently_played", "playback_progress", "recent_searches", "category_filters",
            "series_start_season", "watched_series", "remembered_series_seasons", "browse_layouts"
        )
        private val BACKUP_INT_KEYS = setOf("catalog_cache_interval_minutes", "player_controls_timeout_seconds")
    }
}
