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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

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
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val pagedSearchesKey = stringPreferencesKey("paged_search_results")
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
        prefs[recentKey]?.let { runCatching { Json.decodeFromString<List<RecentItem>>(it) }.getOrNull() }.orEmpty()
    }
    val playbackProgress: Flow<List<PlaybackProgress>> = context.dataStore.data.map { prefs ->
        prefs[progressKey]?.let { runCatching { Json.decodeFromString<List<PlaybackProgress>>(it) }.getOrNull() }.orEmpty()
    }
    val playbackUrls: Flow<List<PlaybackUrl>> = context.dataStore.data.map { prefs ->
        prefs[playbackUrlsKey]?.let { runCatching { Json.decodeFromString<List<PlaybackUrl>>(it) }.getOrNull() }.orEmpty()
    }
    val cacheIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[cacheIntervalKey] ?: 60 }
    val recentSearches: Flow<List<RecentSearch>> = context.dataStore.data.map { prefs ->
        prefs[recentSearchesKey]?.let { runCatching { Json.decodeFromString<List<RecentSearch>>(it) }.getOrNull() }.orEmpty()
    }
    val pagedSearches: Flow<List<SearchResultCache>> = context.dataStore.data.map { prefs ->
        prefs[pagedSearchesKey]?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
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
    suspend fun saveRecentSearches(items: List<RecentSearch>) = context.dataStore.edit {
        it[recentSearchesKey] = Json.encodeToString(items)
    }
    suspend fun savePagedSearch(cache: SearchResultCache) = context.dataStore.edit { prefs ->
        val current = prefs[pagedSearchesKey]?.let { runCatching { Json.decodeFromString<List<SearchResultCache>>(it) }.getOrNull() }.orEmpty()
        prefs[pagedSearchesKey] = Json.encodeToString((listOf(cache) + current.filterNot { it.key == cache.key }).take(40))
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
    }

    private fun decodeProfiles(raw: String?, legacy: String?): List<PortalProfile> =
        raw?.let { runCatching { Json.decodeFromString<List<PortalProfile>>(it) }.getOrNull() }
            ?: legacy?.let { runCatching { listOf(Json.decodeFromString<PortalProfile>(it)) }.getOrNull() }.orEmpty()
    private fun decodeSessions(raw: String?, legacy: String?): List<PortalSession> =
        raw?.let { runCatching { Json.decodeFromString<List<PortalSession>>(it) }.getOrNull() }
            ?: legacy?.let { runCatching { listOf(Json.decodeFromString<PortalSession>(it)) }.getOrNull() }.orEmpty()
    private fun PortalProfile.identity() = "$portalType|${portalUrl.trimEnd('/').lowercase()}|${username.ifBlank { macAddress }.lowercase()}"
}
