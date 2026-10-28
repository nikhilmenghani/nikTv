package com.nikhil.niktv.discovery

import kotlinx.coroutines.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val LocalOpenWatchProviders = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }
@Serializable internal data class WatchProvider(val id: Int, val name: String, val logo: String? = null)
@Serializable internal data class WatchRegion(val code: String, val name: String)
@Serializable internal data class WatchProviderPage(val cards: List<DiscoveryCard>, val page: Int, val totalPages: Int)
internal data class WatchProviderRow(val cards: List<DiscoveryCard> = emptyList(), val page: Int = 0,
    val more: Boolean = true, val loading: Boolean = false, val error: String? = null)
internal data class WatchProvidersState(val region: String = "IN", val series: Boolean = false,
    val regions: List<WatchRegion> = emptyList(), val providers: List<WatchProvider> = emptyList(),
    val rows: Map<Int, WatchProviderRow> = emptyMap(), val loading: Boolean = true, val error: String? = null, val revision: Int = 0)
internal interface WatchProvidersSource {
    suspend fun regions(): List<WatchRegion>
    suspend fun providers(region: String, series: Boolean, refresh: Boolean = false): List<WatchProvider>
    suspend fun titles(region: String, series: Boolean, provider: Int, page: Int,
        stale: (WatchProviderPage) -> Unit = {}, refresh: Boolean = false): WatchProviderPage
}
internal fun watchProviderRequest(region: String, series: Boolean, provider: Int, page: Int,
    today: String = currentDiscoveryDate()): Pair<String, Map<String, String>> {
    require(region.matches(Regex("[A-Z]{2}")) && provider > 0 && page > 0)
    return "/discover/${if (series) "tv" else "movie"}" to mapOf(
        "watch_region" to region, "with_watch_providers" to provider.toString(),
        "with_watch_monetization_types" to "flatrate|free|ads", "sort_by" to "popularity.desc",
        "include_adult" to "false", "include_video" to "false", "page" to page.toString(),
        (if (series) "first_air_date.lte" else "primary_release_date.lte") to today)
}
internal fun parseWatchProviders(root: JsonObject): List<WatchProvider> = root["results"]?.jsonArray.orEmpty()
    .mapNotNull { value -> val o = value.jsonObject
        val id = o["provider_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val name = o["provider_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (id <= 0) return@mapNotNull null
        WatchProvider(id, name, o["logo_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w92$it" })
    }.distinctBy { it.id }

/** Only composed provider rows ask for metadata. Cancelled/obsolete work cannot publish across regions. */
internal class WatchProvidersModel(private val scope: CoroutineScope, private val source: WatchProvidersSource,
    region: String = "IN", private val retainedRows: Int = 12, initialSeries: Boolean = false) {
    private val mutable = MutableStateFlow(WatchProvidersState(region = region, series = initialSeries))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var refreshRows = false
    private var catalogue: Job? = null
    private val jobs = mutableMapOf<Int, Job>()
    private val access = linkedSetOf<Int>()
    private var request = 0L
    private val tokens = mutableMapOf<Int, Long>()
    fun select(region: String = state.value.region, series: Boolean = state.value.series, force: Boolean = false, refresh: Boolean = false) {
        if (!force && catalogue != null && region == state.value.region && series == state.value.series) return
        val epoch = ++generation; refreshRows = refresh
        catalogue?.cancel(); jobs.values.forEach { it.cancel() }; jobs.clear(); access.clear(); tokens.clear()
        mutable.value = WatchProvidersState(region, series, regions = state.value.regions, revision = epoch)
        catalogue = scope.launch {
            try {
                // Regions are supplementary: a failure must not prevent provider browsing.
                val available = source.providers(region, series, refresh)
                if (epoch == generation) mutable.update { it.copy(providers = available, loading = false) }
                val regions = runCatching { source.regions() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                if (epoch == generation) mutable.update { it.copy(regions = regions) }
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { if (epoch == generation) mutable.update { it.copy(loading = false, error = "Could not load providers. Retry when connected.") } }
        }
    }
    fun resume() {
        if (catalogue == null || state.value.loading) select(force = true)
    }
    fun refresh() = select(force = true, refresh = true)
    fun refreshProvider(provider: Int) {
        leave(provider)
        mutable.update { it.copy(rows = it.rows - provider) }
        loadPage(provider, false, true)
    }
    fun load(provider: Int, more: Boolean = false) = loadPage(provider, more, false)
    private fun loadPage(provider: Int, more: Boolean, refresh: Boolean) {
        if (jobs[provider]?.isActive == true) return
        val before = state.value.rows[provider] ?: WatchProviderRow()
        if (!more && before.page > 0 || more && !before.more) return
        val epoch = generation; val region = state.value.region; val series = state.value.series
        val token = ++request; tokens[provider] = token
        val page = if (more) before.page + 1 else 1
        access.remove(provider); access.add(provider)
        // Preserve visible/active rows; evict least recently visited inactive metadata only.
        while (access.size > retainedRows) {
            val old = access.firstOrNull { it != provider && jobs[it]?.isActive != true } ?: break
            access.remove(old); tokens.remove(old); mutable.update { it.copy(rows = it.rows - old) }
        }
        mutable.update { it.copy(rows = it.rows + (provider to before.copy(loading = true, error = null))) }
        fun publish(result: WatchProviderPage, loading: Boolean) {
            if (epoch != generation || tokens[provider] != token) return
            val cards = (if (page > 1) before.cards else emptyList()) + result.cards.filter { it.released() }
            mutable.update { it.copy(rows = it.rows + (provider to WatchProviderRow(cards.distinctBy { it.key },
                result.page, result.page < result.totalPages, loading))) }
        }
        jobs[provider] = scope.launch {
            try { publish(source.titles(region, series, provider, page, stale = { publish(it, true) }, refresh = refresh || refreshRows), false) }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (epoch == generation && tokens[provider] == token) mutable.update {
                val current = it.rows[provider] ?: before
                it.copy(rows = it.rows + (provider to current.copy(loading = false, error = "Could not load titles")))
            } }
        }
    }
    fun leave(provider: Int) {
        tokens.remove(provider)
        jobs.remove(provider)?.cancel()
        mutable.update { val row = it.rows[provider]; if (row == null) it else it.copy(rows = it.rows + (provider to row.copy(loading = false))) }
    }
    fun stop() {
        generation++; catalogue?.cancel(); jobs.values.forEach { it.cancel() }; jobs.clear(); tokens.clear()
        mutable.update { it.copy(rows = it.rows.mapValues { (_, row) -> row.copy(loading = false) }) }
    }
}

/** Retain loaded pages across details/playback; visible UI still owns cancellation. */
internal class WatchProvidersViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("watch_providers", 0)
    val model = WatchProvidersModel(viewModelScope, DiscoveryRepository(app),
        preferences.getString("region", "IN") ?: "IN", initialSeries = preferences.getBoolean("series", false))
    override fun onCleared() { model.stop() }
}
