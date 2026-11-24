package com.nikhil.niktv.discovery

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.niktv.model.DashboardSurface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal enum class GenreMarket(val label: String, val language: String, val country: String) {
    BOLLYWOOD("Bollywood", "hi", "IN"),
    HOLLYWOOD("Hollywood", "en", "US");

    val heading get() = "$label Genres"
}

internal data class GenreBrowserDestination(val market: GenreMarket?, val genre: WatchProvider?)

internal fun genreTitleRequest(series: Boolean, genre: Int, page: Int, wallpaper: Boolean = false,
    today: String = currentDiscoveryDate(), market: GenreMarket? = null): Pair<String, Map<String, String>> {
    require(genre > 0 && page in 1..500)
    return "/discover/${if (series) "tv" else "movie"}" to buildMap {
        put("with_genres", genre.toString()); put("page", page.toString())
        put("include_adult", "false"); put("include_video", "false")
        put("sort_by", if (wallpaper) "vote_count.desc" else "popularity.desc")
        put(if (series) "first_air_date.lte" else "primary_release_date.lte", today)
        if (market != null) {
            put("with_original_language", market.language)
            put("with_origin_country", market.country)
        } else if (wallpaper) {
            put("vote_count.gte", if (series) "200" else "800")
            if (!series) { put("with_original_language", "en"); put("region", "US") }
        }
    }
}

/** Prefer a representative title where this is the primary genre, then the best
 * available match. No additional per-title detail requests are needed. */
internal fun genrePoster(root: JsonObject, genre: Int): String? = root["results"]?.jsonArray.orEmpty()
    .mapNotNull { it as? JsonObject }
    .filter { it["adult"]?.jsonPrimitive?.booleanOrNull != true &&
        !it["poster_path"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
    .minByOrNull { card -> card["genre_ids"]?.jsonArray.orEmpty().indexOfFirst { it.jsonPrimitive.intOrNull == genre }
        .takeIf { it >= 0 } ?: Int.MAX_VALUE }
    ?.get("poster_path")?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" }

internal class PopularGenresViewModel @JvmOverloads constructor(app: Application,
    market: GenreMarket? = null, initialSeries: Boolean = false) : AndroidViewModel(app) {
    private val repository = DiscoveryRepository(app)
    private val source = object : WatchProvidersSource {
        override suspend fun regions() = emptyList<WatchRegion>()
        override suspend fun providers(region: String, series: Boolean, refresh: Boolean) = repository.genres(series, refresh)
        override suspend fun titles(region: String, series: Boolean, provider: Int, page: Int,
            stale: (WatchProviderPage) -> Unit, refresh: Boolean) = repository.genreTitles(series, provider, page, stale, refresh, market)
    }
    val model = WatchProvidersModel(viewModelScope, source, initialSeries = initialSeries, catalogueLabel = "genres")
    private val mutableArtwork = MutableStateFlow<Map<GenreArtworkKey, String?>>(emptyMap())
    val artwork = mutableArtwork.asStateFlow()
    private val jobs = mutableMapOf<GenreArtworkKey, Job>()
    fun wallpaper(key: GenreArtworkKey) {
        if (key in mutableArtwork.value || jobs[key]?.isActive == true) return
        jobs[key] = viewModelScope.launch {
            try { val image = repository.genreWallpaper(key.series, key.genre, key.market); mutableArtwork.update { it + (key to image) } }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { mutableArtwork.update { it + (key to null) } }
        }
    }
    fun stop() { model.stop(); jobs.values.forEach { it.cancel() }; jobs.clear() }
    override fun onCleared() { stop() }
}

internal data class GenreArtworkKey(val series: Boolean, val genre: Int, val market: GenreMarket?)

@Composable
internal fun homePopularGenres(profile: String, surface: DashboardSurface, enabled: Boolean): PopularGenresViewModel? {
    if (!enabled) return null
    val holder: PopularGenresViewModel = viewModel(key = "home-genres:${com.nikhil.niktv.ui.LocalViewingOwner.current}:$profile:$surface")
    LaunchedEffect(holder, surface) { holder.model.select(series = surface == DashboardSurface.SERIES); holder.model.resume() }
    DisposableEffect(holder) { onDispose { holder.stop() } }
    return holder
}

