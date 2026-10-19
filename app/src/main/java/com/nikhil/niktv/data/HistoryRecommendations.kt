package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.*
import kotlinx.serialization.Serializable

@Serializable
data class HistoryRecommendationRow(
    val key: String, val title: String,
    val movies: List<TmdbMovie> = emptyList(), val series: List<TmdbSeries> = emptyList(),
    val profile: String = ""
)

@Serializable
internal data class HistoryRecommendationCache(
    val fingerprint: String, val rows: List<HistoryRecommendationRow>,
    val savedAt: Long = System.currentTimeMillis()
)

internal fun meaningfulViewing(positionMs: Long, durationMs: Long): Boolean =
    positionMs >= 120_000L && (positionMs >= 600_000L ||
        (durationMs > 0 && positionMs.toDouble() / durationMs >= 0.2))

/** Starting a new episode must not erase evidence of watching earlier episodes. */
internal fun seriesRecommendationProgress(profile: String, seriesId: String,
    progress: Collection<PlaybackProgress>): PlaybackProgress? {
    val prefix = "progress-v2|$profile|SERIES|series:$seriesId|"
    return progress.filter { it.key.startsWith(prefix) && meaningfulViewing(it.positionMillis, it.durationMillis) }
        .maxByOrNull { it.updatedAtMillis }
}

internal fun recommendationSeeds(profile: String, recent: List<RecentItem>, limit: Int = 3,
    progress: (RecentItem) -> PlaybackProgress?): List<RecentItem> = recent.asSequence()
    .filter { it.profileKey == profile && it.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES) &&
        !it.media.isAdult && (it.lastPlayed?.isAdult != true) }
    .sortedByDescending { it.playedAtMillis }
    .filter { entry -> progress(entry)?.let { meaningfulViewing(it.positionMillis, it.durationMillis) } == true }
    .distinctBy { "${it.kind}:${it.media.id}" }.take(limit.coerceIn(0, 100)).toList()

internal class HistoryRecommendations(private val context: Context, private val tmdb: TmdbClient,
    private val identities: MediaIdentityRepository,
    private val moviesFor: suspend (Int) -> List<TmdbMovie> = tmdb::recommendedMovies,
    private val seriesFor: suspend (Int) -> List<TmdbSeries> = tmdb::recommendedSeries,
    private val configured: () -> Boolean = { tmdb.configured }) {
    suspend fun load(profile: String, seeds: List<RecentItem>, watched: List<RecentItem> = seeds,
        dismissed: Set<String> = emptySet(), requested: Set<String> = emptySet()): List<HistoryRecommendationRow> {
        if (seeds.isEmpty()) return emptyList()
        val resolved = seeds.mapNotNull { seed ->
            val type = if (seed.kind == FavoriteKind.MOVIE) CatalogType.MOVIES else CatalogType.SERIES
            identities.resolve(profile, type, seed.media)?.let { seed to it }
        }.filterNot { (_, identity) -> "${identity.type}:${identity.tmdbId}" in dismissed }
            .distinctBy { (_, identity) -> "${identity.type}:${identity.tmdbId}" }
        val watchedIds = watched.mapNotNull { recent ->
            val type = if (recent.kind == FavoriteKind.MOVIE) CatalogType.MOVIES else CatalogType.SERIES
            (recent.media.externalTmdbId ?: identities.cached(profile, type, recent.media)?.tmdbId)
                ?.let { "$type:$it" }
        }.toSet()
        val fingerprint = resolved.joinToString("|") { (_, identity) -> "${identity.key}:${identity.tmdbId}" } +
            watchedIds.sorted().joinToString("|") + requested.sorted().joinToString("|", prefix = "requested:")
        val key = "history-recommendations-v1:$profile"
        val cached = CatalogDiskCache.read<HistoryRecommendationCache>(context, key)
        if (cached?.fingerprint == fingerprint && System.currentTimeMillis() - cached.savedAt in 0 until 6 * 60 * 60_000L) {
            return cached.rows
        }
        if (!configured()) return emptyList()
        val excluded = (watchedIds + resolved.map { (_, identity) -> "${identity.type}:${identity.tmdbId}" }).toMutableSet()
        val rows = resolved.mapNotNull { (seed, identity) ->
            val id = identity.tmdbId ?: return@mapNotNull null
            val movies = if (identity.type == CatalogType.MOVIES) moviesFor(id)
                .asSequence().filter { excluded.add("${CatalogType.MOVIES}:${it.id}") }.take(12).toList() else emptyList()
            val series = if (identity.type == CatalogType.SERIES) seriesFor(id)
                .asSequence().filter { excluded.add("${CatalogType.SERIES}:${it.id}") }.take(12).toList() else emptyList()
            if (movies.isEmpty() && series.isEmpty()) null else HistoryRecommendationRow(
                "${identity.type}:$id", "${if ("${identity.type}:$id" in requested) "Because you chose" else "Because you watched"} ${identity.movie?.title ?: identity.series?.name ?: seed.media.title}",
                movies, series, profile)
        }
        CatalogDiskCache.write(context, key, HistoryRecommendationCache(fingerprint, rows))
        return rows
    }
}
