package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.model.regionalLanguage
import java.io.IOException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

@kotlinx.serialization.Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseDate: String?,
    val voteAverage: Double?,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val adult: Boolean = false
) {
    val releaseYear: Int?
        get() = releaseDate?.take(4)?.toIntOrNull()

    fun asMediaItem(): MediaItem = MediaItem(
        id = "tmdb-movie:$id",
        title = title,
        logo = posterUrl ?: backdropUrl,
        command = null,
        description = overview,
        cast = cast
    )
}

internal fun TmdbMovie.isReleased(today: String = currentTmdbDate()): Boolean =
    releaseDate.isNullOrBlank() || releaseDate <= today

@kotlinx.serialization.Serializable
data class TmdbSeries(
    val id: Int,
    val name: String,
    val originalName: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val firstAirDate: String?,
    val voteAverage: Double?,
    val cast: List<String> = emptyList(),
    val alternativeNames: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val adult: Boolean = false
) {
    val firstAirYear: Int?
        get() = firstAirDate?.take(4)?.toIntOrNull()

    fun asMediaItem(): MediaItem = MediaItem(
        id = "tmdb-series:$id",
        title = name,
        logo = posterUrl ?: backdropUrl,
        command = null,
        description = overview,
        cast = cast
    )
}

internal fun TmdbSeries.isReleased(today: String = currentTmdbDate()): Boolean =
    firstAirDate.isNullOrBlank() || firstAirDate <= today

private fun currentTmdbDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

data class TrendingMovie(
    val tmdb: TmdbMovie,
    val iptv: MediaItem? = null
)

data class TrendingSeries(
    val tmdb: TmdbSeries,
    val iptv: MediaItem? = null
)

data class TmdbEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String?,
    val overview: String?,
    val airDate: String?,
    val stillUrl: String?
)

class TmdbClient {
    /** Public TMDB metadata for the isolated discovery trial; never resolves IPTV media. */
    internal suspend fun discoveryJson(path: String, query: Map<String, String> = emptyMap()): kotlinx.serialization.json.JsonObject {
        check(configured) { "TMDB is not configured" }
        require(path.startsWith("/") && !path.contains(".."))
        var failure: IOException? = null
        for (base in API_BASE_URLS) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val url = "$base/3$path".toHttpUrl().newBuilder().addQueryParameter("language", "en-US")
            query.forEach { (name, value) -> url.addQueryParameter(name, value) }
            if (readAccessToken.isBlank()) url.addQueryParameter("api_key", apiKey)
            val request = Request.Builder().url(url.build()).header("Accept", "application/json")
                .apply { if (readAccessToken.isNotBlank()) header("Authorization", "Bearer $readAccessToken") }.build()
            try {
                return com.nikhil.niktv.discovery.discoveryHttp(http, request, json)
            } catch (error: IOException) { failure = error }
        }
        throw failure ?: IOException("TMDB could not be reached")
    }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val readAccessToken: String
        get() = RemoteCredentials.get("NIKTV_TMDB_READ_ACCESS_TOKEN")

    private val apiKey: String
        get() = RemoteCredentials.get("NIKTV_TMDB_API_KEY")

    @Volatile
    private var trendingMovieCache: CachedMovies? = null

    @Volatile
    private var trendingSeriesCache: CachedSeries? = null

    @Volatile
    private var thrillerMovieCache: CachedMovies? = null

    val configured: Boolean
        get() = readAccessToken.isNotBlank() || apiKey.isNotBlank()

    suspend fun trailerKey(item: MediaItem, series: Boolean): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val id = item.externalTmdbId ?: item.id.substringAfter(if (series) "tmdb-series:" else "tmdb-movie:", "")
            .toIntOrNull() ?: if (series) confidentlyMatchSeries(item)?.id else confidentlyMatchMovie(item)?.id
        if (id == null) return@withContext null
        execute("/3/${if (series) "tv" else "movie"}/$id/videos", mapOf("include_video_language" to "en,null"))
            .get("results")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["site"]?.jsonPrimitive?.contentOrNull == "YouTube" &&
                it["type"]?.jsonPrimitive?.contentOrNull in listOf("Trailer", "Teaser") }
            .sortedWith(compareByDescending<kotlinx.serialization.json.JsonObject> {
                it["type"]?.jsonPrimitive?.contentOrNull == "Trailer"
            }.thenByDescending { it["official"]?.jsonPrimitive?.contentOrNull == "true" })
            .firstNotNullOfOrNull { it["key"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { key -> key.matches(Regex("[A-Za-z0-9_-]{11}")) } }
    }

    private val seriesAliases = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()

    suspend fun withSeriesAliases(series: TmdbSeries): TmdbSeries = withContext(Dispatchers.IO) {
        if (!configured || series.alternativeNames.isNotEmpty()) return@withContext series
        val aliases = seriesAliases[series.id] ?: run {
            // A failed lookup must remain retryable rather than being cached as no aliases.
            val root = execute("/3/tv/${series.id}/alternative_titles", emptyMap())
            val names = root["results"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            }.distinct()
            seriesAliases[series.id] = names
            names
        }
        series.copy(alternativeNames = aliases)
    }

    suspend fun artworkFor(item: MediaItem, type: com.nikhil.niktv.model.CatalogType): String? =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext null
            val movie = type == com.nikhil.niktv.model.CatalogType.MOVIES
            val series = type == com.nikhil.niktv.model.CatalogType.SERIES
            if (!movie && !series) return@withContext null

            item.externalTmdbId?.let { tmdbId ->
                val root = execute(if (movie) "/3/movie/$tmdbId" else "/3/tv/$tmdbId", emptyMap())
                val poster = root["poster_path"]?.jsonPrimitive?.contentOrNull
                val backdrop = root["backdrop_path"]?.jsonPrimitive?.contentOrNull
                return@withContext poster?.let { "$IMAGE_BASE_URL$it" }
                    ?: backdrop?.let { "$BACKDROP_BASE_URL$it" }
            }

            val queryTitle = item.title.tmdbLookupTitle()
            if (queryTitle.isBlank()) return@withContext null
            val results = if (movie) {
                fetchMovies("/3/search/movie", 8, mapOf("query" to queryTitle))
                    .map { it.title to (it.posterUrl ?: it.backdropUrl) }
            } else {
                fetchSeries("/3/search/tv", 8, mapOf("query" to queryTitle))
                    .map { it.name to (it.posterUrl ?: it.backdropUrl) }
            }
            results.firstOrNull { (title, url) ->
                url != null && title.tmdbLookupTitle() == queryTitle
            }?.second
        }

    suspend fun trendingMovies(
        limit: Int = 10,
        forceRefresh: Boolean = false
    ): List<TmdbMovie> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()

        val cached = trendingMovieCache
        if (
            !forceRefresh &&
            cached != null &&
            !cached.expired() &&
            cached.items.size >= limit.coerceAtLeast(1)
        ) {
            return@withContext cached.items.take(limit.coerceAtLeast(1))
        }

        val movies = fetchMovies(
            path = "/3/trending/movie/day",
            limit = limit
        )
        trendingMovieCache = CachedMovies(System.currentTimeMillis(), movies)
        movies
    }

    suspend fun trendingSeries(
        limit: Int = 10,
        forceRefresh: Boolean = false
    ): List<TmdbSeries> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()

        val cached = trendingSeriesCache
        if (
            !forceRefresh &&
            cached != null &&
            !cached.expired() &&
            cached.items.size >= limit.coerceAtLeast(1)
        ) {
            return@withContext cached.items.take(limit.coerceAtLeast(1))
        }

        val series = fetchSeries(
            path = "/3/trending/tv/day",
            limit = limit
        )
        trendingSeriesCache = CachedSeries(System.currentTimeMillis(), series)
        series
    }

    suspend fun thrillerMovies(
        limit: Int = 10,
        forceRefresh: Boolean = false
    ): List<TmdbMovie> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()

        val cached = thrillerMovieCache
        if (
            !forceRefresh &&
            cached != null &&
            !cached.expired() &&
            cached.items.size >= limit.coerceAtLeast(1)
        ) {
            return@withContext cached.items.take(limit.coerceAtLeast(1))
        }

        // TMDB genre 53 = Thriller. Rank by TMDB popularity.
        val movies = fetchMovies(
            path = "/3/discover/movie",
            limit = limit,
            query = mapOf(
                "include_adult" to "false",
                "include_video" to "false",
                "sort_by" to "popularity.desc",
                "with_genres" to THRILLER_GENRE_ID
            )
        )
        thrillerMovieCache = CachedMovies(System.currentTimeMillis(), movies)
        movies
    }

    suspend fun homeMovies(section: TmdbHomeSection, limit: Int = 50): List<TmdbMovie> = withContext(Dispatchers.IO) {
        if (!configured || section.series) return@withContext emptyList()
        val (path, query) = com.nikhil.niktv.model.tmdbRegionalRequest(section, currentTmdbDate())
        fetchMovies("/3$path", limit, query, section.regionalLanguage)
    }

    suspend fun homeSeries(section: TmdbHomeSection, limit: Int = 50): List<TmdbSeries> = withContext(Dispatchers.IO) {
        if (!configured || !section.series) return@withContext emptyList()
        val (path, query) = com.nikhil.niktv.model.tmdbRegionalRequest(section, currentTmdbDate())
        fetchSeries("/3$path", limit, query, section.regionalLanguage)
    }

    suspend fun confidentlyMatchSeries(item: MediaItem): TmdbSeries? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        item.externalTmdbId?.let { id ->
            val root = execute("/3/tv/$id", emptyMap())
            return@withContext root.toTmdbSeries()
        }
        val lookupTitle = providerSeriesTmdbTitle(item.title)
        val queries = listOf(lookupTitle,
            lookupTitle.replace(Regex("(?i)\\blatest\\b"), "Latent"))
            .filter { it.isNotBlank() }.distinct()
        for (query in queries) {
            val wanted = query.tmdbLookupTitle()
            unambiguousTmdbMatch(query, fetchSeries("/3/search/tv", 20, mapOf("query" to wanted)),
                { listOf(it.name, it.originalName) }, { it.firstAirYear }, { it.id })?.let { return@withContext it }
        }
        null
    }

    suspend fun confidentlyMatchMovie(item: MediaItem): TmdbMovie? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        item.externalTmdbId?.let { id ->
            val root = execute("/3/movie/$id", emptyMap())
            return@withContext root.toTmdbMovie()
        }
        val queries = listOf(providerTmdbTitle(item.title)).filter { it.isNotBlank() }
        for (query in queries) {
            val wanted = query.tmdbLookupTitle()
            unambiguousTmdbMatch(query, fetchMovies("/3/search/movie", 20, mapOf("query" to wanted)),
                { listOf(it.title, it.originalTitle) }, { it.releaseYear }, { it.id })?.let { return@withContext it }
        }
        null
    }

    suspend fun recommendedMovies(id: Int): List<TmdbMovie> = withContext(Dispatchers.IO) {
        fetchMovies("/3/movie/$id/recommendations", 20).filter { it.isReleased() }
    }

    suspend fun recommendedSeries(id: Int): List<TmdbSeries> = withContext(Dispatchers.IO) {
        fetchSeries("/3/tv/$id/recommendations", 20).filter { it.isReleased() }
    }

    suspend fun castFor(type: com.nikhil.niktv.model.CatalogType, tmdbId: Int): List<String> =
        withContext(Dispatchers.IO) {
            if (!configured || type !in setOf(
                    com.nikhil.niktv.model.CatalogType.MOVIES,
                    com.nikhil.niktv.model.CatalogType.SERIES
                )
            ) return@withContext emptyList()
            val path = if (type == com.nikhil.niktv.model.CatalogType.MOVIES) {
                "/3/movie/$tmdbId/credits"
            } else {
                "/3/tv/$tmdbId/credits"
            }
            execute(path, emptyMap())["cast"]?.jsonArray.orEmpty()
                .mapNotNull { entry ->
                    entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        ?.trim()?.takeIf(String::isNotBlank)
                }
                .distinct()
                .take(6)
        }

    suspend fun seasonEpisodes(seriesId: Int, seasonNumber: Int): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        val root = execute("/3/tv/$seriesId/season/$seasonNumber", emptyMap())
        root["episodes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val episode = element.jsonObject
            val number = episode["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val season = episode["season_number"]?.jsonPrimitive?.intOrNull ?: seasonNumber
            val still = episode["still_path"]?.jsonPrimitive?.contentOrNull
            TmdbEpisode(
                seasonNumber = season,
                episodeNumber = number,
                name = episode["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
                overview = episode["overview"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
                airDate = episode["air_date"]?.jsonPrimitive?.contentOrNull,
                stillUrl = still?.let { "$BACKDROP_BASE_URL$it" }
            )
        }
    }

    suspend fun episodeMetadata(seriesId: Int, seasonNumber: Int, episodeNumber: Int): TmdbEpisode? =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext null
            val episode = execute(
                "/3/tv/$seriesId/season/$seasonNumber/episode/$episodeNumber",
                emptyMap()
            )
            val number = episode["episode_number"]?.jsonPrimitive?.intOrNull
                ?: return@withContext null
            val season = episode["season_number"]?.jsonPrimitive?.intOrNull ?: seasonNumber
            val still = episode["still_path"]?.jsonPrimitive?.contentOrNull
            TmdbEpisode(
                seasonNumber = season,
                episodeNumber = number,
                name = episode["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
                overview = episode["overview"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
                airDate = episode["air_date"]?.jsonPrimitive?.contentOrNull,
                stillUrl = still?.let { "$BACKDROP_BASE_URL$it" }
            )
        }

    private fun kotlinx.serialization.json.JsonObject.toTmdbSeries(): TmdbSeries? {
        val id = this["id"]?.jsonPrimitive?.intOrNull ?: return null
        val name = this["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null
        val originalName = this["original_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { name }
        val poster = this["poster_path"]?.jsonPrimitive?.contentOrNull
        val backdrop = this["backdrop_path"]?.jsonPrimitive?.contentOrNull
        return TmdbSeries(
            id, name, originalName,
            this["overview"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            poster?.let { "$IMAGE_BASE_URL$it" }, backdrop?.let { "$BACKDROP_BASE_URL$it" },
            this["first_air_date"]?.jsonPrimitive?.contentOrNull,
            this["vote_average"]?.jsonPrimitive?.doubleOrNull,
            genres = discoveryGenreNames(), adult = this["adult"]?.jsonPrimitive?.contentOrNull == "true"
        )
    }

    private fun kotlinx.serialization.json.JsonObject.toTmdbMovie(): TmdbMovie? {
        val id = this["id"]?.jsonPrimitive?.intOrNull ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        val originalTitle = this["original_title"]?.jsonPrimitive?.contentOrNull
            ?.trim().orEmpty().ifBlank { title }
        val poster = this["poster_path"]?.jsonPrimitive?.contentOrNull
        val backdrop = this["backdrop_path"]?.jsonPrimitive?.contentOrNull
        return TmdbMovie(
            id, title, originalTitle,
            this["overview"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            poster?.let { "$IMAGE_BASE_URL$it" }, backdrop?.let { "$BACKDROP_BASE_URL$it" },
            this["release_date"]?.jsonPrimitive?.contentOrNull,
            this["vote_average"]?.jsonPrimitive?.doubleOrNull,
            genres = discoveryGenreNames(), adult = this["adult"]?.jsonPrimitive?.contentOrNull == "true"
        )
    }

    internal fun kotlinx.serialization.json.JsonObject.discoveryGenreNames(): List<String> {
        val names = mapOf(28 to "Action", 10759 to "Action & Adventure", 35 to "Comedy", 27 to "Horror",
            53 to "Thriller", 10751 to "Family", 99 to "Documentary", 9648 to "Mystery", 18 to "Drama",
            16 to "Animation", 878 to "Science Fiction", 14 to "Fantasy", 36 to "History", 10749 to "Romance")
        return this["genre_ids"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull?.let(names::get) }
    }

    private fun discoverGenre(genreId: String, limit: Int) = fetchMovies(
        "/3/discover/movie", limit,
        mapOf("with_genres" to genreId, "sort_by" to "popularity.desc", "include_adult" to "false")
    )

    private fun recentMovies(language: String, region: String, limit: Int): List<TmdbMovie> {
        val (startDate, endDate) = recentDateRange()
        return fetchMovies(
            "/3/discover/movie",
            limit,
            mapOf(
                "with_original_language" to language,
                "region" to region,
                "release_date.gte" to startDate,
                "release_date.lte" to endDate,
                "sort_by" to "primary_release_date.desc",
                "include_adult" to "false",
                "include_video" to "false"
            )
        )
    }

    private fun recentSeries(language: String, originCountry: String, limit: Int): List<TmdbSeries> {
        val (startDate, endDate) = recentDateRange()
        return fetchSeries(
            "/3/discover/tv",
            limit,
            mapOf(
                "with_original_language" to language,
                "with_origin_country" to originCountry,
                "first_air_date.gte" to startDate,
                "first_air_date.lte" to endDate,
                "sort_by" to "first_air_date.desc",
                "include_adult" to "false"
            )
        )
    }

    private fun recentDateRange(): Pair<String, String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val end = Calendar.getInstance()
        val start = end.clone() as Calendar
        start.add(Calendar.DAY_OF_YEAR, -120)
        return formatter.format(start.time) to formatter.format(end.time)
    }

    private fun discoverSeries(limit: Int, query: Map<String, String>) = fetchSeries(
        "/3/discover/tv",
        limit,
        query + ("sort_by" to "popularity.desc")
    )

    private fun fetchMovies(
        path: String,
        limit: Int,
        query: Map<String, String> = emptyMap(),
        originalLanguage: String? = null
    ): List<TmdbMovie> {
        val pages = ((limit.coerceAtLeast(1) + 19) / 20).coerceAtMost(5)
        return (1..pages).flatMap { page ->
            execute(path, query + ("page" to page.toString()))["results"]?.jsonArray.orEmpty()
        }
            .mapNotNull { element ->
                val obj = element.jsonObject
                if (originalLanguage != null && obj["original_language"]?.jsonPrimitive?.contentOrNull != originalLanguage) return@mapNotNull null
                if (obj["adult"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null

                val originalTitle = obj["original_title"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                    .ifBlank { title }

                val posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull

                TmdbMovie(
                    id = id,
                    title = title,
                    originalTitle = originalTitle,
                    overview = obj["overview"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                    posterUrl = posterPath?.let { "$IMAGE_BASE_URL$it" },
                    backdropUrl = backdropPath?.let { "$BACKDROP_BASE_URL$it" },
                    releaseDate = obj["release_date"]?.jsonPrimitive?.contentOrNull,
                    voteAverage = obj["vote_average"]?.jsonPrimitive?.doubleOrNull
                )
            }
            .take(limit.coerceAtLeast(1))
    }

    private fun fetchSeries(
        path: String,
        limit: Int,
        query: Map<String, String> = emptyMap(),
        originalLanguage: String? = null
    ): List<TmdbSeries> {
        val pages = ((limit.coerceAtLeast(1) + 19) / 20).coerceAtMost(5)
        return (1..pages).flatMap { page ->
            execute(path, query + ("page" to page.toString()))["results"]?.jsonArray.orEmpty()
        }
            .mapNotNull { element ->
                val obj = element.jsonObject
                if (originalLanguage != null && obj["original_language"]?.jsonPrimitive?.contentOrNull != originalLanguage) return@mapNotNull null
                if (obj["adult"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null

                val originalName = obj["original_name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                    .ifBlank { name }

                val posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull

                TmdbSeries(
                    id = id,
                    name = name,
                    originalName = originalName,
                    overview = obj["overview"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                    posterUrl = posterPath?.let { "$IMAGE_BASE_URL$it" },
                    backdropUrl = backdropPath?.let { "$BACKDROP_BASE_URL$it" },
                    firstAirDate = obj["first_air_date"]?.jsonPrimitive?.contentOrNull,
                    voteAverage = obj["vote_average"]?.jsonPrimitive?.doubleOrNull
                )
            }
            .take(limit.coerceAtLeast(1))
    }

    private fun execute(
        path: String,
        query: Map<String, String>
    ) = run {
        var lastConnectionFailure: IOException? = null
        for (baseUrl in API_BASE_URLS) {
            try {
                val urlBuilder = "$baseUrl$path"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("language", "en-US")

                query.forEach { (name, value) ->
                    urlBuilder.addQueryParameter(name, value)
                }

                if (readAccessToken.isBlank()) {
                    urlBuilder.addQueryParameter("api_key", apiKey)
                }

                val requestBuilder = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Accept", "application/json")

                if (readAccessToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $readAccessToken")
                }

                return@run http.newCall(requestBuilder.build()).execute().use {
                    if (!it.isSuccessful) {
                        error("TMDB returned HTTP ${it.code} for $path")
                    }
                    json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
                }
            } catch (failure: IOException) {
                lastConnectionFailure = failure
            }
        }
        throw lastConnectionFailure ?: IOException("Could not connect to TMDB")
    }

    private data class CachedMovies(
        val cachedAtMillis: Long,
        val items: List<TmdbMovie>
    ) {
        fun expired(): Boolean =
            System.currentTimeMillis() - cachedAtMillis >= CACHE_TTL_MILLIS
    }

    private data class CachedSeries(
        val cachedAtMillis: Long,
        val items: List<TmdbSeries>
    ) {
        fun expired(): Boolean =
            System.currentTimeMillis() - cachedAtMillis >= CACHE_TTL_MILLIS
    }

    private companion object {
        // Some Indian mobile/ISP DNS resolvers black-hole api.themoviedb.org.
        // api.tmdb.org serves the same API and remains reachable on those networks.
        val API_BASE_URLS = listOf(
            "https://api.tmdb.org",
            "https://api.themoviedb.org"
        )
        const val CACHE_TTL_MILLIS = 30L * 60L * 1000L
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
        const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w780"
        const val THRILLER_GENRE_ID = "53"
    }
}

fun matchTmdbMovie(
    movie: TmdbMovie,
    candidates: List<MediaItem>
): MediaItem? = rankTmdbMovieMatches(movie, candidates).firstOrNull()

fun rankTmdbMovieMatches(
    movie: TmdbMovie,
    candidates: List<MediaItem>
): List<MediaItem> = rankTmdbTitleMatches(
    tmdbId = movie.id,
    titles = listOf(movie.title, movie.originalTitle),
    year = movie.releaseYear,
    candidates = candidates
)

fun matchTmdbSeries(
    series: TmdbSeries,
    candidates: List<MediaItem>
): MediaItem? = rankTmdbSeriesMatches(series, candidates).firstOrNull()

fun rankTmdbSeriesMatches(
    series: TmdbSeries,
    candidates: List<MediaItem>
): List<MediaItem> = rankTmdbTitleMatches(
    tmdbId = series.id,
    titles = listOf(series.name, series.originalName) + series.alternativeNames,
    year = series.firstAirYear,
    candidates = candidates,
    series = true
)

private fun rankTmdbTitleMatches(
    tmdbId: Int,
    titles: List<String>,
    year: Int?,
    candidates: List<MediaItem>,
    series: Boolean = false
): List<MediaItem> {
    if (candidates.isEmpty()) return emptyList()

    candidates.filter { it.externalTmdbId == tmdbId }.distinctBy { it.id }
        .takeIf { it.isNotEmpty() }?.let { return it }

    val wantedKeys = titles
        .asSequence()
        .map { it.mediaMatchKey() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    if (wantedKeys.isEmpty()) return emptyList()

    return candidates
        .asSequence()
        .distinctBy { it.id }
        .mapNotNull { candidate ->
            val candidateKeys = listOf(candidate.title, candidate.title.substringAfter(" : ", candidate.title))
                .map { it.mediaMatchKey() }.distinct()
            val candidateKey = candidateKeys.first()
            if (candidateKey.isBlank()) return@mapNotNull null

            val titleScore = wantedKeys.maxOf { wanted ->
                candidateKeys.maxOf { candidateKey -> when {
                    candidateKey == wanted -> 1000
                    candidateKey.startsWith("$wanted ") ||
                        wanted.startsWith("$candidateKey ") -> 780
                    tokenSimilarity(candidateKey, wanted) >= 0.86 -> 700
                    else -> 0
                } }
            }

            if (titleScore == 0) return@mapNotNull null

            val candidateYear = tmdbYearPattern.find(candidate.title)?.value?.toIntOrNull()
            val yearScore = when {
                year == null || candidateYear == null -> 0
                candidateYear == year -> 240
                series -> 0
                else -> -300
            }

            candidate to (titleScore + yearScore)
        }
        .filter { (_, score) -> score >= 700 }
        .sortedWith(compareByDescending<Pair<MediaItem, Int>> { it.second }.thenBy { it.first.title })
        .map { it.first }
        .toList()
}

private val tmdbDiacriticPattern = Regex("\\p{M}+")
private val tmdbYearPattern = Regex("\\b(?:19|20)\\d{2}\\b")
private val tmdbQualifiedLanguagePattern =
    Regex("\\([^)]*(?:english|hindi|tamil|telugu|season|complete)[^)]*\\)")
private val tmdbQualityPattern =
    Regex("\\b(?:4k|uhd|hdr|2160p|1080p|720p|english|multi|dubbed|cam|hdcam|hdts|telesync|dvdscr)\\b")
private val mediaQualityPattern = Regex(
    "\\b(?:4k|uhd|hdr10?|2160p|1080p|720p|480p|bluray|blu\\s*ray|webrip|web\\s*dl|x264|x265|h264|h265|hevc|aac|atmos)\\b",
    RegexOption.IGNORE_CASE
)
private val nonAlphaNumericPattern = Regex("[^\\p{L}\\p{N}]+")
private val repeatedWhitespacePattern = Regex("\\s+")

internal fun String.tmdbLookupTitle(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(tmdbDiacriticPattern, "")
        .replace(tmdbYearPattern, " ")
        .replace(tmdbQualifiedLanguagePattern, " ")
        .replace(tmdbQualityPattern, " ")
        .replace(nonAlphaNumericPattern, " ")
        .trim()
        .replace(repeatedWhitespacePattern, " ")

private fun String.mediaMatchKey(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(tmdbDiacriticPattern, "")
        .replace(tmdbYearPattern, " ")
        .replace(mediaQualityPattern, " ")
        .replace(nonAlphaNumericPattern, " ")
        .trim()
        .replace(repeatedWhitespacePattern, " ")
        .replace(Regex("^(?:the|a|an)\\s+"), "")

private fun tokenSimilarity(left: String, right: String): Double {
    val a = left.split(' ').filter(String::isNotBlank).toSet()
    val b = right.split(' ').filter(String::isNotBlank).toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0

    val intersection = a.intersect(b).size.toDouble()
    val union = a.union(b).size.toDouble()
    return intersection / union
}

private fun String.withoutConfigurationQuotes(): String {
    val value = trim()
    return if (
        value.length >= 2 &&
        (
            value.first() == '"' && value.last() == '"' ||
            value.first() == '\'' && value.last() == '\''
        )
    ) {
        value.substring(1, value.length - 1).trim()
    } else {
        value
    }
}
