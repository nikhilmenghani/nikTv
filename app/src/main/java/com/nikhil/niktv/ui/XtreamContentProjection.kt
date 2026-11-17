package com.nikhil.niktv.ui

import com.nikhil.niktv.data.ProfilePortalAccess
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.util.IdentityHashMap

/** Preserve synchronous StateFlow.value, including existing navigation callers and tests. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal class ContentFilteredStateFlow(private val source: StateFlow<NikTvState>) : StateFlow<NikTvState> {
    private val projection = XtreamContentProjection()
    override val value get() = projection.project(source.value)
    override val replayCache get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<NikTvState>): Nothing {
        source.map(projection::project).distinctUntilChanged().flowOn(Dispatchers.Default).collect(collector)
        error("State flow collection completed")
    }
}

/** Raw catalogue caches remain complete and reusable by other viewers. Only the exposed view is filtered. */
internal class XtreamContentProjection {
    private var previous: NikTvState? = null
    private var projected: NikTvState? = null
    private var identity: Pair<String, XtreamContentPolicy>? = null
    private var cachedGate: XtreamContentGate? = null
    private val lists = IdentityHashMap<List<*>, MutableMap<String, List<*>>>()
    @Synchronized fun project(state: NikTvState): NikTvState {
        if (state === previous) return projected ?: state
        val profile = state.session?.profile ?: state.savedProfile
        if (profile?.portalType != PortalType.XTREAM || !state.xtreamContentPolicy.enabled) return state
        val nextIdentity = profile.cacheKey() to state.xtreamContentPolicy
        if (nextIdentity != identity || state.rawCategoriesByType !== previous?.rawCategoriesByType ||
            state.browseCachesByType.mapValues { it.value.categories } != previous?.browseCachesByType?.mapValues { it.value.categories }) {
            lists.clear(); identity = nextIdentity; cachedGate = null
        }
        if (lists.size > 256) lists.clear()
        val gate = cachedGate?.takeIf { state.selectedType == previous?.selectedType } ?: XtreamContentGate(state).also { cachedGate = it }
        @Suppress("UNCHECKED_CAST")
        fun <T> filtered(items: List<T>, key: String, allow: (T) -> Boolean): List<T> {
            val cache = lists.getOrPut(items) { HashMap() }
            return cache.getOrPut(key) { items.filter(allow).let { if (it.size == items.size) items else it } } as List<T>
        }
        fun categories(items: List<Category>) = filtered(items, "categories") { gate.allowsCategory(it) }
        fun media(items: List<MediaItem>, type: CatalogType, parent: MediaItem? = null, title: String? = null) =
            filtered(items, "${type.name}:${parent?.id}:$title") { gate.allows(parent ?: it, type, title) }
        fun cache(cache: BrowseCatalogCache) = cache.copy(categories = categories(cache.categories),
            itemsByCategory = cache.itemsByCategory.mapValues { (id, items) -> media(items, cache.type,
                title = cache.categories.firstOrNull { it.id == id }?.title) })
        fun kind(kind: FavoriteKind) = when (kind) {
            FavoriteKind.CHANNEL -> CatalogType.LIVE_TV
            FavoriteKind.MOVIE -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        val playing = state.nowPlaying
        val filteredPlaying = playing?.let {
            val queue = media(it.episodeQueue, it.catalogType, it.series)
            if (queue.size == it.episodeQueue.size) it else {
                val index = queue.indexOfFirst { queued -> queued.id == it.media.id }
                val wrap = it.catalogType == CatalogType.LIVE_TV && queue.size > 1
                it.copy(episodeQueue = queue,
                    previousEpisode = if (index < 0) null else queue.getOrNull(index - 1) ?: queue.lastOrNull().takeIf { wrap },
                    nextEpisode = if (index < 0) null else queue.getOrNull(index + 1) ?: queue.firstOrNull().takeIf { wrap })
            }
        }
        val result = state.copy(
            contentPolicyCategories = state.browseCachesByType.mapValues { it.value.categories } + state.rawCategoriesByType,
            categories = categories(state.categories),
            rawCategoriesByType = state.rawCategoriesByType.mapValues { categories(it.value) },
            selectedCategory = state.selectedCategory?.takeIf(gate::allowsCategory),
            modernIptvCategory = state.modernIptvCategory?.takeIf(gate::allowsCategory),
            selectedSeries = state.selectedSeries?.takeIf { gate.allows(it, CatalogType.SERIES) },
            providerMovieDetails = state.providerMovieDetails?.takeIf { gate.allows(it.item, CatalogType.MOVIES) }
                ?.let { it.copy(queue = media(it.queue, CatalogType.MOVIES)) },
            items = media(state.items, state.selectedType, state.selectedSeries),
            fullSearchItems = state.fullSearchItems?.let { rows -> filtered(rows, "full-search") { item ->
                gate.allows(item, when (item.searchResultType) {
                    SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
                    SearchContentType.SERIES, SearchContentType.EPISODES -> CatalogType.SERIES
                    SearchContentType.MOVIES -> CatalogType.MOVIES
                    else -> gate.inferType(item)
                })
            } },
            searchResults = filtered(state.searchResults, "search") { gate.allows(it, when (it.searchResultType ?: state.searchType) {
                SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
                SearchContentType.SERIES, SearchContentType.EPISODES -> CatalogType.SERIES
                SearchContentType.MOVIES -> CatalogType.MOVIES
                else -> gate.inferType(it)
            }) },
            searchCategories = categories(state.searchCategories),
            favorites = filtered(state.favorites, "favorites") { gate.allows(it.series ?: it.media, kind(it.kind), it.categoryTitle) },
            recentlyPlayed = filtered(state.recentlyPlayed, "recent") { gate.allows(it.series ?: it.media, kind(it.kind), it.categoryTitle) },
            watchedSeries = filtered(state.watchedSeries, "watched") { gate.allows(it.series, CatalogType.SERIES, it.categoryTitle) },
            offlineDownloads = filtered(state.offlineDownloads, "offline") { entry ->
                gate.allows(entry.series ?: entry.media, entry.catalogType) },
            movieMatchCandidates = media(state.movieMatchCandidates, CatalogType.MOVIES),
            seriesMatchCandidates = media(state.seriesMatchCandidates, CatalogType.SERIES),
            historyRecommendations = state.historyRecommendations.map { row -> row.copy(
                movies = row.movies.filter { gate.allows(it.asMediaItem(), CatalogType.MOVIES, "Movies") },
                series = row.series.filter { gate.allows(it.asMediaItem(), CatalogType.SERIES, "Series") })
            }.filter { it.movies.isNotEmpty() || it.series.isNotEmpty() },
            trendingMovies = filtered(state.trendingMovies, "trending-movies") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.MOVIES, "Movies") },
            trendingSeries = filtered(state.trendingSeries, "trending-series") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.SERIES, "Series") },
            thrillerMovies = filtered(state.thrillerMovies, "thrillers") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.MOVIES, "Movies") },
            tmdbHomeMovieRows = state.tmdbHomeMovieRows.mapValues { (_, rows) -> filtered(rows, "home-movies") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.MOVIES, "Movies") } },
            tmdbHomeSeriesRows = state.tmdbHomeSeriesRows.mapValues { (_, rows) -> filtered(rows, "home-series") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.SERIES, "Series") } },
            modernTmdbMovies = filtered(state.modernTmdbMovies, "modern-movies") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.MOVIES, "Movies") },
            modernTmdbSeries = filtered(state.modernTmdbSeries, "modern-series") { gate.allows(it.iptv ?: it.tmdb.asMediaItem(), CatalogType.SERIES, "Series") },
            modernTmdbMovieMatches = state.modernTmdbMovieMatches.mapValues { media(it.value, CatalogType.MOVIES) },
            modernTmdbSeriesMatches = state.modernTmdbSeriesMatches.mapValues { media(it.value, CatalogType.SERIES) },
            browseCache = state.browseCache?.let(::cache),
            browseCachesByType = state.browseCachesByType.mapValues { cache(it.value) },
            nowPlaying = filteredPlaying
        )
        previous = state; projected = result
        return result
    }
}

/** Shared by presentation, fresh provider results and final playback authorization. */
internal class XtreamContentGate(private val state: NikTvState) {
    private val profile = state.session?.profile ?: state.savedProfile
    val active = profile?.portalType == PortalType.XTREAM && state.xtreamContentPolicy.enabled
    private val filter = if (active) XtreamContentFilter(state.xtreamContentPolicy, ProfilePortalAccess.key(profile!!)) else null
    private val categories = buildMap<Pair<CatalogType, String>, Category> {
        state.contentPolicyCategories.forEach { (type, cats) -> cats.forEach { put(type to it.id, it) } }
        state.browseCachesByType.forEach { (type, cache) -> cache.categories.forEach { put(type to it.id, it) } }
        state.rawCategoriesByType.forEach { (type, cats) -> cats.forEach { put(type to it.id, it) } }
        state.categories.forEach { put(it.type to it.id, it) }
    }
    fun allowsCategory(category: Category) = filter?.allowsCategory(category) != false
    fun allows(item: MediaItem, type: CatalogType, categoryTitle: String? = null): Boolean {
        if (filter == null) return true
        val category = item.portalCategoryId?.let { categories[type to it] }
            ?: categoryTitle?.let { Category(item.portalCategoryId.orEmpty(), it, type) }
        return filter.allows(item, type, category)
    }
    fun inferType(item: MediaItem): CatalogType = when {
        item.id.startsWith("tmdb-series:") || item.command?.contains("/series/") == true -> CatalogType.SERIES
        item.id.startsWith("tmdb-movie:") || item.command?.contains("/movie/") == true -> CatalogType.MOVIES
        item.command?.contains("/live/") == true -> CatalogType.LIVE_TV
        else -> state.selectedType
    }
}
