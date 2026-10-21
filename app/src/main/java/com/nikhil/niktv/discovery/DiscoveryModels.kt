package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Preserve the pre-overlay phone tile footprint, including its former caption space. */
internal fun phoneDiscoveryCardHeight(width: Float, wide: Boolean, fontScale: Float): Float =
    width / (if (wide) 16f / 9f else 2f / 3f) + 7f + (if (wide) 68f else 45f) * fontScale.coerceAtLeast(1f)

/** Public discovery metadata only. Provider URLs and personal data never enter this cache. */
@Serializable
internal data class DiscoveryCard(
    val id: Int, val series: Boolean, val title: String, val overview: String? = null,
    val poster: String? = null, val backdrop: String? = null, val date: String? = null,
    val rating: Double? = null, val genres: List<String> = emptyList(),
    val runtime: Int? = null, val certification: String? = null, val titleLogo: String? = null,
    val adult: Boolean = false, val originalLanguage: String? = null
) {
    val key get() = "${if (series) "tv" else "movie"}:$id"
    val media get() = MediaItem("tmdb-${if (series) "series" else "movie"}:$id", title, poster, null,
        description = overview, externalTmdbId = id, isAdult = adult)
    fun movie() = TmdbMovie(id, title, title, overview, poster, backdrop, date, rating)
    fun show() = TmdbSeries(id, title, title, overview, poster, backdrop, date, rating)
    fun released(today: String = currentDiscoveryDate()): Boolean = !adult && releasedDiscoveryDate(date, today)
}

/** Keep provider details relevant without additional searches or guessing a language from the title. */
internal fun relevantDiscoveryRecommendations(source: DiscoveryCard, cards: List<DiscoveryCard>): List<DiscoveryCard> {
    val candidates = cards.filter { it.key != source.key && it.series == source.series && it.released() }.distinctBy { it.key }
    val language = source.originalLanguage?.takeIf(String::isNotBlank)
    val relevant = candidates.filter { source.genres.isEmpty() || it.genres.any(source.genres::contains) }
    return relevant.sortedWith(compareByDescending<DiscoveryCard> { language != null && it.originalLanguage == language }
        .thenByDescending { card -> card.genres.count(source.genres::contains) })
}

internal fun TmdbMovie.discovery() = DiscoveryCard(id, false, title, overview, posterUrl, backdropUrl, releaseDate, voteAverage, genres, adult = adult)
internal fun TmdbSeries.discovery() = DiscoveryCard(id, true, name, overview, posterUrl, backdropUrl, firstAirDate, voteAverage, genres, adult = adult)

@Serializable
internal data class DiscoveryPerson(val name: String, val role: String = "", val portrait: String? = null, val id: Int? = null)
@Serializable
internal data class DiscoveryReview(val author: String, val text: String)
@Serializable
internal data class DiscoveryCollection(val id: Int, val title: String, val artwork: String? = null)
@Serializable
internal data class DiscoveryDetails(
    val card: DiscoveryCard, val cast: List<DiscoveryPerson> = emptyList(),
    val seasons: List<Int> = emptyList(), val collection: DiscoveryCollection? = null
)
@Serializable
internal data class DiscoveryEpisode(
    val season: Int, val number: Int, val title: String, val overview: String? = null,
    val date: String? = null, val still: String? = null
) {
    fun released(today: String = currentDiscoveryDate()) = releasedDiscoveryDate(date, today)
}

internal data class DiscoveryRow(val id: String, val title: String, val cards: List<DiscoveryCard>, val ranked: Boolean = false)
internal data class DiscoveryState(
    val profile: String = "", val surface: DashboardSurface = DashboardSurface.HOME,
    val sections: List<TmdbHomeSection> = emptyList(), val feeds: Map<TmdbHomeSection, List<DiscoveryCard>> = emptyMap(),
    val loading: Set<String> = emptySet(), val errors: Map<String, String> = emptyMap(),
    val detail: DiscoveryCard? = null, val details: DiscoveryDetails? = null,
    val related: List<DiscoveryCard> = emptyList(), val collection: List<DiscoveryCard> = emptyList(),
    val reviews: List<DiscoveryReview> = emptyList(), val episodes: List<DiscoveryEpisode> = emptyList(),
    val season: Int? = null, val heroes: List<DiscoveryCard> = emptyList(), val heroIndex: Int = 0,
    val heroPresentation: DiscoveryCard? = null
) {
    val hero get() = heroes.getOrNull(heroIndex)
}

internal fun discoveryHeroes(surface: DashboardSurface, movies: List<DiscoveryCard>, shows: List<DiscoveryCard>): List<DiscoveryCard> =
    when (surface) {
        DashboardSurface.MOVIES -> movies.take(10)
        DashboardSurface.SERIES -> shows.take(10)
        else -> (0 until 5).flatMap { listOfNotNull(movies.getOrNull(it), shows.getOrNull(it)) }
    }.distinctBy { it.key }

internal data class HeroActivity(
    val visible: Boolean, val foreground: Boolean, val focused: Boolean,
    val scrolling: Boolean, val touching: Boolean, val popup: Boolean
) { val mayRotate get() = visible && foreground && !focused && !scrolling && !touching && !popup }

/** Profile, screen and request identity all participate: late work cannot mutate a new route. */
internal class DiscoveryGeneration {
    private var generation = 0L
    fun next(): Long = ++generation
    fun nextToken(): Long = generation
    fun accepts(token: Long) = generation == token
}

internal fun discoveryEpisodeMatch(episodes: List<MediaItem>, season: Int, number: Int): MediaItem? =
    episodes.singleOrNull { it.seasonNumber == season && it.episodeNumber == number }

/** Do not use java.time here: embedded legacy desugaring runtimes on Fire OS omit LocalDate.now(). */
private val isoDatePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
internal fun releasedDiscoveryDate(date: String?, today: String): Boolean {
    if (date == null || !isoDatePattern.matches(date)) return false
    val year = date.substring(0, 4).toInt()
    val month = date.substring(5, 7).toInt()
    val day = date.substring(8, 10).toInt()
    if (year < 1 || month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return day in 1..days[month - 1] && date <= today
}
private object DiscoveryDateClock {
    var bucket = Long.MIN_VALUE
    var zone = ""
    var date = ""
}
internal fun currentDiscoveryDate(): String = synchronized(DiscoveryDateClock) {
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault().id
    if (DiscoveryDateClock.bucket != now / 60000 || DiscoveryDateClock.zone != zone) {
        DiscoveryDateClock.date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        DiscoveryDateClock.bucket = now / 60000
        DiscoveryDateClock.zone = zone
    }
    DiscoveryDateClock.date
}

/** Profile-scoped progress only; legacy unscoped keys cannot safely identify this profile. */
internal fun discoveryRecentProgress(recent: RecentItem, progress: List<PlaybackProgress>): PlaybackProgress? {
    val type = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES
    val prefix = "progress-v2|${recent.profileKey}|${type.name}|"
    val identity = if (type == CatalogType.SERIES) {
        val episode = recent.lastPlayed ?: return null
        "series:${recent.media.id}|s:${episode.seasonNumber ?: -1}|e:${episode.episodeNumber ?: -1}|"
    } else "movie:${recent.media.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')}|"
    return progress.filter { it.key.startsWith(prefix + identity) }.maxByOrNull { it.updatedAtMillis }
}

internal fun discoveryFeedRequest(section: TmdbHomeSection): Pair<String, Map<String, String>> {
    val type = if (section.series) "tv" else "movie"
    val base = mapOf("include_adult" to "false")
    if (section in setOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES)) return "/trending/$type/day" to base
    if (section in setOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.TOP_RATED_SERIES)) return "/$type/top_rated" to base
    val language = when (section) {
        TmdbHomeSection.NEW_BOLLYWOOD_MOVIES, TmdbHomeSection.NEW_BOLLYWOOD_SERIES, TmdbHomeSection.BOLLYWOOD, TmdbHomeSection.BOLLYWOOD_SERIES -> "hi"
        TmdbHomeSection.NEW_HOLLYWOOD_MOVIES, TmdbHomeSection.NEW_HOLLYWOOD_SERIES, TmdbHomeSection.HOLLYWOOD, TmdbHomeSection.HOLLYWOOD_SERIES -> "en"
        else -> null
    }
    val genre = when (section) {
        TmdbHomeSection.ACTION -> "28"; TmdbHomeSection.ACTION_SERIES -> "10759"
        TmdbHomeSection.COMEDY, TmdbHomeSection.COMEDY_SERIES -> "35"
        TmdbHomeSection.HORROR -> "27"; TmdbHomeSection.THRILLER -> "53"; TmdbHomeSection.MYSTERY_SERIES -> "9648"
        TmdbHomeSection.FAMILY, TmdbHomeSection.FAMILY_SERIES -> "10751"
        TmdbHomeSection.DOCUMENTARY, TmdbHomeSection.DOCUMENTARY_SERIES -> "99"
        else -> null
    }
    val recent = section.name.startsWith("NEW_")
    val query = base + mapOf("sort_by" to if (recent) { if (section.series) "first_air_date.desc" else "primary_release_date.desc" } else "popularity.desc",
        (if (section.series) "first_air_date.lte" else "primary_release_date.lte") to currentDiscoveryDate()) +
        listOfNotNull(language?.let { "with_original_language" to it }, genre?.let { "with_genres" to it }).toMap()
    return "/discover/$type" to query
}

/** Personal availability counters never cross a profile or media-type boundary. */
internal fun discoveryAvailabilityKey(profile: String, card: DiscoveryCard) = "$profile|${card.key}"
