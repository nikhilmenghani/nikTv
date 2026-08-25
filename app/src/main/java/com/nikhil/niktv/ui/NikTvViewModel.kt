package com.nikhil.niktv.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.StalkerPortalClient
import com.nikhil.niktv.data.TmdbClient
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.matchTmdbMovie
import com.nikhil.niktv.data.matchTmdbSeries
import com.nikhil.niktv.data.prefetchArtwork
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class NikTvState(
    val profiles: List<PortalProfile> = emptyList(),
    val profileEditorOpen: Boolean = false,
    val savedProfile: PortalProfile? = null,
    val session: PortalSession? = null,
    val selectedType: CatalogType = CatalogType.LIVE_TV,
    val categories: List<Category> = emptyList(),
    val rawCategoriesByType: Map<CatalogType, List<Category>> = emptyMap(),
    val selectedCategory: Category? = null,
    val items: List<MediaItem> = emptyList(),
    val trendingMovies: List<TrendingMovie> = emptyList(),
    val trendingMoviesLoading: Boolean = false,
    val trendingMoviesError: String? = null,
    val trendingSeries: List<TrendingSeries> = emptyList(),
    val trendingSeriesLoading: Boolean = false,
    val trendingSeriesError: String? = null,
    val thrillerMovies: List<TrendingMovie> = emptyList(),
    val thrillerMoviesLoading: Boolean = false,
    val thrillerMoviesError: String? = null,
    val tmdbSectionsBySurface: Map<DashboardSurface, List<TmdbHomeSection>> = emptyMap(),
    val tmdbHomeMovieRows: Map<TmdbHomeSection, List<TrendingMovie>> = emptyMap(),
    val tmdbHomeSeriesRows: Map<TmdbHomeSection, List<TrendingSeries>> = emptyMap(),
    val tmdbSectionsLoading: Set<TmdbHomeSection> = emptySet(),
    val feedRefreshing: Boolean = false,
    val feedRefreshMessage: String = "Refreshing feed…",
    val loading: Boolean = false,
    val profileLoadProgress: Float? = null,
    val profileLoadMessage: String = "Preparing profile…",
    val error: String? = null,
    val nowPlaying: PlayingMedia? = null,
    val playbackReturnFocusId: String? = null,
    val restoring: Boolean = true,
    val settingsOpen: Boolean = false,
    val selectedSeries: MediaItem? = null,
    val fullSearchItems: List<MediaItem>? = null,
    val fullSearchLoading: Boolean = false,
    val fullSearchCachedAtMillis: Long? = null,
    val favorites: List<FavoriteItem> = emptyList(),
    val favoritesOpen: Boolean = false,
    val seriesOpenedFromFavorites: Boolean = false,
    val recentlyPlayed: List<RecentItem> = emptyList(),
    val homeOpen: Boolean = true,
    val seriesOpenedFromHome: Boolean = false,
    val playbackProgress: List<PlaybackProgress> = emptyList(),
    val playbackUrls: List<PlaybackUrl> = emptyList(),
    val cacheIntervalMinutes: Int = 60,
    val playerControlsTimeoutSeconds: Int = 3,
    val playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    val seriesStartSeason: SeriesStartSeason = SeriesStartSeason.FIRST,
    val availableSeriesSeasons: List<Int> = emptyList(),
    val selectedSeriesSeason: Int? = null,
    val watchedSeries: List<WatchedSeries> = emptyList(),
    val browseLayout: BrowseLayout = BrowseLayout.SECTIONS,
    val browseCache: BrowseCatalogCache? = null,
    val browseCachesByType: Map<CatalogType, BrowseCatalogCache> = emptyMap(),
    val searchOpen: Boolean = false,
    val searchType: SearchContentType = SearchContentType.SERIES,
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val searchServerLoading: Boolean = false,
    val searchUsedServer: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val searchPage: Int = 0,
    val searchHasMore: Boolean = false,
    val searchCategories: List<Category> = emptyList(),
    val searchCategoryId: String = "*",
    val categoryFilters: Map<String, List<String>> = emptyMap(),
    val categoryManagerOpen: Boolean = false,
    val categoryManagerType: CatalogType = CatalogType.LIVE_TV
    ,val backupMessage: String? = null
    ,val catalogPage: Int = 1
    ,val catalogHasMore: Boolean = false
    ,val catalogLoadingMore: Boolean = false
    ,val episodePage: Int = 1
    ,val episodeHasMore: Boolean = false
    ,val episodeLoadingMore: Boolean = false
)

class NikTvViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val portal = StalkerPortalClient(application)
    private val tmdb = TmdbClient()
    private val _state = MutableStateFlow(NikTvState())
    val state: StateFlow<NikTvState> = _state.asStateFlow()
    private var allFavorites: List<FavoriteItem> = emptyList()
    private var allRecentlyPlayed: List<RecentItem> = emptyList()
    private var categoryFilterApplyInProgress = false
    private var allWatchedSeries: List<WatchedSeries> = emptyList()
    private var rememberedSeriesSeasons: Map<String, Int> = emptyMap()
    private var episodeSeasonCaches: List<EpisodeSeasonCache> = emptyList()
    private var browseLayouts: Map<String, BrowseLayout> = emptyMap()
    private var tmdbDashboardConfigs: Map<String, List<TmdbHomeSection>> = emptyMap()
    private val watchRefreshMutex = Mutex()

    init {
        prepareProfileChooser()
        viewModelScope.launch { store.favorites.collect { favorites ->
            allFavorites = favorites
            refreshProfileLibrary()
        } }
        viewModelScope.launch { store.recentlyPlayed.collect { recent ->
            allRecentlyPlayed = recent
            refreshProfileLibrary()
        } }
        viewModelScope.launch { store.playbackProgress.collect { progress -> _state.update { it.copy(playbackProgress = progress) } } }
        viewModelScope.launch { store.playbackUrls.collect { urls -> _state.update { it.copy(playbackUrls = urls) } } }
        viewModelScope.launch { store.cacheIntervalMinutes.collect { minutes -> _state.update { it.copy(cacheIntervalMinutes = minutes) } } }
        viewModelScope.launch { store.playerControlsTimeoutSeconds.collect { seconds -> _state.update { it.copy(playerControlsTimeoutSeconds = seconds) } } }
        viewModelScope.launch { store.playbackEngine.collect { engine -> _state.update { it.copy(playbackEngine = engine) } } }
        viewModelScope.launch { store.seriesStartSeason.collect { value -> _state.update { it.copy(seriesStartSeason = value) } } }
        viewModelScope.launch { store.rememberedSeriesSeasons.collect { rememberedSeriesSeasons = it } }
        viewModelScope.launch { store.episodeSeasonCaches.collect { episodeSeasonCaches = it } }
        viewModelScope.launch { store.browseLayouts.collect { layouts ->
            browseLayouts = layouts
            val key = _state.value.session?.profile?.cacheKey()
            _state.update { it.copy(browseLayout = key?.let(layouts::get) ?: BrowseLayout.SECTIONS) }
        } }
        viewModelScope.launch { store.tmdbDashboardSections.collect { stored ->
            tmdbDashboardConfigs = stored
            val profileKey = _state.value.session?.profile?.cacheKey()
            val sections = if (profileKey == null) emptyMap() else DashboardSurface.entries.associateWith { surface ->
                stored["$profileKey|${surface.name}"].orEmpty()
            }
            _state.update { it.copy(tmdbSectionsBySurface = sections) }
            if (_state.value.session != null) loadConfiguredTmdbHomeSections()
        } }
        viewModelScope.launch { store.watchedSeries.collect { entries ->
            allWatchedSeries = entries
            refreshProfileLibrary()
        } }
        viewModelScope.launch { store.recentSearches.collect { searches -> _state.update { it.copy(recentSearches = searches) } } }
        viewModelScope.launch { store.profiles.collect { profiles -> _state.update { it.copy(profiles = profiles) } } }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                runCatching { refreshWatchedSeriesIfDue() }
            }
        }
        viewModelScope.launch {
            store.categoryFilters.collect { filters ->
                val snapshot = _state.value
                val profileKey = snapshot.session?.profile?.cacheKey()
                val activeFilterChanged = !categoryFilterApplyInProgress && profileKey != null &&
                    snapshot.categoryFilters[filterKey(profileKey, snapshot.selectedType)] !=
                    filters[filterKey(profileKey, snapshot.selectedType)]
                val raw = snapshot.rawCategoriesByType[snapshot.selectedType] ?: snapshot.categories
                val filtered = filterCategories(raw, profileKey, snapshot.selectedType, filters)
                val selected = if (snapshot.selectedCategory != null && filtered.any { it.id == snapshot.selectedCategory.id }) {
                    snapshot.selectedCategory
                } else {
                    filtered.firstOrNull { snapshot.selectedType != CatalogType.SERIES || it.id != "*" }
                        ?: filtered.firstOrNull()
                }
                val needsReload = selected != snapshot.selectedCategory
                _state.update { it.copy(categoryFilters = filters, categories = filtered, selectedCategory = selected) }
                if (
                    needsReload &&
                    snapshot.browseLayout != BrowseLayout.SECTIONS &&
                    selected != null &&
                    snapshot.session != null
                ) {
                    loadCategory(selected)
                }
                if (
                    activeFilterChanged &&
                    snapshot.browseLayout == BrowseLayout.SECTIONS
                ) {
                    viewModelScope.launch {
                        loadCategorySections(snapshot.session, snapshot.selectedType)
                    }
                }
            }
        }
    }

    private fun prepareProfileChooser() = viewModelScope.launch {
        // A cold app launch always starts at the profile chooser. Authentication is
        // restored only after the viewer deliberately chooses a profile.
        val profiles = store.profiles.first()
        _state.update { it.copy(profiles = profiles, session = null, savedProfile = null,
            profileEditorOpen = profiles.isEmpty(), restoring = false) }
    }

    fun connect(profile: PortalProfile) = task {
        _state.update { it.copy(savedProfile = profile) }
        updateProfileLoad(0.08f, "Authenticating ${profile.name}…")
        val session = portal.authenticate(profile)
        updateProfileLoad(0.24f, "Authentication complete")
        store.save(session)
        _state.update {
            it.copy(
                session = session,
                savedProfile = session.profile,
                profileEditorOpen = false,
                trendingMovies = emptyList(),
                trendingMoviesLoading = false,
                trendingMoviesError = null,
                trendingSeries = emptyList(),
                trendingSeriesLoading = false,
                trendingSeriesError = null,
                thrillerMovies = emptyList(),
                thrillerMoviesLoading = false,
                thrillerMoviesError = null
            )
        }
        loadProfileLibrary(session.profile.cacheKey())
        preloadDashboard(session)
    }

    /**
     * Warms only the first enabled category for each dashboard type. Requests are
     * deliberately sequential and loadTypeInternal observes the configured cache
     * TTL, so choosing a profile cannot fan out into a burst of portal calls.
     */
    private suspend fun preloadDashboard(session: PortalSession) {
        // Old builds combined every profile's browse data in a single value.
        // Remove it without decoding before creating bounded, profile-scoped caches.
        store.discardLegacyBrowseCatalogs()
        updateProfileLoad(0.30f, "Loading Movies…")
        loadTypeInternal(session, CatalogType.MOVIES)
        updateProfileLoad(0.40f, "Preparing Movies…")
        updateProfileLoad(0.48f, "Loading Series…")
        loadTypeInternal(session, CatalogType.SERIES)
        updateProfileLoad(0.60f, "Preparing Series…")
        updateProfileLoad(0.68f, "Loading Live TV…")
        loadTypeInternal(session, CatalogType.LIVE_TV)
        updateProfileLoad(0.82f, "Preparing Live TV…")
        updateProfileLoad(0.90f, "Preparing your dashboard…")
        refreshWatchedSeriesIfDue()
        loadDashboardDiscovery()
        updateProfileLoad(0.94f, "Loading selected TMDB sections…")
        // Keep the existing profile loading screen visible while discovery
        // rows settle. The dashboard is never exposed in a half-composed state
        // where late row insertion can steal focus or appear frozen.
        withTimeoutOrNull(20_000L) {
            state.map { current ->
                current.tmdbSectionsLoading.isEmpty() &&
                    !current.trendingMoviesLoading &&
                    !current.trendingSeriesLoading &&
                    !current.thrillerMoviesLoading
            }.first { ready -> ready }
        }
        viewModelScope.launch { enrichHomeArtwork(session.profile.cacheKey()) }
        updateProfileLoad(1f, "Opening dashboard…")
    }

    private suspend fun enrichHomeArtwork(profileKey: String) = coroutineScope {
        if (!tmdb.configured || _state.value.session?.profile?.cacheKey() != profileKey) {
            return@coroutineScope
        }

        data class HomeArtworkTarget(val kind: FavoriteKind, val media: MediaItem)

        val targets = (
            _state.value.recentlyPlayed.map { HomeArtworkTarget(it.kind, it.media) } +
                _state.value.favorites.map { HomeArtworkTarget(it.kind, it.media) }
            )
            .filter { it.kind == FavoriteKind.MOVIE || it.kind == FavoriteKind.SERIES }
            .distinctBy { "${it.kind}:${it.media.id}" }
            .take(8)

        val enriched = targets.map { target ->
            async {
                val type = if (target.kind == FavoriteKind.MOVIE) CatalogType.MOVIES else CatalogType.SERIES
                val tmdbLogo = runCatching { tmdb.artworkFor(target.media, type) }.getOrNull()
                target to tmdbLogo
            }
        }.awaitAll().mapNotNull { (target, logo) ->
            logo?.let { "${target.kind}:${target.media.id}" to it }
        }.toMap()

        if (enriched.isEmpty() || _state.value.session?.profile?.cacheKey() != profileKey) {
            return@coroutineScope
        }

        fun MediaItem.withPreferredArtwork(kind: FavoriteKind): MediaItem =
            enriched["$kind:$id"]?.let { copy(logo = it) } ?: this

        val scopedRecent = _state.value.recentlyPlayed.map { recent ->
            recent.copy(media = recent.media.withPreferredArtwork(recent.kind))
        }
        val scopedFavorites = _state.value.favorites.map { favorite ->
            favorite.copy(media = favorite.media.withPreferredArtwork(favorite.kind))
        }

        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == profileKey } + scopedRecent
        allFavorites = allFavorites.filterNot { it.profileKey == profileKey } + scopedFavorites
        store.saveRecentlyPlayed(allRecentlyPlayed)
        store.saveFavorites(allFavorites)
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profileKey) current
            else current.copy(recentlyPlayed = scopedRecent, favorites = scopedFavorites)
        }

        prefetchArtwork(
            getApplication(),
            (scopedRecent.map { it.media } + scopedFavorites.map { it.media }),
            limit = 12
        )
    }

    private suspend fun warmVisibleArtwork(progress: Float, message: String) {
        updateProfileLoad(progress, message)
        withTimeoutOrNull(3_000L) { prefetchArtwork(getApplication(), _state.value.items, limit = 3) }
    }

    private fun updateProfileLoad(progress: Float, message: String) =
        _state.update { it.copy(profileLoadProgress = progress.coerceIn(0f, 1f), profileLoadMessage = message) }

    private fun refreshProfileLibrary() {
        val profileKey = _state.value.session?.profile?.cacheKey()
        _state.update { it.copy(
            favorites = if (profileKey == null) emptyList() else allFavorites.filter { entry -> entry.profileKey == profileKey },
            recentlyPlayed = if (profileKey == null) emptyList() else allRecentlyPlayed.filter { entry -> entry.profileKey == profileKey },
            watchedSeries = if (profileKey == null) emptyList() else allWatchedSeries.filter { entry -> entry.profileKey == profileKey },
            browseLayout = profileKey?.let(browseLayouts::get) ?: BrowseLayout.SECTIONS
            ,tmdbSectionsBySurface = if (profileKey == null) emptyMap() else DashboardSurface.entries.associateWith { surface ->
                tmdbDashboardConfigs["$profileKey|${surface.name}"].orEmpty()
            }
        ) }
    }

    private suspend fun loadProfileLibrary(profileKey: String) {
        // Claim legacy unscoped rows once for the first profile opened after this upgrade.
        val storedFavorites = store.favorites.first()
        val storedRecent = store.recentlyPlayed.first()
        val migratedFavorites = storedFavorites.map { if (it.profileKey.isBlank()) it.copy(profileKey = profileKey) else it }
        val migratedRecent = storedRecent.map { if (it.profileKey.isBlank()) it.copy(profileKey = profileKey) else it }
        if (migratedFavorites != storedFavorites) store.saveFavorites(migratedFavorites)
        if (migratedRecent != storedRecent) store.saveRecentlyPlayed(migratedRecent)
        allFavorites = migratedFavorites
        allRecentlyPlayed = migratedRecent
        refreshProfileLibrary()
    }

    fun reconnect() { _state.value.savedProfile?.let(::connect) }

    fun loadType(type: CatalogType) {
        if (activateWarmedType(type)) return
        viewModelScope.launch {
            runCatching { loadTypeInternal(requireNotNull(_state.value.session), type) }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not load ${type.title}") } }
        }
    }

    fun openCatalogType(type: CatalogType) {
        if (activateWarmedType(type, closeOverlays = true)) {
            if (_state.value.browseLayout == BrowseLayout.SECTIONS) {
                _state.value.session?.let { session ->
                    viewModelScope.launch { loadCategorySections(session, type) }
                }
            }
            return
        }

        _state.update { it.copy(
            homeOpen = false, favoritesOpen = false, settingsOpen = false, searchOpen = false,
            selectedType = type, selectedSeries = null
        ) }
        loadType(type)
    }

    /** Applies a profile-warmed tab in the caller's frame without disk, network or coroutine hops. */
    private fun activateWarmedType(type: CatalogType, closeOverlays: Boolean = false): Boolean {
        val snapshot = _state.value
        val session = snapshot.session ?: return false
        val cache = snapshot.browseCachesByType[type]
            ?.takeIf { it.profileKey == session.profile.cacheKey() }
            ?: return false
        val filteredCategories = filterCategories(cache.categories, session.profile.cacheKey(), type, snapshot.categoryFilters)
        val selected = filteredCategories.firstOrNull { type != CatalogType.SERIES || it.id != "*" }
            ?: filteredCategories.firstOrNull()
        val items = selected?.let { cache.itemsByCategory[it.id] } ?: if (selected == null) emptyList() else return false
        _state.update { current -> current.copy(
            selectedType = type,
            categories = filteredCategories,
            selectedCategory = selected,
            items = items,
            catalogPage = 1,
            catalogHasMore = session.profile.portalType == PortalType.STALKER && items.isNotEmpty(),
            selectedSeries = null,
            fullSearchItems = null,
            fullSearchCachedAtMillis = null,
            browseCache = cache,
            homeOpen = if (closeOverlays) false else current.homeOpen,
            favoritesOpen = if (closeOverlays) false else current.favoritesOpen,
            settingsOpen = if (closeOverlays) false else current.settingsOpen,
            searchOpen = if (closeOverlays) false else current.searchOpen
        ) }
        return true
    }

    private suspend fun loadTypeInternal(session: PortalSession, type: CatalogType, forceRefresh: Boolean = false, preferredCategoryId: String? = null) {
        val profileKey = session.profile.cacheKey()
        val maxAge = _state.value.cacheIntervalMinutes * 60_000L
        var rawCategories: List<Category>? = null
        var cachedItems: Map<String, List<MediaItem>> = emptyMap()
        var cachedBrowse: BrowseCatalogCache? = if (!forceRefresh) {
            _state.value.browseCachesByType[type]?.takeIf { it.profileKey == profileKey }
        } else null

        cachedBrowse?.let { cached ->
            rawCategories = cached.categories
            cachedItems = cached.itemsByCategory
        }

        if (!forceRefresh && cachedBrowse == null) {
            store.browseCatalog(type, profileKey).first()?.takeIf { it.categories.isNotEmpty() }?.let { cached ->
                rawCategories = cached.categories
                cachedItems = cached.itemsByCategory
                cachedBrowse = cached
            }
        }
        if (rawCategories == null) {
            rawCategories = portal.categories(session, type)
        }
        val allCategories = rawCategories.orEmpty()
        val filterKey = filterKey(profileKey, type)
        val enabledIds = _state.value.categoryFilters[filterKey]
        val filteredCategories = if (enabledIds == null) allCategories else allCategories.filter { it.id in enabledIds }
        val selected = filteredCategories.firstOrNull { it.id == preferredCategoryId }
            ?: filteredCategories.firstOrNull { type != CatalogType.SERIES || it.id != "*" }
            ?: filteredCategories.firstOrNull()

        val items = if (selected != null) {
            val cachedForSelected = cachedItems[selected.id]
            if (!forceRefresh && cachedBrowse != null && cachedForSelected != null && (System.currentTimeMillis() - cachedBrowse.cachedAtMillis < maxAge)) {
                cachedForSelected
            } else {
                portal.catalog(session, selected).let { loaded ->
                    if (session.profile.portalType == PortalType.XTREAM) loaded.take(120) else loaded
                }
            }
        } else emptyList()

        val cache = cachedBrowse?.copy(categories = allCategories, itemsByCategory = if (selected != null) cachedItems + (selected.id to items) else cachedItems)
            ?: BrowseCatalogCache(profileKey, type, System.currentTimeMillis(), allCategories, selected?.let { mapOf(it.id to items) }.orEmpty())

        if (allCategories.isNotEmpty() && (selected == null || items.isNotEmpty()) && cache != cachedBrowse) {
            store.saveBrowseCatalog(cache)
        }

        _state.update { current ->
            val updatedRaw = current.rawCategoriesByType + (type to allCategories)
            current.copy(
                selectedType = type,
                rawCategoriesByType = updatedRaw,
                categories = filteredCategories,
                selectedCategory = selected,
                items = items,
                catalogPage = 1,
                catalogHasMore = session.profile.portalType == PortalType.STALKER && items.isNotEmpty(),
                selectedSeries = null,
                fullSearchItems = null,
                fullSearchCachedAtMillis = null,
                browseCache = cache,
                browseCachesByType = current.browseCachesByType + (type to cache)
            )
        }

        if (
            _state.value.browseLayout == BrowseLayout.SECTIONS &&
            _state.value.profileLoadProgress == null
        ) {
            loadCategorySections(session, type)
        } else if (type == CatalogType.MOVIES || type == CatalogType.SERIES) {
            viewModelScope.launch { enrichCatalogArtwork(session, type) }
        }
    }

    fun loadCategory(category: Category) = task {
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedCategory = category, items = emptyList(), selectedSeries = null) }
        val cached = _state.value.browseCachesByType[category.type]?.itemsByCategory?.get(category.id)
        val firstPage = if (cached == null) portal.catalogPage(session, category, 1) else null
        val items = cached ?: firstPage!!.items
        if (cached == null) {
            val existing = _state.value.browseCachesByType[category.type]
            if (existing != null && existing.type == category.type) {
                val updated = existing.copy(itemsByCategory = existing.itemsByCategory + (category.id to items))
                store.saveBrowseCatalog(updated)
                _state.update { current -> current.copy(
                    browseCache = updated,
                    browseCachesByType = current.browseCachesByType + (category.type to updated)
                ) }
            }
        }
        _state.update { it.copy(
            items = items,
            catalogPage = 1,
            catalogHasMore = firstPage?.hasMore ?: (session.profile.portalType == PortalType.STALKER && items.isNotEmpty())
        ) }
    }

    fun loadMoreCatalog() {
        val snapshot = _state.value
        val session = snapshot.session ?: return
        val category = snapshot.selectedCategory ?: return
        if (snapshot.catalogLoadingMore || !snapshot.catalogHasMore ||
            snapshot.selectedType !in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) return
        viewModelScope.launch {
            _state.update { it.copy(catalogLoadingMore = true) }
            runCatching { portal.catalogPage(session, category, snapshot.catalogPage + 1) }
                .onSuccess { result ->
                    /*
                     * PAGINATION_ACTUALLY_ADDED_V2
                     *
                     * Some portals report another page even when that page is
                     * empty or contains only IDs we already have. Treat that
                     * as the real end of the catalog so Load More disappears
                     * instead of repeatedly offering a no-op request.
                     */
                    val currentItems = _state.value.items
                    val merged =
                        (currentItems + result.items)
                            .distinctBy { it.id }

                    val actuallyAdded =
                        merged.size > currentItems.size

                    val existing =
                        _state.value
                            .browseCachesByType[category.type]

                    val updated = existing?.copy(
                        cachedAtMillis =
                            System.currentTimeMillis(),
                        itemsByCategory =
                            existing.itemsByCategory +
                                (category.id to merged)
                    )

                    if (updated != null) {
                        store.saveBrowseCatalog(updated)
                    }

                    _state.update { current ->
                        current.copy(
                            items = merged,
                            catalogPage = result.page,
                            catalogHasMore =
                                result.hasMore && actuallyAdded,
                            catalogLoadingMore = false,
                            browseCache =
                                updated ?: current.browseCache,
                            browseCachesByType =
                                if (updated == null) {
                                    current.browseCachesByType
                                } else {
                                    current.browseCachesByType +
                                        (category.type to updated)
                                }
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(catalogLoadingMore = false, error = error.message ?: "Could not load more titles") } }
        }
    }

    fun refreshCatalog() = task {
        val snapshot = _state.value
        val series = snapshot.selectedSeries
        val session = requireNotNull(snapshot.session)
        if (series != null) {
            _state.update { it.copy(items = emptyList()) }
            loadSeriesEpisodes(series, snapshot.selectedSeriesSeason, forceRefresh = true)
        } else {
            loadTypeInternal(session, snapshot.selectedType, forceRefresh = true,
                preferredCategoryId = snapshot.selectedCategory?.id)
        }
    }

    fun setCacheIntervalMinutes(minutes: Int) = viewModelScope.launch {
        store.setCacheIntervalMinutes(minutes)
    }

    fun setPlayerControlsTimeoutSeconds(seconds: Int) = viewModelScope.launch {
        store.setPlayerControlsTimeoutSeconds(seconds)
    }

    fun setPlaybackEngine(engine: PlaybackEngine) = viewModelScope.launch {
        store.setPlaybackEngine(engine)
    }

    fun setSeriesStartSeason(value: SeriesStartSeason) = viewModelScope.launch {
        store.setSeriesStartSeason(value)
    }

    fun setBrowseLayout(layout: BrowseLayout) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        _state.update { it.copy(browseLayout = layout) }
        store.setBrowseLayout(profileKey, layout)
        if (layout == BrowseLayout.SECTIONS) {
            _state.value.session?.let { loadCategorySections(it, _state.value.selectedType) }
        }
    }

    fun loadSeriesSeason(season: Int) = task {
        val series = requireNotNull(_state.value.selectedSeries)
        _state.update { it.copy(items = emptyList()) }
        loadSeriesEpisodes(series, season)
    }

    private suspend fun loadSeriesEpisodes(series: MediaItem, requestedSeason: Int? = null, forceRefresh: Boolean = false) {
        val session = requireNotNull(_state.value.session)
        val profileKey = session.profile.cacheKey()
        val remembered = rememberedSeriesSeasons["$profileKey|${series.id}"]
        val desired = requestedSeason ?: remembered
        val maxAge = _state.value.cacheIntervalMinutes * 60_000L
        val cached = if (forceRefresh) null else episodeSeasonCaches.firstOrNull { cache ->
            cache.profileKey == profileKey && cache.seriesId == series.id &&
                (desired == null || cache.season == desired) && System.currentTimeMillis() - cache.cachedAtMillis < maxAge
        }
        val result = cached?.let { EpisodeSeasonResult(it.episodes, it.availableSeasons, it.season, it.page, it.hasMore) }
            ?: portal.episodeSeason(session, series, _state.value.seriesStartSeason, desired).also { loaded ->
                val cache = EpisodeSeasonCache(profileKey, series.id, loaded.selectedSeason, loaded.availableSeasons, loaded.episodes, loaded.page, loaded.hasMore)
                episodeSeasonCaches = listOf(cache) + episodeSeasonCaches.filterNot { it.key == cache.key }
                store.saveEpisodeSeasonCache(cache)
            }
        result.selectedSeason?.let { store.rememberSeriesSeason(profileKey, series.id, it) }
        _state.update { it.copy(items = result.episodes, availableSeriesSeasons = result.availableSeasons, selectedSeriesSeason = result.selectedSeason,
            episodePage = result.page, episodeHasMore = result.hasMore, episodeLoadingMore = false) }
    }

    fun loadMoreEpisodes() {
        val snapshot = _state.value
        val series = snapshot.selectedSeries ?: return
        val session = snapshot.session ?: return
        if (!snapshot.episodeHasMore || snapshot.episodeLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(episodeLoadingMore = true) }
            runCatching {
                portal.episodeSeason(session, series, snapshot.seriesStartSeason, snapshot.selectedSeriesSeason,
                    snapshot.episodePage + 1, snapshot.items.firstOrNull()?.portalSeasonId)
            }.onSuccess { next ->
                val combined = (snapshot.items + next.episodes).distinctBy { it.id }
                val actuallyAdded = combined.size > snapshot.items.size
                _state.update { it.copy(items = combined, episodePage = next.page,
                    episodeHasMore = next.hasMore && actuallyAdded, episodeLoadingMore = false) }
                val cache = EpisodeSeasonCache(session.profile.cacheKey(), series.id, next.selectedSeason,
                    next.availableSeasons.ifEmpty { snapshot.availableSeriesSeasons }, combined, next.page, next.hasMore && actuallyAdded)
                episodeSeasonCaches = listOf(cache) + episodeSeasonCaches.filterNot { it.key == cache.key }
                store.saveEpisodeSeasonCache(cache)
            }.onFailure { error ->
                _state.update { it.copy(episodeLoadingMore = false, error = error.message ?: "Could not load more episodes") }
            }
        }
    }


    fun loadDashboardDiscovery(forceRefresh: Boolean = false) {
        loadTrendingMovies(forceRefresh)
        loadTrendingSeries(forceRefresh)
        loadThrillerMovies(forceRefresh)
        loadConfiguredTmdbHomeSections(forceRefresh)
    }

    fun setTmdbSections(surface: DashboardSurface, sections: List<TmdbHomeSection>) {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return
        // Publish the complete intended configuration immediately, but keep
        // the dashboard covered until every newly required row has settled.
        _state.update { current ->
            current.copy(
                tmdbSectionsBySurface = current.tmdbSectionsBySurface + (surface to sections.distinct()),
                feedRefreshing = true,
                feedRefreshMessage = "Refreshing ${surface.name.lowercase().replace('_', ' ')} feed…"
            )
        }
        loadConfiguredTmdbHomeSections()
        if (_state.value.tmdbSectionsLoading.isEmpty()) {
            _state.update { it.copy(feedRefreshing = false) }
        }
        viewModelScope.launch {
            store.setTmdbDashboardSections(profileKey, surface, sections)
        }
    }

    fun resetScreenConfiguration(surface: DashboardSurface) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.setTmdbDashboardSections(profileKey, surface, emptyList())
        val type = when (surface) {
            DashboardSurface.LIVE_TV -> CatalogType.LIVE_TV
            DashboardSurface.MOVIES -> CatalogType.MOVIES
            DashboardSurface.SERIES -> CatalogType.SERIES
            DashboardSurface.HOME -> null
        }
        if (type != null) store.saveCategoryFilter(profileKey, type, emptyList())
    }

    private fun loadConfiguredTmdbHomeSections(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        if (!tmdb.configured) return
        val requested = snapshot.tmdbSectionsBySurface.values.flatten().distinct()
        val pendingMovies = requested.filterNot { it.series }.filter { section ->
            (forceRefresh || snapshot.tmdbHomeMovieRows[section].orEmpty().isEmpty()) && section !in snapshot.tmdbSectionsLoading
        }
        val pendingSeries = requested.filter { it.series }.filter { section ->
            (forceRefresh || snapshot.tmdbHomeSeriesRows[section].orEmpty().isEmpty()) && section !in snapshot.tmdbSectionsLoading
        }
        val pending = (pendingMovies + pendingSeries).toSet()
        if (pending.isNotEmpty()) {
            _state.update { it.copy(tmdbSectionsLoading = it.tmdbSectionsLoading + pending) }
        }
        pendingMovies.forEach { section ->
            if (!forceRefresh && snapshot.tmdbHomeMovieRows[section].orEmpty().isNotEmpty()) return@forEach
            viewModelScope.launch {
                val rows = runCatching {
                    val candidates = localMovieCandidates(_state.value)
                    tmdb.homeMovies(section, 50).map { movie ->
                        TrendingMovie(movie, matchTmdbMovie(movie, candidates))
                    }
                }.getOrDefault(emptyList())
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        tmdbHomeMovieRows = current.tmdbHomeMovieRows + (section to rows),
                        tmdbSectionsLoading = current.tmdbSectionsLoading - section,
                        feedRefreshing = current.feedRefreshing &&
                            (current.tmdbSectionsLoading - section).isNotEmpty()
                    )
                }
            }
        }
        pendingSeries.forEach { section ->
            if (!forceRefresh && snapshot.tmdbHomeSeriesRows[section].orEmpty().isNotEmpty()) return@forEach
            viewModelScope.launch {
                val rows = runCatching {
                    val candidates = localSeriesCandidates(_state.value)
                    tmdb.homeSeries(section, 50).map { series ->
                        TrendingSeries(series, matchTmdbSeries(series, candidates))
                    }
                }.getOrDefault(emptyList())
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        tmdbHomeSeriesRows = current.tmdbHomeSeriesRows + (section to rows),
                        tmdbSectionsLoading = current.tmdbSectionsLoading - section,
                        feedRefreshing = current.feedRefreshing &&
                            (current.tmdbSectionsLoading - section).isNotEmpty()
                    )
                }
            }
        }
    }

    fun loadTrendingMovies(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        if (!tmdb.configured) return
        if (
            snapshot.trendingMoviesLoading ||
            (!forceRefresh && snapshot.trendingMovies.isNotEmpty())
        ) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    trendingMoviesLoading = true,
                    trendingMoviesError = null
                )
            }

            runCatching {
                val movies = tmdb.trendingMovies(
                    limit = 50,
                    forceRefresh = forceRefresh
                )
                val localCandidates = localMovieCandidates(_state.value)
                val savedMappings = store.tmdbMappings.first()
                    .filter { it.profileKey == profileKey && it.type == CatalogType.MOVIES }
                    .associateBy { it.tmdbId }
                movies.map { movie ->
                    TrendingMovie(
                        tmdb = movie,
                        iptv = savedMappings[movie.id]?.media
                            ?: matchTmdbMovie(movie, localCandidates)
                    )
                }
            }.onSuccess { movies ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        trendingMovies = movies,
                        trendingMoviesLoading = false,
                        trendingMoviesError = null
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        trendingMoviesLoading = false,
                        trendingMoviesError =
                            error.message ?: "Could not load TMDB trending movies"
                    )
                }
            }
        }
    }

    fun loadTrendingSeries(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        if (!tmdb.configured) return
        if (
            snapshot.trendingSeriesLoading ||
            (!forceRefresh && snapshot.trendingSeries.isNotEmpty())
        ) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    trendingSeriesLoading = true,
                    trendingSeriesError = null
                )
            }

            runCatching {
                val series = tmdb.trendingSeries(
                    limit = 50,
                    forceRefresh = forceRefresh
                )
                val localCandidates = localSeriesCandidates(_state.value)
                val savedMappings = store.tmdbMappings.first()
                    .filter { it.profileKey == profileKey && it.type == CatalogType.SERIES }
                    .associateBy { it.tmdbId }
                series.map { item ->
                    TrendingSeries(
                        tmdb = item,
                        iptv = savedMappings[item.id]?.media
                            ?: matchTmdbSeries(item, localCandidates)
                    )
                }
            }.onSuccess { series ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        trendingSeries = series,
                        trendingSeriesLoading = false,
                        trendingSeriesError = null
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        trendingSeriesLoading = false,
                        trendingSeriesError =
                            error.message ?: "Could not load TMDB trending series"
                    )
                }
            }
        }
    }

    fun loadThrillerMovies(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        if (!tmdb.configured) return
        if (
            snapshot.thrillerMoviesLoading ||
            (!forceRefresh && snapshot.thrillerMovies.isNotEmpty())
        ) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    thrillerMoviesLoading = true,
                    thrillerMoviesError = null
                )
            }

            runCatching {
                val movies = tmdb.thrillerMovies(
                    limit = 50,
                    forceRefresh = forceRefresh
                )
                val localCandidates = localMovieCandidates(_state.value)
                val savedMappings = store.tmdbMappings.first()
                    .filter { it.profileKey == profileKey && it.type == CatalogType.MOVIES }
                    .associateBy { it.tmdbId }
                movies.map { movie ->
                    TrendingMovie(
                        tmdb = movie,
                        iptv = savedMappings[movie.id]?.media
                            ?: matchTmdbMovie(movie, localCandidates)
                    )
                }
            }.onSuccess { movies ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        thrillerMovies = movies,
                        thrillerMoviesLoading = false,
                        thrillerMoviesError = null
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current
                    else current.copy(
                        thrillerMoviesLoading = false,
                        thrillerMoviesError =
                            error.message ?: "Could not load TMDB thriller movies"
                    )
                }
            }
        }
    }

    fun openTrendingMovie(entry: TrendingMovie) = task {
        val session = requireNotNull(_state.value.session)

        val resolved =
            entry.iptv
                ?: matchTmdbMovie(
                    entry.tmdb,
                    localMovieCandidates(_state.value)
                )
                ?: resolveTmdbMovieFromPortal(session, entry)

        if (resolved == null) {
            error(
                "\"${entry.tmdb.title}\" was not found in this IPTV profile."
            )
        }

        store.saveTmdbMapping(
            TmdbIptvMapping(session.profile.cacheKey(), CatalogType.MOVIES, entry.tmdb.id, resolved)
        )

        _state.update { current ->
            current.copy(
                trendingMovies =
                    current.trendingMovies.resolveMovie(entry.tmdb.id, resolved),
                thrillerMovies =
                    current.thrillerMovies.resolveMovie(entry.tmdb.id, resolved)
            )
        }

        playInternal(
            item = resolved,
            type = CatalogType.MOVIES,
            series = null,
            episodes = listOf(resolved)
        )
    }

    fun openTrendingSeries(entry: TrendingSeries) = task {
        val session = requireNotNull(_state.value.session)

        /*
         * Enter the detail destination before a provider lookup. Xtream may
         * need to resolve an uncached TMDB title, and keeping homeOpen visible
         * during that work made the click appear to open the Series dashboard.
         */
        _state.update { current ->
            current.copy(
                homeOpen = false,
                favoritesOpen = false,
                settingsOpen = false,
                searchOpen = false,
                categoryManagerOpen = false,
                selectedType = CatalogType.SERIES,
                selectedSeries = entry.tmdb.asMediaItem(),
                seriesOpenedFromHome = true,
                seriesOpenedFromFavorites = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null
            )
        }

        val resolved = runCatching {
            entry.iptv
                ?: matchTmdbSeries(entry.tmdb, localSeriesCandidates(_state.value))
                ?: resolveTmdbSeriesFromPortal(session, entry)
        }.getOrElse { error ->
            returnToHomeAfterTrendingSeriesFailure()
            throw error
        }

        if (resolved == null) {
            returnToHomeAfterTrendingSeriesFailure()
            error(
                "\"${entry.tmdb.name}\" was not found in this IPTV profile."
            )
        }


        store.saveTmdbMapping(
            TmdbIptvMapping(session.profile.cacheKey(), CatalogType.SERIES, entry.tmdb.id, resolved)
        )

        _state.update { current ->
            current.copy(
                trendingSeries = current.trendingSeries.map { item ->
                    if (item.tmdb.id == entry.tmdb.id) {
                        item.copy(iptv = resolved)
                    } else item
                },
                homeOpen = false,
                favoritesOpen = false,
                settingsOpen = false,
                searchOpen = false,
                selectedType = CatalogType.SERIES,
                selectedSeries = resolved,
                seriesOpenedFromHome = true,
                seriesOpenedFromFavorites = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null
            )
        }

        loadSeriesEpisodes(resolved)
    }

    private fun returnToHomeAfterTrendingSeriesFailure() {
        _state.update {
            it.copy(
                selectedSeries = null,
                seriesOpenedFromHome = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null,
                homeOpen = true
            )
        }
    }

    private suspend fun loadCategorySections(session: PortalSession, type: CatalogType) {
        val profileKey = session.profile.cacheKey()
        val categories = _state.value.categories.take(DASHBOARD_CATEGORY_LIMIT)

        for (category in categories) {
            if (_state.value.session?.profile?.cacheKey() != profileKey ||
                _state.value.selectedType != type ||
                _state.value.browseLayout != BrowseLayout.SECTIONS
            ) return

            val existing = _state.value.browseCachesByType[type] ?: return
            if (existing.itemsByCategory.containsKey(category.id)) continue
            val items = runCatching { portal.catalog(session, category) }
                .getOrDefault(emptyList())
                .filter { it.portalCategoryId == null || it.portalCategoryId == category.id }
                .take(50)
            val updated = existing.copy(
                cachedAtMillis = System.currentTimeMillis(),
                itemsByCategory = existing.itemsByCategory + (category.id to items)
            )
            store.saveBrowseCatalog(updated)
            _state.update { current ->
                if (current.session?.profile?.cacheKey() != profileKey) current
                else current.copy(
                    browseCache = updated,
                    browseCachesByType = current.browseCachesByType + (type to updated)
                )
            }
        }

        if (type == CatalogType.MOVIES || type == CatalogType.SERIES) {
            enrichCatalogArtwork(session, type)
        }
    }

    fun loadMoreCategorySection(category: Category) = viewModelScope.launch {
        val snapshot = _state.value
        val session = snapshot.session ?: return@launch
        if (snapshot.browseLayout != BrowseLayout.SECTIONS || snapshot.selectedType != category.type) return@launch
        val profileKey = session.profile.cacheKey()
        val cache = snapshot.browseCachesByType[category.type] ?: return@launch
        val currentItems = cache.itemsByCategory[category.id].orEmpty()
        if (currentItems.size >= 50) return@launch

        val additions = if (session.profile.portalType == PortalType.STALKER) {
            val nextPage = (currentItems.size / STALKER_SECTION_PAGE_SIZE) + 1
            runCatching { portal.catalogPage(session, category, nextPage).items }.getOrDefault(emptyList())
        } else {
            // Xtream sections are streamed and bounded during their initial request.
            emptyList()
        }
        if (additions.isEmpty()) return@launch

        val merged = (currentItems + additions)
            .distinctBy { it.id }
            .take(50)
        if (merged.size == currentItems.size) return@launch
        val updated = cache.copy(
            cachedAtMillis = System.currentTimeMillis(),
            itemsByCategory = cache.itemsByCategory + (category.id to merged)
        )
        store.saveBrowseCatalog(updated)
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profileKey) current
            else current.copy(
                items = if (current.selectedCategory?.id == category.id) merged else current.items,
                browseCache = updated,
                browseCachesByType = current.browseCachesByType + (category.type to updated)
            )
        }
    }

    private suspend fun enrichCatalogArtwork(session: PortalSession, type: CatalogType) = coroutineScope {
        if (!tmdb.configured) return@coroutineScope
        val profileKey = session.profile.cacheKey()
        val cache = _state.value.browseCachesByType[type] ?: return@coroutineScope
        val targets = cache.itemsByCategory.values.flatten()
            .distinctBy { it.id }
            .filterNot { it.logo.orEmpty().startsWith("https://image.tmdb.org/") }
            .take(30)

        val artwork = targets.map { media ->
            async { media.id to runCatching { tmdb.artworkFor(media, type) }.getOrNull() }
        }.awaitAll().mapNotNull { (id, logo) -> logo?.let { id to it } }.toMap()
        if (artwork.isEmpty() || _state.value.session?.profile?.cacheKey() != profileKey) return@coroutineScope

        val enrichedItems = cache.itemsByCategory.mapValues { (_, items) ->
            items.map { media -> artwork[media.id]?.let { media.copy(logo = it) } ?: media }
        }
        val enrichedCache = cache.copy(itemsByCategory = enrichedItems)
        store.saveBrowseCatalog(enrichedCache)
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profileKey) current
            else current.copy(
                items = enrichedItems[current.selectedCategory?.id] ?: current.items,
                browseCache = enrichedCache,
                browseCachesByType = current.browseCachesByType + (type to enrichedCache)
            )
        }
        prefetchArtwork(getApplication(), enrichedItems.values.flatten(), limit = 10)
    }

    private suspend fun resolveTmdbMovieFromPortal(
        session: PortalSession,
        entry: TrendingMovie
    ): MediaItem? {
        if (session.profile.portalType == PortalType.XTREAM) {
            return resolveTmdbFromXtreamCatalog(
                session,
                CatalogType.MOVIES,
                entry.tmdb.id
            ) { candidates -> matchTmdbMovie(entry.tmdb, candidates) }
        }

        val queries = listOf(entry.tmdb.title, entry.tmdb.originalTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for (query in queries) {
            val page = portal.search(
                session = session,
                type = SearchContentType.MOVIES,
                query = query,
                page = 1,
                categoryId = "*"
            )
            matchTmdbMovie(entry.tmdb, page.items)?.let { return it }
        }
        return null
    }

    private suspend fun resolveTmdbSeriesFromPortal(
        session: PortalSession,
        entry: TrendingSeries
    ): MediaItem? {
        if (session.profile.portalType == PortalType.XTREAM) {
            return resolveTmdbFromXtreamCatalog(
                session,
                CatalogType.SERIES,
                entry.tmdb.id
            ) { candidates -> matchTmdbSeries(entry.tmdb, candidates) }
        }

        val queries = listOf(entry.tmdb.name, entry.tmdb.originalName)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for (query in queries) {
            val page = portal.search(
                session = session,
                type = SearchContentType.SERIES,
                query = query,
                page = 1,
                categoryId = "*"
            )
            matchTmdbSeries(entry.tmdb, page.items)?.let { return it }
        }
        return null
    }

    private suspend fun resolveTmdbFromXtreamCatalog(
        session: PortalSession,
        type: CatalogType,
        tmdbId: Int,
        titleMatcher: (List<MediaItem>) -> MediaItem?
    ): MediaItem? {
        val profileKey = session.profile.cacheKey()
        val cached = store.searchCatalog(type, profileKey).first()?.items
        val candidates = if (!cached.isNullOrEmpty()) {
            cached
        } else {
            portal.fullCatalog(session, type, emptyList()).also { items ->
                if (items.isNotEmpty()) {
                    store.saveSearchCatalog(
                        SearchCatalogCache(profileKey, type, System.currentTimeMillis(), items)
                    )
                }
            }
        }

        return candidates.firstOrNull { it.externalTmdbId == tmdbId }
            ?: titleMatcher(candidates)
    }

    private fun localMovieCandidates(snapshot: NikTvState): List<MediaItem> {
        val cached = snapshot
            .browseCachesByType[CatalogType.MOVIES]
            ?.itemsByCategory
            ?.values
            ?.flatten()
            .orEmpty()

        val visible =
            if (snapshot.selectedType == CatalogType.MOVIES) snapshot.items
            else emptyList()

        return (cached + visible).distinctBy { it.id }
    }

    private fun localSeriesCandidates(snapshot: NikTvState): List<MediaItem> {
        val cached = snapshot
            .browseCachesByType[CatalogType.SERIES]
            ?.itemsByCategory
            ?.values
            ?.flatten()
            .orEmpty()

        val visible =
            if (
                snapshot.selectedType == CatalogType.SERIES &&
                snapshot.selectedSeries == null
            ) snapshot.items else emptyList()

        return (cached + visible).distinctBy { it.id }
    }

    private fun List<TrendingMovie>.resolveMovie(
        tmdbId: Int,
        resolved: MediaItem
    ): List<TrendingMovie> = map { item ->
        if (item.tmdb.id == tmdbId) item.copy(iptv = resolved)
        else item
    }

    fun openMedia(item: MediaItem) {
        val snapshot = _state.value
        if (snapshot.selectedType == CatalogType.SERIES && snapshot.selectedSeries == null) {
            task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(selectedSeries = item, items = emptyList(), availableSeriesSeasons = emptyList(), selectedSeriesSeason = null, seriesOpenedFromFavorites = false, seriesOpenedFromHome = false) }
                loadSeriesEpisodes(item)
            }
        } else {
            val categoryItems = snapshot.browseCachesByType[snapshot.selectedType]
                ?.itemsByCategory
                ?.let { itemsByCategory ->
                    item.portalCategoryId
                        ?.let(itemsByCategory::get)
                        ?.takeIf { category -> category.any { it.id == item.id } }
                        ?: itemsByCategory.values.firstOrNull { category ->
                            category.any { it.id == item.id }
                        }
                }
            val queue = categoryItems
                ?: snapshot.items.takeIf { visible -> visible.any { it.id == item.id } }
                ?: emptyList()
            play(item, snapshot.selectedType, snapshot.selectedSeries, queue)
        }
    }

    fun prepareFullSearch(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.selectedSeries != null || snapshot.fullSearchLoading) return
        val session = snapshot.session ?: return
        val type = snapshot.selectedType
        val categories = snapshot.categories
        val profileKey = session.profile.cacheKey()
        val now = System.currentTimeMillis()
        val searchCacheTtl = snapshot.cacheIntervalMinutes * 60_000L
        if (!forceRefresh && snapshot.fullSearchItems != null &&
            now - (snapshot.fullSearchCachedAtMillis ?: 0L) < searchCacheTtl) return
        viewModelScope.launch {
            _state.update { it.copy(fullSearchLoading = true) }
            if (!forceRefresh) {
                store.searchCatalog(type, profileKey).first()?.let { cached ->
                    _state.update { current ->
                        if (current.selectedType == type && current.selectedSeries == null)
                            current.copy(fullSearchItems = cached.items, fullSearchCachedAtMillis = cached.cachedAtMillis)
                        else current
                    }
                    if (now - cached.cachedAtMillis < searchCacheTtl) {
                        _state.update { it.copy(fullSearchLoading = false) }
                        return@launch
                    }
                }
            }
            runCatching {
                portal.fullCatalog(session, type, categories)
            }.onSuccess { results ->
                val cachedAt = System.currentTimeMillis()
                store.saveSearchCatalog(SearchCatalogCache(profileKey, type, cachedAt, results))
                _state.update { current ->
                    if (current.selectedType == type && current.selectedSeries == null)
                        current.copy(fullSearchItems = results, fullSearchLoading = false, fullSearchCachedAtMillis = cachedAt)
                    else current.copy(fullSearchLoading = false)
                }
            }.onFailure { error ->
                _state.update { it.copy(fullSearchLoading = false, error = error.message ?: "Could not search the full catalog") }
            }
        }
    }

    fun refreshFullSearch() = prepareFullSearch(forceRefresh = true)

    fun openSearch() {
        _state.update { it.copy(searchOpen = true, settingsOpen = false, favoritesOpen = false) }
        loadSearchCategories(_state.value.searchType)
    }
    fun closeSearch() = _state.update { it.copy(searchOpen = false, searchServerLoading = false) }
    fun setSearchType(type: SearchContentType) = _state.update {
        it.copy(searchType = type, searchResults = emptyList(), searchUsedServer = false, searchPage = 0,
            searchHasMore = false, searchCategoryId = "*", searchCategories = emptyList())
    }.also { loadSearchCategories(type) }
    fun setSearchCategory(categoryId: String) = _state.update {
        it.copy(searchCategoryId = categoryId, searchResults = emptyList(), searchUsedServer = false, searchPage = 0, searchHasMore = false)
    }

    private fun loadSearchCategories(type: SearchContentType) = viewModelScope.launch {
        val catalogType = when (type) {
            SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            SearchContentType.MOVIES -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        val session = _state.value.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val cached = store.browseCatalog(catalogType, profileKey).first()?.categories.orEmpty()
        val raw = if (cached.isNotEmpty()) cached else runCatching { portal.categories(session, catalogType) }.getOrDefault(emptyList())
        val filtered = filterCategories(raw, profileKey, catalogType, _state.value.categoryFilters)
        _state.update { current ->
            if (current.searchType == type) current.copy(searchCategories = filtered.distinctBy { it.id }) else current
        }
    }
    fun setSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    fun search(forceServer: Boolean = false) {
        val snapshot = _state.value
        val query = snapshot.searchQuery.trim()
        if (query.isBlank() || snapshot.searchServerLoading) return
        viewModelScope.launch {
            rememberSearch(query, snapshot.searchType)
            val saved = store.pagedSearches.first().firstOrNull {
                it.profileKey == snapshot.session?.profile?.cacheKey() && it.type == snapshot.searchType &&
                    it.categoryId == snapshot.searchCategoryId && it.query.equals(query, true)
            }
            val local = (localSearch(snapshot.searchType, query) + saved?.items.orEmpty()).distinctBy { it.id }
            _state.update { it.copy(searchResults = local, searchUsedServer = saved != null,
                searchPage = saved?.lastPage ?: 0, searchHasMore = saved?.hasMore ?: false) }
            if (!forceServer && local.isNotEmpty()) return@launch
            fetchSearchPage(query, snapshot.searchType, snapshot.searchCategoryId, 1, emptyList())
        }
    }

    fun loadMoreSearch() {
        val snapshot = _state.value
        if (!snapshot.searchHasMore || snapshot.searchServerLoading || snapshot.searchQuery.isBlank()) return
        viewModelScope.launch {
            repeat(3) {
                val current = _state.value
                if (!current.searchHasMore || current.searchServerLoading) return@launch
                val previousPage = current.searchPage
                fetchSearchPage(current.searchQuery.trim(), current.searchType, current.searchCategoryId,
                    current.searchPage + 1, current.searchResults)
                if (_state.value.searchPage == previousPage) return@launch
            }
        }
    }

    private suspend fun fetchSearchPage(query: String, type: SearchContentType, categoryId: String, page: Int, existing: List<MediaItem>) {
            _state.update { it.copy(searchServerLoading = true) }
            val session = requireNotNull(_state.value.session)
            runCatching { portal.search(session, type, query, page, categoryId) }
                .onSuccess { result ->
                    val combined = (existing + result.items).distinctBy { it.id }
                    val cache = SearchResultCache(session.profile.cacheKey(), type, query, categoryId, result.page, result.hasMore, combined)
                    store.savePagedSearch(cache)
                    _state.update { current -> current.copy(searchResults = combined,
                        searchServerLoading = false, searchUsedServer = true,
                        searchPage = result.page, searchHasMore = result.hasMore) }
                }.onFailure { error ->
                    _state.update { it.copy(searchServerLoading = false, error = error.message ?: "Server search failed") }
                }
    }

    private suspend fun localSearch(type: SearchContentType, query: String): List<MediaItem> {
        val catalogType = when (type) {
            SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            SearchContentType.SERIES -> CatalogType.SERIES
            else -> CatalogType.MOVIES
        }
        val profileKey = _state.value.session?.profile?.cacheKey()
        val indexed = store.searchCatalog(catalogType, profileKey).first()?.items.orEmpty()
        val browsed = store.browseCatalog(catalogType, profileKey).first()?.itemsByCategory?.values?.flatten().orEmpty()
        val episodes = if (type == SearchContentType.EPISODES) {
            (_state.value.favorites.filter { it.kind == FavoriteKind.EPISODE }.map { it.media } +
                _state.value.recentlyPlayed.filter { it.kind == FavoriteKind.EPISODE }.map { it.media } +
                _state.value.items.filter { it.episodeNumber != null })
        } else emptyList()
        val source = if (type == SearchContentType.EPISODES) episodes else indexed + browsed
        val categoryId = _state.value.searchCategoryId
        return source.distinctBy { it.id }
            .filter { (categoryId == "*" || it.portalCategoryId == categoryId) && it.title.matchesTitleKeywords(query) }
            .sortedByDescending { it.title.titleKeywordScore(query) }
    }

    private suspend fun rememberSearch(query: String, type: SearchContentType) {
        val snapshot = _state.value
        val categoryId = snapshot.searchCategoryId
        val categoryTitle = snapshot.searchCategories.firstOrNull { it.id == categoryId }?.title
            ?: if (categoryId == "*") "All categories" else categoryId
        store.addRecentSearch(RecentSearch(
            query = query,
            type = type,
            categoryId = categoryId,
            categoryTitle = categoryTitle
        ))
    }
    fun useRecentSearch(search: RecentSearch) {
        _state.update { it.copy(searchQuery = search.query, searchType = search.type, searchOpen = true,
            searchCategoryId = search.categoryId, searchCategories = emptyList(), searchResults = emptyList()) }
        loadSearchCategories(search.type)
        search()
    }
    fun deleteRecentSearch(search: RecentSearch) = viewModelScope.launch {
        store.removeRecentSearch(search)
    }
    fun openSearchResult(item: MediaItem) {
        when (_state.value.searchType) {
            SearchContentType.LIVE_TV -> play(item, CatalogType.LIVE_TV)
            SearchContentType.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(searchOpen = false, homeOpen = false, selectedType = CatalogType.SERIES, selectedSeries = item, items = emptyList(), availableSeriesSeasons = emptyList(), selectedSeriesSeason = null) }
                loadSeriesEpisodes(item)
            }
            SearchContentType.MOVIES -> play(item, CatalogType.MOVIES)
            SearchContentType.EPISODES -> play(item, CatalogType.SERIES)
        }
    }

    private fun play(item: MediaItem, type: CatalogType, series: MediaItem? = null, episodes: List<MediaItem> = emptyList()) = task {
        playInternal(item, type, series, episodes)
    }

    private suspend fun playInternal(
        item: MediaItem,
        type: CatalogType,
        series: MediaItem?,
        episodes: List<MediaItem>,
        forceFreshUrl: Boolean = false,
        authorizationRetryCount: Int = 0,
        resumePositionOverride: Long? = null
    ) {
        var session = requireNotNull(_state.value.session)
        val urlKey = "${type.name}:${item.id}"
        // Stalker create_link results are signed/session-bound and can expire after playback.
        // Only Xtream VOD paths are stable enough to reuse. Retry always bypasses every cache.
        val mayReuseUrl = !forceFreshUrl &&
            type != CatalogType.LIVE_TV &&
            session.profile.portalType == PortalType.XTREAM
        val cachedUrl = if (mayReuseUrl) {
            _state.value.playbackUrls.firstOrNull { it.key == urlKey }?.url
        } else null
        val url = cachedUrl ?: runCatching { portal.playableUrl(session, item, type) }.getOrElse { firstError ->
            if (session.profile.portalType != PortalType.STALKER || !firstError.isAuthenticationFailure()) throw firstError
            session = refreshSession(session.profile)
            portal.playableUrl(session, item, type)
        }.also { resolved ->
            if (type != CatalogType.LIVE_TV && session.profile.portalType == PortalType.XTREAM) {
                val updated = (listOf(PlaybackUrl(urlKey, resolved)) + _state.value.playbackUrls.filterNot { it.key == urlKey })
                    .take(MAX_PLAYBACK_URLS)
                _state.update { it.copy(playbackUrls = updated) }
                store.savePlaybackUrls(updated)
            }
        }
        val playbackQueue = when (type) {
            CatalogType.SERIES -> episodes.sortedWith(
                compareBy<MediaItem>({ it.seasonNumber ?: Int.MAX_VALUE }, { it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE }, { it.title.lowercase() })
            )
            CatalogType.LIVE_TV,
            CatalogType.MOVIES,
            CatalogType.RADIO -> playbackCatalogQueue(session, item, type, episodes)
        }
        val queueIndex = playbackQueue.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
        val wrapLiveQueue = type == CatalogType.LIVE_TV && playbackQueue.size > 1
        val previousItem = queueIndex?.let { index ->
            playbackQueue.getOrNull(index - 1) ?: playbackQueue.lastOrNull().takeIf { wrapLiveQueue }
        }
        val nextItem = queueIndex?.let { index ->
            playbackQueue.getOrNull(index + 1) ?: playbackQueue.firstOrNull().takeIf { wrapLiveQueue }
        }
        // Use a profile-scoped, content-based key so resume survives session/token refreshes.
        val progressKey = progressKeyFor(session.profile, item, type, series)
        val legacyProgressKey = legacyProgressKeyFor(item, type)
        // Keep backward compatibility with already-saved progress entries.
        val saved = _state.value.playbackProgress.firstOrNull { it.key == progressKey }
            ?: _state.value.playbackProgress
                .asSequence()
                .filter { it.key == legacyProgressKey }
                .maxByOrNull { it.updatedAtMillis }
        val resumePosition = resumePositionOverride
            ?: saved?.positionMillis?.takeIf { saved.durationMillis <= 0L || saved.durationMillis - it > 5_000L }
            ?: 0L
        _state.update {
            it.copy(
                nowPlaying = PlayingMedia(
                    media = item,
                    url = url,
                    catalogType = type,
                    previousEpisode = previousItem,
                    nextEpisode = nextItem,
                    series = series,
                    episodeQueue = playbackQueue,
                    resumePositionMillis = resumePosition,
                    progressKey = progressKey,
                    authorizationRetryCount = authorizationRetryCount
                ),
                playbackReturnFocusId = item.id
            )
        }
        recordRecent(item, type, series)
    }

    private suspend fun playbackCatalogQueue(
        session: PortalSession,
        item: MediaItem,
        type: CatalogType,
        supplied: List<MediaItem>
    ): List<MediaItem> {
        if (supplied.isNotEmpty()) return supplied.distinctBy { it.id }

        val snapshot = _state.value
        val visible = snapshot.items.distinctBy { it.id }
        if (snapshot.selectedType == type && visible.any { it.id == item.id }) {
            return visible
        }

        val categoryId = item.portalCategoryId
        if (!categoryId.isNullOrBlank()) {
            val cached = snapshot.browseCachesByType[type]
                ?.itemsByCategory
                ?.get(categoryId)
                .orEmpty()
            if (cached.isNotEmpty()) return (listOf(item) + cached).distinctBy { it.id }

            val category = snapshot.rawCategoriesByType[type]
                .orEmpty()
                .firstOrNull { it.id == categoryId }
                ?: runCatching {
                    portal.categories(session, type).firstOrNull { it.id == categoryId }
                }.getOrNull()
            if (category != null) {
                val loaded = runCatching { portal.catalog(session, category) }.getOrDefault(emptyList())
                if (loaded.isNotEmpty()) return (listOf(item) + loaded).distinctBy { it.id }
            }
        }

        // A single correct item is safer than presenting an unrelated category.
        return listOf(item)
    }

    private suspend fun refreshSession(profile: PortalProfile): PortalSession {
        val refreshed = portal.authenticate(profile)
        store.save(refreshed)
        _state.update { it.copy(session = refreshed, savedProfile = refreshed.profile) }
        return refreshed
    }

    fun savePlaybackProgress(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank()) return
        viewModelScope.launch {
            val current = _state.value.playbackProgress
            val updated = if (durationMillis > 0L && positionMillis >= durationMillis - 5_000L) {
                current.filterNot { it.key == key }
            } else {
                (listOf(PlaybackProgress(key, positionMillis.coerceAtLeast(0L), durationMillis)) + current.filterNot { it.key == key })
                    .take(MAX_PROGRESS_ITEMS)
            }
            _state.update { it.copy(playbackProgress = updated) }
            store.savePlaybackProgress(updated)
        }
    }


    /*
     * MOVIE_LIVE_NAVIGATION_QUEUE_V8
     *
     * PlayingMedia.episodeQueue is a snapshot. For catalog-style playback,
     * prefer the current visible catalog when it still contains the active
     * item so Next/Previous follows exactly what the user sees in the rail.
     */
    private fun playbackNavigationQueue(
        playing: PlayingMedia
    ): List<MediaItem> {
        val snapshot = _state.value
        val currentCatalog =
            snapshot.items.distinctBy { it.id }

        val catalogCanOwnNavigation =
            playing.catalogType in setOf(
                CatalogType.LIVE_TV,
                CatalogType.MOVIES,
                CatalogType.RADIO
            ) &&
                snapshot.selectedType == playing.catalogType &&
                currentCatalog.any {
                    it.id == playing.media.id
                }

        return if (catalogCanOwnNavigation) {
            currentCatalog
        } else {
            playing.episodeQueue.distinctBy { it.id }
        }
    }

    fun playNextEpisode() {
        val playing = _state.value.nowPlaying ?: return
        val queue = playbackNavigationQueue(playing)

        val index =
            queue.indexOfFirst {
                it.id == playing.media.id
            }

        if (index < 0) return

        val next =
            queue.getOrNull(index + 1)
                ?: queue
                    .firstOrNull()
                    .takeIf {
                        playing.catalogType ==
                            CatalogType.LIVE_TV &&
                            queue.size > 1
                    }
                ?: return

        viewModelScope.launch {
            runCatching {
                playInternal(
                    item = next,
                    type = playing.catalogType,
                    series = playing.series,
                    episodes = queue
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        error =
                            error.message
                                ?: "Could not play the next item"
                    )
                }
            }
        }
    }

    fun playPreviousEpisode() {
        val playing = _state.value.nowPlaying ?: return
        val queue = playbackNavigationQueue(playing)

        val index =
            queue.indexOfFirst {
                it.id == playing.media.id
            }

        if (index < 0) return

        val previous =
            queue.getOrNull(index - 1)
                ?: queue
                    .lastOrNull()
                    .takeIf {
                        playing.catalogType ==
                            CatalogType.LIVE_TV &&
                            queue.size > 1
                    }
                ?: return

        viewModelScope.launch {
            runCatching {
                playInternal(
                    item = previous,
                    type = playing.catalogType,
                    series = playing.series,
                    episodes = queue
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        error =
                            error.message
                                ?: "Could not play the previous item"
                    )
                }
            }
        }
    }

    private suspend fun recordRecent(item: MediaItem, type: CatalogType, series: MediaItem?) {
        val profileKey = requireNotNull(_state.value.session).profile.cacheKey()
        val additions = buildList {
            if (type == CatalogType.SERIES && series != null) {
                add(RecentItem(FavoriteKind.SERIES, series, lastPlayed = item, profileKey = profileKey))
            } else add(RecentItem(when (type) {
                CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
                CatalogType.MOVIES -> FavoriteKind.MOVIE
                CatalogType.SERIES -> FavoriteKind.SERIES
                CatalogType.RADIO -> FavoriteKind.CHANNEL
            }, item, series, profileKey = profileKey))
        }
        val updated = (additions + _state.value.recentlyPlayed.filterNot { it.kind == FavoriteKind.EPISODE })
            .distinctBy { it.key }.take(MAX_RECENT_ITEMS)
        _state.update { it.copy(recentlyPlayed = updated) }
        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == profileKey } + updated
        store.saveRecentlyPlayed(allRecentlyPlayed)
    }

    fun closeSeries() = task {
        if (_state.value.seriesOpenedFromHome) {
            _state.update { it.copy(selectedSeries = null, seriesOpenedFromHome = false, homeOpen = true) }
            return@task
        }
        if (_state.value.seriesOpenedFromFavorites) {
            _state.update { it.copy(selectedSeries = null, seriesOpenedFromFavorites = false, favoritesOpen = true) }
            return@task
        }
        val category = requireNotNull(_state.value.selectedCategory)
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedSeries = null, items = emptyList()) }
        _state.update { it.copy(items = portal.catalog(session, category)) }
    }

    fun closePlayer() = _state.update { it.copy(nowPlaying = null) }
    fun retryPlayback() {
        val playing = _state.value.nowPlaying ?: return
        viewModelScope.launch {
            _state.update { it.copy(nowPlaying = null, error = null) }
            runCatching {
                playInternal(
                    playing.media,
                    playing.catalogType,
                    playing.series,
                    playing.episodeQueue,
                    forceFreshUrl = true
                )
            }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Playback retry failed") } }
        }
    }
    fun retryPlaybackWithAlternateDecoder(
        positionMillis: Long
    ) {
        val playing =
            _state.value.nowPlaying
                ?: return

        viewModelScope.launch {
            /*
             * MTK_DECODER_RELEASE_GRACE_V14
             *
             * Recreate the player so the decoder selector is queried again.
             * MediaTek/Fire TV codec services can take longer than a single
             * frame to release the failed codec instance, so allow a short
             * grace period before constructing the replacement player.
             *
             * Keep the already-resolved stream URL: a codec crash is not a
             * portal/link failure.
             */
            _state.update {
                it.copy(
                    nowPlaying = null,
                    error = null
                )
            }

            delay(450L)

            _state.update {
                it.copy(
                    nowPlaying =
                        playing.copy(
                            resumePositionMillis =
                                positionMillis
                                    .coerceAtLeast(0L)
                        )
                )
            }
        }
    }
    fun retryPlaybackAfterAuthorizationFailure(positionMillis: Long) {
        val playing = _state.value.nowPlaying ?: return
        if (playing.authorizationRetryCount > 0) return
        viewModelScope.launch {
            _state.update { it.copy(nowPlaying = null, error = null) }
            runCatching {
                playInternal(
                    item = playing.media,
                    type = playing.catalogType,
                    series = playing.series,
                    episodes = playing.episodeQueue,
                    forceFreshUrl = true,
                    authorizationRetryCount = playing.authorizationRetryCount + 1,
                    resumePositionOverride = positionMillis
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not refresh stream authorization") }
            }
        }
    }
    fun openSettings() = _state.update { it.copy(settingsOpen = true) }
    fun openSettingsFromProfileChooser(profile: PortalProfile) =
        _state.update { it.copy(savedProfile = profile, settingsOpen = true, profileEditorOpen = false) }
    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }
    fun reauthenticate() { _state.value.savedProfile?.let(::connect) }
    fun editProfile() = _state.update { it.copy(session = null, settingsOpen = false, profileEditorOpen = true) }
    fun addProfile() = _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false, profileEditorOpen = true) }
    fun openProfileSwitcher() {
        val snapshot = _state.value
        if (snapshot.profiles.size < 2) return
        val currentIndex = snapshot.profiles.indexOfFirst {
            it.cacheKey() == snapshot.savedProfile?.cacheKey()
        }.takeIf { it >= 0 } ?: 0
        switchProfile(snapshot.profiles[(currentIndex + 1) % snapshot.profiles.size])
    }
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching {
            val content = store.exportBackup()
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                it.write(content)
            }
        }.onSuccess {
            _state.update { it.copy(backupMessage = "NikTV backup exported") }
        }.onFailure { error ->
            _state.update { it.copy(backupMessage = "Could not export backup: ${error.message}") }
        }
    }
    fun importBackup(uri: Uri) = viewModelScope.launch {
        runCatching {
            val content = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            store.importBackup(content)
        }.onSuccess {
            val profiles = store.profiles.first()
            _state.update { NikTvState(
                profiles = profiles,
                savedProfile = profiles.firstOrNull(),
                profileEditorOpen = profiles.isEmpty(),
                restoring = false,
                backupMessage = "Backup imported. Choose a profile to authenticate."
            ) }
        }.onFailure { error ->
            _state.update { it.copy(backupMessage = "Could not import backup: ${error.message}") }
        }
    }
    fun dismissBackupMessage() = _state.update { it.copy(backupMessage = null) }
    fun cancelProfileEditor() = _state.update { current ->
        val fallback = current.profiles.firstOrNull()
        current.copy(profileEditorOpen = false, savedProfile = fallback)
    }
    fun openCategoryManager(type: CatalogType = _state.value.selectedType) {
        _state.update { it.copy(categoryManagerOpen = true, categoryManagerType = type) }
        loadRawCategoriesFor(type)
    }
    fun closeCategoryManager() = _state.update { it.copy(categoryManagerOpen = false) }
    fun setCategoryManagerType(type: CatalogType) {
        _state.update { it.copy(categoryManagerType = type) }
        loadRawCategoriesFor(type)
    }
    fun loadRawCategoriesFor(type: CatalogType) {
        val snapshot = _state.value
        if (snapshot.rawCategoriesByType[type]?.isNotEmpty() == true) return
        val session = snapshot.session ?: return
        viewModelScope.launch {
            val cached = store.browseCatalog(type, session.profile.cacheKey()).first()?.categories.orEmpty()
            val categories = if (cached.isNotEmpty()) cached else runCatching { portal.categories(session, type) }.getOrDefault(emptyList())
            if (categories.isNotEmpty()) {
                _state.update { current ->
                    val updated = current.rawCategoriesByType + (type to categories)
                    current.copy(rawCategoriesByType = updated)
                }
            }
        }
    }
    fun setCategoryFilter(type: CatalogType, enabledCategoryIds: List<String>) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.saveCategoryFilter(profileKey, type, enabledCategoryIds.distinct().take(DASHBOARD_CATEGORY_LIMIT))
    }

    fun applyCategoryFilters(selections: Map<CatalogType, List<String>>) = viewModelScope.launch {
        val snapshot = _state.value
        val session = snapshot.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val normalized = selections.mapValues { (_, ids) ->
            ids.distinct().take(DASHBOARD_CATEGORY_LIMIT)
        }
        if (normalized.isEmpty()) return@launch

        _state.update {
            it.copy(
                feedRefreshing = true,
                feedRefreshMessage = "Refreshing ${snapshot.selectedType.title} feed…"
            )
        }
        categoryFilterApplyInProgress = true
        try {
            normalized.forEach { (type, ids) ->
                store.saveCategoryFilter(profileKey, type, ids)
            }

            val activeIds = normalized[snapshot.selectedType]
            if (activeIds != null) {
                val updatedFilters = _state.value.categoryFilters +
                    (filterKey(profileKey, snapshot.selectedType) to activeIds)
                val raw = _state.value.rawCategoriesByType[snapshot.selectedType]
                    ?: _state.value.categories
                val categories = filterCategories(raw, profileKey, snapshot.selectedType, updatedFilters)
                val selected = categories.firstOrNull {
                    it.id == _state.value.selectedCategory?.id
                } ?: categories.firstOrNull {
                    snapshot.selectedType != CatalogType.SERIES || it.id != "*"
                } ?: categories.firstOrNull()
                _state.update {
                    it.copy(
                        categoryFilters = updatedFilters,
                        categories = categories,
                        selectedCategory = selected
                    )
                }

                if (snapshot.browseLayout == BrowseLayout.SECTIONS) {
                    loadCategorySections(session, snapshot.selectedType)
                } else {
                    loadTypeInternal(
                        session = session,
                        type = snapshot.selectedType,
                        preferredCategoryId = selected?.id
                    )
                }
            }
        } finally {
            categoryFilterApplyInProgress = false
            _state.update { it.copy(feedRefreshing = false) }
        }
    }
    fun toggleCategoryFilter(type: CatalogType, categoryId: String) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val raw = _state.value.rawCategoriesByType[type].orEmpty()
        val filterKey = filterKey(profileKey, type)
        val currentEnabled = _state.value.categoryFilters[filterKey] ?: raw.take(DASHBOARD_CATEGORY_LIMIT).map { it.id }
        val updated = if (categoryId in currentEnabled) {
            currentEnabled - categoryId
        } else if (currentEnabled.size >= DASHBOARD_CATEGORY_LIMIT) {
            currentEnabled
        } else {
            currentEnabled + categoryId
        }
        setCategoryFilter(type, updated)
    }
    fun selectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val firstCategories = _state.value.rawCategoriesByType[type].orEmpty()
            .take(DASHBOARD_CATEGORY_LIMIT)
            .map { it.id }
        store.saveCategoryFilter(profileKey, type, firstCategories)
    }
    fun deselectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.saveCategoryFilter(profileKey, type, emptyList())
    }

    fun switchProfile(profile: PortalProfile) = task {
        _state.update { it.copy(savedProfile = profile) }
        updateProfileLoad(0.04f, "Loading ${profile.name}…")
        store.activate(profile)
        // Profile selection is the session boundary: always obtain a fresh token.
        updateProfileLoad(0.08f, "Authenticating ${profile.name}…")
        val session = portal.authenticate(profile).also { store.save(it) }
        updateProfileLoad(0.24f, "Authentication complete")
        _state.update { current -> current.copy(session = session, savedProfile = profile, settingsOpen = false, profileEditorOpen = false,
            searchOpen = false, favoritesOpen = false, homeOpen = true, categories = emptyList(), rawCategoriesByType = emptyMap(),
            categoryManagerOpen = false, items = emptyList(),
            selectedSeries = null, browseCache = null, browseCachesByType = emptyMap(), fullSearchItems = null, playbackUrls = emptyList(),
            trendingMovies = emptyList(), trendingMoviesLoading = false, trendingMoviesError = null,
            trendingSeries = emptyList(), trendingSeriesLoading = false, trendingSeriesError = null,
            thrillerMovies = emptyList(), thrillerMoviesLoading = false, thrillerMoviesError = null) }
        loadProfileLibrary(session.profile.cacheKey())
        preloadDashboard(session)
    }
    fun removeProfile(profile: PortalProfile) = viewModelScope.launch {
        store.removeProfile(profile)
        if (_state.value.savedProfile?.cacheKey() == profile.cacheKey()) {
            _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false) }
        }
    }
    fun openFavorites() = _state.update { it.copy(favoritesOpen = true, homeOpen = false, settingsOpen = false, searchOpen = false) }
    fun closeFavorites() = _state.update { it.copy(favoritesOpen = false) }
    fun openHome() {
        _state.update { it.copy(
            homeOpen = true,
            favoritesOpen = false,
            settingsOpen = false,
            searchOpen = false,
            selectedSeries = null,
            seriesOpenedFromFavorites = false,
            seriesOpenedFromHome = false,
            availableSeriesSeasons = emptyList(),
            selectedSeriesSeason = null
        ) }
        viewModelScope.launch { runCatching { refreshWatchedSeriesIfDue() } }
        loadDashboardDiscovery()
    }
    fun closeHome() = _state.update { it.copy(homeOpen = false) }

    fun toggleFavorite(item: MediaItem) {
        val snapshot = _state.value
        val kind = when {
            snapshot.selectedType == CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
            snapshot.selectedType == CatalogType.MOVIES -> FavoriteKind.MOVIE
            snapshot.selectedSeries != null -> FavoriteKind.EPISODE
            else -> FavoriteKind.SERIES
        }
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        toggleFavorite(FavoriteItem(kind, item, if (kind == FavoriteKind.EPISODE) snapshot.selectedSeries else null,
            profileKey = profileKey, categoryTitle = snapshot.selectedCategory?.title))
    }

    fun toggleFavorite(favorite: FavoriteItem) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val scopedFavorite = if (favorite.profileKey == profileKey) favorite else favorite.copy(profileKey = profileKey)
        val updated = _state.value.favorites.toMutableList().apply {
            val index = indexOfFirst { it.key == scopedFavorite.key }
            if (index >= 0) removeAt(index) else add(scopedFavorite)
        }
        _state.update { it.copy(favorites = updated) }
        allFavorites = allFavorites.filterNot { it.profileKey == profileKey } + updated
        store.saveFavorites(allFavorites)
    }

    fun openFavorite(favorite: FavoriteItem) {
        when (favorite.kind) {
            FavoriteKind.CHANNEL -> play(favorite.media, CatalogType.LIVE_TV)
            FavoriteKind.MOVIE -> play(favorite.media, CatalogType.MOVIES)
            FavoriteKind.EPISODE -> task {
                val episodes = favorite.series?.let { portal.episodeSeason(requireNotNull(_state.value.session), it, _state.value.seriesStartSeason, favorite.media.seasonNumber).episodes }.orEmpty()
                playInternal(favorite.media, CatalogType.SERIES, favorite.series, episodes)
            }
            FavoriteKind.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(favoritesOpen = false, selectedType = CatalogType.SERIES, selectedSeries = favorite.media, seriesOpenedFromFavorites = true, items = emptyList(), availableSeriesSeasons = emptyList(), selectedSeriesSeason = null) }
                loadSeriesEpisodes(favorite.media)
            }
        }
    }
    fun openRecent(recent: RecentItem) {
        when (recent.kind) {
            FavoriteKind.CHANNEL -> task {
                val snapshot = _state.value
                val session = requireNotNull(snapshot.session)
                val categoryId = recent.media.portalCategoryId
                val category = snapshot.rawCategoriesByType[CatalogType.LIVE_TV]
                    .orEmpty()
                    .firstOrNull { it.id == categoryId }
                    ?: snapshot.categories.firstOrNull { it.id == categoryId }
                    ?: runCatching {
                        portal.categories(session, CatalogType.LIVE_TV).firstOrNull { it.id == categoryId }
                    }.getOrNull()
                if (category != null) {
                    val channels = (listOf(recent.media) + portal.catalog(session, category)).distinctBy { it.id }
                    _state.update {
                        it.copy(
                            homeOpen = false,
                            selectedType = CatalogType.LIVE_TV,
                            selectedCategory = category,
                            items = channels,
                            selectedSeries = null
                        )
                    }
                    playInternal(recent.media, CatalogType.LIVE_TV, null, channels)
                } else {
                    playInternal(recent.media, CatalogType.LIVE_TV, null, listOf(recent.media))
                }
            }
            FavoriteKind.MOVIE -> play(recent.media, CatalogType.MOVIES)
            FavoriteKind.EPISODE -> task {
                val episodes = recent.series?.let { portal.episodeSeason(requireNotNull(_state.value.session), it, _state.value.seriesStartSeason, recent.media.seasonNumber).episodes }.orEmpty()
                playInternal(recent.media, CatalogType.SERIES, recent.series, episodes)
            }
            FavoriteKind.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                val episodes = portal.episodeSeason(session, recent.media, _state.value.seriesStartSeason, recent.lastPlayed?.seasonNumber).episodes
                val resumeEpisode = recent.lastPlayed?.let { saved -> episodes.firstOrNull { it.id == saved.id } ?: saved }
                if (resumeEpisode != null) playInternal(resumeEpisode, CatalogType.SERIES, recent.media, episodes)
                else {
                    _state.update { it.copy(homeOpen = false, selectedType = CatalogType.SERIES, selectedSeries = recent.media, seriesOpenedFromFavorites = false, seriesOpenedFromHome = true, items = episodes) }
                }
            }
        }
    }
    fun removeRecent(recent: RecentItem) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val updated = _state.value.recentlyPlayed.filterNot { it.key == recent.key }
        _state.update { it.copy(recentlyPlayed = updated) }
        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == profileKey } + updated
        store.saveRecentlyPlayed(allRecentlyPlayed)
    }
    fun clearRecent(kind: FavoriteKind) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val updated = _state.value.recentlyPlayed.filterNot { it.kind == kind }
        _state.update { it.copy(recentlyPlayed = updated) }
        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == profileKey } + updated
        store.saveRecentlyPlayed(allRecentlyPlayed)
    }

    fun toggleSeriesWatch() = viewModelScope.launch {
        val snapshot = _state.value
        val series = snapshot.selectedSeries ?: return@launch
        val session = snapshot.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val existing = snapshot.watchedSeries.firstOrNull { it.series.id == series.id }
        val updated = if (existing != null) {
            snapshot.watchedSeries.filterNot { it.key == existing.key }
        } else {
            val latest = runCatching { portal.episodeSeason(session, series, SeriesStartSeason.LAST) }.getOrNull()
            snapshot.watchedSeries + WatchedSeries(
                profileKey = profileKey,
                series = series,
                categoryTitle = snapshot.selectedCategory?.title,
                knownEpisodeIds = latest?.episodes?.map { it.id }?.toSet().orEmpty(),
                checkedAtMillis = System.currentTimeMillis()
            )
        }
        _state.update { it.copy(watchedSeries = updated) }
        allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
        store.saveWatchedSeries(allWatchedSeries)
    }

    private suspend fun refreshWatchedSeriesIfDue() {
        if (!watchRefreshMutex.tryLock()) return
        try {
        val session = _state.value.session ?: return
        val profileKey = session.profile.cacheKey()
        val interval = _state.value.cacheIntervalMinutes * 60_000L
        val now = System.currentTimeMillis()
        var scoped = allWatchedSeries.filter { it.profileKey == profileKey }
        var changed = false
        scoped.forEachIndexed { index, watched ->
            if (now - watched.checkedAtMillis < interval) return@forEachIndexed
            val latest = runCatching { portal.episodeSeason(session, watched.series, SeriesStartSeason.LAST) }.getOrNull()
                ?: return@forEachIndexed
            val discovered = latest.episodes.filterNot { it.id in watched.knownEpisodeIds }
            val replacement = watched.copy(
                knownEpisodeIds = watched.knownEpisodeIds + latest.episodes.map { it.id },
                newEpisodes = (discovered + watched.newEpisodes).distinctBy { it.id }
                    .sortedWith(compareByDescending<MediaItem> { it.seasonNumber ?: 0 }.thenByDescending { it.episodeNumber ?: 0 }),
                checkedAtMillis = now
            )
            scoped = scoped.map { if (it.key == watched.key) replacement else it }
            changed = true
            if (index < scoped.lastIndex) delay(1_200L)
        }
        if (changed) {
            allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + scoped
            _state.update { it.copy(watchedSeries = scoped) }
            store.saveWatchedSeries(allWatchedSeries)
        }
        } finally {
            watchRefreshMutex.unlock()
        }
    }

    fun openWatchedEpisode(watched: WatchedSeries, episode: MediaItem) = task {
        val session = requireNotNull(_state.value.session)
        val queue = portal.episodeSeason(session, watched.series, _state.value.seriesStartSeason, episode.seasonNumber).episodes
        val updated = _state.value.watchedSeries.map {
            if (it.key == watched.key) it.copy(newEpisodes = it.newEpisodes.filterNot { candidate -> candidate.id == episode.id }) else it
        }
        val profileKey = session.profile.cacheKey()
        allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
        _state.update { it.copy(watchedSeries = updated) }
        store.saveWatchedSeries(allWatchedSeries)
        playInternal(episode, CatalogType.SERIES, watched.series, queue)
    }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun logout() = viewModelScope.launch { store.clear(); _state.value = NikTvState(restoring = false) }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                withTimeout(90_000L) { block() }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(error = "The portal did not respond within 90 seconds. Please try again.") }
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Unexpected error") }
            } finally {
                _state.update { it.copy(loading = false, profileLoadProgress = null, profileLoadMessage = "Preparing profile…") }
            }
        }
    }

    private fun filterKey(profileKey: String, type: CatalogType): String = "$profileKey|${type.name}"
    private fun filterCategories(raw: List<Category>, profileKey: String?, type: CatalogType, filters: Map<String, List<String>>): List<Category> {
        if (profileKey == null) return raw
        val key = filterKey(profileKey, type)
        val enabledIds = filters[key] ?: return raw
        val enabledSet = enabledIds.toSet()
        return raw.filter { it.id in enabledSet }
    }
    private fun progressKeyFor(profile: PortalProfile, item: MediaItem, type: CatalogType, series: MediaItem?): String {
        // Keep keys bounded for storage while still differentiating profile/type/media.
        val base = buildString {
            append("progress-v2|")
            append(profile.cacheKey())
            append('|')
            append(type.name)
            append('|')
            append(stableMediaKeyPart(item, type, series))
        }
        return if (base.length <= 240) base else base.take(240)
    }
    private fun legacyProgressKeyFor(item: MediaItem, type: CatalogType): String = "${type.name}:${item.id}"
    private fun stableMediaKeyPart(item: MediaItem, type: CatalogType, series: MediaItem?): String {
        val normalizedTitle = item.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        // Fingerprint the command path (without query params) to avoid volatile-token mismatches.
        val commandFingerprint = item.command.orEmpty()
            .substringAfter(' ')
            .substringBefore('?')
            .substringAfter("://", missingDelimiterValue = item.command.orEmpty().substringAfter(' ').substringBefore('?'))
            .substringAfter('/')
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}/._-]+"), "")
            .take(64)
        return when (type) {
            CatalogType.SERIES -> "series:${series?.id ?: item.id}|s:${item.seasonNumber ?: -1}|e:${item.episodeNumber ?: -1}|$normalizedTitle|$commandFingerprint"
            CatalogType.MOVIES -> "movie:$normalizedTitle|$commandFingerprint"
            else -> "item:${item.id}"
        }
    }
    private fun String.episodeOrderFromTitle(): Int? = listOf(
        Regex("(?i)S\\d+[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d+)"),
        Regex("(?i)\\bEP(?:ISODE)?[ ._:-]*(\\d+)"),
        Regex("(?i)\\bE[ ._:-]*(\\d+)"),
        Regex("\\b(\\d+)\\b")
    ).firstNotNullOfOrNull { it.findAll(this).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun Throwable.isAuthenticationFailure(): Boolean = message.orEmpty().let { text ->
        text.contains("Authorization failed", ignoreCase = true) ||
            text.contains("HTTP status: 401") || text.contains("HTTP status: 403")
    }

    companion object {
        private const val MAX_RECENT_ITEMS = 100
        private const val MAX_PROGRESS_ITEMS = 200
        private const val MAX_PLAYBACK_URLS = 500
        private const val DASHBOARD_CATEGORY_LIMIT = 10
        private const val STALKER_SECTION_PAGE_SIZE = 14
    }
}
