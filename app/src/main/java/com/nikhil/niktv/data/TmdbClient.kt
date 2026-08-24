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

    private fun fetchMovies(
        path: String,
        limit: Int,
        query: Map<String, String> = emptyMap()
    ): List<TmdbMovie> {
        val root = execute(path, query)

        return root["results"]
            ?.jsonArray
            .orEmpty()
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
        val root = execute(path, query)

        return root["results"]
            ?.jsonArray
            .orEmpty()
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
        val urlBuilder = "https://api.themoviedb.org$path"
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

        val response = http.newCall(requestBuilder.build()).execute()
        response.use {
            if (!it.isSuccessful) {
                error("TMDB returned HTTP ${it.code} for $path")
            }
            json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }
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
        const val CACHE_TTL_MILLIS = 30L * 60L * 1000L
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
        const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w780"
        const val THRILLER_GENRE_ID = "53"
    }
}

fun matchTmdbMovie(
    movie: TmdbMovie,
    candidates: List<MediaItem>
): MediaItem? = matchTmdbTitle(
    titles = listOf(movie.title, movie.originalTitle),
    year = movie.releaseYear,
    candidates = candidates
)

fun matchTmdbSeries(
    series: TmdbSeries,
    candidates: List<MediaItem>
): MediaItem? = matchTmdbTitle(
    titles = listOf(series.name, series.originalName),
    year = series.firstAirYear,
    candidates = candidates
)

private fun matchTmdbTitle(
    titles: List<String>,
    year: Int?,
    candidates: List<MediaItem>
): MediaItem? {
    if (candidates.isEmpty()) return null

    val wantedKeys = titles
        .asSequence()
        .map { it.mediaMatchKey() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    if (wantedKeys.isEmpty()) return null

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

            val yearScore =
                if (
                    year != null &&
                    Regex("\\b$year\\b").containsMatchIn(candidate.title)
                ) 120 else 0

            candidate to (titleScore + yearScore)
        }
        .filter { (_, score) -> score >= 700 }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

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
