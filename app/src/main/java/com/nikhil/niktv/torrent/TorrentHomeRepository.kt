package com.nikhil.niktv.torrent

import android.content.Context
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.TmdbHomeSection

internal val HOME_SECTIONS = listOf(
    TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES,
    TmdbHomeSection.BOLLYWOOD, TmdbHomeSection.HOLLYWOOD,
    TmdbHomeSection.BOLLYWOOD_SERIES, TmdbHomeSection.HOLLYWOOD_SERIES
)

internal data class TorrentHomeShelf(
    val section: TmdbHomeSection, val cards: List<DiscoveryCard> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val hasMore: Boolean = false
) {
    val key: String get() = "torrent-home:${section.name}"
    val title: String get() = section.title
}

internal data class TorrentHomeState(
    val shelves: List<TorrentHomeShelf> = HOME_SECTIONS.map { TorrentHomeShelf(it) },
    val genres: List<WatchProvider> = emptyList(), val providers: List<WatchProvider> = emptyList(),
    val genresLoading: Boolean = false, val providersLoading: Boolean = false,
    val genresError: String? = null, val providersError: String? = null,
    val genreArtwork: Map<GenreArtworkKey, String?> = emptyMap(), val heroIndex: Int = 0
) {
    val heroes: List<DiscoveryCard> get() {
        val today = torrentCurrentDate()
        val rows = shelves.map { shelf -> shelf.cards.filter { it.torrentPublished(today) } }
        return (0 until 10).flatMap { index -> rows.mapNotNull { it.getOrNull(index) } }
            .distinctBy(DiscoveryCard::key).take(10)
    }
    val hero: DiscoveryCard? get() = heroes.getOrNull(heroIndex) ?: heroes.firstOrNull()
}

/** A public-metadata collection destination; none of these values identify an IPTV connection. */
internal data class TorrentHomeCollection(
    val key: String, val title: String, val series: Boolean,
    val section: TmdbHomeSection? = null, val genre: WatchProvider? = null,
    val market: GenreMarket? = null, val provider: WatchProvider? = null, val region: String = "IN"
)

internal fun TorrentHomeCollection.validate() {
    require(listOfNotNull(section, genre, provider).size == 1) { "Choose one collection to browse." }
    require(section == null || section.series == series) { "This collection has a different media type." }
    require(genre == null || genre.id > 0) { "Choose a valid genre." }
    require(provider == null || provider.id > 0) { "Choose a valid provider." }
}

/** Thin adapter over Home's existing public cache, throttling and TMDB request builders. */
internal class TorrentHomeRepository(context: Context) {
    private val discovery = DiscoveryRepository(context.applicationContext, providerProfile = null)
    val configured: Boolean get() = discovery.configured

    suspend fun feed(section: TmdbHomeSection, stale: (DiscoveryFeedPage) -> Unit = {}): DiscoveryFeedPage =
        discovery.feedPage(section) { stale(publishedTorrentFeed(it)) }.let { publishedTorrentFeed(it) }

    suspend fun genres(series: Boolean = false): List<WatchProvider> = discovery.genres(series)
    suspend fun providers(series: Boolean = false, region: String = "IN"): List<WatchProvider> =
        discovery.providers(region, series, refresh = false)
    suspend fun genreArtwork(key: GenreArtworkKey): String? = discovery.genreWallpaper(key.series, key.genre, key.market)

    suspend fun collection(target: TorrentHomeCollection, page: Int,
        stale: (WatchProviderPage) -> Unit = {}, refresh: Boolean = false): WatchProviderPage {
        target.validate()
        require(page in 1..500) { "This collection has no further pages." }
        val publish: (WatchProviderPage) -> Unit = { stale(publishedTorrentPage(it)) }
        return when {
            target.section != null -> discovery.feedPage(target.section, page, publish, refresh)
            target.genre != null -> discovery.genreTitles(target.series, target.genre.id, page, publish, refresh, target.market)
            else -> discovery.titles(target.region, target.series, requireNotNull(target.provider).id, page, publish, refresh)
        }.let { publishedTorrentPage(it) }
    }

}
