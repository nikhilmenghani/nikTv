package com.nikhil.niktv.data

import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.model.MediaItem
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
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseDate: String?,
    val voteAverage: Double?
) {
    val releaseYear: Int?
        get() = releaseDate?.take(4)?.toIntOrNull()

    fun asMediaItem(): MediaItem = MediaItem(
        id = "tmdb-movie:$id",
        title = title,
        logo = posterUrl ?: backdropUrl,
        command = null,
        description = overview
    )
}

data class TmdbSeries(
    val id: Int,
    val name: String,
    val originalName: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val firstAirDate: String?,
    val voteAverage: Double?
) {
    val firstAirYear: Int?
        get() = firstAirDate?.take(4)?.toIntOrNull()

    fun asMediaItem(): MediaItem = MediaItem(
        id = "tmdb-series:$id",
        title = name,
        logo = posterUrl ?: backdropUrl,
        command = null,
        description = overview
    )
}

data class TrendingMovie(
    val tmdb: TmdbMovie,
    val iptv: MediaItem? = null
)

data class TrendingSeries(
    val tmdb: TmdbSeries,
    val iptv: MediaItem? = null
)

class TmdbClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val readAccessToken =
        BuildConfig.TMDB_READ_ACCESS_TOKEN
            .trim()
            .withoutConfigurationQuotes()

    private val apiKey =
        BuildConfig.TMDB_API_KEY
            .trim()
            .withoutConfigurationQuotes()

    @Volatile
    private var trendingMovieCache: CachedMovies? = null

    @Volatile
    private var trendingSeriesCache: CachedSeries? = null

    @Volatile
    private var thrillerMovieCache: CachedMovies? = null

    val configured: Boolean
        get() = readAccessToken.isNotBlank() || apiKey.isNotBlank()

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
        if (!forceRefresh && cached != null && !cached.expired()) {
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
        if (!forceRefresh && cached != null && !cached.expired()) {
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
        if (!forceRefresh && cached != null && !cached.expired()) {
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
        when (section) {
            TmdbHomeSection.TRENDING_MOVIES -> trendingMovies(limit)
            TmdbHomeSection.TOP_RATED_MOVIES -> fetchMovies("/3/movie/top_rated", limit)
            TmdbHomeSection.HOLLYWOOD -> fetchMovies("/3/discover/movie", limit, mapOf("with_original_language" to "en", "region" to "US", "sort_by" to "popularity.desc"))
            TmdbHomeSection.BOLLYWOOD -> fetchMovies("/3/discover/movie", limit, mapOf("with_original_language" to "hi", "region" to "IN", "sort_by" to "popularity.desc"))
            TmdbHomeSection.ACTION -> discoverGenre("28", limit)
            TmdbHomeSection.COMEDY -> discoverGenre("35", limit)
            TmdbHomeSection.HORROR -> discoverGenre("27", limit)
            TmdbHomeSection.THRILLER -> thrillerMovies(limit)
            TmdbHomeSection.FAMILY -> discoverGenre("10751", limit)
            TmdbHomeSection.DOCUMENTARY -> discoverGenre("99", limit)
            TmdbHomeSection.TRENDING_SERIES,
            TmdbHomeSection.TOP_RATED_SERIES,
            TmdbHomeSection.HOLLYWOOD_SERIES,
            TmdbHomeSection.BOLLYWOOD_SERIES,
            TmdbHomeSection.ACTION_SERIES,
            TmdbHomeSection.COMEDY_SERIES,
            TmdbHomeSection.MYSTERY_SERIES,
            TmdbHomeSection.FAMILY_SERIES,
            TmdbHomeSection.DOCUMENTARY_SERIES -> emptyList()
        }
    }

    suspend fun homeSeries(section: TmdbHomeSection, limit: Int = 50): List<TmdbSeries> = withContext(Dispatchers.IO) {
        if (!configured || !section.series) return@withContext emptyList()
        when (section) {
            TmdbHomeSection.TRENDING_SERIES -> trendingSeries(limit)
            TmdbHomeSection.TOP_RATED_SERIES -> fetchSeries("/3/tv/top_rated", limit)
            TmdbHomeSection.HOLLYWOOD_SERIES -> discoverSeries(limit, mapOf("with_original_language" to "en"))
            TmdbHomeSection.BOLLYWOOD_SERIES -> discoverSeries(limit, mapOf("with_original_language" to "hi"))
            TmdbHomeSection.ACTION_SERIES -> discoverSeries(limit, mapOf("with_genres" to "10759"))
            TmdbHomeSection.COMEDY_SERIES -> discoverSeries(limit, mapOf("with_genres" to "35"))
            TmdbHomeSection.MYSTERY_SERIES -> discoverSeries(limit, mapOf("with_genres" to "9648"))
            TmdbHomeSection.FAMILY_SERIES -> discoverSeries(limit, mapOf("with_genres" to "10751"))
            TmdbHomeSection.DOCUMENTARY_SERIES -> discoverSeries(limit, mapOf("with_genres" to "99"))
            else -> emptyList()
        }
    }

    private fun discoverGenre(genreId: String, limit: Int) = fetchMovies(
        "/3/discover/movie", limit,
        mapOf("with_genres" to genreId, "sort_by" to "popularity.desc", "include_adult" to "false")
    )

    private fun discoverSeries(limit: Int, query: Map<String, String>) = fetchSeries(
        "/3/discover/tv",
        limit,
        query + ("sort_by" to "popularity.desc")
    )

    private fun fetchMovies(
        path: String,
        limit: Int,
        query: Map<String, String> = emptyMap()
    ): List<TmdbMovie> {
        val pages = ((limit.coerceAtLeast(1) + 19) / 20).coerceAtMost(3)
        return (1..pages).flatMap { page ->
            execute(path, query + ("page" to page.toString()))["results"]?.jsonArray.orEmpty()
        }
            .mapNotNull { element ->
                val obj = element.jsonObject
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
        query: Map<String, String> = emptyMap()
    ): List<TmdbSeries> {
        val pages = ((limit.coerceAtLeast(1) + 19) / 20).coerceAtMost(3)
        return (1..pages).flatMap { page ->
            execute(path, query + ("page" to page.toString()))["results"]?.jsonArray.orEmpty()
        }
            .mapNotNull { element ->
                val obj = element.jsonObject
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
    titles = listOf(series.name, series.originalName),
    year = series.firstAirYear,
    candidates = candidates
)

private fun rankTmdbTitleMatches(
    tmdbId: Int,
    titles: List<String>,
    year: Int?,
    candidates: List<MediaItem>
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
            val candidateKey = candidate.title.mediaMatchKey()
            if (candidateKey.isBlank()) return@mapNotNull null

            val titleScore = wantedKeys.maxOf { wanted ->
                when {
                    candidateKey == wanted -> 1000
                    candidateKey.startsWith("$wanted ") ||
                        wanted.startsWith("$candidateKey ") -> 780
                    tokenSimilarity(candidateKey, wanted) >= 0.86 -> 700
                    else -> 0
                }
            }

            if (titleScore == 0) return@mapNotNull null

            val candidateYear = Regex("\\b(?:19|20)\\d{2}\\b")
                .find(candidate.title)?.value?.toIntOrNull()
            val yearScore = when {
                year == null || candidateYear == null -> 0
                candidateYear == year -> 240
                else -> -300
            }

            candidate to (titleScore + yearScore)
        }
        .filter { (_, score) -> score >= 700 }
        .sortedWith(compareByDescending<Pair<MediaItem, Int>> { it.second }.thenBy { it.first.title })
        .map { it.first }
        .toList()
}

private fun String.tmdbLookupTitle(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
        .replace(Regex("\\([^)]*(?:english|hindi|tamil|telugu|season|complete)[^)]*\\)"), " ")
        .replace(Regex("\\b(?:4k|uhd|hdr|2160p|1080p|720p|english|multi|dubbed)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun String.mediaMatchKey(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
        .replace(
            Regex(
                "\\b(?:4k|uhd|hdr10?|2160p|1080p|720p|480p|bluray|blu\\s*ray|webrip|web\\s*dl|x264|x265|h264|h265|hevc|aac|atmos)\\b",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

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
