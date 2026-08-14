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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

class ProfileStore(private val context: Context) {
    private val key = stringPreferencesKey("active_profile")
    private val sessionKey = stringPreferencesKey("active_session")
    private val favoritesKey = stringPreferencesKey("favorites")
    private val recentKey = stringPreferencesKey("recently_played")
    private val progressKey = stringPreferencesKey("playback_progress")
    private val playbackUrlsKey = stringPreferencesKey("playback_urls")
    private val cacheIntervalKey = intPreferencesKey("catalog_cache_interval_minutes")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val pagedSearchesKey = stringPreferencesKey("paged_search_results")
    val activeProfile: Flow<PortalProfile?> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { Json.decodeFromString<PortalProfile>(it) }.getOrNull() }
    }
    val activeSession: Flow<PortalSession?> = context.dataStore.data.map { prefs ->
        prefs[sessionKey]?.let { runCatching { Json.decodeFromString<PortalSession>(it) }.getOrNull() }
    }
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
        it[key] = Json.encodeToString(session.profile)
        it[sessionKey] = Json.encodeToString(session)
    }
    suspend fun clearSession() = context.dataStore.edit { it.remove(sessionKey) }
    fun searchCatalog(type: CatalogType): Flow<SearchCatalogCache?> {
        val cacheKey = stringPreferencesKey("search_catalog_${type.name.lowercase()}")
        return context.dataStore.data.map { prefs ->
            prefs[cacheKey]?.let { runCatching { Json.decodeFromString<SearchCatalogCache>(it) }.getOrNull() }
        }
    }
    suspend fun saveSearchCatalog(cache: SearchCatalogCache) {
        val cacheKey = stringPreferencesKey("search_catalog_${cache.type.name.lowercase()}")
        context.dataStore.edit { it[cacheKey] = Json.encodeToString(cache) }
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
    fun browseCatalog(type: CatalogType): Flow<BrowseCatalogCache?> {
        val browseKey = stringPreferencesKey("browse_catalog_${type.name.lowercase()}")
        return context.dataStore.data.map { prefs ->
            prefs[browseKey]?.let { runCatching { Json.decodeFromString<BrowseCatalogCache>(it) }.getOrNull() }
        }
    }
    suspend fun saveBrowseCatalog(cache: BrowseCatalogCache) {
        val browseKey = stringPreferencesKey("browse_catalog_${cache.type.name.lowercase()}")
        context.dataStore.edit { it[browseKey] = Json.encodeToString(cache) }
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
        CatalogType.entries.forEach { type -> it.remove(stringPreferencesKey("search_catalog_${type.name.lowercase()}")) }
        CatalogType.entries.forEach { type -> it.remove(stringPreferencesKey("browse_catalog_${type.name.lowercase()}")) }
        it.remove(favoritesKey)
        it.remove(recentKey)
        it.remove(progressKey)
        it.remove(playbackUrlsKey)
        it.remove(recentSearchesKey)
        it.remove(pagedSearchesKey)
    }
}
