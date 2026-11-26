package com.nikhil.niktv.torrent

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.UserProfilePreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.discovery.GenreMarket
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.TmdbHomeSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal data class TorrentDiscoveryConfiguration(
    val sections: List<TmdbHomeSection>, val genres: Boolean = true, val providers: Boolean = true,
    val genreMarkets: Set<GenreMarket> = GenreMarket.entries.toSet()
)

/** Torrent discovery choices are portable per viewer and never reuse IPTV's dashboard keys. */
internal object TorrentDiscoveryPreferences {
    private const val DOMAIN = "torrent_discovery_v1"
    private fun allowed(surface: DashboardSurface): List<TmdbHomeSection> = when (surface) {
        DashboardSurface.HOME -> TmdbHomeSection.entries
        DashboardSurface.MOVIES -> TmdbHomeSection.entries.filterNot(TmdbHomeSection::series)
        DashboardSurface.SERIES -> TmdbHomeSection.entries.filter(TmdbHomeSection::series)
        else -> throw IllegalArgumentException("Choose Home, Movies or Series.")
    }
    fun defaults(surface: DashboardSurface): TorrentDiscoveryConfiguration {
        val available = allowed(surface).toSet()
        return TorrentDiscoveryConfiguration(HOME_SECTIONS.filter { it in available })
    }
    fun read(context: Context, owner: UserScope?, surface: DashboardSurface): TorrentDiscoveryConfiguration {
        val fallback = defaults(surface)
        if (owner == null) return fallback
        val preferences = UserProfilePreferences.get(context, DOMAIN, owner = owner)
        val sections = preferences.getString("${surface.name}:sections", null)?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let { runCatching { TmdbHomeSection.valueOf(it) }.getOrNull() }
            } }.getOrNull()
        } ?: fallback.sections
        val available = allowed(surface).toSet()
        val markets = preferences.getString("${surface.name}:markets", null)?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let { runCatching { GenreMarket.valueOf(it) }.getOrNull() }
            }.toSet() }.getOrNull()
        } ?: fallback.genreMarkets
        return TorrentDiscoveryConfiguration(sections.filter { it in available }.distinct(),
            preferences.getBoolean("${surface.name}:genres", fallback.genres),
            preferences.getBoolean("${surface.name}:providers", fallback.providers), genreMarkets = markets)
    }
    fun observe(context: Context, owner: UserScope?, surface: DashboardSurface): Flow<TorrentDiscoveryConfiguration> {
        if (owner == null) return flowOf(defaults(surface))
        val app = context.applicationContext
        return callbackFlow {
            UserProfilePreferences.warm(app, owner)
            val preferences = UserProfilePreferences.get(app, DOMAIN, owner = owner)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key?.startsWith("${surface.name}:") == true) trySend(Unit)
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate().map { read(app, owner, surface) }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }
    suspend fun save(context: Context, owner: UserScope, surface: DashboardSurface, configuration: TorrentDiscoveryConfiguration) = withContext(Dispatchers.IO) {
        val available = allowed(surface).toSet()
        UserProfilePreferences.warm(context, owner)
        val sections = configuration.sections.filter { it in available }.distinct()
        check(UserProfilePreferences.get(context, DOMAIN, owner = owner).edit()
            .putString("${surface.name}:sections", JsonArray(sections.map { JsonPrimitive(it.name) }).toString())
            .putString("${surface.name}:markets", JsonArray(GenreMarket.entries.filter { it in configuration.genreMarkets }
                .map { JsonPrimitive(it.name) }).toString())
            .putBoolean("${surface.name}:genres", configuration.genres)
            .putBoolean("${surface.name}:providers", configuration.providers).commit())
        AccountSyncManager.get(context).requestProfileSync(owner.accountId)
    }
}
