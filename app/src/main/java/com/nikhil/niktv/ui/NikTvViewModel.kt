package com.nikhil.niktv.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.nikhil.niktv.update.AppUpdates
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.StalkerPortalClient
import com.nikhil.niktv.data.TmdbClient
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.matchTmdbMovie
import com.nikhil.niktv.data.matchTmdbSeries
import com.nikhil.niktv.data.isReleased
import com.nikhil.niktv.data.rankTmdbMovieMatches
import com.nikhil.niktv.data.rankTmdbSeriesMatches
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.TmdbSeries
import com.nikhil.niktv.data.prefetchArtwork
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.data.SearchMetadataSyncScheduler
import com.nikhil.niktv.data.SearchMetadataSyncWorker
import com.nikhil.niktv.data.SearchCatalogScanner
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable

data class TmdbIptvLookupMiss(val request: AlternateSourceRequest, val profileKey: String)

data class NikTvState(
    val profileSwitcherOpen: Boolean = false,
    val alternateSources: AlternateSourcePickerState? = null,
    val tmdbIptvLookupMiss: TmdbIptvLookupMiss? = null,
    val discoverySurface: DashboardSurface? = null,
    val watchProvidersOpen: Boolean = false,
    val discoveryNavigationId: Long = 0,
    val discoveryMatchCounts: Map<String, Int> = emptyMap(),
    val parentalControlsEnabled: Boolean = true,
    val parentalRelockMinutes: Int = 5,
    val parentalPrompt: String? = null,
    val parentalPinError: Boolean = false,
    val profiles: List<PortalProfile> = emptyList(),
    val profileEditorOpen: Boolean = false,
    val savedProfile: PortalProfile? = null,
    val session: PortalSession? = null,
    val selectedType: CatalogType = CatalogType.LIVE_TV,
    val categories: List<Category> = emptyList(),
    val rawCategoriesByType: Map<CatalogType, List<Category>> = emptyMap(),
    val categoryRefreshes: Set<Pair<String, CatalogType>> = emptySet(),
    val selectedCategory: Category? = null,
    val items: List<MediaItem> = emptyList(),
    internal val historyRecommendations: List<com.nikhil.niktv.data.HistoryRecommendationRow> = emptyList(),
    val historyRecommendationsMessage: String? = "Watch a movie or show to get personal recommendations.",
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
    val movieMatchSelection: TmdbMovie? = null,
    val movieMatchCandidates: List<MediaItem> = emptyList(),
    val movieMatchLoadingMore: Boolean = false,
    val seriesMatchSelection: TmdbSeries? = null,
    val seriesMatchCandidates: List<MediaItem> = emptyList(),
    val feedRefreshing: Boolean = false,
    val feedRefreshMessage: String = "Refreshing feed…",
    val loading: Boolean = false,
    val profileLoadProgress: Float? = null,
    val profileLoadMessage: String = "Preparing profile…",
    val error: String? = null,
    val reauthenticating: Boolean = false,
    val nowPlaying: PlayingMedia? = null,
    val playbackReturnFocusId: String? = null,
    val liveGuidePriorityId: String? = null,
    val liveGuideLoadingIds: Set<String> = emptySet(),
    val liveGuideWaitingUntilById: Map<String, Long> = emptyMap(),
    val manualLiveGuideLoadingId: String? = null,
    val restoring: Boolean = true,
    val settingsOpen: Boolean = false,
    val selectedSeries: MediaItem? = null,
    val providerMovieDetails: com.nikhil.niktv.discovery.ProviderMovieDetails? = null,
    val useTmdbEpisodeMetadata: Boolean = true,
    val tmdbEpisodeCacheRefreshing: Boolean = false,
    val episodeTmdbRefresh: EpisodeTmdbRefresh? = null,
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
    val offlineDownloads: List<OfflineMediaDownload> = emptyList(),
    val offlineDownloadRevision: Long = 0L,
    val offlineDownloadsOpen: Boolean = false,
    val cacheIntervalMinutes: Int = 60,
    val initialCatalogItems: Int = 14,
    val playerControlsTimeoutSeconds: Int = 3,
    val keepAwakeOnlyDuringPlayback: Boolean = false,
    val automaticReauthentication: Boolean = true,
    val modernTmdbSection: TmdbHomeSection? = null,
    val modernTmdbSectionOpenId: Long = 0,
    val modernIptvCategory: Category? = null,
    val modernIptvCategoryOpenId: Long = 0,
    val modernSectionOriginHome: Boolean = false,
    val modernTmdbMovies: List<TrendingMovie> = emptyList(),
    val modernTmdbSeries: List<TrendingSeries> = emptyList(),
    val modernTmdbMovieMatches: Map<Int, List<MediaItem>> = emptyMap(),
    val modernTmdbSeriesMatches: Map<Int, List<MediaItem>> = emptyMap(),
    val modernTmdbLoading: Boolean = false,
    val modernTmdbPage: Int = 0,
    val modernTmdbHasMore: Boolean = false,
    val modernTmdbError: String? = null,
    val seriesOpenedFromModernSection: Boolean = false,
    val playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    val seriesStartSeason: SeriesStartSeason = SeriesStartSeason.LAST,
    val availableSeriesSeasons: List<Int> = emptyList(),
    val selectedSeriesSeason: Int? = null,
    val seriesSeasonEpisodeCounts: Map<Int, Int> = emptyMap(),
    val seriesSeasonEpisodeCountsKey: String? = null,
    val watchedSeries: List<WatchedSeries> = emptyList(),
    val browseLayout: BrowseLayout = BrowseLayout.SECTIONS,
    val browseCache: BrowseCatalogCache? = null,
    val browseCachesByType: Map<CatalogType, BrowseCatalogCache> = emptyMap(),
    val searchOpen: Boolean = false,
    val searchType: SearchContentType = SearchContentType.ALL,
    val searchScopeLocked: Boolean = false,
    val searchQuery: String = "",
    val searchLocalLoading: Boolean = false,
    val searchResults: List<MediaItem> = emptyList(),
    val searchServerLoading: Boolean = false,
    val searchProviderMessage: String? = null,
    val searchActivityTitle: String? = null,
    val searchActivityDetail: String? = null,
    val searchActivityProgress: Float? = null,
    val searchUsedServer: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val searchPage: Int = 0,
    val searchHasMore: Boolean = false,
    val searchPaginationGeneration: Int = 0,
    val searchCategories: List<Category> = emptyList(),
    val searchCategoryId: String = "*",
    val categoryFilters: Map<String, List<String>> = emptyMap(),
    val categoryManagerOpen: Boolean = false,
    val categoryManagerType: CatalogType = CatalogType.LIVE_TV
    ,val backupMessage: String? = null
    ,val catalogPage: Int = 1
    ,val catalogHasMore: Boolean = false
    ,val catalogLoadingMore: Boolean = false
    ,val categoryRefreshing: Boolean = false
    ,val categoryFindQuery: String = ""
    ,val categoryFindSearching: Boolean = false
    ,val categoryFindPage: Int = 0
    ,val categoryFindTotalPages: Int? = null
    ,val categoryFindMessage: String? = null
    ,val categoryFindCheckingGuides: Boolean = false
    ,val categoryFindGuideChecked: Int = 0
    ,val categoryFindGuideTotal: Int = 0
    ,val episodePage: Int = 1
    ,val episodeHasMore: Boolean = false
    ,val episodeLoadingMore: Boolean = false
    ,val episodeDestination: EpisodeDestinationState? = null
    ,val playbackQueueScope: String? = null
    ,val playbackQueuePage: Int = 1
    ,val playbackQueueHasMore: Boolean = false
    ,val playbackQueueLoadingMore: Boolean = false
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NikTvViewModel(application: Application) : AndroidViewModel(application) {
    private val discoveryMetadataRequests = com.nikhil.niktv.discovery.DiscoveryMetadataRequests()
    private val store = ProfileStore(application)
    private val portal = StalkerPortalClient(application)
    @Volatile private var catalogueTitleIndex: CatalogueTitleIndex? = null
    private val tmdb = TmdbClient()
    private val mediaIdentities = com.nikhil.niktv.data.MediaIdentityRepository(application, tmdb)
    private val historyRecommendations = com.nikhil.niktv.data.HistoryRecommendations(application, tmdb, mediaIdentities)
    private var recommendationJob: Job? = null
    private var recommendationFingerprint: String? = null
    private var requestedRecommendationSeeds: List<RecentItem> = emptyList()
    private var dismissedRecommendationCategories: Map<String, Set<String>> = emptyMap()
    private var recommendationProfile: String? = null
    private var recommendationProgress: List<PlaybackProgress> = emptyList()
    private val artworkEnrichmentSlots = kotlinx.coroutines.sync.Semaphore(3)
    private val tmdbAvailabilityCatalogs = mutableMapOf<Pair<String, CatalogType>, SearchCatalogCache>()
    private val tmdbAvailability = com.nikhil.niktv.data.TmdbAvailabilityRepository(application)
    private var tmdbAvailabilityJob: Job? = null
    private var movieMatchLookupJob: Job? = null
    private val categoryMetadataRefreshJobs = mutableMapOf<Pair<String, CatalogType>, Job>()
    private val categoryRefreshAttempts = mutableMapOf<Pair<String, CatalogType>, Long>()
    private val categoryRefreshSlots = kotlinx.coroutines.sync.Semaphore(1)
    internal var categoryMetadataLookup: suspend (PortalSession, CatalogType, Boolean) -> List<Category> = { session, type, background -> portal.categories(session, type, background) }
    private val _state = MutableStateFlow(NikTvState(parentalControlsEnabled = com.nikhil.niktv.data.ParentalControls.enabled(application), parentalRelockMinutes = com.nikhil.niktv.data.ParentalControls.relockMinutes(application)))
    val state: StateFlow<NikTvState> = _state.asStateFlow()
    private val channelAvailability = IptvAvailabilityStore(application)
    internal fun observeLivePlayback(playback: PlayingMedia, failed: Boolean) {
        val current = _state.value
        val profile = current.session?.profile?.cacheKey() ?: current.savedProfile?.cacheKey().orEmpty()
        channelAvailability.observe(profile, current.nowPlaying, playback, failed)
    }
    private var allFavorites: List<FavoriteItem> = emptyList()
    private var allRecentlyPlayed: List<RecentItem> = emptyList()
    private var categoryFilterApplyInProgress = false
    private var searchSeriesReturn: SearchSeriesReturn? = null
    private var movieStructureJob: Job? = null
    internal var movieStructureLookup: suspend (PortalSession, MediaItem) -> Boolean = portal::movieHasEpisodes
    private var allWatchedSeries: List<WatchedSeries> = emptyList()
    private var rememberedSeriesSeasons: Map<String, Int> = emptyMap()
    private var episodeSeasonCaches: List<EpisodeSeasonCache> = emptyList()
    private var episodeDestinationJob: Job? = null
    private var episodePageJob: Job? = null
    private var episodeMetadataRefreshJob: Job? = null
    private var episodeMetadataRefreshRequest = 0L
    private var singleEpisodeMetadataJob: Job? = null
    private var singleEpisodeMetadataRequest = 0L
    private var episodeDestinationRequest = 0L
    private val episodePageMutex = Mutex()
    internal var episodePageLookup: suspend (PortalSession, MediaItem, Int?, Int, String?) -> EpisodeSeasonResult =
        { session, series, season, page, seasonId ->
            portal.episodeSeason(session, series, _state.value.seriesStartSeason, season, page, seasonId)
        }
    internal var episodePageEnrichment: suspend (MediaItem, EpisodeSeasonCache) -> Unit =
        { series, cache -> enrichEpisodeCacheFromTmdb(series, cache, force = false) }
    internal var episodeTmdbSeriesLookup: suspend (MediaItem) -> com.nikhil.niktv.data.TmdbSeries? = { series ->
        check(tmdb.configured) { "TMDB is not configured" }
        tmdb.confidentlyMatchSeries(series)
    }
    internal var episodeTmdbSeasonLookup: suspend (Int, Int) -> List<com.nikhil.niktv.data.TmdbEpisode> = tmdb::seasonEpisodes
    internal var episodeTmdbSeasonsLookup: suspend (Int) -> List<Int> = tmdb::episodeSeasons
    internal var episodeTmdbEpisodeLookup: suspend (Int, Int, Int) -> com.nikhil.niktv.data.TmdbEpisode? = tmdb::episodeMetadata

    private fun NikTvState.episodeBrowseKey(): EpisodeBrowseKey? {
        val activeSession = session ?: return null
        return selectedSeries?.let { EpisodeBrowseKey(activeSession.profile.cacheKey(), it.id, selectedSeriesSeason) }
    }
    private var browseLayouts: Map<String, BrowseLayout> = emptyMap()
    private var tmdbDashboardConfigs: Map<String, List<TmdbHomeSection>> = emptyMap()
    private val watchRefreshMutex = com.nikhil.niktv.data.NewEpisodeNotifications.refreshMutex
    private val favoriteSeriesMetadataMutex = Mutex()
    private val favoriteEpisodeCacheRefreshMutex = Mutex()
    private val sessionRefreshMutex = Mutex()
    private val playbackQueuePrefetches = SpeculativePageCache<PortalCatalogPage>(
        viewModelScope, SystemClock::elapsedRealtime
    )
    private val categoryRefreshJobs = mutableMapOf<String, Job>()
    private var categoryFindJob: Job? = null
    private var categoryFindGeneration = 0
    private val categoryFindSaveMutex = Mutex()
    private data class LiveGuideTarget(val profileKey: String, val categoryId: String, val itemId: String) {
        val key: String get() = "$profileKey|$categoryId|$itemId"
    }
    private val liveGuideForcedKeys = mutableSetOf<String>()
    private val liveGuideScheduler = VisibleLiveGuideScheduler<LiveGuideTarget>(
        scope = viewModelScope,
        isDue = ::isVisibleLiveGuideDue,
        load = ::loadVisibleLiveGuide,
        onFailure = { Log.w("NikTvLiveGuide", "Could not load a visible channel guide", it) },
        // PortalRequestGate already enforces a one-second request spacing and
        // a 16/minute background ceiling. A second post-response delay only
        // makes later visible channels wait longer after slow responses.
        spacingMillis = 0L
    )
    private val liveGuideAttemptedAt = mutableMapOf<String, Long>()
    private val liveGuideRetryPolicy = LiveGuideRetryPolicy()
    private var lastVisibleLiveGuideIds: Pair<String, List<String>>? = null
    private var liveGuideCacheSaveJob: Job? = null
    private var liveGuideExpiryJob: Job? = null
    private var profilePreparationJob: Job? = null
    private var searchPreviewJob: kotlinx.coroutines.Job? = null
    private var searchServerJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            state.map { it.episodeBrowseKey() }.distinctUntilChanged().collect { key ->
                val request = _state.value.episodeDestination
                if (request != null && key != EpisodeBrowseKey(request.profile, request.series, request.season)) {
                    cancelEpisodeDestination()
                }
            }
        }
        viewModelScope.launch {
            _state.collect { com.nikhil.niktv.data.CatalogPlaybackActivity.playing = it.nowPlaying != null }
        }
        prepareProfileChooser()
        viewModelScope.launch { store.favorites.collect { favorites ->
            allFavorites = favorites
            refreshProfileLibrary()
        } }
        viewModelScope.launch { store.recentlyPlayed.collect { recent ->
            allRecentlyPlayed = recent
            refreshProfileLibrary()
        } }
        viewModelScope.launch { store.playbackProgress.collect { progress ->
            _state.update { it.copy(playbackProgress = progress) }
            scheduleHistoryRecommendations()
        } }
        viewModelScope.launch { store.requestedRecommendations.collect {
            requestedRecommendationSeeds = it
            recommendationFingerprint = null
            scheduleHistoryRecommendations()
        } }
        viewModelScope.launch { store.dismissedRecommendations.collect {
            dismissedRecommendationCategories = it
            recommendationFingerprint = null
            scheduleHistoryRecommendations()
        } }
        viewModelScope.launch { store.recommendationProgress.collect {
            recommendationProgress = it
            scheduleHistoryRecommendations()
        } }
        viewModelScope.launch { store.playbackUrls.collect { urls -> _state.update { it.copy(playbackUrls = urls) } } }
        viewModelScope.launch { store.offlineDownloads.collect { downloads ->
            _state.update { it.copy(offlineDownloads = downloads) }
        } }
        viewModelScope.launch { store.cacheIntervalMinutes.collect { minutes -> _state.update { it.copy(cacheIntervalMinutes = minutes) } } }
        viewModelScope.launch {
            store.initialCatalogItems.collect { items ->
                _state.update { it.copy(initialCatalogItems = items) }
            }
        }
        viewModelScope.launch { store.playerControlsTimeoutSeconds.collect { seconds -> _state.update { it.copy(playerControlsTimeoutSeconds = seconds) } } }
        viewModelScope.launch { store.keepAwakeOnlyDuringPlayback.collect { enabled ->
            _state.update { it.copy(keepAwakeOnlyDuringPlayback = enabled) }
        } }
        viewModelScope.launch { store.automaticReauthentication.collect { enabled ->
            _state.update { it.copy(automaticReauthentication = enabled) }
        } }
        viewModelScope.launch { store.playbackEngine.collect { engine -> _state.update { it.copy(playbackEngine = engine) } } }
        viewModelScope.launch { store.seriesStartSeason.collect { value -> _state.update { it.copy(seriesStartSeason = value) } } }
        viewModelScope.launch { store.rememberedSeriesSeasons.collect { rememberedSeriesSeasons = it } }
        viewModelScope.launch { store.episodeSeasonCaches.collect { episodeSeasonCaches = it } }
        viewModelScope.launch { store.browseLayouts.collect { layouts ->
            browseLayouts = layouts
            val key = _state.value.session?.profile?.cacheKey()
            _state.update { it.copy(browseLayout = key?.let(layouts::get) ?: BrowseLayout.SECTIONS) }
        } }
        viewModelScope.launch { store.tmdbDashboardSections.distinctUntilChanged().collect { stored ->
            tmdbDashboardConfigs = stored
            val profileKey = _state.value.session?.profile?.cacheKey()
            val sections = if (profileKey == null) emptyMap() else DashboardSurface.entries.associateWith { surface ->
                stored["$profileKey|${surface.name}"].orEmpty()
            }
            _state.update { it.copy(tmdbSectionsBySurface = sections) }

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
            _state.map { it.offlineDownloads }.distinctUntilChanged().collectLatest { downloads ->
                while (isActive) {
                    val infos = queryOfflineDownloadSnapshot(downloads) {
                        OfflineMediaDownloads.info(getApplication(), it.requestId, it.downloadId)
                    }
                    if (infos != OfflineDownloadSnapshots.state.value) {
                        OfflineDownloadSnapshots.publish(infos)
                        _state.update { it.copy(offlineDownloadRevision = it.offlineDownloadRevision + 1L) }
                    }
                    delay(2_000L)
                }
            }
        }
        viewModelScope.launch {
            store.categoryFilters.distinctUntilChanged().collect { filters ->
                val snapshot = _state.value
                val profileKey = snapshot.session?.profile?.cacheKey()
                val raw = snapshot.rawCategoriesByType[snapshot.selectedType] ?: snapshot.categories
                val filtered = filterCategories(raw, profileKey, snapshot.selectedType, filters)
                val selected = if (snapshot.selectedCategory != null && filtered.any { it.id == snapshot.selectedCategory.id }) {
                    snapshot.selectedCategory
                } else {
                    filtered.firstOrNull { snapshot.selectedType != CatalogType.SERIES || it.id != "*" }
                        ?: filtered.firstOrNull()
                }
                _state.update { it.copy(categoryFilters = filters, categories = filtered, selectedCategory = selected) }

            }
        }
    }

    private fun prepareProfileChooser() = viewModelScope.launch {
        // A cold app launch always starts at the profile chooser. Authentication is
        // restored only after the viewer deliberately chooses a profile.
        val profiles = store.profiles.first()
        _state.update { it.copy(profiles = profiles, session = null, savedProfile = null,
            profileEditorOpen = false, restoring = false) }
    }

    fun connect(profile: PortalProfile) = task {
        portal.prioritizePlayback()
        profilePreparationJob?.cancel()
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()
        catalogueTitleIndex = null
        _state.update { it.copy(savedProfile = profile, providerMovieDetails = null) }
        updateProfileLoad(0.08f, "Authenticating ${profile.name}…")
        val session = authenticateProfileForOpening(profile)
        updateProfileLoad(0.24f, "Authentication complete")
        store.save(session)
        _state.update {
            it.copy(
                session = session,
                savedProfile = session.profile,
                homeOpen = true,
                watchProvidersOpen = false, discoverySurface = DashboardSurface.HOME,
                favoritesOpen = false,
                settingsOpen = false,
                offlineDownloadsOpen = false,
                items = emptyList(),
                categories = emptyList(),
                rawCategoriesByType = emptyMap(),
                browseCache = null,
                browseCachesByType = emptyMap(),
                fullSearchItems = null,
                selectedCategory = null,
                selectedSeries = null,
                modernIptvCategory = null,
                modernTmdbSection = null,
                profileEditorOpen = false,
                searchOpen = false,
                searchScopeLocked = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchCategories = emptyList(),
                searchCategoryId = "*",
                searchUsedServer = false,
                searchServerLoading = false,
                searchPage = 0,
                searchHasMore = false,
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
        updateProfileLoad(0.26f, "Loading saved favorites and watch history…")
        loadProfileLibrary(session.profile.cacheKey())
        prepareDashboardInBackground(session)
        SearchMetadataSyncScheduler.configureProfile(getApplication(), session.profile)
    }

    private fun prepareDashboardInBackground(session: PortalSession) {
        profilePreparationJob?.cancel()
        profilePreparationJob = viewModelScope.launch {
            val profileKey = session.profile.cacheKey()
            suspend fun step(block: suspend () -> Unit) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    withTimeoutOrNull(15_000L) { block() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Optional preparation retries when its destination opens. */ }
            }
            step { store.discardLegacyBrowseCatalogs() }
            if (session.profile.portalType == PortalType.XTREAM) launch {
                // Xtream supplies each complete media listing in one request.
                // Warm the established profile-scoped file cache off the UI path;
                // collectionCatalog reuses fresh data and obeys refresh intervals.
                for (type in listOf(CatalogType.MOVIES, CatalogType.SERIES, CatalogType.LIVE_TV)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    try { collectionCatalog(session, type, force = false) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A destination/search retries when needed. */ }
                }
                // Leave the active type decoded and its normalized titles ready,
                // rather than making the first query build the index on demand.
                if (_state.value.session?.profile?.cacheKey() == profileKey) {
                    val primeType = if (_state.value.searchOpen && _state.value.searchType != SearchContentType.ALL)
                        catalogTypeForSearch(_state.value.searchType) else _state.value.selectedType
                    try {
                        val cache = collectionCatalog(session, primeType, force = false)
                        if (_state.value.session?.profile?.cacheKey() == profileKey &&
                            cache.items.sumOf { it.title.length.toLong() } <= Runtime.getRuntime().maxMemory() / 64 &&
                            catalogueTitleIndex?.isFor(cache.items) != true) {
                            catalogueTitleIndex = withContext(Dispatchers.Default) { CatalogueTitleIndex(cache.items, profileKey, primeType) }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Search can prime it on demand. */ }
                }
            }
            // Live TV is the most latency-sensitive destination and is commonly
            // opened immediately after profile selection. Warm its lightweight
            // category metadata before the larger VOD catalogues.
            for (type in listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) {
                step {
                    loadTypeMetadataInternal(session, type)
                }
            }
            run {
                // Category tiles are ready at this point. Hydrate the much
                // larger media maps afterward so profile navigation never
                // waits for full catalogue deserialization.
                launch {
                    for (type in listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) {
                        step { hydrateTypeCacheFromDisk(session, type) }
                    }
                }
            }
            // These are optional enrichments, never a prerequisite for opening Home.
            // Opening a profile must reconcile subscribed series before the
            // viewer has to visit each series. Later checks remain interval-bound.
            step { refreshWatchedSeriesIfDue(force = true) }
            loadDashboardDiscovery()
            step { enrichHomeArtwork(profileKey) }
        }
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

    private fun String.cleanedIptvSeriesTitle(): String {
        val original = trim()
        if (original.isBlank()) return original

        val cleaned = original
            .replace(
                Regex(
                    "\\([^)]*(?:english|hindi|tamil|telugu|season|complete)[^)]*\\)",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(
                Regex(
                    "\\b(?:4k|uhd|hdr10?|2160p|1080p|720p|480p|bluray|blu\\s*ray|webrip|web\\s*dl|x264|x265|h264|h265|hevc|aac|atmos|english|multi|dubbed)\\b",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', '·', ':', '.', '_')

        return cleaned.ifBlank { original }
    }

    private fun MediaItem.withPreferredSeriesPresentation(
        metadata: TmdbSeries?
    ): MediaItem {
        val fallbackTitle = title.cleanedIptvSeriesTitle()
        if (metadata == null) {
            return if (fallbackTitle == title) this else copy(title = fallbackTitle)
        }

        return copy(
            title = metadata.name.ifBlank { fallbackTitle },
            description = metadata.overview ?: description
        )
    }

    private fun MediaItem.favoriteTmdbSeriesId(): Int? =
        externalTmdbId
            ?: id.removePrefix("tmdb-series:")
                .takeIf { it != id }
                ?.toIntOrNull()

    private fun MediaItem.withFavoriteSeriesPresentation(favorite: MediaItem): MediaItem = copy(
        title = favorite.title.ifBlank { title },
        logo = favorite.logo ?: logo,
        description = favorite.description ?: description
    )

    private suspend fun enrichFavoriteSeriesPresentation(profileKey: String) {
        if (_state.value.session?.profile?.cacheKey() != profileKey) return
        if (!favoriteSeriesMetadataMutex.tryLock()) return

        try {
            val seriesMappings = store.tmdbMappings.first()
                .filter { mapping ->
                    mapping.profileKey == profileKey &&
                        mapping.type == CatalogType.SERIES
                }
            val mappingsByMediaId = seriesMappings.associateBy { it.media.id }
            val mappingsByTmdbId = seriesMappings.associateBy { it.tmdbId }

            val targets = _state.value.favorites
                .filter { it.kind == FavoriteKind.SERIES }

            var changed = false
            for (chunk in targets.chunked(4)) {
                if (_state.value.session?.profile?.cacheKey() != profileKey) return

                val replacements = coroutineScope {
                    chunk.map { favorite ->
                        async {
                            val mapping = mappingsByMediaId[favorite.media.id]
                                ?: favorite.media.favoriteTmdbSeriesId()?.let(mappingsByTmdbId::get)
                            val mappedTmdbId = mapping?.tmdbId
                            val lookupMedia = mappedTmdbId
                                ?.let { favorite.media.copy(externalTmdbId = it) }
                                ?: favorite.media
                            val metadata = if (tmdb.configured) {
                                runCatching { tmdb.confidentlyMatchSeries(lookupMedia) }
                                    .onFailure { error ->
                                        Log.w(
                                            "NikTvFavoriteMetadata",
                                            "TMDB series favorite lookup failed for ${favorite.media.id}",
                                            error
                                        )
                                    }
                                    .getOrNull()
                            } else {
                                null
                            }
                            // A TMDB row can be favorited before its provider match
                            // finishes resolving. Replace that synthetic identity with
                            // the saved Wio/Xtream item while keeping its presentation.
                            val updatedMedia = (mapping?.media ?: favorite.media)
                                .withPreferredSeriesPresentation(metadata)
                                .withFavoriteSeriesPresentation(favorite.media)
                            val source = when {
                                favorite.source == FavoriteSource.TMDB -> FavoriteSource.TMDB
                                favorite.media.id.startsWith("tmdb-") -> FavoriteSource.TMDB
                                // Older TMDB favorites were enriched before source
                                // provenance was persisted. This is the legacy Wio
                                // shape: a mapped provider id with no provider command
                                // and no IPTV category attached to the favorite.
                                favorite.profileKey.contains("|STALKER|") &&
                                    favorite.categoryTitle == null &&
                                    favorite.media.command.isNullOrBlank() &&
                                    mapping != null -> FavoriteSource.TMDB
                                else -> FavoriteSource.IPTV
                            }
                            favorite.key to favorite.copy(media = updatedMedia, source = source)
                        }
                    }.awaitAll()
                }.toMap()

                val chunkChanged = replacements.any { (key, replacement) ->
                    _state.value.favorites
                        .firstOrNull { it.key == key }
                        ?.media != replacement.media
                }
                if (!chunkChanged) continue

                changed = true
                _state.update { current ->
                    current.copy(
                        favorites = current.favorites.map { favorite ->
                            replacements[favorite.key] ?: favorite
                        }
                    )
                }
                allFavorites = allFavorites.map { favorite ->
                    replacements[favorite.key] ?: favorite
                }
            }

            if (
                changed &&
                _state.value.session?.profile?.cacheKey() == profileKey
            ) {
                store.saveFavorites(allFavorites)
            }
        } finally {
            favoriteSeriesMetadataMutex.unlock()
        }
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
        scheduleHistoryRecommendations()
    }

    fun recommendationTypeFor(item: MediaItem, requestedType: CatalogType?): CatalogType? {
        val snapshot = _state.value
        val profile = snapshot.session?.profile?.cacheKey() ?: return null
        val recent = snapshot.recentlyPlayed.firstOrNull { it.profileKey == profile && it.media.id == item.id }
        val knownKind = recent?.kind ?: snapshot.favorites.firstOrNull {
            it.profileKey == profile && it.media.id == item.id
        }?.kind
        return when {
            item.searchResultType == SearchContentType.SERIES -> CatalogType.SERIES
            item.searchResultType == SearchContentType.MOVIES -> CatalogType.MOVIES
            item.searchResultType == SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            item.id.startsWith("tmdb-series:") -> CatalogType.SERIES
            item.id.startsWith("tmdb-movie:") -> CatalogType.MOVIES
            knownKind == FavoriteKind.CHANNEL -> CatalogType.LIVE_TV
            requestedType != null -> requestedType
            knownKind == FavoriteKind.SERIES -> CatalogType.SERIES
            knownKind == FavoriteKind.MOVIE -> CatalogType.MOVIES
            else -> snapshot.selectedType
        }
    }

    fun showRecommendationsOnHome(item: MediaItem, requestedType: CatalogType?) {
        val profile = _state.value.session?.profile?.cacheKey() ?: return
        val type = recommendationTypeFor(item, requestedType) ?: return
        if (type == CatalogType.LIVE_TV) {
            showLiveProgrammeRecommendations(item, profile)
            return
        }
        if (type !in setOf(CatalogType.MOVIES, CatalogType.SERIES) || item.isAdult || item.episodeNumber != null) return
        viewModelScope.launch {
            try {
                val identity = mediaIdentities.resolve(profile, type, item)
                if (identity?.tmdbId == null) {
                    android.widget.Toast.makeText(getApplication(), "Could not match this title to TMDB.", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val category = "${identity.type}:${identity.tmdbId}"
                val seed = RecentItem(if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE,
                    item, profileKey = profile)
                store.requestRecommendations(seed, category)
                android.widget.Toast.makeText(getApplication(), "Recommendations added to Home", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                  Log.w("NikTvRecommendations", "Could not add recommendation source", error)
                  android.widget.Toast.makeText(getApplication(), "Could not add recommendations. Please try again.", android.widget.Toast.LENGTH_LONG).show()
              }
        }
    }

    private fun showLiveProgrammeRecommendations(channel: MediaItem, profile: String) {
        val programme = com.nikhil.niktv.data.liveProgrammeRecommendationItem(channel, System.currentTimeMillis())
        if (programme == null) {
            android.widget.Toast.makeText(getApplication(), "No current programme information available.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        viewModelScope.launch {
            try {
                val identity = com.nikhil.niktv.data.resolveLiveProgrammeRecommendation(programme) { type, item ->
                    mediaIdentities.resolve(profile, type, item)
                }
                if (identity?.tmdbId == null) {
                    android.widget.Toast.makeText(getApplication(), "Could not find a clear TMDB match for this programme.", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val seed = RecentItem(if (identity.type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE,
                    programme, profileKey = profile)
                store.requestRecommendations(seed, "${identity.type}:${identity.tmdbId}")
                android.widget.Toast.makeText(getApplication(), "Recommendations added to Home", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                  Log.w("NikTvRecommendations", "Could not add live programme recommendation source", error)
                  android.widget.Toast.makeText(getApplication(), "Could not add recommendations. Please try again.", android.widget.Toast.LENGTH_LONG).show()
              }
        }
    }

    fun dismissRecommendation(row: com.nikhil.niktv.data.HistoryRecommendationRow) {
        if (_state.value.session?.profile?.cacheKey() != row.profile) return
        recommendationJob?.cancel()
        dismissedRecommendationCategories = dismissedRecommendationCategories +
            (row.profile to (dismissedRecommendationCategories[row.profile].orEmpty() + row.key))
        _state.update { it.copy(historyRecommendations = it.historyRecommendations.filterNot { entry ->
            entry.profile == row.profile && entry.key == row.key
        }) }
        viewModelScope.launch { store.dismissRecommendation(row.profile, row.key) }
    }

    private fun scheduleHistoryRecommendations() {
        val snapshot = _state.value
        val session = snapshot.session
        val profile = session?.profile?.cacheKey()
        if (profile != recommendationProfile) {
            recommendationJob?.cancel()
            recommendationProfile = profile
            recommendationFingerprint = null
            _state.update { it.copy(historyRecommendations = emptyList()) }
        }
        if (profile == null || session == null) return
        val progress = (recommendationProgress + snapshot.playbackProgress).groupBy { it.key }
            .mapValues { (_, entries) -> entries.maxBy { it.positionMillis } }
        val watched = com.nikhil.niktv.data.recommendationSeeds(profile, snapshot.recentlyPlayed, limit = 100) { recent ->
            val type = if (recent.kind == FavoriteKind.MOVIE) CatalogType.MOVIES else CatalogType.SERIES
            val episode = recent.lastPlayed ?: recent.media
            val direct = progress[progressKeyFor(session.profile, episode, type,
                if (type == CatalogType.SERIES) recent.media else null)]
                ?: progress[legacyProgressKeyFor(episode, type)]
            if (type == CatalogType.SERIES && (direct == null ||
                !com.nikhil.niktv.data.meaningfulViewing(direct.positionMillis, direct.durationMillis)))
                com.nikhil.niktv.data.seriesRecommendationProgress(profile, recent.media.id, progress.values) ?: direct
            else direct
        }
        val requested = requestedRecommendationSeeds.filter { it.profileKey == profile }
        val seeds = (requested + watched.take(3)).distinctBy { "${it.kind}:${it.media.id}" }
        val fingerprint = profile + ":${tmdb.configured}:" + (requested + watched).joinToString { "${it.kind}:${it.media.id}:${it.media.title}:${it.media.externalTmdbId}" }
        if (fingerprint == recommendationFingerprint && recommendationJob?.isCancelled != true) return
        recommendationFingerprint = fingerprint
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch {
            if (seeds.isEmpty()) {
                _state.update { it.copy(historyRecommendations = emptyList(),
                    historyRecommendationsMessage = "Watch 10 minutes of a movie or show, or 20% with a 2-minute minimum. Recommendations use this IPTV profile's history.") }
                return@launch
            }
            _state.update { it.copy(historyRecommendationsMessage = "Finding recommendations…") }
            delay(750)
            try {
                val requestedIds = requested.mapNotNull { seed ->
                    val type = if (seed.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES
                    mediaIdentities.cached(profile, type, seed.media)?.tmdbId?.let { "$type:$it" }
                }.toSet()
                val rows = historyRecommendations.load(profile, seeds, watched,
                    dismissedRecommendationCategories[profile].orEmpty(), requestedIds)
                ensureActive()
                _state.update { if (it.session?.profile?.cacheKey() == profile)
                    it.copy(historyRecommendations = rows, historyRecommendationsMessage =
                        if (rows.isEmpty()) "No recommendations yet: your watched titles could not be matched to TMDB, or returned no related titles."
                        else null) else it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                recommendationFingerprint = null
                if (_state.value.session?.profile?.cacheKey() == profile)
                    _state.update { it.copy(historyRecommendationsMessage = "Recommendations are temporarily unavailable. Reopen Home to retry.") }
                Log.w("NikTvRecommendations", "Could not refresh recommendations", error)
            }
        }
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
            val session = requireNotNull(_state.value.session)
            runCatching {
                withAutomaticSessionRetry(session) { activeSession ->
                    loadTypeInternal(activeSession, type)
                }
            }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not load ${type.title}") } }
        }
    }

    fun openCatalogType(type: CatalogType) {
        searchSeriesReturn = null
        com.nikhil.niktv.discovery.modernCatalogSurface(type)?.let { openDiscovery(it); return }
        _state.update { current ->
            current.enterCatalogRoot(type).copy(
                modernTmdbSection = null,
                modernIptvCategory = null,
                modernSectionOriginHome = false,
                modernTmdbMovies = emptyList(),
                modernTmdbSeries = emptyList(),
                modernTmdbMovieMatches = emptyMap(),
                modernTmdbSeriesMatches = emptyMap(),
                modernTmdbLoading = false,
                modernTmdbPage = 0,
                modernTmdbHasMore = false,
                modernTmdbError = null,
                seriesOpenedFromModernSection = false,
                offlineDownloadsOpen = false
            )
        }

        if (type in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
        ) {
            val snapshot = _state.value
            val session = snapshot.session ?: return
            val cached = snapshot.browseCachesByType[type]
            val categories = cached?.categories.orEmpty()

            if (categories.isNotEmpty()) {
                val filtered = filterCategories(
                    categories,
                    session.profile.cacheKey(),
                    type,
                    snapshot.categoryFilters
                )
                val selected = filtered.firstOrNull {
                    type != CatalogType.SERIES || it.id != "*"
                } ?: filtered.firstOrNull()
                _state.update {
                    it.copy(
                        homeOpen = false,
                        favoritesOpen = false,
                        settingsOpen = false,
                        searchOpen = false,
                        selectedType = type,
                        categories = filtered,
                        selectedCategory = selected,
                        items = emptyList(),
                        selectedSeries = null,
                        browseCache = cached
                    )
                }
                return
            }

            _state.update {
                it.copy(
                    homeOpen = false,
                    favoritesOpen = false,
                    settingsOpen = false,
                    searchOpen = false,
                    selectedType = type,
                    items = emptyList(),
                    selectedSeries = null
                )
            }
            viewModelScope.launch {
                runCatching {
                    withAutomaticSessionRetry(session) { activeSession ->
                        loadTypeMetadataInternal(activeSession, type)
                    }
                }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                error =
                                    error.message
                                        ?: "Could not load ${type.title} categories"
                            )
                        }
                    }
            }
            return
        }

        if (activateWarmedType(type, closeOverlays = true)) {

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
            catalogPage = selected?.let { cache.pagesByCategory[it.id] } ?: 1,
            catalogHasMore = selected?.let { cache.hasMoreByCategory[it.id] } ?: false,
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

    /**
     * Tile-first browsing only needs destination metadata at startup. Preserve
     * any existing per-category cache but do not load a media list until a
     * destination tile is opened.
     */
    private suspend fun loadTypeMetadataInternal(
        session: PortalSession,
        type: CatalogType,
        forceRefresh: Boolean = false
    ) {
        val profileKey = session.profile.cacheKey()
        var cachedBrowse = if (!forceRefresh) {
            _state.value.browseCachesByType[type]
                ?.takeIf { it.profileKey == profileKey }
        } else null

        if (!forceRefresh && cachedBrowse == null) {
            cachedBrowse = store.browseCatalogMetadata(type, profileKey).first()
                ?.takeIf { it.categories.isNotEmpty() }
                ?.let { metadata ->
                    BrowseCatalogCache(
                        profileKey = metadata.profileKey,
                        type = metadata.type,
                        cachedAtMillis = metadata.cachedAtMillis,
                        categories = metadata.categories,
                        itemsByCategory = emptyMap(),
                        categoriesCachedAtMillis = metadata.categoriesCachedAtMillis
                    )
                }
        }

        val fetchedCategories = cachedBrowse?.categories.isNullOrEmpty()
        val allCategories = cachedBrowse?.categories?.takeIf { it.isNotEmpty() }
            ?: portal.categories(session, type)

        val filteredCategories = filterCategories(
            allCategories,
            profileKey,
            type,
            _state.value.categoryFilters
        )

        val selected = filteredCategories.firstOrNull {
            type != CatalogType.SERIES || it.id != "*"
        } ?: filteredCategories.firstOrNull()

        val cache = cachedBrowse?.let { existing -> existing.copy(categories = allCategories,
            categoriesCachedAtMillis = if (fetchedCategories) System.currentTimeMillis() else existing.categoriesCachedAtMillis) }
            ?: BrowseCatalogCache(
                profileKey = profileKey,
                type = type,
                cachedAtMillis = System.currentTimeMillis(),
                categories = allCategories,
                itemsByCategory = emptyMap()
            )

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profileKey) return@update current
            val active = current.selectedType == type
            current.copy(
                rawCategoriesByType =
                    current.rawCategoriesByType + (type to allCategories),
                categories =
                    if (active) filteredCategories else current.categories,
                selectedCategory =
                    if (active) selected else current.selectedCategory,
                items = if (active) emptyList() else current.items,
                browseCache = if (active) cache else current.browseCache,
                browseCachesByType =
                    current.browseCachesByType + (type to cache)
            )
        }
        if (fetchedCategories && allCategories.isNotEmpty()) {
            viewModelScope.launch {
                runCatching {
                    store.saveBrowseCatalogMetadata(
                        BrowseCatalogMetadataCache(
                            profileKey = profileKey,
                            type = type,
                            cachedAtMillis = cache.cachedAtMillis,
                            categories = allCategories,
                            categoriesCachedAtMillis = cache.categoriesCachedAtMillis
                        )
                    )
                }.onFailure {
                    Log.w("NikTvCatalogCache", "Could not persist $type category metadata", it)
                }
            }
        }
        if (!fetchedCategories && _state.value.selectedType == type && _state.value.nowPlaying == null &&
            categoryMetadataExpired(cache.categoriesCachedAtMillis, System.currentTimeMillis(), _state.value.cacheIntervalMinutes)) {
            refreshProviderCategories(type, userRequested = false)
        }

    }

    private suspend fun hydrateTypeCacheFromDisk(session: PortalSession, type: CatalogType) {
        val profileKey = session.profile.cacheKey()
        val disk = store.browseCatalog(type, profileKey).first() ?: return
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profileKey) return@update current
            val memory = current.browseCachesByType[type]
                ?.takeIf { it.profileKey == profileKey }
            val merged = if (memory == null) {
                disk
            } else {
                disk.copy(
                    categories = memory.categories.ifEmpty { disk.categories },
                    cachedAtMillis = maxOf(disk.cachedAtMillis, memory.cachedAtMillis),
                    itemsByCategory = disk.itemsByCategory + memory.itemsByCategory,
                    pagesByCategory = disk.pagesByCategory + memory.pagesByCategory,
                    hasMoreByCategory = disk.hasMoreByCategory + memory.hasMoreByCategory,
                    categoryCachedAtMillis = disk.categoryCachedAtMillis + memory.categoryCachedAtMillis
                )
            }
            current.copy(
                browseCache = if (current.selectedType == type) merged else current.browseCache,
                browseCachesByType = current.browseCachesByType + (type to merged)
            )
        }
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

        var loadedPage: PortalCatalogPage? = null
        val items = if (selected != null) {
            val cachedForSelected = cachedItems[selected.id]?.takeIf {
                cachedBrowse?.pagesByCategory?.containsKey(selected.id) == true &&
                    cachedBrowse?.hasMoreByCategory?.containsKey(selected.id) == true
            }
            if (!forceRefresh && cachedBrowse != null && cachedForSelected != null) {
                cachedForSelected
            } else {
                fetchInitialCatalogPage(session, selected).also { loadedPage = it }.items
            }
        } else emptyList()

        val pageUpdates = selected?.let { category -> loadedPage?.let { mapOf(category.id to it.page) } }.orEmpty()
        val hasMoreUpdates = selected?.let { category -> loadedPage?.let { mapOf(category.id to it.hasMore) } }.orEmpty()
        val categoryTimeUpdates = selected?.let { category ->
            loadedPage?.let { mapOf(category.id to System.currentTimeMillis()) }
        }.orEmpty()
        val cache = cachedBrowse?.copy(
            cachedAtMillis = if (loadedPage != null) System.currentTimeMillis() else cachedBrowse.cachedAtMillis,
            categories = allCategories,
            itemsByCategory = if (selected != null) cachedItems + (selected.id to items) else cachedItems,
            pagesByCategory = cachedBrowse.pagesByCategory + pageUpdates,
            hasMoreByCategory = cachedBrowse.hasMoreByCategory + hasMoreUpdates,
            categoryCachedAtMillis = cachedBrowse.categoryCachedAtMillis + categoryTimeUpdates
        ) ?: BrowseCatalogCache(
            profileKey, type, System.currentTimeMillis(), allCategories,
            selected?.let { mapOf(it.id to items) }.orEmpty(), pageUpdates, hasMoreUpdates,
            categoryTimeUpdates
        )

        if (allCategories.isNotEmpty() &&
            (selected == null || items.isNotEmpty() || loadedPage != null) &&
            cache != cachedBrowse
        ) {
            store.saveBrowseCatalog(cache)
        }

        _state.update { current ->
            val refreshedItemsById = cache.itemsByCategory
                .values
                .asSequence()
                .flatten()
                .associateBy { it.id }
            val updatedRaw = current.rawCategoriesByType + (type to allCategories)
            current.copy(
                selectedType = type,
                rawCategoriesByType = updatedRaw,
                categories = filteredCategories,
                selectedCategory = selected,
                items = items,
                catalogPage = selected?.let { cache.pagesByCategory[it.id] } ?: 1,
                catalogHasMore = selected?.let { cache.hasMoreByCategory[it.id] } ?: false,
                selectedSeries = null,
                fullSearchItems = null,
                fullSearchCachedAtMillis = null,
                browseCache = cache,
                browseCachesByType = current.browseCachesByType + (type to cache),
                // Refresh runs off the UI thread. Once its cache is ready, update
                // the open player's metadata snapshot atomically without touching
                // the active stream URL or restarting playback.
                nowPlaying = current.nowPlaying?.let { playing ->
                    if (playing.catalogType == type && refreshedItemsById.isNotEmpty()) {
                        playing.withRefreshedQueueItems(refreshedItemsById)
                    } else {
                        playing
                    }
                }
            )
        }

        if (type == CatalogType.MOVIES || type == CatalogType.SERIES) {
            viewModelScope.launch { enrichCatalogArtwork(session, type) }
        }
    }

    private var playerGuideViewportActive = false

    fun enrichPlayerLiveGuides(visible: List<MediaItem>) {
        playerGuideViewportActive = visible.isNotEmpty() && _state.value.nowPlaying?.catalogType == CatalogType.LIVE_TV
        loadVisibleLiveGuides(visible, force = false)
    }

    /** Fill missing guide entries only for channels visible in the browse grid. */
    fun enrichVisibleLiveGuides(visible: List<MediaItem>) {
        // Disposing browse UI must not clear an already visible player queue.
        if (_state.value.nowPlaying != null && playerGuideViewportActive) return
        playerGuideViewportActive = false
        loadVisibleLiveGuides(visible, force = false)
    }

    private fun loadVisibleLiveGuides(visible: List<MediaItem>, force: Boolean) {
        if (visible.isEmpty()) {
            lastVisibleLiveGuideIds = null
            liveGuideForcedKeys.clear()
            liveGuideScheduler.update(emptyList())
            liveGuideExpiryJob?.cancel()
            liveGuideExpiryJob = null
            return
        }
        val snapshot = _state.value
        val session = snapshot.session ?: return
        val category = (if (playerGuideViewportActive) snapshot.selectedCategory else snapshot.modernIptvCategory)
            ?.takeIf { it.type == CatalogType.LIVE_TV } ?: return
        val profileKey = session.profile.cacheKey()
        lastVisibleLiveGuideIds = categoryRefreshKey(session, category) to
            visible.map { it.id }.distinct()
        val targets = visible.distinctBy { it.id }.map {
            LiveGuideTarget(profileKey, category.id, it.id)
        }
        val visibleKeys = targets.map { it.key }.toSet()
        liveGuideForcedKeys.retainAll(visibleKeys)
        if (force) liveGuideForcedKeys.addAll(visibleKeys)
        val paused = manualLiveGuideTarget != null || (snapshot.nowPlaying != null && !playerGuideViewportActive) || snapshot.loading ||
            snapshot.categoryFindSearching || snapshot.catalogLoadingMore
        liveGuideScheduler.update(targets, paused = paused)
        scheduleVisibleLiveGuideExpiry(targets, paused)
    }

    /** Wake the guide queue just after the first visible programme finishes. */
    private fun scheduleVisibleLiveGuideExpiry(targets: List<LiveGuideTarget>, paused: Boolean) {
        liveGuideExpiryJob?.cancel()
        liveGuideExpiryJob = null
        if (paused || targets.isEmpty()) return
        val now = System.currentTimeMillis()
        val refreshAt = (listOfNotNull(nextVisibleProgrammeExpiry(
            targets.mapNotNull(::visibleLiveGuideItem), now)) +
            targets.mapNotNull { target ->
                visibleLiveGuideItem(target)?.let { item ->
                    nextLiveGuideAttemptAt(item, now, liveGuideAttemptedAt[target.key],
                        liveGuideRetryPolicy.retryAt(target.profileKey, target.itemId))
                }
            }.filter { it > now })
            .minOrNull() ?: return
        liveGuideExpiryJob = viewModelScope.launch {
            delay((refreshAt - System.currentTimeMillis()).coerceAtLeast(0L) + 1_000L)
            val snapshot = _state.value
            val stillPaused = manualLiveGuideTarget != null || (snapshot.nowPlaying != null && !playerGuideViewportActive) || snapshot.loading ||
                snapshot.categoryFindSearching || snapshot.catalogLoadingMore
            liveGuideScheduler.update(targets, paused = stillPaused)
            if (!stillPaused) scheduleVisibleLiveGuideExpiry(targets, paused = false)
        }
    }

    private fun visibleLiveGuideItem(target: LiveGuideTarget): MediaItem? {
        val snapshot = _state.value
        if (snapshot.session?.profile?.cacheKey() != target.profileKey || snapshot.loading ||
            snapshot.categoryFindSearching || snapshot.catalogLoadingMore) return null
        val playing = snapshot.nowPlaying
        if (playing != null) {
            if (!playerGuideViewportActive || playing.catalogType != CatalogType.LIVE_TV) return null
            return (playing.episodeQueue + playing.media).firstOrNull {
                it.id == target.itemId && (it.portalCategoryId == target.categoryId || it.portalCategoryId == null ||
                    (snapshot.selectedCategory?.id == target.categoryId &&
                        it.portalCategoryId in snapshot.selectedCategory.sourceCategoryIds))
            }
        }
        if (snapshot.modernIptvCategory?.id != target.categoryId ||
            snapshot.selectedCategory?.id != target.categoryId) return null
        return snapshot.items.firstOrNull { it.id == target.itemId && (it.portalCategoryId == target.categoryId ||
            it.portalCategoryId in snapshot.selectedCategory.sourceCategoryIds) }
    }

    private fun isVisibleLiveGuideDue(target: LiveGuideTarget): Boolean {
        if (selectedLiveGuideTarget == target && channelScheduleJob?.isActive == true) return false
        val item = visibleLiveGuideItem(target) ?: return false
        val now = System.currentTimeMillis()
        if (now < liveGuideRetryPolicy.retryAt(target.profileKey, target.itemId)) return false
        val attemptAt = nextLiveGuideAttemptAt(item, now, liveGuideAttemptedAt[target.key],
            liveGuideRetryPolicy.retryAt(target.profileKey, target.itemId))
        return target.key in liveGuideForcedKeys ||
            (attemptAt != null && now >= attemptAt)
    }

    private suspend fun loadVisibleLiveGuide(target: LiveGuideTarget) {
        val item = visibleLiveGuideItem(target) ?: return
        val session = _state.value.session ?: return
        val profileKey = target.profileKey
        val key = target.key
        val attemptedAt = System.currentTimeMillis()
        liveGuideAttemptedAt[key] = attemptedAt
        liveGuideForcedKeys.remove(key)
        _state.update { it.copy(liveGuideLoadingIds = it.liveGuideLoadingIds + item.id) }
        try {
            val cleanItem = item.copy(
                liveProgramme = item.liveProgramme?.takeUnless {
                    isMissingLiveProgrammeTitle(it.title)
                },
                liveSchedule = item.liveSchedule.filterNot {
                    isMissingLiveProgrammeTitle(it.title)
                }
            )
            val enriched = withAutomaticSessionRetry(session) { activeSession ->
                portal.playingChannelSchedule(
                    activeSession,
                    cleanItem,
                    onProviderWait = { waitMillis ->
                        _state.update { state -> state.copy(
                            liveGuideWaitingUntilById = state.liveGuideWaitingUntilById +
                                (item.id to (System.currentTimeMillis() + waitMillis))
                        ) }
                    },
                    onRequestStarted = {
                        _state.update { state -> state.copy(
                            liveGuideWaitingUntilById = state.liveGuideWaitingUntilById - item.id
                        ) }
                    }
                )
            }
            Log.i(
                "NikTvLiveGuide",
                "Channel ${item.id}: current programme=${enriched.currentLiveProgramme(System.currentTimeMillis()) != null}, " +
                    "${enriched.liveSchedule.size} stored guide entries in ${System.currentTimeMillis() - attemptedAt}ms"
            )
            liveGuideRetryPolicy.record(profileKey, item.id,
                enriched.currentLiveProgramme(System.currentTimeMillis()) != null, System.currentTimeMillis())
            publishLiveChannelGuide(profileKey, enriched)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            liveGuideAttemptedAt.remove(key, attemptedAt)
            throw cancelled
        } catch (error: Exception) {
            liveGuideRetryPolicy.recordFailure(profileKey, item.id, System.currentTimeMillis())
            throw error
        } finally {
            _state.update { it.copy(
                liveGuideLoadingIds = it.liveGuideLoadingIds - item.id,
                liveGuideWaitingUntilById = it.liveGuideWaitingUntilById - item.id
            ) }
            rescheduleVisibleLiveGuideExpiry()
        }
    }

    private fun publishLiveChannelGuide(profileKey: String, enriched: MediaItem) {
        if (_state.value.session?.profile?.cacheKey() != profileKey) return
        collectionCatalogMemory?.takeIf { it.profileKey == profileKey && it.type == CatalogType.LIVE_TV }?.let { cache ->
            collectionCatalogMemory = cache.copy(items = cache.items.map {
                if (it.id == enriched.id) it.copy(liveProgramme = enriched.liveProgramme, liveSchedule = enriched.liveSchedule) else it
            })
        }
        _state.update { it.withLiveChannelGuide(profileKey, enriched) }
        rescheduleVisibleLiveGuideExpiry()
        liveGuideCacheSaveJob?.cancel()
        liveGuideCacheSaveJob = viewModelScope.launch {
            delay(2_500L)
            _state.value.browseCachesByType[CatalogType.LIVE_TV]
                ?.takeIf { it.profileKey == profileKey }
                ?.let { store.saveBrowseCatalog(it, scheduleMetadataSync = false) }
        }
    }

    private fun rescheduleVisibleLiveGuideExpiry() {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return
        val visible = lastVisibleLiveGuideIds
        if (visible?.first?.startsWith(profileKey) == true) {
            val snapshot = _state.value
            val categoryId = (if (playerGuideViewportActive) snapshot.selectedCategory else snapshot.modernIptvCategory)?.id
            if (categoryId != null) {
                val targets = visible.second.map { LiveGuideTarget(profileKey, categoryId, it) }
                scheduleVisibleLiveGuideExpiry(
                    targets,
                    manualLiveGuideTarget != null || (snapshot.nowPlaying != null && !playerGuideViewportActive) || snapshot.loading ||
                        snapshot.categoryFindSearching || snapshot.catalogLoadingMore
                )
            }
        }
    }

    private var manualLiveGuideTarget: LiveGuideTarget? = null

    fun refreshLiveProgramme(item: MediaItem) {
        val session = _state.value.session ?: return
        if (manualLiveGuideTarget?.let { it.profileKey == session.profile.cacheKey() && it.itemId == item.id } == true) return
        requestSelectedLiveGuide(session, item, force = true)
    }

    private fun requestSelectedLiveGuide(session: PortalSession, selectedItem: MediaItem, force: Boolean = false) {
        val item = _state.value.withCachedLiveGuide(session.profile.cacheKey(), selectedItem)
        val target = LiveGuideTarget(session.profile.cacheKey(), item.portalCategoryId.orEmpty(), item.id)
        if (!force && selectedLiveGuideTarget == target && channelScheduleJob?.isActive == true) return
        channelScheduleJob?.cancel()
        publishLiveChannelGuide(target.profileKey, item)
        // An old non-empty schedule can be expired; check what is airing now.
        if (!force && (item.currentLiveProgramme(System.currentTimeMillis()) != null ||
            System.currentTimeMillis() < liveGuideRetryPolicy.retryAt(target.profileKey, item.id))) return
        fun feedback(message: String) {
            if (force && _state.value.session?.profile?.cacheKey() == target.profileKey) {
                Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
            }
        }
        if (force) {
            manualLiveGuideTarget = target
            liveGuideScheduler.pause()
            _state.update { it.copy(manualLiveGuideLoadingId = item.id) }
            feedback("Refreshing programme…")
        }
        selectedLiveGuideTarget = target
        channelScheduleJob = viewModelScope.launch {
            val attemptedAt = System.currentTimeMillis()
            liveGuideAttemptedAt[target.key] = attemptedAt
            _state.update { it.copy(liveGuideLoadingIds = it.liveGuideLoadingIds + item.id) }
            try {
                val enriched = withAutomaticSessionRetry(session) {
                    portal.playingChannelSchedule(it,
                        item,
                        userSelected = true,
                        forceRefresh = force,
                        onProviderWait = { waitMillis ->
                            _state.update { state -> state.copy(
                                liveGuideWaitingUntilById = state.liveGuideWaitingUntilById +
                                    (item.id to (System.currentTimeMillis() + waitMillis))
                            ) }
                        },
                        onRequestStarted = {
                            _state.update { state -> state.copy(
                                liveGuideWaitingUntilById = state.liveGuideWaitingUntilById - item.id
                            ) }
                        })
                }
                Log.d("NikTvLiveGuide", "Selected channel ${item.id}: ${enriched.liveSchedule.size} guide entries")
                liveGuideRetryPolicy.record(target.profileKey, item.id,
                    enriched.currentLiveProgramme(System.currentTimeMillis()) != null, System.currentTimeMillis())
                publishLiveChannelGuide(target.profileKey, enriched)
                val now = System.currentTimeMillis()
                val previousProgramme = item.currentLiveProgramme(now)
                val refreshedProgramme = enriched.currentLiveProgramme(now)
                feedback(when {
                    refreshedProgramme == null -> "No current programme available from this provider"
                    refreshedProgramme != previousProgramme -> "Programme updated"
                    else -> "Current programme is already up to date"
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                liveGuideAttemptedAt.remove(target.key, attemptedAt)
                throw cancelled
            } catch (error: Exception) {
                Log.w("NikTvLiveGuide", "Could not load the selected channel guide", error)
                liveGuideRetryPolicy.recordFailure(target.profileKey, item.id, System.currentTimeMillis())
                feedback(error.message ?: "Could not refresh programme. Please try again.")
            } finally {
                _state.update { it.copy(
                    liveGuideLoadingIds = it.liveGuideLoadingIds - item.id,
                    liveGuideWaitingUntilById = it.liveGuideWaitingUntilById - item.id
                ) }
                if (manualLiveGuideTarget === target) {
                    manualLiveGuideTarget = null
                    _state.update { it.copy(manualLiveGuideLoadingId = null) }
                    val visible = lastVisibleLiveGuideIds?.second.orEmpty().toSet()
                    val snapshot = _state.value
                    val items = if (playerGuideViewportActive) snapshot.nowPlaying?.episodeQueue.orEmpty()
                        else snapshot.items
                    loadVisibleLiveGuides(items.filter { it.id in visible }, force = false)
                }
                rescheduleVisibleLiveGuideExpiry()
            }
        }
    }

    private var parentalRequest: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    fun lockAdultContent() { com.nikhil.niktv.data.ParentalControls.lock(getApplication()) }
    fun cancelParentalPin() { parentalRequest?.complete(false) }
    fun submitParentalPin(pin: String) {
        if (com.nikhil.niktv.data.ParentalControls.accepts(getApplication(), pin)) parentalRequest?.complete(true)
        else _state.update { it.copy(parentalPinError = true) }
    }
    private suspend fun authorizeParental(force: Boolean = false): Boolean {
        if (!force && (!com.nikhil.niktv.data.ParentalControls.enabled(getApplication()) || com.nikhil.niktv.data.ParentalControls.unlocked(getApplication()))) return true
        parentalRequest?.complete(false)
        val request = kotlinx.coroutines.CompletableDeferred<Boolean>()
        parentalRequest = request
        _state.update { it.copy(parentalPrompt = "Enter parental PIN", parentalPinError = false) }
        return try {
            request.await().also { if (it) com.nikhil.niktv.data.ParentalControls.unlock(getApplication()) }
        } finally {
            if (parentalRequest === request) {
                parentalRequest = null
                _state.update { it.copy(parentalPrompt = null, parentalPinError = false) }
            }
        }
    }
    private fun isAdultMedia(item: MediaItem, type: CatalogType): Boolean {
        val snapshot = _state.value
        val profile = snapshot.session?.profile?.cacheKey() ?: snapshot.savedProfile?.cacheKey().orEmpty()
        return item.isAdult || com.nikhil.niktv.data.adultContentLabel(item.title) ||
            com.nikhil.niktv.data.ParentalControls.adultCategory(getApplication(), profile, type, item.portalCategoryId) ||
            (snapshot.rawCategoriesByType[type].orEmpty() + snapshot.categories).any {
                it.type == type && it.id == item.portalCategoryId && (it.isAdult || com.nikhil.niktv.data.adultContentLabel(it.title))
            }
    }
    private fun gateAdultAction(item: MediaItem, type: CatalogType, action: () -> Unit): Boolean {
        if (!isAdultMedia(item, type) || !com.nikhil.niktv.data.ParentalControls.enabled(getApplication()) ||
            com.nikhil.niktv.data.ParentalControls.unlocked(getApplication())) return false
        viewModelScope.launch { if (authorizeParental()) action() }
        return true
    }
    fun setParentalControlsEnabled(enabled: Boolean) = viewModelScope.launch {
        if (!authorizeParental(force = true)) return@launch
        com.nikhil.niktv.data.ParentalControls.setEnabled(getApplication(), enabled)
        lockAdultContent()
        _state.update { it.copy(parentalControlsEnabled = enabled) }
    }
    fun authorizeParentalSettings(onAuthorized: () -> Unit) = viewModelScope.launch {
        if (authorizeParental(force = true)) onAuthorized()
    }
    fun setParentalRelockMinutes(minutes: Int) = viewModelScope.launch {
        if (!authorizeParental(force = true)) return@launch
        com.nikhil.niktv.data.ParentalControls.setRelockMinutes(getApplication(), minutes)
        _state.update { it.copy(parentalRelockMinutes = minutes) }
    }
    fun changeParentalPin(pin: String) {
        com.nikhil.niktv.data.ParentalControls.setPin(getApplication(), pin)
        lockAdultContent()
    }

    fun loadCategory(category: Category) = task {
        searchSeriesReturn = null
        if ((category.isAdult || com.nikhil.niktv.data.adultContentLabel(category.title)) && !authorizeParental()) return@task
        val session = requireNotNull(_state.value.session)
        val profileKey = session.profile.cacheKey()
        _state.update { it.copy(selectedCategory = category, items = emptyList(), selectedSeries = null,
            catalogLoadingMore = false, catalogHasMore = false) }
        if (category.sourceCategoryIds.isNotEmpty()) {
            val page = fetchInitialCatalogPage(session, category)
            _state.update { current ->
                if (current.session?.profile?.cacheKey() != profileKey || current.selectedCategory?.id != category.id) current
                else current.copy(items = page.items, catalogPage = 1, catalogHasMore = false)
            }
            return@task
        }

        // The per-type disk cache can contain tens of megabytes of catalogue
        // data. It is already loaded while entering the tab, so do not parse
        // the whole file again for every category selection.
        val memoryCache = _state.value.browseCachesByType[category.type]
            ?.takeIf { it.profileKey == profileKey }
        val categoryCache = (memoryCache
            ?: store.browseCatalog(category.type, profileKey).first())?.validateCategory(category.id, category.sourceCategoryIds)
        if (_state.value.session?.profile?.cacheKey() != profileKey ||
            _state.value.selectedType != category.type ||
            _state.value.selectedCategory?.id != category.id) return@task
        if (categoryCache != null) _state.update { it.copy(browseCache = categoryCache,
            browseCachesByType = it.browseCachesByType + (category.type to categoryCache)) }
        val cached = categoryCache?.itemsByCategory?.get(category.id)?.takeIf {
            categoryCache.pagesByCategory.containsKey(category.id) &&
                categoryCache.hasMoreByCategory.containsKey(category.id) &&
                !(session.profile.portalType == PortalType.XTREAM && category.type != CatalogType.LIVE_TV &&
                    categoryCache.hasMoreByCategory[category.id] == true)
        }

        if (cached != null) {
            _state.update { current -> current.copy(
                items = cached,
                catalogPage = categoryCache.pagesByCategory[category.id] ?: 1,
                catalogHasMore = categoryCache.hasMoreByCategory[category.id] ?: false
            ) }
            val cachedAt = categoryCache.categoryCachedAtMillis[category.id] ?: 0L
            val maxAge = _state.value.cacheIntervalMinutes * 60_000L
            // Programme guide entries age much faster than channel lists. Refresh
            // the selected Live TV category in the background so opening it stays instant.
            val effectiveMaxAge = if (category.type == CatalogType.LIVE_TV) {
                minOf(maxAge, 30 * 60_000L)
            } else maxAge
            if (System.currentTimeMillis() - cachedAt >= effectiveMaxAge) {
                refreshCategoryInBackground(session, category)
            }
            return@task
        }

        val firstPage = fetchInitialCatalogPage(session, category)
        if (_state.value.session?.profile?.cacheKey() != profileKey ||
            _state.value.selectedType != category.type ||
            _state.value.selectedCategory?.id != category.id) return@task
        val existing = _state.value.browseCachesByType[category.type]
            ?.takeIf { it.profileKey == profileKey && it.type == category.type }
            ?: categoryCache
        val updated = existing?.copy(
            cachedAtMillis = System.currentTimeMillis(),
            itemsByCategory = existing.itemsByCategory + (category.id to firstPage.items),
            pagesByCategory = existing.pagesByCategory + (category.id to firstPage.page),
            hasMoreByCategory = existing.hasMoreByCategory + (category.id to firstPage.hasMore),
            categoryCachedAtMillis = existing.categoryCachedAtMillis +
                (category.id to System.currentTimeMillis())
        )

        // Publish provider results before serializing the enlarged cache. This
        // keeps a cache write from extending the visible loading state.
        _state.update { current -> current.copy(
            items = firstPage.items,
            catalogPage = firstPage.page,
            catalogHasMore = firstPage.hasMore,
            browseCache = updated ?: current.browseCache,
            browseCachesByType = updated?.let {
                current.browseCachesByType + (category.type to it)
            } ?: current.browseCachesByType
        ) }
        if (updated != null) {
            viewModelScope.launch {
                runCatching { store.saveBrowseCatalog(updated) }
                    .onFailure { Log.w("NikTvCatalogCache", "Could not persist category ${category.id}", it) }
            }
        }
    }

    private fun categoryRefreshKey(session: PortalSession, category: Category) =
        "${session.profile.cacheKey()}|${category.type.name}|${category.id}"

    private fun refreshCategoryInBackground(
        session: PortalSession,
        category: Category,
        userRequested: Boolean = false
    ) {
        val key = categoryRefreshKey(session, category)
        val existingRefresh = categoryRefreshJobs[key]?.takeIf { it.isActive }
        if (existingRefresh != null) {
            if (userRequested) {
                _state.update { it.copy(categoryRefreshing = true) }
                viewModelScope.launch {
                    existingRefresh.join()
                    _state.update { it.copy(categoryRefreshing = false) }
                    Toast.makeText(getApplication(), "Category refresh finished", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        categoryRefreshJobs[key] = viewModelScope.launch {
            if (userRequested) _state.update { it.copy(categoryRefreshing = true, error = null) }
            try {
                val page = withAutomaticSessionRetry(session) { activeSession ->
                    fetchInitialCatalogPage(activeSession, category, forceRefresh = true,
                        minimumPages = if (userRequested) {
                            _state.value.browseCachesByType[category.type]
                                ?.takeIf { it.profileKey == session.profile.cacheKey() }
                                ?.pagesByCategory?.get(category.id) ?: 1
                        } else 1)
                }
                val profileKey = session.profile.cacheKey()
                if (_state.value.session?.profile?.cacheKey() != profileKey) return@launch
                if (category.sourceCategoryIds.isNotEmpty()) {
                    _state.update { current ->
                        if (current.selectedCategory?.id != category.id) current
                        else current.copy(items = page.items, catalogPage = 1, catalogHasMore = false)
                    }
                    return@launch
                }
                val currentCache = _state.value.browseCachesByType[category.type]
                    ?.takeIf { it.profileKey == profileKey }
                    ?: BrowseCatalogCache(profileKey, category.type, System.currentTimeMillis(),
                        _state.value.rawCategoriesByType[category.type].orEmpty().ifEmpty { listOf(category) }, emptyMap())
                val refreshedAt = System.currentTimeMillis()
                val cachedItems = currentCache.itemsByCategory[category.id].orEmpty()
                val cachedPage = currentCache.pagesByCategory[category.id] ?: 1
                // A background refresh only fetches the provider's first page.
                // Keep the pages the user already loaded instead of silently
                // collapsing the category back to page one when its cache ages.
                val refreshedWindow = com.nikhil.niktv.data.refreshedCategoryWindow(
                    cachedItems, cachedPage, currentCache.hasMoreByCategory[category.id], page, userRequested)
                val pageItems = refreshedWindow.items
                val cachedById = if (category.type == CatalogType.LIVE_TV) {
                    cachedItems.associateBy { it.id }
                } else emptyMap()
                val refreshedItems = pageItems.map { fresh ->
                    val previous = cachedById[fresh.id]
                    if (previous != null &&
                        previous.liveSchedule.size > fresh.liveSchedule.size &&
                        previous.liveSchedule.any {
                            !isMissingLiveProgrammeTitle(it.title) &&
                                (it.endTimeMillis ?: 0L) > refreshedAt
                        }) {
                        // The first-page response often has only a generic
                        // channel row. Keep its separately fetched guide.
                        fresh.copy(
                            liveProgramme = previous.liveProgramme,
                            liveSchedule = previous.liveSchedule
                        )
                    } else fresh
                }
                val refreshedPage = refreshedWindow.page
                val refreshedHasMore = refreshedWindow.hasMore
                val updated = currentCache.copy(
                    cachedAtMillis = refreshedAt,
                    itemsByCategory = currentCache.itemsByCategory + (category.id to refreshedItems),
                    pagesByCategory = currentCache.pagesByCategory + (category.id to refreshedPage),
                    hasMoreByCategory = currentCache.hasMoreByCategory + (category.id to refreshedHasMore),
                    categoryCachedAtMillis = currentCache.categoryCachedAtMillis +
                        (category.id to refreshedAt)
                )
                _state.update { current ->
                    val stillOpen = current.selectedType == category.type &&
                        current.selectedCategory?.id == category.id
                    current.copy(
                        items = if (stillOpen) refreshedItems else current.items,
                        catalogPage = if (stillOpen) refreshedPage else current.catalogPage,
                        catalogHasMore = if (stillOpen) refreshedHasMore else current.catalogHasMore,
                        browseCache = if (stillOpen) updated else current.browseCache,
                        browseCachesByType = current.browseCachesByType + (category.type to updated)
                    )
                }
                store.saveBrowseCatalog(updated)
                if (userRequested) Toast.makeText(getApplication(), "Category refreshed", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (userRequested) {
                    _state.update { it.copy(error = error.message ?: "Could not refresh this category") }
                } else {
                    Log.w("NikTvCatalogCache", "Background refresh failed for category ${category.id}", error)
                }
            } finally {
                categoryRefreshJobs.remove(key)
                if (userRequested) _state.update { it.copy(categoryRefreshing = false) }
            }
        }
    }

    /*
     * PLAYBACK_QUEUE_SCOPE_V16
     *
     * The browse screen and player can point at different categories.
     * Keep pagination keyed to the active playback source.
     */
    private fun playbackQueueScope(
        session: PortalSession,
        item: MediaItem,
        type: CatalogType,
        series: MediaItem?,
        selectedSeriesSeason: Int?
    ): String? {
        val source =
            when (type) {
                CatalogType.LIVE_TV,
                CatalogType.MOVIES ->
                    item.portalCategoryId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "category:$it" }

                CatalogType.SERIES ->
                    series?.id?.let { seriesId ->
                        val season =
                            item.seasonNumber
                                ?: selectedSeriesSeason
                                ?: -1
                        "series:$seriesId:season:$season"
                    }

                CatalogType.RADIO ->
                    null
            }

        return source?.let {
            "${session.profile.cacheKey()}|${type.name}|$it"
        }
    }

    /*
     * PLAYBACK_QUEUE_PAGINATION_SYNC_V15
     *
     * The player intentionally owns a category/season-scoped queue snapshot.
     * Pagination must therefore append to both state.items and the active
     * PlayingMedia queue. Otherwise the catalog grows while the player list
     * remains frozen on the first page.
     */
    private fun PlayingMedia.withAppendedPlaybackQueue(
        additions: List<MediaItem>
    ): PlayingMedia {
        if (additions.isEmpty()) return this

        val baseQueue =
            episodeQueue.ifEmpty { listOf(media) }

        val updatedQueue =
            (baseQueue + additions)
                .distinctBy { it.id }
                .let { queue ->
                    if (catalogType == CatalogType.SERIES) {
                        queue.sortedWith(
                            compareBy<MediaItem>(
                                { it.seasonNumber ?: Int.MAX_VALUE },
                                { it.episodeNumber ?: it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE },
                                { it.title.lowercase() }
                            )
                        )
                    } else {
                        queue
                    }
                }

        val currentIndex =
            updatedQueue.indexOfFirst {
                it.id == media.id
            }

        val wrap =
            catalogType == CatalogType.LIVE_TV &&
                updatedQueue.size > 1

        return copy(
            episodeQueue = updatedQueue,
            previousEpisode =
                updatedQueue.getOrNull(currentIndex - 1)
                    ?: updatedQueue.lastOrNull()
                        .takeIf {
                            wrap && currentIndex >= 0
                        },
            nextEpisode =
                updatedQueue.getOrNull(currentIndex + 1)
                    ?: updatedQueue.firstOrNull()
                        .takeIf {
                            wrap && currentIndex >= 0
                        }
        )
    }

    fun loadMoreCatalog() {
        val snapshot = _state.value
        val session = snapshot.session ?: return
        val category = snapshot.selectedCategory ?: return
        if (snapshot.loading || snapshot.catalogLoadingMore || snapshot.categoryFindSearching || !snapshot.catalogHasMore ||
            snapshot.selectedType !in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) return
        _state.update { it.copy(catalogLoadingMore = true) }
        viewModelScope.launch {
            runCatching {
                withAutomaticSessionRetry(session) { activeSession ->
                    portal.catalogPage(
                        session = activeSession,
                        category = category,
                        page = snapshot.catalogPage + 1,
                        pageSize = snapshot.initialCatalogItems.takeIf {
                            activeSession.profile.portalType == PortalType.XTREAM
                        },
                        includeEpg = false
                    )
                }
            }
                .onSuccess { result ->
                    /*
                     * PAGINATION_ACTUALLY_ADDED_V2
                     *
                     * Some portals report another page even when that page is
                     * empty or contains only IDs we already have. Treat that
                     * as the real end of the catalog so Load More disappears
                     * instead of repeatedly offering a no-op request.
                     */
                    val activeCatalog = _state.value
                    // A page may finish after the user has opened another
                    // category/profile. Never append it to that new selection.
                    if (activeCatalog.session?.profile?.cacheKey() != session.profile.cacheKey() ||
                        activeCatalog.selectedType != category.type ||
                        activeCatalog.selectedCategory?.id != category.id) return@onSuccess
                    val currentItems = activeCatalog.items
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
                                (category.id to merged),
                        pagesByCategory = existing.pagesByCategory + (category.id to result.page),
                        hasMoreByCategory = existing.hasMoreByCategory +
                            (category.id to (result.hasMore && actuallyAdded))
                    )

                    _state.update { current ->
                        val active =
                            current.nowPlaying

                        val updatedPlaying =
                            if (
                                active != null &&
                                active.catalogType == category.type &&
                                active.media.portalCategoryId == category.id
                            ) {
                                active.withAppendedPlaybackQueue(
                                    result.items.filter { active.queueFilter?.includes(it, active.catalogType) != false }
                                )
                            } else {
                                active
                            }

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
                                },
                            nowPlaying = updatedPlaying
                        )
                    }
                    if (updated != null) {
                        // Saving a large per-type cache can take noticeable time.
                        // The fetched page is already usable, so persist it without
                        // holding the Load more progress state open.
                        viewModelScope.launch {
                            runCatching { store.saveBrowseCatalog(updated) }
                                .onFailure {
                                    Log.w(
                                        "NikTvCatalogCache",
                                        "Could not persist page ${result.page} for category ${category.id}",
                                        it
                                    )
                                }
                        }
                    }
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.update {
                        if (it.session?.profile?.cacheKey() != session.profile.cacheKey() ||
                            it.selectedType != category.type || it.selectedCategory?.id != category.id) it
                        else it.copy(catalogLoadingMore = false, error = error.message ?: "Could not load more titles")
                    }
                }
        }
    }

    fun cancelCategoryFind() {
        categoryFindGeneration++
        categoryFindJob?.cancel()
        categoryFindJob = null
        _state.update { current ->
            current.copy(
                categoryFindSearching = false,
                categoryFindMessage = if (current.categoryFindSearching) {
                    "Search stopped. Results are kept; press Search to continue checking."
                } else current.categoryFindMessage
            )
        }
    }

    /** Search the whole selected category, then fill gaps in its current programme guide. */
    fun findChannelsInCategory(query: String) {
        val requestedQuery = query.trim()
        val snapshot = _state.value
        val session = snapshot.session ?: return
        val category = snapshot.modernIptvCategory
            ?.takeIf { it.type == CatalogType.LIVE_TV && it.id == snapshot.selectedCategory?.id }
            ?: return
        if (requestedQuery.isBlank() || snapshot.categoryFindSearching || snapshot.loading || snapshot.catalogLoadingMore) return

        val profileKey = session.profile.cacheKey()
        val key = categoryRefreshKey(session, category)
        categoryRefreshJobs[key]?.cancel()
        categoryFindJob?.cancel()
        liveGuideScheduler.pause()
        val generation = ++categoryFindGeneration
        categoryFindJob = viewModelScope.launch {
            var terminalMessage = "Checked every available page."
            var fetchedPages = 0
            var repeatedPages = 0
            _state.update { it.copy(
                categoryFindQuery = requestedQuery,
                categoryFindSearching = true,
                categoryFindPage = it.catalogPage,
                categoryFindTotalPages = null,
                categoryFindCheckingGuides = false,
                categoryFindGuideChecked = 0,
                categoryFindGuideTotal = 0,
                categoryFindMessage = null
            ) }
            try {
                while (true) {
                    val current = _state.value
                    if (generation != categoryFindGeneration ||
                        current.session?.profile?.cacheKey() != profileKey ||
                        current.modernIptvCategory?.id != category.id ||
                        !current.catalogHasMore) break
                    // Fetch channel pages first; current programme text may
                    // already be included. Fill missing guides in a second pass.
                    delay(650L)
                    val nextPage = current.catalogPage + 1
                    val result = withAutomaticSessionRetry(session) { activeSession ->
                        portal.catalogPage(
                            session = activeSession,
                            category = category,
                            page = nextPage,
                            pageSize = current.initialCatalogItems.takeIf {
                                activeSession.profile.portalType == PortalType.XTREAM
                            },
                            includeEpg = false
                        )
                    }
                    coroutineContext.ensureActive()
                    val latest = _state.value
                    if (generation != categoryFindGeneration ||
                        latest.session?.profile?.cacheKey() != profileKey ||
                        latest.modernIptvCategory?.id != category.id) break
                    val merged = (latest.items + result.items).distinctBy { it.id }
                    repeatedPages = if (merged.size == latest.items.size) repeatedPages + 1 else 0
                    val hasMore = result.hasMore
                    val cache = latest.browseCachesByType[CatalogType.LIVE_TV]
                        ?.takeIf { it.profileKey == profileKey }
                    val updated = cache?.copy(
                        cachedAtMillis = System.currentTimeMillis(),
                        itemsByCategory = cache.itemsByCategory + (category.id to merged),
                        pagesByCategory = cache.pagesByCategory + (category.id to result.page),
                        hasMoreByCategory = cache.hasMoreByCategory + (category.id to hasMore)
                    )
                    _state.update { state ->
                        if (generation != categoryFindGeneration ||
                            state.session?.profile?.cacheKey() != profileKey ||
                            state.modernIptvCategory?.id != category.id) state
                        else state.copy(
                            items = merged,
                            catalogPage = result.page,
                            catalogHasMore = hasMore,
                            categoryFindPage = result.page,
                            categoryFindTotalPages = result.totalPages,
                            browseCache = updated ?: state.browseCache,
                            browseCachesByType = if (updated == null) state.browseCachesByType
                                else state.browseCachesByType + (CatalogType.LIVE_TV to updated),
                            nowPlaying = state.nowPlaying?.takeIf {
                                it.catalogType == CatalogType.LIVE_TV && it.media.portalCategoryId == category.id
                            }?.withAppendedPlaybackQueue(result.items) ?: state.nowPlaying
                        )
                    }
                    fetchedPages++
                    if (fetchedPages % 5 == 0) {
                        runCatching { persistCategoryFindCache(profileKey) }
                            .onFailure { Log.w("NikTvCatalogCache", "Could not persist channel search pages", it) }
                    }
                    if (repeatedPages >= 3) {
                        terminalMessage = "The provider repeated three pages, so the search stopped at page ${result.page}."
                        break
                    }
                    if (!hasMore) break
                }
                coroutineContext.ensureActive()
                if (generation == categoryFindGeneration &&
                    _state.value.session?.profile?.cacheKey() == profileKey &&
                    _state.value.modernIptvCategory?.id == category.id) {
                    val guideSummary = findCategoryProgrammes(session, category, generation)
                    terminalMessage = if (_state.value.catalogHasMore) "$terminalMessage $guideSummary"
                        else "All channel pages checked. $guideSummary"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                terminalMessage = "Could not continue after page ${_state.value.catalogPage}: ${error.message ?: "provider unavailable"}"
            } finally {
                withContext(NonCancellable) {
                    runCatching { persistCategoryFindCache(profileKey) }
                        .onFailure { Log.w("NikTvCatalogCache", "Could not persist channel search", it) }
                }
                if (generation == categoryFindGeneration) {
                    _state.update { it.copy(
                        categoryFindSearching = false,
                        categoryFindMessage = terminalMessage
                    ) }
                }
            }
        }
    }

    private suspend fun findCategoryProgrammes(session: PortalSession, category: Category, generation: Int): String {
        val profileKey = session.profile.cacheKey()
        fun isCurrent() = generation == categoryFindGeneration &&
            _state.value.session?.profile?.cacheKey() == profileKey &&
            _state.value.modernIptvCategory?.id == category.id
        fun publishGuide(enriched: List<MediaItem>) {
            val byId = enriched.associateBy { it.id }
            _state.update { state ->
                if (!isCurrent()) state else {
                    val items = state.items.map { byId[it.id] ?: it }
                    val cache = state.browseCachesByType[CatalogType.LIVE_TV]
                        ?.takeIf { it.profileKey == profileKey }
                    val updated = cache?.takeIf { category.sourceCategoryIds.isEmpty() }
                        ?.copy(itemsByCategory = cache.itemsByCategory + (category.id to items))
                    state.copy(items = items, browseCache = updated ?: state.browseCache,
                        browseCachesByType = if (updated == null) state.browseCachesByType
                            else state.browseCachesByType + (CatalogType.LIVE_TV to updated))
                }
            }
        }
        val items = _state.value.items
        _state.update { it.copy(categoryFindCheckingGuides = true, categoryFindGuideTotal = items.size) }
        if (items.any { it.currentLiveProgramme(System.currentTimeMillis()) == null }) {
            val enriched = withAutomaticSessionRetry(session) { portal.categoryChannelSchedules(it, items) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isCurrent()) return ""
            publishGuide(enriched)
        }
        var unavailable = 0
        var failed = 0
        var consecutiveFailures = 0
        val channels = _state.value.items
        for ((index, channel) in channels.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isCurrent()) return ""
            var enriched = channel
            val now = System.currentTimeMillis()
            val key = "$profileKey|${category.id}|${channel.id}"
            if (channel.currentLiveProgramme(now) == null &&
                now >= liveGuideRetryPolicy.retryAt(profileKey, channel.id) &&
                now - (liveGuideAttemptedAt[key] ?: 0L) >= 2 * 60_000L) {
                try {
                    delay(650L)
                    enriched = withAutomaticSessionRetry(session) { portal.playingChannelSchedule(it, channel) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return ""
                    liveGuideAttemptedAt[key] = System.currentTimeMillis()
                    liveGuideRetryPolicy.record(profileKey, channel.id,
                        enriched.currentLiveProgramme(System.currentTimeMillis()) != null, System.currentTimeMillis())
                    publishGuide(listOf(enriched))
                    consecutiveFailures = 0
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed++
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) return "Guide checks stopped after three provider errors. Press Search to retry."
                }
            }
            if (enriched.currentLiveProgramme(System.currentTimeMillis()) == null) unavailable++
            _state.update { it.copy(categoryFindGuideChecked = index + 1) }
        }
        return "Programme guides checked. $unavailable channels have no current programme information." +
            if (failed > 0) " $failed guide requests failed; press Search to retry." else ""
    }

    private suspend fun persistCategoryFindCache(profileKey: String) {
        categoryFindSaveMutex.withLock {
            _state.value.browseCachesByType[CatalogType.LIVE_TV]
                ?.takeIf { it.profileKey == profileKey }
                ?.let { store.saveBrowseCatalog(it, scheduleMetadataSync = false) }
        }
    }

    /** Refresh category names/IDs only; retain media pages, history, filters and current selection. */
    fun refreshProviderCategories(type: CatalogType = _state.value.selectedType, userRequested: Boolean = true) {
        val session = _state.value.session ?: return
        if (!userRequested && _state.value.nowPlaying != null) return
        val profile = session.profile.cacheKey()
        val key = profile to type
        if (categoryMetadataRefreshJobs[key]?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!userRequested && categoryRefreshAttempts[key]?.let { now - it < 5 * 60_000L } == true) return
        categoryRefreshAttempts[key] = now
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            _state.update { it.copy(categoryRefreshes = it.categoryRefreshes + key) }
            try {
                val fresh = categoryRefreshSlots.withPermit {
                    withAutomaticSessionRetry(session) { active -> categoryMetadataLookup(active, type, !userRequested) }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                require(fresh.isNotEmpty()) { "The provider returned no categories. Your existing list has been kept." }
                val refreshedAt = System.currentTimeMillis()
                _state.update { current ->
                    if (current.session?.profile != session.profile) current else {
                        val old = current.browseCachesByType[type]?.takeIf { it.profileKey == profile }
                            ?: BrowseCatalogCache(profile, type, refreshedAt, emptyList(), emptyMap())
                        val cache = old.copy(categories = fresh, categoriesCachedAtMillis = refreshedAt)
                        val active = current.selectedType == type
                        current.copy(rawCategoriesByType = current.rawCategoriesByType + (type to fresh),
                            categories = if (active) filterCategories(fresh, profile, type, current.categoryFilters) else current.categories,
                            selectedCategory = if (active) current.selectedCategory?.let { selected -> fresh.firstOrNull { it.id == selected.id } ?: selected } else current.selectedCategory,
                            browseCachesByType = current.browseCachesByType + (type to cache),
                            browseCache = if (current.browseCache?.type == type) cache else current.browseCache)
                    }
                }
                if (_state.value.session?.profile == session.profile) {
                    // Persist only small category metadata, never rewrite the potentially large media cache here.
                    store.saveBrowseCatalogMetadata(BrowseCatalogMetadataCache(profile, type, refreshedAt, fresh, refreshedAt))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (userRequested) _state.update { current -> if (current.session?.profile != session.profile) current
                    else current.copy(error = error.message ?: "Could not refresh IPTV categories") }
            } finally {
                val owner = kotlinx.coroutines.currentCoroutineContext()[Job]
                if (categoryMetadataRefreshJobs[key] == null || categoryMetadataRefreshJobs[key] == owner) {
                    _state.update { it.copy(categoryRefreshes = it.categoryRefreshes - key) }
                    if (categoryMetadataRefreshJobs[key] == owner) categoryMetadataRefreshJobs.remove(key)
                }
            }
        }
        categoryMetadataRefreshJobs[key] = job
        job.start()
    }

    fun refreshCatalog() {
        val snapshot = _state.value
        val series = snapshot.selectedSeries
        if (series != null) {
            task {
                _state.update { it.copy(items = emptyList()) }
                loadSeriesEpisodes(series, snapshot.selectedSeriesSeason, forceRefresh = true)
            }
            return
        }
        val session = snapshot.session ?: return
        val category = snapshot.selectedCategory ?: return
        // Category refresh updates channel/media listings. Programme refresh remains
        // an explicit per-channel action from the long-press menu.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { portal.invalidateCatalog(session.profile, category) }
            if (_state.value.session?.profile == session.profile &&
                _state.value.selectedCategory?.id == category.id) {
                refreshCategoryInBackground(session, category, userRequested = true)
            }
        }
    }

    fun setCacheIntervalMinutes(minutes: Int) = viewModelScope.launch {
        store.setCacheIntervalMinutes(minutes)
    }

    fun setInitialCatalogItems(items: Int) = viewModelScope.launch {
        val normalized = items.takeIf { it in INITIAL_CATALOG_OPTIONS } ?: 14
        store.setInitialCatalogItems(normalized)
        _state.update { it.copy(initialCatalogItems = normalized) }
        val snapshot = _state.value
        val session = snapshot.session
        if (session != null) {
            loadTypeInternal(
                session = session,
                type = snapshot.selectedType,
                forceRefresh = true,
                preferredCategoryId = snapshot.selectedCategory?.id
            )
        }
    }

    fun setPlayerControlsTimeoutSeconds(seconds: Int) {
        val normalized = if (seconds <= 0) 0 else seconds.coerceIn(1, 30)
        _state.update { it.copy(playerControlsTimeoutSeconds = normalized) }
        viewModelScope.launch {
            store.setPlayerControlsTimeoutSeconds(normalized)
        }
    }

    fun setKeepAwakeOnlyDuringPlayback(enabled: Boolean) = viewModelScope.launch {
        store.setKeepAwakeOnlyDuringPlayback(enabled)
    }
    fun setAutomaticReauthentication(enabled: Boolean) = viewModelScope.launch {
        store.setAutomaticReauthentication(enabled)
    }

    /*
     * PLAYER_LOAD_MORE_QUEUE_V16
     *
     * This is intentionally independent from loadMoreCatalog(). The latter
     * belongs to the browse screen and uses selectedCategory. The player must
     * instead paginate the category/series that is actually playing.
     */
    fun loadMorePlaybackQueue(): Boolean {
        val snapshot = _state.value
        val playing = snapshot.nowPlaying ?: return false
        val session = snapshot.session ?: return false
        val queueCategoryId = playing.media.portalCategoryId?.takeIf { it.isNotBlank() }
            ?: playing.episodeQueue.firstNotNullOfOrNull { it.portalCategoryId?.takeIf(String::isNotBlank) }
        val scopedMedia = if (playing.media.portalCategoryId.isNullOrBlank() && queueCategoryId != null) {
            playing.media.copy(portalCategoryId = queueCategoryId)
        } else playing.media
        val scope = snapshot.playbackQueueScope ?: playbackQueueScope(
            session = session,
            item = scopedMedia,
            type = playing.catalogType,
            series = playing.series,
            selectedSeriesSeason = snapshot.selectedSeriesSeason
        ) ?: return false

        val earliestLoadedEpisode = if (playing.catalogType == CatalogType.SERIES) {
            playing.episodeQueue.mapNotNull { it.episodeNumber }.minOrNull()
        } else null
        val hasEarlierEpisodes = earliestLoadedEpisode != null && earliestLoadedEpisode > 1
        val canLoadMore = snapshot.playbackQueueHasMore || hasEarlierEpisodes

        if (
            snapshot.playbackQueueLoadingMore ||
            !canLoadMore
        ) {
            return false
        }

        _state.update { current ->
            if (current.nowPlaying == null) {
                current
            } else {
                current.copy(
                    playbackQueueScope = scope,
                    playbackQueueLoadingMore = true
                )
            }
        }

        viewModelScope.launch {

            if (
                playing.catalogType == CatalogType.SERIES &&
                playing.series != null
            ) {
                val series = playing.series
                val season =
                    playing.media.seasonNumber
                        ?: snapshot.selectedSeriesSeason

                val cachedSeason = episodeSeasonCaches.firstOrNull {
                    it.profileKey == session.profile.cacheKey() &&
                        it.seriesId == series.id &&
                        (season == null || it.season == season)
                }
                val targetPage = maxOf(snapshot.playbackQueuePage, cachedSeason?.page ?: 1) + 1

                runCatching {
                    withTimeout(45_000L) {
                        portal.episodeSeason(
                            session,
                            series,
                            snapshot.seriesStartSeason,
                            season,
                            targetPage,
                            playing.media.portalSeasonId
                                ?: playing.episodeQueue
                                    .firstOrNull()
                                    ?.portalSeasonId
                                ?: cachedSeason?.episodes?.firstOrNull()?.portalSeasonId
                        )
                    }
                }.onSuccess { next ->
                    if (_state.value.nowPlaying?.playbackAttemptId != playing.playbackAttemptId ||
                        _state.value.session != session) return@onSuccess
                    val sameSeasonEpisodes = next.episodes.filter { season == null || it.seasonNumber == null || it.seasonNumber == season }
                    val oldQueue =
                        playing.episodeQueue.ifEmpty {
                            listOf(playing.media)
                        }

                    val mergedQueue =
                        (oldQueue + sameSeasonEpisodes)
                            .distinctBy { it.id }
                            .sortedWith(
                                compareBy<MediaItem>(
                                    { it.seasonNumber ?: Int.MAX_VALUE },
                                    { it.episodeNumber ?: it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE },
                                    { it.title.lowercase() }
                                )
                            )

                    val actuallyAdded =
                        mergedQueue.size > oldQueue.size

                    val stillHasEarlier = mergedQueue
                        .mapNotNull { it.episodeNumber }
                        .minOrNull()
                        ?.let { it > 1 } == true

                    val hasMore =
                        (next.hasMore && actuallyAdded) || (next.hasMore && stillHasEarlier)

                    val previousCache = episodeSeasonCaches.firstOrNull {
                        it.profileKey == session.profile.cacheKey() &&
                            it.seriesId == series.id &&
                            (season == null || it.season == season)
                    }
                    val previousEnriched = previousCache?.episodes.orEmpty().associateBy { it.id }
                    val rawQueue = mergedQueue.map { episode ->
                        previousCache?.rawIptvEpisodes()
                            ?.firstOrNull { it.id == episode.id }
                            ?: episode
                    }
                    val cache =
                        EpisodeSeasonCache(
                            session.profile.cacheKey(),
                            series.id,
                            next.selectedSeason ?: season,
                            mergeSeriesSeasons(previousCache?.availableSeasons.orEmpty() + snapshot.availableSeriesSeasons,
                                next.availableSeasons, next.selectedSeason ?: season),
                            rawQueue.map { previousEnriched[it.id] ?: it },
                            maxOf(next.page, targetPage),
                            hasMore,
                            EPISODE_METADATA_VERSION,
                            iptvEpisodes = rawQueue,
                            tmdbEpisodeKeys = previousCache?.tmdbEpisodeKeys.orEmpty(),
                            tmdbSeriesId = previousCache?.tmdbSeriesId,
                            tmdbCachedAtMillis = previousCache?.tmdbCachedAtMillis ?: 0L,
                            seasonIndexValidated = previousCache?.seasonIndexValidated == true
                        )

                    episodeSeasonCaches =
                        listOf(cache) +
                            episodeSeasonCaches.filterNot {
                                it.key == cache.key
                            }
                    store.saveEpisodeSeasonCache(cache)

                    // Keep the loading tile visible until new episodes have their
                    // cached/fetched metadata, then publish the page atomically.
                    runCatching {
                        enrichEpisodeCacheFromTmdb(series, cache, force = false)
                    }.onFailure {
                        Log.w("NikTvEpisodeMetadata", "Player page enrichment failed", it)
                    }
                    val publishedCache = episodeSeasonCaches.firstOrNull { it.key == cache.key } ?: cache
                    val nextIds = next.episodes.mapTo(mutableSetOf()) { it.id }
                    val enrichedAdditions = publishedCache.episodes.filter { it.id in nextIds }

                    _state.update { current ->
                        if (
                            current.playbackQueueScope != scope
                        ) {
                            current.copy(
                                playbackQueueLoadingMore = false
                            )
                        } else {
                            val active = current.nowPlaying

                            if (
                                active == null ||
                                active.catalogType !=
                                    CatalogType.SERIES ||
                                active.series?.id != series.id ||
                                active.media.seasonNumber != playing.media.seasonNumber ||
                                active.playbackAttemptId != playing.playbackAttemptId ||
                                current.session != session
                            ) {
                                current.copy(
                                    playbackQueueLoadingMore = false
                                )
                            } else {
                                val updatedPlaying =
                                    active.withAppendedPlaybackQueue(
                                        enrichedAdditions
                                    )

                                val browseMatches =
                                    current.selectedSeries?.id ==
                                        series.id

                                current.copy(
                                    nowPlaying = updatedPlaying,
                                    playbackQueuePage = maxOf(next.page, targetPage),
                                    playbackQueueHasMore = hasMore,
                                    playbackQueueLoadingMore = false,
                                    items =
                                        if (browseMatches) {
                                            (current.items + enrichedAdditions)
                                                .distinctBy { it.id }
                                                .sortedWith(
                                                    compareBy<MediaItem>(
                                                        { it.seasonNumber ?: Int.MAX_VALUE },
                                                        { it.episodeNumber ?: it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE },
                                                        { it.title.lowercase() }
                                                    )
                                                )
                                        } else {
                                            current.items
                                        },
                                    episodePage =
                                        if (browseMatches) {
                                            next.page
                                        } else {
                                            current.episodePage
                                        },
                                    episodeHasMore =
                                        if (browseMatches) {
                                            hasMore
                                        } else {
                                            current.episodeHasMore
                                        }
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                    if (_state.value.nowPlaying?.playbackAttemptId != playing.playbackAttemptId ||
                        _state.value.session != session) return@onFailure
                    _state.update {
                        it.copy(
                            playbackQueueLoadingMore = false,
                            error =
                                error.message
                                    ?: "Could not load more episodes"
                        )
                    }
                }

                return@launch
            }

            if (
                playing.catalogType !in setOf(
                    CatalogType.LIVE_TV,
                    CatalogType.MOVIES
                )
            ) {
                _state.update {
                    it.copy(
                        playbackQueueHasMore = false,
                        playbackQueueLoadingMore = false
                    )
                }
                return@launch
            }

            val categoryId = queueCategoryId

            if (categoryId == null) {
                _state.update {
                    it.copy(
                        playbackQueueHasMore = false,
                        playbackQueueLoadingMore = false
                    )
                }
                return@launch
            }

            val category =
                snapshot.selectedCategory
                    ?.takeIf {
                        it.id == categoryId &&
                            it.type == playing.catalogType
                    }
                    ?: snapshot.rawCategoriesByType[
                        playing.catalogType
                    ]
                    .orEmpty()
                    .firstOrNull {
                        it.id == categoryId
                    }
                    // catalogPage only needs the authoritative IPTV category
                    // id and media type. Avoid a second categories request when
                    // playback came from Home/search and no browse category is
                    // currently selected.
                    ?: Category(
                        id = categoryId,
                        title = "Playback queue",
                        type = playing.catalogType
                    )

            val existingPlaybackIds =
                playing.episodeQueue
                    .ifEmpty { listOf(playing.media) }
                    .mapTo(mutableSetOf()) { it.id }

            runCatching {
                withTimeout(45_000L) {
                    val requestedPage = snapshot.playbackQueuePage + 1
                    val prefetchKey = playbackQueuePrefetchKey(scope, requestedPage)
                    val startedAt = SystemClock.elapsedRealtime()
                    val prefetchedResult = playbackQueuePrefetches.takeReady(prefetchKey, session)
                    val result = prefetchedResult
                        ?: fetchNextUniquePlaybackPage(
                            session = session,
                            category = category,
                            requestedPage = requestedPage,
                            existingIds = existingPlaybackIds
                        )
                    Log.i(
                        PLAYBACK_QUEUE_LOG_TAG,
                        "Load more page=${result.page} source=${if (prefetchedResult != null) "prefetch" else "foreground"} " +
                            "waitMs=${SystemClock.elapsedRealtime() - startedAt} items=${result.items.size}"
                    )
                    result
                }
            }.onSuccess { result ->
                if (_state.value.nowPlaying?.playbackAttemptId != playing.playbackAttemptId ||
                    _state.value.session != session) return@onSuccess
                val oldQueue =
                    playing.episodeQueue.ifEmpty {
                        listOf(playing.media)
                    }

                val mergedQueue =
                    (oldQueue + result.items)
                        .distinctBy { it.id }

                val actuallyAdded =
                    mergedQueue.size > oldQueue.size

                val hasMore =
                    result.hasMore && actuallyAdded

                val existingCache =
                    _state.value
                        .browseCachesByType[
                            playing.catalogType
                        ]

                val cachedCategoryItems =
                    existingCache
                        ?.itemsByCategory
                        ?.get(categoryId)
                        .orEmpty()

                val updatedCacheItems =
                    (
                        cachedCategoryItems +
                            result.items
                        ).distinctBy { it.id }

                val updatedCache =
                    existingCache?.copy(
                        cachedAtMillis =
                            System.currentTimeMillis(),
                        itemsByCategory =
                            existingCache.itemsByCategory +
                                (
                                    categoryId to
                                        updatedCacheItems
                                    )
                    )

                if (updatedCache != null) {
                    store.saveBrowseCatalog(updatedCache)
                }

                if (hasMore) {
                    schedulePlaybackQueuePrefetch(
                        session = session,
                        scope = scope,
                        category = category,
                        currentPage = result.page,
                        existingIds = mergedQueue.mapTo(mutableSetOf()) { it.id }
                    )
                }

                _state.update { current ->
                    if (
                        current.playbackQueueScope != scope
                    ) {
                        current.copy(
                            playbackQueueLoadingMore = false
                        )
                    } else {
                        val active = current.nowPlaying

                        val activeMatches =
                            active != null &&
                                active.catalogType ==
                                    playing.catalogType &&
                                (
                                    active.media.portalCategoryId == categoryId ||
                                        active.episodeQueue.any { it.portalCategoryId == categoryId }
                                    )

                        if (!activeMatches) {
                            current.copy(
                                playbackQueueLoadingMore = false
                            )
                        } else {
                            val updatedPlaying =
                                active.withAppendedPlaybackQueue(
                                    result.items.filter { active.queueFilter?.includes(it, active.catalogType) != false }
                                )

                            val browseMatches =
                                current.selectedType ==
                                    playing.catalogType &&
                                    current.selectedCategory?.id ==
                                        categoryId

                            current.copy(
                                nowPlaying = updatedPlaying,
                                playbackQueuePage = result.page,
                                playbackQueueHasMore = hasMore,
                                playbackQueueLoadingMore = false,
                                items =
                                    if (browseMatches) {
                                        (
                                            current.items +
                                                result.items
                                            ).distinctBy {
                                                it.id
                                            }
                                    } else {
                                        current.items
                                    },
                                catalogPage =
                                    if (browseMatches) {
                                        result.page
                                    } else {
                                        current.catalogPage
                                    },
                                catalogHasMore =
                                    if (browseMatches) {
                                        hasMore
                                    } else {
                                        current.catalogHasMore
                                    },
                                browseCache =
                                    updatedCache
                                        ?: current.browseCache,
                                browseCachesByType =
                                    if (updatedCache == null) {
                                        current.browseCachesByType
                                    } else {
                                        current.browseCachesByType +
                                            (
                                                playing.catalogType to
                                                    updatedCache
                                                )
                                    }
                            )
                        }
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (_state.value.nowPlaying?.playbackAttemptId != playing.playbackAttemptId ||
                    _state.value.session != session) return@onFailure
                _state.update {
                    it.copy(
                        playbackQueueLoadingMore = false,
                        error =
                            error.message
                                ?: "Could not load more titles"
                    )
                }
            }
        }
        return true
    }

    private suspend fun fetchNextUniquePlaybackPage(
        session: PortalSession,
        category: Category,
        requestedPage: Int,
        existingIds: Set<String>,
        background: Boolean = false
    ): PortalCatalogPage {
        var nextPage = requestedPage
        var result = portal.catalogPage(session, category, nextPage, includeEpg = false, background = background)
        var duplicatePagesSkipped = 0

        while (
            result.hasMore &&
            result.items.none { it.id !in existingIds } &&
            duplicatePagesSkipped < 3
        ) {
            duplicatePagesSkipped++
            nextPage = (result.page + 1).coerceAtLeast(nextPage + 1)
            result = portal.catalogPage(session, category, nextPage, includeEpg = false, background = background)
        }
        return result
    }

    /**
     * Apply the configured initial media count to both provider types. Xtream
     * returns a full category and is sliced locally; WIO/Ministra fixes remote
     * responses at 14 rows, so complete pages are combined up to the target.
     */
    private val collectionCatalogMutex = Mutex()
    private var collectionCatalogMemory: SearchCatalogCache? = null

    private suspend fun collectionCatalog(
        session: PortalSession, type: CatalogType, force: Boolean
    ): SearchCatalogCache = collectionCatalogMutex.withLock {
        val profile = session.profile.cacheKey()
        val now = System.currentTimeMillis()
        val ttl = _state.value.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L
        val memory = collectionCatalogMemory?.takeIf { it.profileKey == profile && it.type == type }
        val cached = if (force) null else memory ?: store.searchCatalog(type, profile).first()
        if (!force && cached != null && "*" in cached.completedCategoryIds &&
            now >= cached.cachedAtMillis && now - cached.cachedAtMillis < ttl) {
            collectionCatalogMemory = cached
            Log.i("NikTvCollections", "$type: local catalogue hit (${cached.items.size} titles)")
            return@withLock cached
        }
        if (force || cached != null) withContext(Dispatchers.IO) { portal.invalidateFullCatalog(session.profile, type) }
        val started = SystemClock.elapsedRealtime()
        val items = portal.fullCatalog(session, type, emptyList())
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        Log.i("NikTvCollections", "$type: full catalogue ready (${items.size} titles) in ${SystemClock.elapsedRealtime() - started} ms")
        SearchCatalogCache(profile, type, System.currentTimeMillis(), items, setOf("*")).also {
            collectionCatalogMemory = it
            // This is the existing file search cache, not the experimental Room catalogue.
            store.saveSearchCatalog(it, scheduleMetadataSync = false)
        }
    }

    private suspend fun fetchInitialCatalogPage(
        session: PortalSession,
        category: Category,
        forceRefresh: Boolean = false,
        minimumPages: Int = 1
    ): PortalCatalogPage {
        if (session.profile.portalType == PortalType.XTREAM && category.sourceCategoryIds.isNotEmpty()) {
            val catalog = collectionCatalog(session, category.type, forceRefresh)
            val items = withContext(Dispatchers.Default) { CategoryOrganization.select(catalog.items, category) }
            return PortalCatalogPage(items, 1, false, totalPages = 1, totalItems = items.size)
        }
        // Xtream already returns the entire category in one response. Publish it
        // once so local filtering can search every title, not just the first UI page.
        if (session.profile.portalType == PortalType.XTREAM && category.type != CatalogType.LIVE_TV) {
            return portal.catalogPage(session, category, 1)
        }
        val target = _state.value.initialCatalogItems
        val firstPage = portal.catalogPage(
            session = session,
            category = category,
            page = 1,
            pageSize = target.takeIf { session.profile.portalType == PortalType.XTREAM },
            includeEpg = false
        )
        if (session.profile.portalType != PortalType.STALKER || !firstPage.hasMore) {
            return firstPage
        }

        val merged = firstPage.items.toMutableList()
        val knownIds = merged.mapTo(mutableSetOf()) { it.id }
        var latestPage = firstPage

        while (latestPage.hasMore && (merged.size < target || latestPage.page < minimumPages)) {
            val nextPage = portal.catalogPage(session, category, latestPage.page + 1,
                includeEpg = false)
            val additions = nextPage.items.filter { knownIds.add(it.id) }
            latestPage = nextPage
            if (additions.isEmpty()) break
            merged += additions
        }

        return PortalCatalogPage(
            // Keep the complete final provider page. Truncating a 14-row page
            // at the target would skip its remaining rows when pagination
            // resumes from the following page.
            items = merged,
            page = latestPage.page,
            hasMore = latestPage.hasMore,
            totalPages = latestPage.totalPages ?: firstPage.totalPages,
            totalItems = latestPage.totalItems ?: firstPage.totalItems,
            pageSize = latestPage.pageSize ?: firstPage.pageSize
        )
    }

    private fun playbackQueuePrefetchKey(scope: String, requestedPage: Int) =
        "$scope:page:$requestedPage"

    private fun schedulePlaybackQueuePrefetch(
        session: PortalSession,
        scope: String,
        category: Category,
        currentPage: Int,
        existingIds: Set<String>
    ) {
        if (session.profile.portalType != PortalType.STALKER) return
        if (category.type !in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES)) return

        val requestedPage = currentPage + 1
        val key = playbackQueuePrefetchKey(scope, requestedPage)
        val memory = android.app.ActivityManager.MemoryInfo()
        (getApplication<android.app.Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager).getMemoryInfo(memory)
        playbackQueuePrefetches.schedule(key, session, permitted = !memory.lowMemory) {
            val startedAt = SystemClock.elapsedRealtime()
            fetchNextUniquePlaybackPage(
                session = session,
                category = category,
                requestedPage = requestedPage,
                existingIds = existingIds,
                background = true
            ).also { result ->
                check(result.items.size <= 1000) { "Speculative catalog page exceeds warm-cache budget" }
                Log.i(
                    PLAYBACK_QUEUE_LOG_TAG,
                    "Prefetched page=${result.page} elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "items=${result.items.size}"
                )
            }
        }
    }

    fun refreshPlaybackQueue() = task {
        val snapshot = _state.value
        val playing = snapshot.nowPlaying ?: return@task
        val session = requireNotNull(snapshot.session)
        val queue = if (playing.catalogType == CatalogType.SERIES && playing.series != null) {
            portal.episodeSeason(
                session,
                playing.series,
                snapshot.seriesStartSeason,
                playing.media.seasonNumber
            ).episodes
        } else {
            val categoryId = playing.media.portalCategoryId
                ?: return@task
            val category = snapshot.rawCategoriesByType[playing.catalogType]
                .orEmpty()
                .firstOrNull { it.id == categoryId }
                ?: portal.categories(session, playing.catalogType).firstOrNull { it.id == categoryId }
                ?: return@task
            portal.catalog(session, category).also { refreshed ->
                snapshot.browseCachesByType[playing.catalogType]?.let { cached ->
                    val updated = cached.copy(
                        cachedAtMillis = System.currentTimeMillis(),
                        itemsByCategory = cached.itemsByCategory + (category.id to refreshed)
                    )
                    store.saveBrowseCatalog(updated)
                    _state.update { current -> current.copy(
                        browseCache = updated,
                        browseCachesByType = current.browseCachesByType + (playing.catalogType to updated)
                    ) }
                }
            }
        }.distinctBy { it.id }

        if (queue.isEmpty()) return@task
        _state.update { current ->
            val active = current.nowPlaying ?: return@update current
            if (active.media.id != playing.media.id) return@update current
            val index = queue.indexOfFirst { it.id == active.media.id }
            val wrap = active.catalogType == CatalogType.LIVE_TV && queue.size > 1
            current.copy(nowPlaying = active.copy(
                episodeQueue = queue,
                previousEpisode = queue.getOrNull(index - 1)
                    ?: queue.lastOrNull().takeIf { wrap && index >= 0 },
                nextEpisode = queue.getOrNull(index + 1)
                    ?: queue.firstOrNull().takeIf { wrap && index >= 0 }
            ))
        }
    }

    fun setPlaybackEngine(engine: PlaybackEngine) {
        val canonicalEngine = if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else engine
        // Reflect in-player changes immediately so the label, next cycle step,
        // and Settings selection cannot lag behind the newly created engine.
        _state.update { it.copy(playbackEngine = canonicalEngine) }
        viewModelScope.launch { store.setPlaybackEngine(canonicalEngine) }
    }

    fun setSeriesStartSeason(value: SeriesStartSeason) = viewModelScope.launch {
        store.setSeriesStartSeason(value)
    }

    fun setBrowseLayout(layout: BrowseLayout) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        _state.update { it.copy(browseLayout = layout) }
        store.setBrowseLayout(profileKey, layout)

    }

    fun loadSeriesSeason(season: Int) = task {
        cancelEpisodeDestination()
        episodePageJob?.cancel()
        val series = requireNotNull(_state.value.selectedSeries)
        _state.update { it.copy(items = emptyList(), selectedSeriesSeason = season) }
        loadSeriesEpisodes(series, season)
    }

    fun setUseTmdbEpisodeMetadata(enabled: Boolean) {
        if (_state.value.useTmdbEpisodeMetadata == enabled) return
        _state.update { current ->
            val series = current.selectedSeries
            val cache = series?.let { selected ->
                episodeSeasonCaches.firstOrNull { cached ->
                    cached.profileKey == current.session?.profile?.cacheKey() &&
                        cached.seriesId == selected.id &&
                        (current.selectedSeriesSeason == null || cached.season == current.selectedSeriesSeason)
                }
            }
            current.copy(
                useTmdbEpisodeMetadata = enabled,
                items = cache?.episodesForDisplay(enabled) ?: current.items
            )
        }
    }

    private fun MediaItem.tmdbEpisodeCacheKey(): String =
        "${seasonNumber ?: -1}:${episodeNumber ?: title.episodeOrderFromTitle() ?: -1}:$id"

    private fun EpisodeSeasonCache.rawIptvEpisodes(): List<MediaItem> =
        iptvEpisodes.ifEmpty { episodes }

    private fun EpisodeSeasonCache.episodesForDisplay(useTmdb: Boolean): List<MediaItem> =
        if (useTmdb) episodes else rawIptvEpisodes()

    private fun publishEpisodeCache(cache: EpisodeSeasonCache) {
        val current = _state.value
        if (current.selectedSeries?.id != cache.seriesId || current.session?.profile?.cacheKey() != cache.profileKey ||
            (current.selectedSeriesSeason != null && current.selectedSeriesSeason != cache.season)) return
        _state.update {
            it.copy(
                items = cache.episodesForDisplay(it.useTmdbEpisodeMetadata),
                availableSeriesSeasons = cache.availableSeasons,
                selectedSeriesSeason = cache.season,
                seriesSeasonEpisodeCounts = episodeSeasonCaches
                    .filter { it.profileKey == cache.profileKey && it.seriesId == cache.seriesId && !it.hasMore && it.season != null }
                    .associate { it.season!! to it.episodes.size },
                seriesSeasonEpisodeCountsKey = "${cache.profileKey}|${cache.seriesId}",
                episodePage = cache.page,
                episodeHasMore = cache.hasMore,
                episodeLoadingMore = false
            )
        }
    }

    private suspend fun cacheSeason(cache: EpisodeSeasonCache) {
        episodeSeasonCaches = listOf(cache) + episodeSeasonCaches.filterNot { it.key == cache.key }
        store.saveEpisodeSeasonCache(cache)
        publishEpisodeCache(episodeSeasonCaches.firstOrNull { it.key == cache.key } ?: cache)
    }

    private suspend fun enrichEpisodeCacheFromTmdb(
        series: MediaItem,
        source: EpisodeSeasonCache,
        force: Boolean
    ) {
        if ((!tmdb.configured && !force) || source.rawIptvEpisodes().isEmpty()) return
        val profileKey = source.profileKey
        val mappedId = source.tmdbSeriesId ?: store.tmdbMappings.first().firstOrNull { mapping ->
            mapping.profileKey == profileKey && mapping.type == CatalogType.SERIES && mapping.media.id == series.id
        }?.tmdbId
        val matchedId = mappedId ?: if (force) {
            episodeTmdbSeriesLookup(series)?.id ?: error("No confident TMDB series match was found")
        } else runCatching {
            episodeTmdbSeriesLookup(series)
        }.onFailure { Log.w("NikTvEpisodeMetadata", "TMDB series lookup failed", it) }
            .getOrNull()?.id ?: return

        val raw = source.rawIptvEpisodes()
        val targets = if (force) raw else raw.filter { it.tmdbEpisodeCacheKey() !in source.tmdbEpisodeKeys }
        if (targets.isEmpty() && !force) return
        val existing = source.episodes.associateBy { it.id }
        val availableSeasons = try { episodeTmdbSeasonsLookup(matchedId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (force) throw failure
                Log.w("NikTvEpisodeMetadata", "TMDB season index unavailable; keeping cached details", failure)
                return
            }
        val seasonMapping = targets.map { it.seasonNumber ?: source.season }.distinct()
            .associateWith { tmdbSeasonForProviderSeason(it, availableSeasons) }
        val translatedSeason = seasonMapping.any { (provider, canonical) -> canonical != null && provider != canonical }
        val fetched = try { if (force || translatedSeason) {
            val seasons = seasonMapping.values.filterNotNull().distinct()
            val metadataBySeason = coroutineScope {
                seasons.map { season ->
                    async {
                        episodeTmdbSeasonLookup(matchedId, season)
                    }
                }.awaitAll().flatten()
            }.groupBy { it.seasonNumber }
            withContext(Dispatchers.Default) {
                val indexes = metadataBySeason.mapValues { TmdbEpisodeMetadataIndex(it.value) }
                targets.associateWith { episode ->
                    val season = seasonMapping[episode.seasonNumber ?: source.season]
                    val number = episode.episodeNumber ?: episode.title.episodeOrderFromTitle()
                    indexes[season]?.select(episode.copy(episodeNumber = number))
                }
            }
        } else {
            targets.chunked(4).flatMap { chunk ->
                coroutineScope {
                    chunk.map { episode ->
                        async {
                            val season = episode.seasonNumber ?: source.season
                            val number = episode.episodeNumber ?: episode.title.episodeOrderFromTitle()
                            val metadata = if (season != null && number != null && season in availableSeasons) {
                                runCatching { tmdb.episodeMetadata(matchedId, season, number) }
                                    .onFailure { Log.w("NikTvEpisodeMetadata", "TMDB episode lookup failed for S${season}E${number}", it) }
                                    .getOrNull()
                            } else null
                            episode to metadata
                        }
                    }.awaitAll()
                }
            }.toMap()
        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (force) throw failure
            Log.w("NikTvEpisodeMetadata", "TMDB season details unavailable; keeping cached details", failure)
            return
        }
        val enriched = withContext(Dispatchers.Default) { raw.map { episode ->
            val metadata = fetched[episode]
            when {
                metadata != null -> mergeTmdbEpisodePresentation(episode, existing[episode.id] ?: episode,
                    metadata, series.title, matchedId)
                else -> existing[episode.id] ?: episode
            }
        } }
        if (force && fetched.values.none { it != null })
            error("TMDB returned no matching episode details for this season")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (_state.value.session?.profile?.cacheKey() != profileKey) return
        val checkedKeys = if (force) {
            targets.mapTo(mutableSetOf()) { it.tmdbEpisodeCacheKey() }
        } else {
            source.tmdbEpisodeKeys + targets.map { it.tmdbEpisodeCacheKey() }
        }
        // Metadata enrichment started before pagination must not replace newer provider pages.
        val latest = episodeSeasonCaches.firstOrNull { it.key == source.key }
        if (latest != null && (latest.page != source.page || latest.hasMore != source.hasMore ||
                latest.rawIptvEpisodes().map { it.id } != raw.map { it.id })) return
        val sourceById = source.episodes.associateBy { it.id }
        val latestById = latest?.episodes.orEmpty().associateBy { it.id }
        val reconciled = enriched.map { candidate ->
            latestById[candidate.id]?.takeIf { it != sourceById[candidate.id] } ?: candidate
        }
        cacheSeason(
            source.copy(
                episodes = reconciled,
                iptvEpisodes = raw,
                tmdbEpisodeKeys = checkedKeys,
                tmdbSeriesId = matchedId,
                tmdbCachedAtMillis = System.currentTimeMillis(),
                metadataVersion = EPISODE_METADATA_VERSION
            )
        )
    }

    private suspend fun refreshEpisodeCache(
        series: MediaItem,
        requestedSeason: Int?,
        previous: EpisodeSeasonCache?,
        forceTmdb: Boolean,
        backgroundTmdb: Boolean = false
    ) {
        val session = _state.value.session ?: return
        val destinationRevision = episodeDestinationRequest
        val refreshed = withAutomaticSessionRetry(session) { activeSession ->
            loadInitialEpisodeBatch(activeSession, series, requestedSeason)
        }
        if (destinationRevision != episodeDestinationRequest || _state.value.episodeDestination?.running == true) return
        val latestCache = episodeSeasonCaches.firstOrNull { it.profileKey == session.profile.cacheKey() &&
            it.seriesId == series.id && it.season == refreshed.selectedSeason } ?: previous
        val loaded = refreshed.retainLoadedEpisodes(latestCache, session.profile.cacheKey(), series.id,
            latestCache?.rawIptvEpisodes().orEmpty())
        val raw = loaded.episodes.distinctBy { it.id }
        if (raw.isEmpty() && previous?.rawIptvEpisodes().orEmpty().isNotEmpty()) {
            // A transient or incomplete provider response must not erase a
            // previously playable season from the device cache.
            publishEpisodeCache(previous!!)
            return
        }
        val retained = latestCache?.episodes.orEmpty().associateBy { it.id }
        val base = EpisodeSeasonCache(
            profileKey = session.profile.cacheKey(),
            seriesId = series.id,
            season = loaded.selectedSeason,
            availableSeasons = loaded.availableSeasons,
            episodes = raw.map { episode -> retained[episode.id]?.copy(episodeAddedAtMillis = episode.episodeAddedAtMillis
                ?: retained[episode.id]?.episodeAddedAtMillis) ?: episode },
            page = loaded.page,
            hasMore = loaded.hasMore,
            metadataVersion = EPISODE_METADATA_VERSION,
            iptvEpisodes = raw,
            tmdbEpisodeKeys = if (forceTmdb) emptySet() else previous?.tmdbEpisodeKeys.orEmpty(),
            tmdbSeriesId = previous?.tmdbSeriesId,
            tmdbCachedAtMillis = if (forceTmdb) 0L else previous?.tmdbCachedAtMillis ?: 0L,
            seasonIndexValidated = true
        )
        cacheSeason(base)
        if (backgroundTmdb) {
            launchEpisodeMetadataEnrichment(series, base, forceTmdb)
        } else {
            enrichEpisodeCacheFromTmdb(series, base, forceTmdb)
        }
    }

    /** Optional metadata failure must never cancel a successfully refreshed provider catalogue. */
    internal fun launchEpisodeMetadataEnrichment(series: MediaItem, cache: EpisodeSeasonCache, force: Boolean) =
        viewModelScope.launch {
            val destinationRevision = episodeDestinationRequest
            try {
                withTimeout(90_000L) { enrichEpisodeCacheFromTmdb(series, cache, force) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                reportBackgroundEpisodeMetadataFailure(series, cache, force, destinationRevision,
                    "TMDB episode details timed out. Your IPTV episodes are still available.")
            } catch (failure: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                Log.w("NikTvEpisodeMetadata", "Optional episode metadata refresh failed; keeping provider episodes", failure)
                reportBackgroundEpisodeMetadataFailure(series, cache, force, destinationRevision,
                    "Could not update TMDB episode details. Your IPTV episodes are still available. " +
                        if (failure.message == "No confident TMDB series match was found") "No confident series match was found." else "Please retry.")
            }
        }

    private fun reportBackgroundEpisodeMetadataFailure(series: MediaItem, cache: EpisodeSeasonCache,
        force: Boolean, destinationRevision: Long, message: String) {
        if (!force) return
        _state.update { current ->
            if (episodeDestinationRequest == destinationRevision && current.session?.profile?.cacheKey() == cache.profileKey &&
                current.selectedSeries?.id == series.id && current.selectedSeriesSeason == cache.season)
                current.copy(error = message)
            else current
        }
    }

    private suspend fun loadInitialEpisodeBatch(
        session: PortalSession,
        series: MediaItem,
        requestedSeason: Int?
    ): EpisodeSeasonResult {
        var loaded = portal.episodeSeason(
            session,
            series,
            _state.value.seriesStartSeason,
            requestedSeason
        )
        var combined = loaded.episodes.distinctBy { it.id }

        while (loaded.hasMore && combined.size < INITIAL_EPISODE_BATCH_LIMIT) {
            val seasonId = combined.firstNotNullOfOrNull { it.portalSeasonId }
                ?: break
            val next = portal.episodeSeason(
                session,
                series,
                _state.value.seriesStartSeason,
                loaded.selectedSeason ?: requestedSeason,
                loaded.page + 1,
                seasonId
            )
            val expanded = (combined + next.episodes).distinctBy { it.id }
            val addedEpisodes = expanded.size > combined.size
            combined = expanded
            loaded = EpisodeSeasonResult(
                episodes = combined,
                availableSeasons =
                    (loaded.availableSeasons + next.availableSeasons)
                        .distinct()
                        .sorted(),
                selectedSeason = next.selectedSeason ?: loaded.selectedSeason,
                page = next.page,
                hasMore = next.hasMore && addedEpisodes
            )
            if (!addedEpisodes) break
        }

        return loaded.copy(episodes = combined)
    }

    private suspend fun refreshFavoriteEpisodeCachesIfDue(profileKey: String) {
        if (!favoriteEpisodeCacheRefreshMutex.tryLock()) return
        try {
            val maxAge = _state.value.cacheIntervalMinutes * 60_000L
            val now = System.currentTimeMillis()
            val favoritesById = _state.value.favorites
                .filter { it.kind == FavoriteKind.SERIES }
                .associateBy { it.media.id }
            episodeSeasonCaches
                .filter { cache ->
                    cache.profileKey == profileKey &&
                        cache.seriesId in favoritesById &&
                        (cache.iptvEpisodes.isEmpty() ||
                            now - cache.cachedAtMillis >= maxAge ||
                            now - cache.tmdbCachedAtMillis >= maxAge)
                }
                .forEach { cache ->
                    if (_state.value.session?.profile?.cacheKey() != profileKey) return
                    val series = favoritesById.getValue(cache.seriesId).media
                    runCatching {
                        refreshEpisodeCache(
                            series = series,
                            requestedSeason = cache.season,
                            previous = cache,
                            forceTmdb = now - cache.tmdbCachedAtMillis >= maxAge
                        )
                    }.onFailure {
                        Log.w("NikTvEpisodeMetadata", "Background favorite cache refresh failed for ${series.id}", it)
                    }
                }
        } finally {
            favoriteEpisodeCacheRefreshMutex.unlock()
        }
    }

    fun dismissEpisodeTmdbRefresh() = _state.update { it.copy(episodeTmdbRefresh = null) }

    /** Refresh a single public metadata record; never reload provider episodes or resolve playback. */
    fun refreshEpisodeTmdb(episode: MediaItem) {
        val snapshot = _state.value
        val session = snapshot.session ?: return
        val series = snapshot.selectedSeries ?: return
        if (snapshot.items.none { it.id == episode.id }) return
        val profile = session.profile.cacheKey()
        val season = episode.seasonNumber ?: snapshot.selectedSeriesSeason
        val old = episodeSeasonCaches.firstOrNull { it.profileKey == profile && it.seriesId == series.id && it.season == season }
            ?: EpisodeSeasonCache(profile, series.id, season, snapshot.availableSeriesSeasons, snapshot.items,
                snapshot.episodePage, snapshot.episodeHasMore, iptvEpisodes = snapshot.items)
        val raw = old.rawIptvEpisodes().firstOrNull { it.id == episode.id } ?: return
        val request = ++singleEpisodeMetadataRequest
        singleEpisodeMetadataJob?.cancel()
        val status = EpisodeTmdbRefresh(profile, series.id, episode.id)
        _state.update { it.copy(episodeTmdbRefresh = status) }
        fun valid() = request == singleEpisodeMetadataRequest &&
            _state.value.session?.profile?.cacheKey() == profile && _state.value.selectedSeries?.id == series.id &&
            _state.value.selectedSeriesSeason == snapshot.selectedSeriesSeason
        fun result(message: String) {
            if (valid()) _state.update { it.copy(episodeTmdbRefresh = status.copy(running = false, message = message)) }
        }
        singleEpisodeMetadataJob = viewModelScope.launch {
            try {
                withTimeout(45_000L) {
                    val tmdbId = old.tmdbSeriesId ?: store.tmdbMappings.first().firstOrNull {
                        it.profileKey == profile && it.type == CatalogType.SERIES && it.media.id == series.id
                    }?.tmdbId ?: episodeTmdbSeriesLookup(series)?.id
                    if (tmdbId == null) { result("No confident TMDB series match was found. Existing details were kept."); return@withTimeout }
                    val canonical = tmdbSeasonForProviderSeason(season, episodeTmdbSeasonsLookup(tmdbId))
                    val number = raw.episodeNumber ?: raw.title.episodeOrderFromTitle()
                    if (canonical == null || number == null) {
                        result("This provider episode could not be matched safely to TMDB. Existing details were kept.")
                        return@withTimeout
                    }
                    val metadata = episodeTmdbEpisodeLookup(tmdbId, canonical, number)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!valid()) return@withTimeout
                    if (metadata == null || metadata.seasonNumber != canonical || metadata.episodeNumber != number) {
                        result("TMDB did not return a matching episode. Existing details were kept."); return@withTimeout
                    }
                    // Pagination or a simultaneous season refresh may have finished while waiting.
                    // Merge only this episode into the newest cache, never replace its other pages.
                    val latest = episodeSeasonCaches.firstOrNull { it.key == old.key } ?: old
                    val latestRaw = latest.rawIptvEpisodes().firstOrNull { it.id == raw.id } ?: return@withTimeout
                    val merged = latest.episodes.map { existing ->
                        if (existing.id == raw.id) mergeTmdbEpisodePresentation(latestRaw, existing, metadata, series.title, tmdbId)
                        else existing
                    }
                    cacheSeason(latest.copy(episodes = merged, iptvEpisodes = latest.rawIptvEpisodes(),
                        tmdbSeriesId = tmdbId, tmdbEpisodeKeys = latest.tmdbEpisodeKeys + raw.tmdbEpisodeCacheKey(),
                        metadataVersion = EPISODE_METADATA_VERSION))
                    val before = latest.episodes.first { it.id == raw.id }
                    val after = merged.first { it.id == raw.id }
                    result(episodeTmdbRefreshMessage(latestRaw, before, after, metadata, series.title) +
                        if (!snapshot.useTmdbEpisodeMetadata) " Select Use TMDB episode details to display them." else "")
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                result("TMDB episode refresh timed out. Existing details were kept. Please retry.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                result(if (failure.message?.contains("HTTP 404") == true)
                    "TMDB has no record for this episode yet. Existing details were kept."
                    else "TMDB could not update this episode. Existing details were kept. Please retry.")
            } finally {
                if (request == singleEpisodeMetadataRequest && _state.value.episodeTmdbRefresh?.running == true)
                    _state.update { it.copy(episodeTmdbRefresh = null) }
            }
        }
    }

    fun forceRefreshTmdbEpisodeCache() {
        val snapshot = _state.value
        val series = snapshot.selectedSeries ?: return
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return
        val season = snapshot.selectedSeriesSeason
        val request = ++episodeMetadataRefreshRequest
        episodeMetadataRefreshJob?.cancel()
        episodeMetadataRefreshJob = viewModelScope.launch {
            _state.update { it.copy(tmdbEpisodeCacheRefreshing = true) }
            try {
                withTimeout(90_000L) {
                    val old = episodeSeasonCaches.firstOrNull { it.profileKey == profileKey && it.seriesId == series.id && (season == null || it.season == season) }
                    // A TMDB-only action must not depend on provider availability or
                    // discard already-loaded pages to refresh their presentation.
                    val source = old ?: EpisodeSeasonCache(profileKey, series.id, season,
                        snapshot.availableSeriesSeasons, snapshot.items, snapshot.episodePage,
                        snapshot.episodeHasMore, metadataVersion = EPISODE_METADATA_VERSION,
                        iptvEpisodes = snapshot.items)
                    check(source.rawIptvEpisodes().isNotEmpty()) { "Load episodes before fetching TMDB details" }
                    enrichEpisodeCacheFromTmdb(series, source, force = true)
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                reportEpisodeRefreshFailure(request, profileKey, series.id, season,
                    "Episode refresh timed out. Your saved episodes are still available; please retry.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                Log.w("NikTvEpisodeMetadata", "Episode refresh failed; keeping cached episodes", failure)
                reportEpisodeRefreshFailure(request, profileKey, series.id, season,
                    "Could not refresh episodes: ${failure.message ?: "Provider unavailable"}. Please retry.")
            } finally {
                if (request == episodeMetadataRefreshRequest)
                    _state.update { it.copy(tmdbEpisodeCacheRefreshing = false) }
            }
        }
    }

    private fun reportEpisodeRefreshFailure(request: Long, profileKey: String, seriesId: String, season: Int?, message: String) {
        _state.update { current ->
            if (request == episodeMetadataRefreshRequest && current.session?.profile?.cacheKey() == profileKey &&
                current.selectedSeries?.id == seriesId && current.selectedSeriesSeason == season)
                current.copy(error = message)
            else current
        }
    }

    private suspend fun resolveSeriesContainer(
        session: PortalSession,
        series: MediaItem
    ): MediaItem {
        if (
            session.profile.portalType != PortalType.STALKER ||
            !series.command.isNullOrBlank()
        ) return series

        val profileKey = session.profile.cacheKey()
        val localCandidates = buildList {
            addAll(store.searchCatalog(CatalogType.SERIES, profileKey).first()?.items.orEmpty())
            addAll(localSeriesCandidates(_state.value))
            addAll(
                _state.value.recentlyPlayed
                    .filter { it.kind == FavoriteKind.SERIES }
                    .map { it.media }
            )
        }
        localCandidates.firstOrNull {
            it.id == series.id && !it.command.isNullOrBlank()
        }?.let { return it.withFavoriteSeriesPresentation(series) }

        // TMDB-backed favorites can retain the provider id but lose the Stalker
        // command when their display metadata is refreshed. Resolve that command
        // only on this cache miss; ordinary episode opens remain cache-only.
        val query = series.title.cleanedIptvSeriesTitle()
        val providerItems = runCatching {
            portal.search(
                session = session,
                type = SearchContentType.SERIES,
                query = query,
                page = 1,
                categoryId = "*"
            ).items
        }.onFailure {
            Log.w("NikTvSeries", "Could not restore provider identity for ${series.id}", it)
        }.getOrDefault(emptyList())
        val resolved = providerItems.firstOrNull {
            it.id == series.id && !it.command.isNullOrBlank()
        } ?: return series

        store.mergeSearchMetadata(
            SearchCatalogCache(profileKey, CatalogType.SERIES, System.currentTimeMillis(), providerItems)
        )
        return resolved.withFavoriteSeriesPresentation(series)
    }

    private suspend fun loadSeriesEpisodes(requestedSeries: MediaItem, requestedSeason: Int? = null, forceRefresh: Boolean = false) {
        if (isAdultMedia(requestedSeries, CatalogType.SERIES) && !authorizeParental()) return
        val session = requireNotNull(_state.value.session)
        val series = resolveSeriesContainer(session, requestedSeries)
        if (series != requestedSeries && _state.value.selectedSeries?.id == requestedSeries.id) {
            searchSeriesReturn = searchSeriesReturn?.resolved(session.profile.cacheKey(), requestedSeries.id, series.id)
            _state.update { it.copy(selectedSeries = series) }
        }
        val profileKey = session.profile.cacheKey()
        val maxAge = _state.value.cacheIntervalMinutes * 60_000L
        // Explicit browsing wins; fresh entry resumes this profile's watched season,
        // rather than whichever season was most recently cached in the background.
        val lastEpisode = _state.value.recentlyPlayed.firstOrNull {
            it.profileKey == profileKey && it.kind == FavoriteKind.SERIES &&
                (it.media.id == series.id || it.series?.id == series.id)
        }?.lastPlayed
        val desired = requestedSeason ?: lastEpisode?.let { it.seasonNumber ?: it.title.seasonNumberFromTitle() }
        if (requestedSeason == null && desired != null) _state.update { it.copy(selectedSeriesSeason = desired) }
        val stored = episodeSeasonCaches.firstOrNull { cache ->
            cache.profileKey == profileKey && cache.seriesId == series.id &&
                cache.metadataVersion == EPISODE_METADATA_VERSION &&
                (desired == null || cache.season == desired)
        }
        val cached = stored?.let { withContext(Dispatchers.Default) { repairEpisodePresentationCache(it, series.title) } }
        if (cached != null && !forceRefresh) {
            if (cached != stored) cacheSeason(cached)
            publishEpisodeCache(cached)
            cached.season?.let { store.rememberSeriesSeason(profileKey, series.id, it) }
            val now = System.currentTimeMillis()
            val cacheAge = now - cached.cachedAtMillis
            val incompleteSeasonIndexNeedsRefresh =
                cached.availableSeasons.size <= 1 &&
                    cacheAge >= minOf(maxAge, SEASON_DISCOVERY_RECHECK_MILLIS)
            val initialEpisodeBatchNeedsFill =
                cached.hasMore &&
                    cached.rawIptvEpisodes().size < INITIAL_EPISODE_BATCH_LIMIT
            viewModelScope.launch {
                runCatching {
                    if (
                        cached.iptvEpisodes.isEmpty() ||
                        cacheAge >= maxAge ||
                        incompleteSeasonIndexNeedsRefresh || needsSeriesSeasonIndexRepair(cached) ||
                        initialEpisodeBatchNeedsFill
                    ) {
                        refreshEpisodeCache(series, desired ?: cached.season, cached, forceTmdb = now - cached.tmdbCachedAtMillis >= maxAge)
                    } else {
                        enrichEpisodeCacheFromTmdb(series, cached, force = false)
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w("NikTvEpisodeMetadata", "Background episode cache refresh failed for ${series.id}; keeping cached episodes", error)
                }
            }
            return
        }

        refreshEpisodeCache(
            series,
            desired,
            cached,
            forceTmdb = forceRefresh,
            backgroundTmdb = true
        )
        episodeSeasonCaches.firstOrNull { it.profileKey == profileKey && it.seriesId == series.id && (desired == null || it.season == desired) }
            ?.season?.let { store.rememberSeriesSeason(profileKey, series.id, it) }
    }

    private fun String.hasSpecificEpisodeTitle(): Boolean {
        return episodeSpecificTitleKey().isNotBlank()
    }

    private fun String.episodeSpecificTitleKey(): String {
        val remainder = trim()
            .replaceFirst(
                Regex("^\\s*(?:S\\d+\\s*[:._-]?\\s*E(?:P(?:ISODE)?)?\\s*\\d+|(?:EPISODE|EP|E)\\s*#?\\s*\\d+)\\s*[. :|\\-–—]*\\s*", RegexOption.IGNORE_CASE),
                ""
            )
            .trim(' ', '.', ':', '-', '–', '—', '|')
            .replaceFirst(Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\s*[. :|\\-–—]*\\s*"), "")
            .trim(' ', '.', ':', '-', '–', '—', '|')
        return remainder.episodeMetadataTitleKey()
    }

    private fun String.specialEpisodeKey(): String? {
        val match = Regex(
            "(?i)\\b(bonus|discarded|deleted(?: moments)?|special)[ ._:#-]*(?:ep(?:isode)?)?[ ._:#-]*(\\d+)\\b"
        ).find(this) ?: return null
        return "${match.groupValues[1].lowercase().replace(" ", "-")}:${match.groupValues[2].toIntOrNull() ?: return null}"
    }

    private fun String.episodeMetadataTitleKey(): String = lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    fun cancelEpisodeDestination() {
        episodeDestinationRequest++
        episodeDestinationJob?.cancel()
        episodeDestinationJob = null
        _state.update { it.copy(episodeDestination = null) }
    }

    /** null requests the true first episode of this season; a number requests that exact episode. */
    fun goToSeriesEpisode(number: Int? = null) {
        if (number != null && number < 1) return
        val snapshot = _state.value
        val key = snapshot.episodeBrowseKey() ?: return
        if (snapshot.items.isEmpty() || snapshot.loading) return
        cancelEpisodeDestination()
        episodePageJob?.cancel()
        val request = episodeDestinationRequest
        _state.update { it.copy(episodeDestination = EpisodeDestinationState(key.profile, key.series, key.season,
            request, number, snapshot.items.size, snapshot.episodePage)) }
        val cachedTarget = if (number != null) snapshot.items.firstOrNull { it.destinationEpisodeNumber() == number }
            else if (!snapshot.episodeHasMore) snapshot.items.minWithOrNull(episodeComparator(false)) else null
        if (cachedTarget != null || !snapshot.episodeHasMore) {
            _state.update { state -> state.copy(episodeDestination = state.episodeDestination?.copy(running = false,
                focusId = cachedTarget?.id, message = if (cachedTarget == null) "Episode $number was not found in this season." else null)) }
            return
        }
        fun valid() = _state.value.episodeBrowseKey() == key && episodeDestinationRequest == request
        episodeDestinationJob = viewModelScope.launch {
            try {
                episodePageMutex.withLock {
                    if (!valid()) return@withLock
                    val current = _state.value
                    val initial = EpisodeSeasonResult(current.items, current.availableSeriesSeasons, key.season,
                        current.episodePage, current.episodeHasMore)
                    val (_, target) = loadEpisodeDestination(initial, number,
                        nextPage = { loaded ->
                            check(valid()) { "Episode selection changed" }
                            val activeSession = requireNotNull(_state.value.session)
                            withAutomaticSessionRetry(activeSession) { active ->
                                episodePageLookup(active, requireNotNull(snapshot.selectedSeries), key.season,
                                    loaded.page + 1, loaded.episodes.firstNotNullOfOrNull { it.portalSeasonId })
                            }.also {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                check(valid()) { "Episode selection changed" }
                            }
                        },
                        onPage = { loaded ->
                            persistEpisodePage(key, loaded)
                            if (valid()) _state.update { state -> state.copy(episodeDestination = state.episodeDestination?.copy(
                                loaded = loaded.episodes.size, page = loaded.page)) }
                        })
                    if (valid()) _state.update { state -> state.copy(episodeLoadingMore = false,
                        episodeDestination = state.episodeDestination?.copy(running = false, focusId = target?.id,
                            message = if (target == null) "Episode $number was not found in this season." else null)) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (valid()) _state.update { state -> state.copy(episodeLoadingMore = false,
                    episodeDestination = state.episodeDestination?.copy(running = false,
                        message = error.message ?: "Could not load episodes. Please retry.")) }
            }
        }
    }

    private suspend fun persistEpisodePage(key: EpisodeBrowseKey, loaded: EpisodeSeasonResult): EpisodeSeasonCache {
        val previous = episodeSeasonCaches.firstOrNull { it.profileKey == key.profile && it.seriesId == key.series && it.season == key.season }
        val enriched = previous?.episodes.orEmpty().associateBy { it.id }
        val rawById = previous?.rawIptvEpisodes().orEmpty().associateBy { it.id }
        val raw = loaded.episodes.map { rawById[it.id] ?: it }
        val cache = EpisodeSeasonCache(profileKey = key.profile, seriesId = key.series, season = key.season,
            availableSeasons = mergeSeriesSeasons(previous?.availableSeasons.orEmpty(), loaded.availableSeasons, key.season),
            episodes = loaded.episodes.map { enriched[it.id] ?: it }, page = loaded.page, hasMore = loaded.hasMore,
            metadataVersion = EPISODE_METADATA_VERSION, iptvEpisodes = raw,
            tmdbEpisodeKeys = previous?.tmdbEpisodeKeys.orEmpty(), tmdbSeriesId = previous?.tmdbSeriesId,
            tmdbCachedAtMillis = previous?.tmdbCachedAtMillis ?: 0L, seasonIndexValidated = previous?.seasonIndexValidated == true)
        cacheSeason(cache)
        _state.update { state ->
            val active = state.nowPlaying
            if (state.episodeBrowseKey() != key) state else state.copy(
                nowPlaying = if (active != null && active.profileKey == key.profile && active.catalogType == CatalogType.SERIES &&
                    active.series?.id == key.series) active.withAppendedPlaybackQueue(raw) else active)
        }
        return cache
    }

    fun loadMoreEpisodes() {
        val snapshot = _state.value
        val key = snapshot.episodeBrowseKey() ?: return
        val series = snapshot.selectedSeries ?: return
        if (!snapshot.episodeHasMore || snapshot.episodeLoadingMore || snapshot.episodeDestination?.running == true) return
        _state.update { it.copy(episodeLoadingMore = true) }
        episodePageJob = viewModelScope.launch {
            try {
                val cache = episodePageMutex.withLock {
                    if (_state.value.episodeBrowseKey() != key || _state.value.episodePage != snapshot.episodePage) return@withLock null
                    val next = withAutomaticSessionRetry(requireNotNull(_state.value.session)) { active ->
                        episodePageLookup(active, series, key.season, snapshot.episodePage + 1,
                            snapshot.items.firstNotNullOfOrNull { it.portalSeasonId })
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (_state.value.episodeBrowseKey() != key) return@withLock null
                    check(next.selectedSeason == key.season && next.page > snapshot.episodePage) { "Unexpected episode page. Please retry." }
                    val combined = (snapshot.items + next.episodes).distinctBy { it.id }
                    check(combined.size > snapshot.items.size || !next.hasMore) { "The provider repeated an episode page. Please retry." }
                    persistEpisodePage(key, next.copy(episodes = combined))
                }
                if (cache != null) episodePageEnrichment(series, cache)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_state.value.episodeBrowseKey() == key) _state.update { it.copy(error = error.message ?: "Could not load more episodes") }
            } finally {
                if (_state.value.episodeBrowseKey() == key) _state.update { it.copy(episodeLoadingMore = false) }
            }
        }
    }

    fun openModernTmdbSection(section: TmdbHomeSection) {
        searchSeriesReturn = null
        tmdbAvailabilityJob?.cancel()
        cancelPendingPlayback()
        val snapshot = _state.value
        if (snapshot.session == null) return

        _state.update { current ->
            current.copy(
                modernTmdbSection = section,
                modernTmdbSectionOpenId = current.modernTmdbSectionOpenId + 1,
                modernIptvCategory = null,
                modernSectionOriginHome = current.homeOpen,
                modernTmdbMovies = emptyList(),
                modernTmdbSeries = emptyList(),
                modernTmdbMovieMatches = emptyMap(),
                modernTmdbSeriesMatches = emptyMap(),
                modernTmdbLoading = false,
                modernTmdbPage = 0,
                modernTmdbHasMore = true,
                modernTmdbError = null,
                playbackReturnFocusId = null,
                selectedType =
                    if (section.series) CatalogType.SERIES
                    else CatalogType.MOVIES,
                selectedSeries = null
            )
        }
        loadModernTmdbPage(section, 1, replace = true)
    }

    fun openModernIptvCategory(category: Category) {
        searchSeriesReturn = null
        if (com.nikhil.niktv.data.ParentalControls.enabled(getApplication()) && !com.nikhil.niktv.data.ParentalControls.unlocked(getApplication()) &&
            (category.isAdult || com.nikhil.niktv.data.adultContentLabel(category.title))) {
            viewModelScope.launch { if (authorizeParental()) openModernIptvCategory(category) }
            return
        }
        tmdbAvailabilityJob?.cancel()
        cancelPendingPlayback()
        val snapshot = _state.value

        if (category.type !in setOf(
                CatalogType.LIVE_TV,
                CatalogType.MOVIES,
                CatalogType.SERIES
            )
        ) {
            return
        }

        cancelCategoryFind()
        _state.update { current ->
            current.copy(
                modernTmdbSection = null,
                modernIptvCategory = category,
                modernIptvCategoryOpenId = current.modernIptvCategoryOpenId + 1,
                categoryFindQuery = "",
                categoryFindPage = 0,
                categoryFindTotalPages = null,
                categoryFindMessage = null,
                modernSectionOriginHome = current.homeOpen,
                modernTmdbMovies = emptyList(),
                modernTmdbSeries = emptyList(),
                modernTmdbLoading = false,
                modernTmdbPage = 0,
                modernTmdbHasMore = false,
                modernTmdbError = null,
                playbackReturnFocusId = null,
                selectedType = category.type,
                selectedCategory = category,
                selectedSeries = null,
                seriesOpenedFromModernSection = false
            )
        }
        loadCategory(category)
    }

    fun closeModernSection() {
        tmdbAvailabilityJob?.cancel()
        cancelCategoryFind()
        _state.update { current ->
            current.copy(
                modernTmdbSection = null,
                modernIptvCategory = null,
                modernTmdbMovies = emptyList(),
                modernTmdbSeries = emptyList(),
                modernTmdbLoading = false,
                modernTmdbPage = 0,
                modernTmdbHasMore = false,
                modernTmdbError = null,
                homeOpen = current.modernSectionOriginHome,
                modernSectionOriginHome = false,
                seriesOpenedFromModernSection = false,
                items = if (current.modernSectionOriginHome) {
                    current.items
                } else {
                    emptyList()
                }
            )
        }
    }

    fun loadMoreModernTmdbSection() {
        val snapshot = _state.value
        val section = snapshot.modernTmdbSection ?: return
        if (snapshot.modernTmdbLoading ||
            !snapshot.modernTmdbHasMore
        ) return
        loadModernTmdbPage(
            section,
            snapshot.modernTmdbPage + 1,
            replace = false
        )
    }

    private fun loadModernTmdbPage(
        section: TmdbHomeSection,
        page: Int,
        replace: Boolean
    ) {
        val snapshot = _state.value
        val session = snapshot.session ?: return
        if (!tmdb.configured || page !in 1..MODERN_TMDB_MAX_PAGES) return
        val profileKey = session.profile.cacheKey()

        viewModelScope.launch {
            _state.update { current ->
                if (current.modernTmdbSection != section) current
                else current.copy(
                    modernTmdbLoading = true,
                    modernTmdbError = null
                )
            }

            if (section.series) {
                runCatching {
                    val requested = page * MODERN_TMDB_PAGE_SIZE
                    val rawPageItems = tmdb.homeSeries(section, requested)
                        .drop((page - 1) * MODERN_TMDB_PAGE_SIZE)
                        .take(MODERN_TMDB_PAGE_SIZE)
                    val pageItems = rawPageItems.filter { it.isReleased() }
                    val cachedMatches = tmdbAvailability.read(profileKey, CatalogType.SERIES).entries
                    val savedMappings = store.tmdbMappings.first()
                        .filter {
                            it.profileKey == profileKey &&
                                it.type == CatalogType.SERIES
                        }
                        .associateBy { it.tmdbId }
                    val rows = withContext(Dispatchers.Default) {
                        pageItems.map { series ->
                            TrendingSeries(
                                tmdb = series,
                                iptv = savedMappings[series.id]?.media
                                    ?: cachedMatches[series.id]?.matches?.firstOrNull()
                            )
                        }
                    }
                    rows to (rawPageItems.size == MODERN_TMDB_PAGE_SIZE)
                }.onSuccess { (rows, rawPageWasFull) ->
                    _state.update { current ->
                        if (current.modernTmdbSection != section ||
                            current.session?.profile?.cacheKey() != profileKey
                        ) current
                        else {
                            val merged = if (replace) rows else {
                                (current.modernTmdbSeries + rows)
                                    .distinctBy { it.tmdb.id }
                            }
                            current.copy(
                                modernTmdbSeries = merged,
                                modernTmdbMovies = emptyList(),
                                modernTmdbLoading = false,
                                modernTmdbPage = page,
                                modernTmdbHasMore =
                                    rawPageWasFull &&
                                        page < MODERN_TMDB_MAX_PAGES,
                                modernTmdbError = null
                            )
                        }
                    }
                    updateModernTmdbAvailability(session, CatalogType.SERIES)
                }.onFailure { error ->
                    _state.update { current ->
                        if (current.modernTmdbSection != section) current
                        else current.copy(
                            modernTmdbLoading = false,
                            modernTmdbHasMore = false,
                            modernTmdbError =
                                error.message ?: "Could not load ${section.title}"
                        )
                    }
                }
            } else {
                runCatching {
                    val requested = page * MODERN_TMDB_PAGE_SIZE
                    val rawPageItems = tmdb.homeMovies(section, requested)
                        .drop((page - 1) * MODERN_TMDB_PAGE_SIZE)
                        .take(MODERN_TMDB_PAGE_SIZE)
                    val pageItems = rawPageItems.filter { it.isReleased() }
                    val cachedMatches = tmdbAvailability.read(profileKey, CatalogType.MOVIES).entries
                    val savedMappings = store.tmdbMappings.first()
                        .filter {
                            it.profileKey == profileKey &&
                                it.type == CatalogType.MOVIES
                        }
                        .associateBy { it.tmdbId }
                    val rows = withContext(Dispatchers.Default) {
                        pageItems.map { movie ->
                            TrendingMovie(
                                tmdb = movie,
                                iptv = savedMappings[movie.id]?.media
                                    ?: cachedMatches[movie.id]?.matches?.firstOrNull()
                            )
                        }
                    }
                    rows to (rawPageItems.size == MODERN_TMDB_PAGE_SIZE)
                }.onSuccess { (rows, rawPageWasFull) ->
                    _state.update { current ->
                        if (current.modernTmdbSection != section ||
                            current.session?.profile?.cacheKey() != profileKey
                        ) current
                        else {
                            val merged = if (replace) rows else {
                                (current.modernTmdbMovies + rows)
                                    .distinctBy { it.tmdb.id }
                            }
                            current.copy(
                                modernTmdbMovies = merged,
                                modernTmdbSeries = emptyList(),
                                modernTmdbLoading = false,
                                modernTmdbPage = page,
                                modernTmdbHasMore =
                                    rawPageWasFull &&
                                        page < MODERN_TMDB_MAX_PAGES,
                                modernTmdbError = null
                            )
                        }
                    }
                    updateModernTmdbAvailability(session, CatalogType.MOVIES)
                }.onFailure { error ->
                    _state.update { current ->
                        if (current.modernTmdbSection != section) current
                        else current.copy(
                            modernTmdbLoading = false,
                            modernTmdbHasMore = false,
                            modernTmdbError =
                                error.message ?: "Could not load ${section.title}"
                        )
                    }
                }
            }
        }
    }

    fun refreshTmdbMatches(id: Int, type: CatalogType) {
        val session = _state.value.session ?: return
        updateModernTmdbAvailability(session, type, forceId = id)
    }

    /** One user-requested preview-tile lookup; never starts during browsing or hero rotation. */
    internal fun refreshDiscoveryMatches(card: com.nikhil.niktv.discovery.DiscoveryCard) {
        val session = _state.value.session ?: return
        if (_state.value.discoverySurface == null) return
        updateModernTmdbAvailability(session, if (card.series) CatalogType.SERIES else CatalogType.MOVIES,
            forceId = card.id, discoveryCard = card)
    }

    private fun updateModernTmdbAvailability(
        session: PortalSession,
        type: CatalogType,
        forceId: Int? = null,
        discoveryCard: com.nikhil.niktv.discovery.DiscoveryCard? = null
    ) {
        tmdbAvailabilityJob?.cancel()
        val profileKey = session.profile.cacheKey()
        val section = _state.value.modernTmdbSection
        val trialSurface = _state.value.discoverySurface
        if (discoveryCard == null && section == null || discoveryCard != null && trialSurface == null) return
        fun accepts(current: NikTvState) = current.session?.profile?.cacheKey() == profileKey &&
            if (discoveryCard == null) current.modernTmdbSection == section
            else current.discoverySurface == trialSurface
        if (forceId == null) {
            // Browsing only paints saved badges. Provider checks are user initiated.
            tmdbAvailabilityJob = viewModelScope.launch {
                val saved = tmdbAvailability.read(profileKey, type).entries.mapValues { it.value.matches }
                _state.update { current ->
                    if (!accepts(current)) current
                    else if (type == CatalogType.MOVIES) current.copy(modernTmdbMovieMatches = saved)
                    else current.copy(modernTmdbSeriesMatches = saved)
                }
            }
            return
        }
        tmdbAvailabilityJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = _state.value
                if (!accepts(snapshot) || snapshot.nowPlaying != null) return@launch
                val movieEntries = discoveryCard?.takeUnless { it.series }?.let { listOf(TrendingMovie(it.movie())) }
                    ?: snapshot.modernTmdbMovies
                val seriesEntries = discoveryCard?.takeIf { it.series }?.let { listOf(TrendingSeries(it.show())) }
                    ?: snapshot.modernTmdbSeries
                val interval = snapshot.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L
                val now = System.currentTimeMillis()
                val saved = tmdbAvailability.read(profileKey, type).entries
                val ids = if (type == CatalogType.MOVIES) movieEntries.map { it.tmdb.id }
                    else seriesEntries.map { it.tmdb.id }
                val cachedMatches = saved.filterKeys { it in ids }.mapValues { it.value.matches }
                _state.update { current ->
                    if (!accepts(current)) current
                    else if (type == CatalogType.MOVIES) current.copy(
                        modernTmdbMovieMatches = current.modernTmdbMovieMatches + cachedMatches)
                    else current.copy(modernTmdbSeriesMatches = current.modernTmdbSeriesMatches + cachedMatches)
                }
                val manualId = forceId
                val pending = ids.filter { id ->
                    if (manualId != null) id == manualId else saved[id]?.isFresh(now, interval) != true
                }
                Log.d("NikTvTmdbMatches", "${type.name}: cached=${cachedMatches.size}, refresh=${pending.size}, manual=${manualId != null}")
                if (pending.isEmpty()) {
                    val nextCheck = ids.mapNotNull { saved[it]?.checkedAtMillis }
                        .minOrNull()?.plus(interval)?.minus(now)?.coerceAtLeast(1_000L) ?: interval
                    delay(nextCheck)
                    continue
                }
                // No full-catalogue decoding or matching is needed while all tile caches are fresh.
                val catalog = if (session.profile.portalType == PortalType.XTREAM) {
                    val key = profileKey to type
                    val memory = tmdbAvailabilityCatalogs[key]
                    val persisted = memory ?: store.searchCatalog(type, profileKey).first()
                    val usable = persisted?.takeIf {
                        manualId == null && now >= it.cachedAtMillis && now - it.cachedAtMillis < interval
                    }
                    usable ?: runCatching {
                        portal.invalidateFullCatalog(session.profile, type)
                        val items = withAutomaticSessionRetry(session) { activeSession ->
                            portal.fullCatalog(activeSession, type, emptyList())
                        }
                        SearchCatalogCache(profileKey, type, System.currentTimeMillis(), items).also {
                            store.saveSearchCatalog(it, scheduleMetadataSync = false)
                        }
                    }.getOrElse { error ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (manualId != null) _state.update { if (accepts(it)) it.copy(error = error.message ?: "Could not refresh IPTV matches") else it }
                        return@launch
                    }
                } else null
                if (catalog != null) tmdbAvailabilityCatalogs[profileKey to type] = catalog
                for (id in pending) {
                    val current = _state.value
                    if (!accepts(current) || current.nowPlaying != null) return@launch
                    val title = if (type == CatalogType.MOVIES)
                        movieEntries.first { it.tmdb.id == id }.tmdb.title
                        else seriesEntries.first { it.tmdb.id == id }.tmdb.name
                    val matchingSeries = if (type == CatalogType.SERIES) {
                        val original = seriesEntries.first { it.tmdb.id == id }.tmdb
                        try { tmdb.withSeriesAliases(original) } catch (error: Exception) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            original
                        }
                    } else null
                    val candidates = catalog?.items ?: runCatching {
                        withAutomaticSessionRetry(session) { activeSession ->
                            val initial = portal.search(activeSession, searchTypeForCatalog(type), title, 1, "*", background = manualId == null).items
                            if (type != CatalogType.SERIES) initial else {
                                val series = requireNotNull(matchingSeries)
                                if (rankTmdbSeriesMatches(series, initial).isNotEmpty()) initial else {
                                    // Bounded fallbacks: provider titles may omit articles, and series can live in VOD.
                                    val queries = (series.alternativeNames + listOf(series.originalName) + tmdbSeriesProviderQueries(series))
                                        .distinct().filter { it.isNotBlank() && it != title }
                                        .sortedByDescending { query -> query.any { it in 'a'..'z' || it in 'A'..'Z' } }
                                        .take(2)
                                    val collected = initial.toMutableList()
                                    for (query in queries.ifEmpty { listOf(title) }) {
                                        delay(2_000L)
                                        collected += portal.search(activeSession, searchTypeForCatalog(type),
                                            query, 1, "*", background = manualId == null).items
                                        if (rankTmdbSeriesMatches(series, collected).isNotEmpty()) break
                                    }
                                    if (rankTmdbSeriesMatches(series, collected).isEmpty()) {
                                        delay(2_000L)
                                        collected += portal.search(activeSession, searchTypeForCatalog(CatalogType.MOVIES),
                                            queries.firstOrNull() ?: title, 1, "*", background = manualId == null).items
                                    }
                                    collected

                                }
                            }
                        }
                    }.getOrElse { error ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (manualId != null) _state.update { if (accepts(it)) it.copy(error = error.message ?: "Could not refresh IPTV matches") else it }
                        delay(2_000L)
                        continue
                    }
                    val found = withContext(Dispatchers.Default) {
                        if (type == CatalogType.MOVIES) rankTmdbMovieMatches(
                            movieEntries.first { it.tmdb.id == id }.tmdb, candidates)
                        else rankTmdbSeriesMatches(requireNotNull(matchingSeries), candidates)
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!accepts(_state.value)) return@launch
                    tmdbAvailability.save(profileKey, type, id,
                        TmdbAvailabilityEntry(System.currentTimeMillis(), found, matchingVersion = 4))
                    _state.update { latest ->
                        if (!accepts(latest)) latest
                        else if (discoveryCard != null) latest.copy(discoveryMatchCounts = latest.discoveryMatchCounts +
                            (com.nikhil.niktv.discovery.discoveryAvailabilityKey(profileKey, discoveryCard) to found.size))
                        else if (type == CatalogType.MOVIES) latest.copy(
                            modernTmdbMovieMatches = latest.modernTmdbMovieMatches + (id to found))
                        else latest.copy(modernTmdbSeriesMatches = latest.modernTmdbSeriesMatches + (id to found))
                    }
                    if (found.isEmpty() && manualId != null) {
                        val media = if (type == CatalogType.MOVIES) movieEntries.first { it.tmdb.id == id }.tmdb.asMediaItem()
                            else seriesEntries.first { it.tmdb.id == id }.tmdb.asMediaItem()
                        showTmdbIptvLookupMiss(AlternateSourceRequest(media, type), profileKey)
                    }
                    if (catalog == null) delay(2_000L)
                }
                // One explicit tile refresh, never a page-wide background retry.
                return@launch
            }
        }
    }

    fun loadDashboardDiscovery(forceRefresh: Boolean = false) {
        scheduleHistoryRecommendations()
    }

    fun setTmdbSections(surface: DashboardSurface, sections: List<TmdbHomeSection>) {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return
        val normalized = sections.distinct()
        val configKey = "$profileKey|${surface.name}"

        // Apply locally before persistence so closing/reopening the dialog and
        // the modern dashboard both observe the exact selection immediately.
        tmdbDashboardConfigs =
            tmdbDashboardConfigs + (configKey to normalized)
        _state.update { current ->
            current.copy(
                tmdbSectionsBySurface =
                    current.tmdbSectionsBySurface + (surface to normalized),
                feedRefreshing = false,
                feedRefreshMessage =
                    "Refreshing ${surface.name.lowercase().replace('_', ' ')} feed…"
            )
        }

        viewModelScope.launch {
            runCatching {
                store.setTmdbDashboardSections(
                    profileKey,
                    surface,
                    normalized
                )
            }.onFailure { failure ->
                _state.update { current ->
                    current.copy(
                        error =
                            failure.message
                                ?: "Could not save TMDB dashboard sections"
                    )
                }
            }
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

    fun openTrendingMovie(entry: TrendingMovie) = openTrendingMovie(entry, chooseVersion = false)

    internal fun chooseTmdbMovieVersion(entry: TrendingMovie) = openTrendingMovie(entry, chooseVersion = true)

    private fun openTrendingMovie(entry: TrendingMovie, chooseVersion: Boolean) {
        discoveryMetadataRequests.cancel()
        val requestId = beginPlaybackRequest()
        playbackTask(requestId) {
            tmdbAvailabilityJob?.cancel()
            val session = requireNotNull(_state.value.session)
            val saved = store.tmdbMappings.first().firstOrNull {
                it.profileKey == session.profile.cacheKey() &&
                    it.type == CatalogType.MOVIES &&
                    it.tmdbId == entry.tmdb.id &&
                    it.playbackVerified &&
                    (it.confirmedByUser || (session.profile.portalType != PortalType.XTREAM &&
                        it.media.externalTmdbId == entry.tmdb.id))
            }
            if (saved != null && !chooseVersion) {
                commitTmdbMovieSelection(session, entry.tmdb, saved.media, saved.confirmedByUser, requestId)
                return@playbackTask
            }

            val prefetched = _state.value.modernTmdbMovieMatches[entry.tmdb.id]?.takeIf { !chooseVersion && it.isNotEmpty() }
            val lookup = if (prefetched != null) MovieMatchLookup(prefetched, prefetched,
                if (session.profile.portalType == PortalType.XTREAM) emptyList()
                else listOf(entry.tmdb.title to 1))
                else resolveInitialTmdbMovieCandidates(session, entry)
            val candidates = lookup.matches
            if (candidates.isEmpty()) {
                showTmdbIptvLookupMiss(AlternateSourceRequest(entry.tmdb.asMediaItem(), CatalogType.MOVIES), session.profile.cacheKey(), requestId)
                return@playbackTask
            }
            if (candidates.size > 1 || chooseVersion) {
                _state.update {
                    it.copy(
                        movieMatchSelection = entry.tmdb,
                        movieMatchCandidates = candidates,
                        movieMatchLoadingMore = lookup.pendingRequests.isNotEmpty()
                    )
                }
                if (lookup.pendingRequests.isNotEmpty()) {
                    movieMatchLookupJob = loadMoreTmdbMovieCandidates(session, entry, lookup, requestId)
                }
                return@playbackTask
            }
            commitTmdbMovieSelection(session, entry.tmdb, candidates.single(), confirmedByUser = false, requestId = requestId)
        }

    }

    fun selectTmdbMovieMatch(item: MediaItem) {
        val requestId = beginPlaybackRequest()
        playbackTask(requestId) {
            val session = requireNotNull(_state.value.session)
            val movie = requireNotNull(_state.value.movieMatchSelection)
            commitTmdbMovieSelection(session, movie, item, confirmedByUser = true, requestId = requestId)
        }

    }

    fun closeTmdbMovieMatches() {
        movieMatchLookupJob?.cancel(); movieMatchLookupJob = null
        _state.update { it.copy(movieMatchSelection = null, movieMatchCandidates = emptyList(), movieMatchLoadingMore = false) }
    }

    private suspend fun commitTmdbMovieSelection(
        session: PortalSession,
        movie: TmdbMovie,
        resolved: MediaItem,
        confirmedByUser: Boolean,
        requestId: Long
    ) {
        val returnToMatchSelection = _state.value.movieMatchSelection != null
        // Do not persist or leave the match page until Wio has accepted the
        // selected item's real command and produced a playable URL.
        playInternal(
            item = resolved,
            type = CatalogType.MOVIES,
            series = null,
            episodes = emptyList(),
            directFullscreen = true,
            requestId = requestId
        )
        // A Movies record may have opened its season browser instead of starting a movie.
        if (!latestPlaybackRequest.isCurrent(requestId) || _state.value.nowPlaying?.let {
                it.catalogType == CatalogType.MOVIES && it.media.id == resolved.id
            } != true) return
        // Persist only authoritative provider IDs or an explicit user selection;
        // automatically selected fuzzy candidates do not establish identity.
        if (resolved.externalTmdbId == movie.id || confirmedByUser) {
            mediaIdentities.save(com.nikhil.niktv.data.MediaIdentity(session.profile.cacheKey(),
                CatalogType.MOVIES, resolved.id, resolved.title, movie = movie,
                method = if (confirmedByUser) "confirmed" else "provider"))
            store.saveTmdbMapping(
                TmdbIptvMapping(
                    profileKey = session.profile.cacheKey(),
                    type = CatalogType.MOVIES,
                    tmdbId = movie.id,
                    media = resolved,
                    confirmedByUser = confirmedByUser,
                    playbackVerified = true
                )
            )
        }
        _state.update { current ->
            current.copy(
                playbackReturnFocusId =
                    if (returnToMatchSelection) {
                        resolved.id
                    } else {
                        movie.asMediaItem().id
                    },
                trendingMovies = current.trendingMovies.resolveMovie(movie.id, resolved),
                thrillerMovies = current.thrillerMovies.resolveMovie(movie.id, resolved),
                modernTmdbMovies = current.modernTmdbMovies.resolveMovie(movie.id, resolved),
                tmdbHomeMovieRows = current.tmdbHomeMovieRows.mapValues { (_, row) -> row.resolveMovie(movie.id, resolved) },
                movieMatchSelection = if (returnToMatchSelection) current.movieMatchSelection else null,
                movieMatchCandidates = if (returnToMatchSelection) current.movieMatchCandidates else emptyList(),
                movieMatchLoadingMore = if (returnToMatchSelection) current.movieMatchLoadingMore else false
            )
        }
    }

    fun openTrendingSeries(entry: TrendingSeries) = task {
        discoveryMetadataRequests.cancel()
        tmdbAvailabilityJob?.cancel()
        val session = requireNotNull(_state.value.session)
        val saved = store.tmdbMappings.first().firstOrNull {
            it.profileKey == session.profile.cacheKey() &&
                it.type == CatalogType.SERIES &&
                it.tmdbId == entry.tmdb.id &&
                (it.confirmedByUser || it.media.externalTmdbId == entry.tmdb.id)
        }
        if (saved != null) {
            commitTmdbSeriesSelection(session, entry.tmdb, saved.media, saved.confirmedByUser)
            return@task
        }

        val candidates = _state.value.modernTmdbSeriesMatches[entry.tmdb.id]?.takeIf { it.isNotEmpty() }
            ?: resolveInitialTmdbSeriesCandidates(session, entry)
        if (candidates.isEmpty()) {
            resolveTmdbSeriesMovieFallback(session, entry.tmdb)?.let { movie ->
                // Wio exposes some series containers in its general VOD/movie
                // listing. Resolve the matching VOD id through the series-detail
                // endpoint so seasons and episodes remain available.
                commitTmdbSeriesSelection(
                    session = session,
                    series = entry.tmdb,
                    resolved = movie,
                    confirmedByUser = true
                )
                return@task
            }
            showTmdbIptvLookupMiss(AlternateSourceRequest(entry.tmdb.asMediaItem(), CatalogType.SERIES), session.profile.cacheKey())
            return@task
        }
        if (candidates.size > 1) {
            _state.update {
                it.copy(
                    seriesMatchSelection = entry.tmdb,
                    seriesMatchCandidates = candidates
                )
            }
            return@task
        }
        commitTmdbSeriesSelection(session, entry.tmdb, candidates.single(), confirmedByUser = false)
    }

    fun selectTmdbSeriesMatch(item: MediaItem) = task {
        pendingDiscoveryEpisode?.takeIf { it.card.id == _state.value.seriesMatchSelection?.id &&
            latestPlaybackRequest.isCurrent(it.request) }?.let { intent ->
            playDiscoveryEpisode(intent, item, confirmedByUser = true)
            return@task
        }
        if (isAdultMedia(item, CatalogType.SERIES) && !authorizeParental()) return@task
        val session = requireNotNull(_state.value.session)
        val series = requireNotNull(_state.value.seriesMatchSelection)
        commitTmdbSeriesSelection(session, series, item, confirmedByUser = true)
    }

    fun closeTmdbSeriesMatches() = _state.update {
        pendingDiscoveryEpisode = null
        it.copy(seriesMatchSelection = null, seriesMatchCandidates = emptyList())
    }

    private suspend fun commitTmdbSeriesSelection(
        session: PortalSession,
        series: TmdbSeries,
        resolved: MediaItem,
        confirmedByUser: Boolean
    ) {
        searchSeriesReturn = null
        val openedFromModernSection =
            _state.value.modernTmdbSection?.series == true
        val displaySeries = resolved.withPreferredSeriesPresentation(series)

        _state.update { current ->
            current.copy(
                trendingSeries = current.trendingSeries.map { item ->
                    if (item.tmdb.id == series.id) {
                        item.copy(iptv = displaySeries)
                    } else item
                },
                modernTmdbSeries = current.modernTmdbSeries.map { item ->
                    if (item.tmdb.id == series.id) {
                        item.copy(iptv = displaySeries)
                    } else item
                },
                tmdbHomeSeriesRows = current.tmdbHomeSeriesRows.mapValues { (_, row) ->
                    row.map { item ->
                        if (item.tmdb.id == series.id) {
                            item.copy(iptv = displaySeries)
                        } else item
                    }
                },
                homeOpen = false,
                favoritesOpen = false,
                settingsOpen = false,
                searchOpen = false,
                selectedType = CatalogType.SERIES,
                selectedSeries = displaySeries,
                playbackReturnFocusId = null,
                seriesOpenedFromHome = !openedFromModernSection,
                seriesOpenedFromModernSection = openedFromModernSection,
                seriesOpenedFromFavorites = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null,
                seriesMatchSelection = null,
                seriesMatchCandidates = emptyList()
            )
        }

        loadSeriesEpisodes(displaySeries)
        if (confirmedByUser || resolved.externalTmdbId == series.id) {
            mediaIdentities.save(com.nikhil.niktv.data.MediaIdentity(session.profile.cacheKey(),
                CatalogType.SERIES, resolved.id, resolved.title, series = series,
                method = if (confirmedByUser) "confirmed" else "provider"))
        }
        // Persist only after the provider accepts this id as a series container
        // and returns its episode structure.
        store.saveTmdbMapping(
            TmdbIptvMapping(
                session.profile.cacheKey(),
                CatalogType.SERIES,
                series.id,
                displaySeries,
                confirmedByUser = confirmedByUser
            )
        )
    }

    private fun returnToHomeAfterTrendingSeriesFailure() {
        _state.update { current ->
            val modernReturn =
                current.seriesOpenedFromModernSection &&
                    current.modernTmdbSection != null
            current.copy(
                selectedSeries = null,
                seriesOpenedFromHome = false,
                seriesOpenedFromModernSection = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null,
                homeOpen =
                    if (modernReturn) current.modernSectionOriginHome else true
            )
        }
    }





    private suspend fun enrichCatalogArtwork(session: PortalSession, type: CatalogType) = coroutineScope {
        if (!tmdb.configured) return@coroutineScope
        val profileKey = session.profile.cacheKey()
        val snapshot = _state.value
        val cached = snapshot.browseCachesByType[type] ?: return@coroutineScope
        val targets = ((if (snapshot.selectedType == type) snapshot.items else emptyList()) +
            cached.itemsByCategory.values.flatten()).distinctBy { it.id }
            .filterNot { it.logo.orEmpty().startsWith("https://image.tmdb.org/") }.take(30)
        targets.map { media -> async {
            artworkEnrichmentSlots.acquire()
            try {
                val identity = try { mediaIdentities.resolve(profileKey, type, media) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val logo = identity?.artwork ?: return@async
                if (_state.value.session?.profile?.cacheKey() != profileKey) return@async
                // Publish each completed title immediately; never wait for the slowest lookup.
                _state.update { current ->
                    if (current.session?.profile?.cacheKey() != profileKey) current else {
                        val cache = current.browseCachesByType[type] ?: return@update current
                        fun enrich(items: List<MediaItem>) = items.map {
                            if (it.id == media.id && it.title == media.title) it.copy(logo = logo) else it
                        }
                        val updated = cache.copy(itemsByCategory = cache.itemsByCategory.mapValues { enrich(it.value) })
                        current.copy(items = if (current.selectedType == type) enrich(current.items) else current.items,
                            browseCachesByType = current.browseCachesByType + (type to updated),
                            browseCache = if (current.browseCache?.type == type) updated else current.browseCache)
                    }
                }
            } finally { artworkEnrichmentSlots.release() }
        } }.awaitAll()
        if (_state.value.session?.profile?.cacheKey() == profileKey) {
            _state.value.browseCachesByType[type]?.let { store.saveBrowseCatalog(it) }
            prefetchArtwork(getApplication(), _state.value.items, limit = 10)
        }
    }

    /** Enriches one deliberately focused IPTV title without scanning the surrounding catalog. */
    suspend fun enrichFocusedCatalogMetadata(item: MediaItem, type: CatalogType) {
        if (type !in setOf(CatalogType.MOVIES, CatalogType.SERIES) || !tmdb.configured) return
        val tmdbItemId = item.id.substringAfter(':', "").toIntOrNull()
        if (item.id.startsWith("tmdb-") && tmdbItemId != null) {
            if (item.cast.isNotEmpty()) return
            val cast = runCatching { tmdb.castFor(type, tmdbItemId) }.getOrDefault(emptyList())
            if (cast.isEmpty()) return
            _state.update { current ->
                if (type == CatalogType.MOVIES) {
                    current.copy(
                        modernTmdbMovies = current.modernTmdbMovies.map { entry ->
                            if (entry.tmdb.id == tmdbItemId) entry.copy(tmdb = entry.tmdb.copy(cast = cast))
                            else entry
                        }
                    )
                } else {
                    current.copy(
                        modernTmdbSeries = current.modernTmdbSeries.map { entry ->
                            if (entry.tmdb.id == tmdbItemId) entry.copy(tmdb = entry.tmdb.copy(cast = cast))
                            else entry
                        }
                    )
                }
            }
            return
        }
        if (!item.description.isNullOrBlank() && item.externalTmdbId != null && item.cast.isNotEmpty()) return

        val snapshot = _state.value
        val session = snapshot.session ?: return
        val profileKey = session.profile.cacheKey()
        val category = snapshot.modernIptvCategory?.takeIf { it.type == type } ?: return
        val metadata = runCatching {
            when (type) {
                CatalogType.MOVIES -> mediaIdentities.resolve(profileKey, type, item)?.movie?.asMediaItem()
                CatalogType.SERIES -> mediaIdentities.resolve(profileKey, type, item)?.series?.asMediaItem()
                else -> null
            }
        }.getOrNull() ?: return
        // Poster/description are usable now; credits must not hold up the visible card.
        publishFocusedMetadata(profileKey, type, category.id, item.id, metadata, emptyList())
        val matchedTmdbId = metadata.id.substringAfter(':').toIntOrNull()
        val cast = matchedTmdbId?.let { id ->
            try { tmdb.castFor(type, id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }.orEmpty()
        if (cast.isNotEmpty()) publishFocusedMetadata(profileKey, type, category.id, item.id, metadata, cast)
    }

    private suspend fun publishFocusedMetadata(profile: String, type: CatalogType, categoryId: String,
        itemId: String, metadata: MediaItem, cast: List<String>) {
        _state.update { current ->
            if (current.session?.profile?.cacheKey() != profile) current else {
                val cache = current.browseCachesByType[type] ?: return@update current
                fun enrich(items: List<MediaItem>) = items.map { existing ->
                    if (existing.id != itemId) existing else existing.copy(
                        logo = metadata.logo ?: existing.logo,
                        description = metadata.description ?: existing.description,
                        cast = cast.ifEmpty { existing.cast })
                }
                val updated = cache.copy(itemsByCategory = cache.itemsByCategory +
                    (categoryId to enrich(cache.itemsByCategory[categoryId].orEmpty())))
                current.copy(items = if (current.modernIptvCategory?.id == categoryId)
                    enrich(current.items) else current.items,
                    browseCache = if (current.browseCache?.type == type) updated else current.browseCache,
                    browseCachesByType = current.browseCachesByType + (type to updated))
            }
        }
        if (_state.value.session?.profile?.cacheKey() == profile)
            _state.value.browseCachesByType[type]?.let { store.saveBrowseCatalog(it) }
    }

    private suspend fun resolveInitialTmdbMovieCandidates(
        session: PortalSession,
        entry: TrendingMovie
    ): MovieMatchLookup {
        if (session.profile.portalType == PortalType.XTREAM) {
            val matches = resolveTmdbFromXtreamCatalog(session, CatalogType.MOVIES) { candidates ->
                rankTmdbMovieMatches(entry.tmdb, candidates)
            }
            return MovieMatchLookup(matches, matches, emptyList())
        }

        val queries = listOf(entry.tmdb.title, entry.tmdb.originalTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        val candidates = localMovieCandidates(_state.value).toMutableList()
        val pending = mutableListOf<Pair<String, Int>>()
        queries.forEachIndexed { index, query ->
            val page = portal.search(
                session = session,
                type = SearchContentType.MOVIES,
                query = query,
                page = 1,
                categoryId = "*"
            )
            candidates += page.items
            if (page.hasMore) pending += query to 2
            if (rankTmdbMovieMatches(entry.tmdb, candidates).size >= MOVIE_MATCH_PREVIEW_THRESHOLD) {
                pending += queries.drop(index + 1).map { it to 1 }
                return MovieMatchLookup(
                    rankTmdbMovieMatches(entry.tmdb, candidates),
                    candidates.distinctBy { it.id },
                    pending.distinct()
                )
            }
        }
        return MovieMatchLookup(
            rankTmdbMovieMatches(entry.tmdb, candidates.distinctBy { it.id }),
            candidates.distinctBy { it.id },
            pending.distinct()
        )
    }

    private fun loadMoreTmdbMovieCandidates(
        session: PortalSession,
        entry: TrendingMovie,
        initial: MovieMatchLookup,
        requestId: Long
    ) = viewModelScope.launch {
        val candidates = initial.rawCandidates.toMutableList()
        val pages = TmdbMovieMatchPaging(initial.pendingRequests)
        val profile = session.profile.cacheKey()
        fun accepts(current: NikTvState) = latestPlaybackRequest.isCurrent(requestId) &&
            current.movieMatchSelection?.id == entry.tmdb.id && current.session?.profile?.cacheKey() == profile
        try {
            while (accepts(_state.value)) {
                val (query, pageNumber) = pages.next() ?: break
                val page = withAutomaticSessionRetry(session) { activeSession ->
                    portal.search(activeSession, SearchContentType.MOVIES, query, pageNumber, "*")
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!accepts(_state.value)) return@launch
                candidates += page.items
                pages.completed(query, pageNumber, page.items.map { it.id }, page.hasMore)
                val ranked = withContext(Dispatchers.Default) {
                    rankTmdbMovieMatches(entry.tmdb, candidates.distinctBy { it.id })
                }
                _state.update { current -> if (!accepts(current)) current else current.copy(movieMatchCandidates = ranked) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { current -> if (!accepts(current)) current
                else current.copy(error = error.message ?: "Could not load more IPTV matches") }
        } finally {
            _state.update { current -> if (!accepts(current)) current else current.copy(movieMatchLoadingMore = false) }
        }
    }

    private data class MovieMatchLookup(
        val matches: List<MediaItem>,
        val rawCandidates: List<MediaItem>,
        val pendingRequests: List<Pair<String, Int>>
    )

    private suspend fun resolveInitialTmdbSeriesCandidates(
        session: PortalSession,
        entry: TrendingSeries
    ): List<MediaItem> {
        val matchingSeries = try { tmdb.withSeriesAliases(entry.tmdb) } catch (error: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            entry.tmdb
        }
        val persisted = store.searchCatalog(CatalogType.SERIES, session.profile.cacheKey()).first()?.items.orEmpty()
        val localMatches = rankTmdbSeriesMatches(matchingSeries,
            (persisted + localSeriesCandidates(_state.value)).distinctBy { it.id })
        if (localMatches.isNotEmpty()) return localMatches
        if (session.profile.portalType == PortalType.XTREAM) {
            val profileKey = session.profile.cacheKey()
            val catalog = portal.fullCatalog(session, CatalogType.SERIES, emptyList()).also { items ->
                    if (items.isNotEmpty()) {
                        store.saveSearchCatalog(
                            SearchCatalogCache(profileKey, CatalogType.SERIES, System.currentTimeMillis(), items)
                        )
                    }
                }
            return rankTmdbSeriesMatches(matchingSeries, catalog)
        }

        val queries = tmdbSeriesProviderQueries(matchingSeries)

        val candidates = localSeriesCandidates(_state.value).toMutableList()
        for (query in queries) {
            val page = portal.search(
                session = session,
                type = SearchContentType.SERIES,
                query = query,
                page = 1,
                categoryId = "*"
            )
            candidates += page.items
            rankTmdbSeriesMatches(matchingSeries, candidates.distinctBy { it.id })
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        for (category in prioritizedProviderCategories(session, CatalogType.SERIES)) {
            for (query in queries.asReversed()) {
                val page = portal.search(
                    session = session,
                    type = SearchContentType.SERIES,
                    query = query,
                    page = 1,
                    categoryId = category.id
                )
                candidates += page.items
                rankTmdbSeriesMatches(matchingSeries, candidates.distinctBy { it.id })
                    .takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return emptyList()
    }

    private suspend fun resolveTmdbSeriesMovieFallback(
        session: PortalSession,
        series: TmdbSeries
    ): MediaItem? {
        val profileKey = session.profile.cacheKey()
        val local = (
            store.searchCatalog(CatalogType.MOVIES, profileKey).first()?.items.orEmpty() +
                localMovieCandidates(_state.value)
            ).distinctBy { it.id }
        rankTmdbSeriesMatches(series, local).firstOrNull()?.let { return it }

        val queries = tmdbSeriesProviderQueries(series)
        val providerCandidates = mutableListOf<MediaItem>()
        for (query in queries) {
            val page = portal.search(
                session = session,
                type = SearchContentType.MOVIES,
                query = query,
                page = 1,
                categoryId = "*"
            )
            providerCandidates += page.items
            rankTmdbSeriesMatches(series, providerCandidates.distinctBy { it.id })
                .firstOrNull()
                ?.let { return it }
        }

        // Several Stalker portals return an empty wildcard VOD search even
        // though the item is present in a concrete category. Reuse local
        // category metadata when possible and put likely web-series buckets
        // first so the fallback normally completes with one additional call.
        for (category in prioritizedProviderCategories(session, CatalogType.MOVIES)) {
            for (query in queries.asReversed()) {
                val page = portal.search(
                    session = session,
                    type = SearchContentType.MOVIES,
                    query = query,
                    page = 1,
                    categoryId = category.id
                )
                providerCandidates += page.items
                rankTmdbSeriesMatches(series, providerCandidates.distinctBy { it.id })
                    .firstOrNull()
                    ?.let { return it }
            }
        }
        return null
    }

    private fun tmdbSeriesProviderQueries(series: TmdbSeries): List<String> {
        val titles = (listOf(series.name, series.originalName) + series.alternativeNames)
            .map(String::trim)
            .filter(String::isNotBlank)
            .flatMap { title ->
                listOf(
                    title,
                    title.replace(Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE), ""),
                    title.replace(Regex("['’]s\\b", RegexOption.IGNORE_CASE), ""),
                    title.replace(Regex("['’]"), "")
                )
            }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .distinct()
        val distinctiveWord = series.name
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .lastOrNull { it.length >= 5 }
        return (titles + listOfNotNull(distinctiveWord)).distinct()
    }

    private suspend fun prioritizedProviderCategories(
        session: PortalSession,
        type: CatalogType
    ): List<Category> {
        val profileKey = session.profile.cacheKey()
        val local = (
            _state.value.rawCategoriesByType[type].orEmpty() +
                _state.value.browseCachesByType[type]?.categories.orEmpty() +
                store.browseCatalog(type, profileKey).first()?.categories.orEmpty()
            )
            .filter { it.id != "*" }
            .distinctBy { it.id }
        return local.ifEmpty {
            portal.categories(session, type).filter { it.id != "*" }
        }.sortedByDescending { category ->
            val title = category.title.lowercase()
            (if ("web" in title && "series" in title) 100 else 0) +
                (if ("series" in title) 50 else 0) +
                (if ("hindi" in title) 25 else 0) +
                (if ("india" in title) 10 else 0)
        }
    }

    private suspend fun resolveTmdbFromXtreamCatalog(
        session: PortalSession,
        type: CatalogType,
        titleMatcher: (List<MediaItem>) -> List<MediaItem>
    ): List<MediaItem> {
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

        // Provider identity can identify the movie, but cannot choose its language
        // or quality variant. Keep every ranked occurrence for the existing picker.
        return withContext(Dispatchers.Default) { titleMatcher(candidates) }
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

    fun openMediaInContext(item: MediaItem, queue: List<MediaItem>, filter: PlaybackQueueFilter) {
        if (_state.value.selectedType == CatalogType.SERIES && _state.value.selectedSeries == null) {
            openMedia(item); return
        }
        val snapshot = _state.value
        val requestId = beginPlaybackRequest()
        playbackNavigationIndex = null
        navigationCursor.select(requestId, queue.map { it.id }, item.id)
        playbackTask(requestId) {
            playInternal(item, snapshot.selectedType, snapshot.selectedSeries, queue,
                directFullscreen = snapshot.selectedType == CatalogType.MOVIES,
                requestId = requestId, queueFilter = filter)
        }
    }

    /** Catalog entry only; player queue navigation continues to call openMedia directly. */
    fun openCatalogDetails(item: MediaItem, queue: List<MediaItem> = listOf(item), filter: PlaybackQueueFilter? = null) {
        if (_state.value.selectedType != CatalogType.MOVIES || _state.value.selectedSeries != null) {
            if (filter != null) openMediaInContext(item, queue, filter) else openMedia(item)
            return
        }
        openProviderMovieDetails(item, queue, filter)
    }
    suspend fun loadDiscoveryCategoryPage(initial: PortalSession, category: Category, page: Int, background: Boolean): PortalCatalogPage {
        val profile = initial.profile.cacheKey()
        if (_state.value.session?.profile?.cacheKey() != profile) throw kotlinx.coroutines.CancellationException("Profile changed")
        return discoveryMetadataRequests.run {
            val result = withAutomaticSessionRetry(initial) { active ->
                if (page == 1) portal.invalidateCatalog(active.profile, category)
                portal.catalogPage(active, category, page, includeEpg = false, background = background)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_state.value.session?.profile?.cacheKey() != profile) throw kotlinx.coroutines.CancellationException("Profile changed")
            result
        }
    }

    fun playDiscoveryProvider(type: CatalogType, item: MediaItem, queue: List<MediaItem>) {
        if (type == CatalogType.SERIES) { openSeriesFromHome(item); return }
        if (type != CatalogType.MOVIES) return
        val profile = _state.value.session?.profile?.cacheKey() ?: return
        if (gateAdultAction(item, type) {
                if (_state.value.session?.profile?.cacheKey() == profile) playDiscoveryProvider(type, item, queue)
            }) return
        openDiscoveryProviderDetails(type, item, queue)
        val selected = _state.value.providerMovieDetails
        if (selected?.profile == profile && selected.item.id == item.id) playProviderMovieDetails()
    }

    fun openDiscoveryProviderDetails(type: CatalogType, item: MediaItem, queue: List<MediaItem>) {
        discoveryMetadataRequests.cancel()
        if (type == CatalogType.SERIES) openSeriesFromHome(item)
        else if (type == CatalogType.MOVIES) openProviderMovieDetails(item, queue,
            PlaybackQueueFilter(fixedIds = queue.mapTo(mutableSetOf()) { it.id }, contextId = "discovery-selected:${type.name}"))
    }

    fun openProviderMovieDetails(item: MediaItem, queue: List<MediaItem> = listOf(item), filter: PlaybackQueueFilter? = null) {
        val profile = _state.value.session?.profile?.cacheKey() ?: return
        if (gateAdultAction(item, CatalogType.MOVIES) {
                if (_state.value.session?.profile?.cacheKey() == profile) openProviderMovieDetails(item, queue, filter)
            }) return
        movieStructureJob?.cancel()
        val selected = com.nikhil.niktv.discovery.ProviderMovieDetails(profile, item, queue, filter)
        _state.update { it.copy(providerMovieDetails = selected) }
        val session = _state.value.session ?: return
        if (session.profile.portalType != PortalType.STALKER || item.id.startsWith("tmdb-")) return
        movieStructureJob = viewModelScope.launch {
            try {
                val episodic = item.vodSeriesContainer || withAutomaticSessionRetry(session) { movieStructureLookup(it, item) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (episodic && _state.value.providerMovieDetails === selected &&
                    _state.value.session?.profile?.cacheKey() == profile && _state.value.nowPlaying == null) {
                    openVodSeriesContainer(item)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Probe failures leave ordinary Play available; episode-loading errors remain actionable.
                if (_state.value.selectedSeries?.id == item.id && _state.value.session?.profile?.cacheKey() == profile)
                    _state.update { it.copy(error = failure.message ?: "Could not load episodes") }
            }
        }
    }
    fun closeProviderMovieDetails() {
        movieStructureJob?.cancel()
        _state.update { it.copy(providerMovieDetails = null) }
    }

    private suspend fun openVodSeriesContainer(item: MediaItem) {
        val series = item.copy(vodSeriesContainer = true)
        searchSeriesReturn = SearchSeriesReturn.capture(_state.value, series, movieContainer = true)
        _state.update { it.copy(providerMovieDetails = null, selectedType = CatalogType.SERIES, error = null,
            movieMatchSelection = null, movieMatchCandidates = emptyList(), movieMatchLoadingMore = false,
            nowPlaying = null, playbackQueueScope = null, playbackQueueHasMore = false, playbackQueueLoadingMore = false,
            selectedSeries = series, searchOpen = false, homeOpen = false, favoritesOpen = false,
            playbackReturnFocusId = null, items = emptyList(), availableSeriesSeasons = emptyList(),
            selectedSeriesSeason = null, seriesOpenedFromHome = false, seriesOpenedFromFavorites = false,
            seriesOpenedFromModernSection = false) }
        _state.update { it.copy(loading = true) }
        try { loadSeriesEpisodes(series) }
        finally { if (_state.value.selectedSeries?.id == series.id) _state.update { it.copy(loading = false) } }
    }
    fun playProviderMovieDetails() {
        val selected = _state.value.providerMovieDetails ?: return
        if (_state.value.session?.profile?.cacheKey() != selected.profile) return
        if (gateAdultAction(selected.item, CatalogType.MOVIES) {
                if (_state.value.providerMovieDetails === selected) playProviderMovieDetails()
            }) return
        selected.item.id.removePrefix("tmdb-movie:").toIntOrNull()?.takeIf { selected.item.id.startsWith("tmdb-movie:") }?.let { id ->
            val item = selected.item
            openTrendingMovie(TrendingMovie(com.nikhil.niktv.data.TmdbMovie(id, item.title, item.title,
                item.description, item.logo, null, null, null)))
            return
        }
        val request = beginPlaybackRequest()
        playbackNavigationIndex = null
        navigationCursor.select(request, selected.queue.map { it.id }, selected.item.id)
        playbackTask(request) {
            if (_state.value.session?.profile?.cacheKey() != selected.profile) return@playbackTask
            playInternal(selected.item, CatalogType.MOVIES, null, selected.queue,
                directFullscreen = true, requestId = request, queueFilter = selected.filter)
        }
    }

    fun openMedia(item: MediaItem) {
        if (gateAdultAction(item, _state.value.selectedType) { openMedia(item) }) return
        navigationCursor.cancel()
        playbackNavigationIndex = null
        openMediaInternal(item)
    }

    fun openMediaFromQueue(item: MediaItem, position: Int) {
        _state.value.nowPlaying?.takeIf { it.alternateSource != null }?.let { playing ->
            val source = requireNotNull(playing.alternateSource)
            retryAlternatePlayback(0, playing, source.target.copy(season = item.seasonNumber ?: source.target.season,
                episode = item.episodeNumber ?: source.target.episode))
            return
        }
        playbackNavigationIndex = position
        openMediaInternal(item)
    }

    private fun openMediaInternal(item: MediaItem) {
        val snapshot = _state.value
        /*
         * OPEN_MEDIA_PREFERS_ACTIVE_QUEUE_V16
         *
         * If the user selects an item from the currently displayed player
         * queue, preserve that exact expanded queue rather than rebuilding it
         * from an unrelated browse surface.
         */
        val activePlayback = snapshot.nowPlaying
        val activeQueue =
            activePlayback
                ?.episodeQueue
                .orEmpty()
                .distinctBy { it.id }

        if (
            activePlayback != null &&
            activeQueue.any { it.id == item.id } &&
            activePlayback.catalogType ==
                snapshot.nowPlaying?.catalogType
        ) {
            /*
             * PLAYER_QUEUE_REFRESH_RECONCILIATION_V17
             *
             * Dashboard/catalog refresh replaces cached MediaItems, including
             * provider playback commands, while the open player's queue remains
             * an intentional ordering snapshot. Reconcile that snapshot by ID
             * before create_link so selecting a tile cannot reuse an expired
             * command. This is entirely local and does not add a catalog request.
             */
            val refreshedItemsById = snapshot
                .browseCachesByType[activePlayback.catalogType]
                ?.itemsByCategory
                ?.values
                ?.asSequence()
                ?.flatten()
                ?.associateBy { it.id }
                .orEmpty()
            val refreshedQueue = activeQueue.map { queued ->
                refreshedItemsById[queued.id] ?: queued
            }
            val refreshedSelection = refreshedItemsById[item.id] ?: item
            val requestId = beginPlaybackRequest()
            navigationCursor.select(requestId, playbackNavigationQueue(activePlayback).map { it.id }, item.id,
                playbackNavigationIndex)
            playbackTask(requestId) {
                playInternal(
                    item = refreshedSelection,
                    type = activePlayback.catalogType,
                    series = activePlayback.series,
                    episodes = refreshedQueue,
                    directFullscreen = activePlayback.directFullscreen,
                    requestId = requestId
                )
            }
            return
        }

        if (snapshot.selectedType == CatalogType.SERIES && snapshot.selectedSeries == null) {
            searchSeriesReturn = null
            task {
                val session = requireNotNull(_state.value.session)
                _state.update {
                    it.copy(
                        selectedSeries = item,
                        playbackReturnFocusId = null,
                        items = emptyList(),
                        availableSeriesSeasons = emptyList(),
                        selectedSeriesSeason = null,
                        seriesOpenedFromFavorites = false,
                        seriesOpenedFromHome = false,
                        seriesOpenedFromModernSection =
                            snapshot.modernIptvCategory != null
                    )
                }
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
            if (
                snapshot.modernIptvCategory != null &&
                snapshot.selectedType == CatalogType.MOVIES
            ) {
                val requestId = beginPlaybackRequest()
                playbackTask(requestId) {
                    playInternal(
                        item = item,
                        requestId = requestId,
                        type = CatalogType.MOVIES,
                        series = null,
                        episodes = queue,
                        directFullscreen = true
                    )
                }
            } else {
                play(
                    item,
                    snapshot.selectedType,
                    snapshot.selectedSeries,
                    queue
                )
            }
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
                    if (com.nikhil.niktv.data.CatalogPreferences.preferLocal(getApplication()) || now - cached.cachedAtMillis < searchCacheTtl) {
                        _state.update { it.copy(fullSearchLoading = false) }
                        return@launch
                    }
                }
            }
            runCatching {
                withAutomaticSessionRetry(session) { activeSession ->
                    portal.fullCatalog(activeSession, type, categories)
                }
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

    private fun catalogTypeForSearch(type: SearchContentType): CatalogType = when (type) {
        SearchContentType.ALL -> error("All search must be split into media types")
        SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
        SearchContentType.MOVIES -> CatalogType.MOVIES
        SearchContentType.SERIES,
        SearchContentType.EPISODES -> CatalogType.SERIES
    }

    private fun searchTypeForCatalog(type: CatalogType): SearchContentType = when (type) {
        CatalogType.LIVE_TV,
        CatalogType.RADIO -> SearchContentType.LIVE_TV
        CatalogType.MOVIES -> SearchContentType.MOVIES
        CatalogType.SERIES -> SearchContentType.SERIES
    }

    fun openSearch() = openSearchInternal(global = false)
    fun openGlobalSearch() = openSearchInternal(global = true)
    private fun openSearchInternal(global: Boolean) {
        cancelPendingPlayback()
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()
        _state.update { it.copy(offlineDownloadsOpen = false) }

        val snapshot = _state.value
        val tabType =
            if (global || snapshot.homeOpen || snapshot.favoritesOpen) {
                null
            } else {
                searchTypeForCatalog(snapshot.selectedType)
            }
        val effectiveType = tabType ?: SearchContentType.ALL

        /*
         * SEARCH_CONTEXT_CATEGORY_V2
         *
         * Tile-first browse keeps selectedCategory warm even on the top-level
         * destination. That is not a real user-selected search scope. In the
         * modern UI, inherit a category only when a category section is
         * actually open. Only an explicitly opened category provides that scope.
         */
        val contextualCategory =
            if (tabType == null) {
                null
            } else {
                snapshot.modernIptvCategory
                    ?.takeIf { it.type == catalogTypeForSearch(effectiveType) }
            }

        val effectiveCategoryId = when {
            global -> "*"
            contextualCategory != null && contextualCategory.sourceCategoryIds.isEmpty() -> contextualCategory.id
            contextualCategory != null -> "*"
            tabType != null -> "*"
            snapshot.searchType == effectiveType -> snapshot.searchCategoryId
            else -> "*"
        }

        _state.update { current ->
            current.openSearchNavigation().copy(
                searchType = effectiveType,
                searchServerLoading = false,
                searchScopeLocked = tabType != null,
                searchResults = emptyList(),
                searchLocalLoading = current.searchQuery.isNotBlank(),
                searchUsedServer = false,
                searchPage = 0,
                searchHasMore = false,
                searchCategories = emptyList(),
                searchCategoryId = effectiveCategoryId
            )
        }

        loadSearchCategories(effectiveType)

        snapshot.searchQuery
            .takeIf { it.isNotBlank() }
            ?.let {
                scheduleSearchPreview(
                    query = it,
                    type = effectiveType,
                    categoryId = effectiveCategoryId
                )
            }
    }

    fun closeSearch() {
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()
        _state.update {
            it.copy(
                searchOpen = false,
                searchLocalLoading = false,
                searchServerLoading = false
                ,searchActivityTitle = null
                ,searchActivityDetail = null
                ,searchActivityProgress = null
            )
        }
    }

    fun setSearchType(type: SearchContentType) {
        val snapshot = _state.value
        if (snapshot.searchScopeLocked || snapshot.searchType == type) return

        searchPreviewJob?.cancel()
        searchServerJob?.cancel()

        _state.update {
            it.copy(
                searchType = type,
                searchServerLoading = false,
                searchResults = emptyList(),
                searchLocalLoading = snapshot.searchQuery.isNotBlank(),
                searchUsedServer = false,
                searchPage = 0,
                searchHasMore = false,
                searchCategoryId = "*",
                searchCategories = emptyList()
            )
        }

        loadSearchCategories(type)

        snapshot.searchQuery
            .takeIf { it.isNotBlank() }
            ?.let {
                scheduleSearchPreview(
                    query = it,
                    type = type,
                    categoryId = "*"
                )
            }
    }

    fun setSearchCategory(categoryId: String) {
        val snapshot = _state.value
        if (snapshot.searchCategoryId == categoryId) return

        searchPreviewJob?.cancel()
        searchServerJob?.cancel()

        _state.update {
            it.copy(
                searchCategoryId = categoryId,
                searchServerLoading = false,
                searchResults = emptyList(),
                searchLocalLoading = snapshot.searchQuery.isNotBlank(),
                searchUsedServer = false,
                searchPage = 0,
                searchHasMore = false
            )
        }

        snapshot.searchQuery
            .takeIf { it.isNotBlank() }
            ?.let {
                scheduleSearchPreview(
                    query = it,
                    type = snapshot.searchType,
                    categoryId = categoryId
                )
            }
    }

    private fun loadSearchCategories(type: SearchContentType) = viewModelScope.launch {
        if (type == SearchContentType.ALL) return@launch
        val catalogType = catalogTypeForSearch(type)
        val session = _state.value.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val beforeCategoryId = _state.value.searchCategoryId
        val snapshot = _state.value

        val memoryCategories =
            snapshot.rawCategoriesByType[catalogType].orEmpty()
                .ifEmpty {
                    snapshot.browseCachesByType[catalogType]
                        ?.categories
                        .orEmpty()
                }

        val cached =
            if (memoryCategories.isNotEmpty()) {
                memoryCategories
            } else {
                store.browseCatalog(catalogType, profileKey)
                    .first()
                    ?.categories
                    .orEmpty()
            }

        val raw =
            if (cached.isNotEmpty()) {
                cached
            } else {
                runCatching {
                    withAutomaticSessionRetry(session) { activeSession ->
                        portal.categories(activeSession, catalogType)
                    }
                }.getOrDefault(emptyList())
            }

        val filtered =
            filterCategories(
                raw,
                profileKey,
                catalogType,
                _state.value.categoryFilters
            ).distinctBy { it.id }

        _state.update { current ->
            if (current.searchType != type) {
                current
            } else {
                val categoryId =
                    current.searchCategoryId.takeIf { requested ->
                        requested == "*" ||
                            filtered.any { it.id == requested }
                    } ?: "*"

                current.copy(
                    searchCategories = filtered,
                    searchCategoryId = categoryId
                )
            }
        }

        val current = _state.value
        if (
            current.searchType == type &&
            current.searchCategoryId != beforeCategoryId &&
            current.searchQuery.isNotBlank()
        ) {
            scheduleSearchPreview(
                query = current.searchQuery,
                type = current.searchType,
                categoryId = current.searchCategoryId
            )
        }
    }

    fun setSearchQuery(query: String) {
        if (_state.value.searchQuery == query) return
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()

        _state.update {
            it.copy(
                searchQuery = query,
                searchProviderMessage = null,
                searchServerLoading = false,
                searchResults = emptyList(),
                searchLocalLoading = query.isNotBlank(),
                searchUsedServer = false,
                searchPage = 0,
                searchHasMore = false,
                searchActivityTitle = null,
                searchActivityDetail = null,
                searchActivityProgress = null
            )
        }
        val current = _state.value
        // Check device caches first. A miss may reach the provider only after typing pauses.
        scheduleSearchPreview(query, current.searchType, current.searchCategoryId)
    }

    private fun scheduleSearchPreview(
        query: String,
        type: SearchContentType,
        categoryId: String
    ) {
        searchPreviewJob?.cancel()

        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    searchLocalLoading = false,
                    searchUsedServer = false,
                    searchPage = 0,
                    searchHasMore = false
                )
            }
            return
        }

        val profileKey =
            _state.value.session?.profile?.cacheKey()
                ?: run {
                    _state.update { it.copy(searchLocalLoading = false) }
                    return
                }

        searchPreviewJob = viewModelScope.launch {
            delay(SEARCH_PREVIEW_DEBOUNCE_MS)

            val saved =
                store.pagedSearches.first().firstOrNull {
                    it.profileKey == profileKey &&
                        it.type == type &&
                        it.categoryId == categoryId &&
                        it.query.equals(normalizedQuery, true)
                }

            val local =
                (
                    localSearch(
                        type = type,
                        query = normalizedQuery,
                        categoryId = categoryId,
                        onPartial = { partial -> publishLocalSearchPartial(profileKey, type, normalizedQuery, categoryId, partial) }
                    ) +
                        saved?.items.orEmpty()
                    )
                    .rankSearchResults(normalizedQuery, type)

            _state.update { current ->
                val stillCurrent =
                    current.searchOpen &&
                        current.session?.profile?.cacheKey() == profileKey &&
                        current.searchType == type &&
                        current.searchCategoryId == categoryId &&
                        current.searchQuery.trim() == normalizedQuery

                if (!stillCurrent) {
                    current
                } else {
                    current.copy(
                        searchResults = local,
                        searchLocalLoading = local.isEmpty() && normalizedQuery.length >= 2,
                        searchUsedServer = saved != null || (type == SearchContentType.ALL && hasGlobalSearchSession(profileKey, normalizedQuery)),
                        searchPage = saved?.lastPage ?: 0,
                        searchHasMore = if (type == SearchContentType.ALL && hasGlobalSearchSession(profileKey, normalizedQuery))
                            globalSearchPager?.hasMore == true else saved?.hasMore ?: false
                    )
                }
            }
            // Avoid a provider call for each keystroke; query/scope changes cancel this job.
            if (local.isEmpty() && normalizedQuery.length >= 2) {
                delay(SEARCH_PROVIDER_FALLBACK_DELAY_MS)
                val current = _state.value
                if (current.searchOpen && current.session?.profile?.cacheKey() == profileKey &&
                    current.searchType == type && current.searchCategoryId == categoryId &&
                    current.searchQuery.trim() == normalizedQuery) {
                    // The preview already checked every cache. Do not decode them a second time.
                    searchServerJob?.cancel()
                    searchServerJob = viewModelScope.launch {
                        rememberSearch(normalizedQuery, type)
                        if (type == SearchContentType.ALL) fetchGlobalSearch(normalizedQuery, profileKey, local)
                        else fetchSearchPage(normalizedQuery, type, categoryId, 1, local)
                    }
                }
            }
        }
    }

    fun search(forceServer: Boolean = false) {
        val snapshot = _state.value
        val query = snapshot.searchQuery.trim()
        val profileKey = snapshot.session?.profile?.cacheKey() ?: return

        if (query.isBlank() || snapshot.searchServerLoading) return
        searchPreviewJob?.cancel()

        _state.update {
            it.copy(
                searchLocalLoading = true,
                searchActivityTitle = "Searching this device",
                searchActivityDetail = "Checking loaded media and saved provider results",
                searchActivityProgress = null
            )
        }

        searchServerJob?.cancel()
        searchServerJob = viewModelScope.launch {
            rememberSearch(query, snapshot.searchType)

            val saved =
                store.pagedSearches.first().firstOrNull {
                    it.profileKey == profileKey &&
                        it.type == snapshot.searchType &&
                        it.categoryId == snapshot.searchCategoryId &&
                        it.query.equals(query, true)
                }

            cacheFirstSearch(
                forceProvider = forceServer,
                cached = {
                    (localSearch(
                        type = snapshot.searchType,
                        query = query,
                        categoryId = snapshot.searchCategoryId,
                        onPartial = { partial -> publishLocalSearchPartial(profileKey, snapshot.searchType, query, snapshot.searchCategoryId, partial) }
                    ) + saved?.items.orEmpty()).rankSearchResults(query, snapshot.searchType)
                },
                publish = { available ->
                    val current = _state.value
                    if (!current.searchOpen || current.session?.profile?.cacheKey() != profileKey ||
                        current.searchType != snapshot.searchType || current.searchCategoryId != snapshot.searchCategoryId ||
                        current.searchQuery.trim() != query) throw kotlinx.coroutines.CancellationException("Search changed")
                    _state.update { it.copy(
                        searchResults = available,
                        searchLocalLoading = false,
                        searchUsedServer = saved != null || (snapshot.searchType == SearchContentType.ALL && hasGlobalSearchSession(profileKey, query)),
                        searchPage = saved?.lastPage ?: 0,
                        searchHasMore = if (snapshot.searchType == SearchContentType.ALL && hasGlobalSearchSession(profileKey, query))
                            globalSearchPager?.hasMore == true else saved?.hasMore ?: false,
                        searchActivityTitle = null, searchActivityDetail = null, searchActivityProgress = null
                    ) }
                },
                provider = { available ->
                    if (snapshot.searchType == SearchContentType.ALL) {
                        fetchGlobalSearch(query, profileKey, available)
                    } else {
                        fetchSearchPage(query, snapshot.searchType, snapshot.searchCategoryId, 1, available)
                    }
                }
            )
        }
    }

    fun loadMoreSearch() {
        val snapshot = _state.value
        if (
            !snapshot.searchHasMore ||
            snapshot.searchServerLoading ||
            snapshot.searchQuery.isBlank()
        ) {
            return
        }

        searchServerJob?.cancel()
        searchServerJob = viewModelScope.launch {
            val current = _state.value
            if (
                !current.searchHasMore ||
                current.searchServerLoading ||
                current.searchQuery.isBlank()
            ) {
                return@launch
            }

            /*
             * Scoped search requests one page; global search has a bounded
             * batch of independently tracked type/category cursors.
             */
            try {
                if (current.searchType == SearchContentType.ALL) {
                    fetchGlobalSearch(current.searchQuery.trim(), current.session?.profile?.cacheKey() ?: return@launch,
                        current.searchResults, append = true)
                } else {
                    fetchSearchPage(
                        query = current.searchQuery.trim(),
                        type = current.searchType,
                        categoryId = current.searchCategoryId,
                        page = current.searchPage + 1,
                        existing = current.searchResults
                    )
                }
            } finally {
                _state.update { latest ->
                    if (latest.searchType == current.searchType && latest.searchQuery == current.searchQuery &&
                        latest.searchCategoryId == current.searchCategoryId && latest.session == current.session) {
                        latest.copy(searchPaginationGeneration = latest.searchPaginationGeneration + 1)
                    } else latest
                }
            }
        }
    }

    private var globalSearchPager: GlobalSearchPager? = null
    private var globalSearchPagerKey: String? = null

    private fun hasGlobalSearchSession(profileKey: String, query: String) =
        globalSearchPager != null && globalSearchPagerKey == "$profileKey|$query"

    private suspend fun searchProviderPage(
        session: PortalSession, type: SearchContentType, query: String, page: Int, categoryId: String
    ): PortalSearchPage {
        if (session.profile.portalType != PortalType.XTREAM || type == SearchContentType.EPISODES) {
            return portal.search(session, type, query, page, categoryId)
        }
        val catalog = collectionCatalog(session, catalogTypeForSearch(type), force = page == 1)
        return withContext(Dispatchers.Default) {
            val matches = catalog.items.filter {
                (categoryId == "*" || it.portalCategoryId == categoryId) && it.title.matchesTitleKeywords(query)
            }.rankSearchResults(query, type)
            PortalSearchPage(matches, 1, false)
        }
    }

    private suspend fun fetchGlobalSearch(
        query: String, profileKey: String, existing: List<MediaItem>, append: Boolean = false
    ) {
        val session = _state.value.session ?: return
        val key = "$profileKey|$query"
        if (!append || globalSearchPagerKey != key) {
            globalSearchPager = GlobalSearchPager()
            globalSearchPagerKey = key
        }
        val pager = requireNotNull(globalSearchPager)
        fun current() = _state.value.let {
            it.searchOpen && it.session?.profile?.cacheKey() == profileKey &&
                it.searchType == SearchContentType.ALL && it.searchQuery.trim() == query
        }
        _state.update { it.copy(searchServerLoading = true, searchLocalLoading = false, searchProviderMessage = null,
            searchResults = existing,
            searchUsedServer = true, searchHasMore = pager.hasMore,
            searchActivityTitle = "Loading provider results") }
        try {
            val batch = pager.next(
                search = { type, category, page ->
                    _state.update { it.copy(searchActivityDetail = "${type.title} · page $page",
                        searchActivityProgress = null) }
                    delay(METADATA_SCAN_REQUEST_DELAY_MS)
                    val result = withAutomaticSessionRetry(session) { activeSession ->
                        searchProviderPage(activeSession, type, query, page, category)
                    }
                    val previous = store.pagedSearches.first().firstOrNull {
                        it.profileKey == profileKey && it.type == type && it.query.equals(query, true) && it.categoryId == category
                    }
                    store.savePagedSearch(SearchResultCache(profileKey, type, query, category, page, result.hasMore,
                        ((if (page > 1) previous?.items.orEmpty() else emptyList()) + result.items).distinctBy { it.id }))
                    result
                },
                categories = { type ->
                    if (session.profile.portalType == PortalType.XTREAM) emptyList() else store.browseCatalog(catalogTypeForSearch(type), profileKey).first()?.categories
                        .orEmpty().ifEmpty {
                            withAutomaticSessionRetry(session) { activeSession ->
                                portal.categories(activeSession, catalogTypeForSearch(type))
                            }
                        }.map { it.id }
                },
                onItems = { items ->
                    if (current()) _state.update { latest -> latest.copy(
                        searchResults = (latest.searchResults + items.rankSearchResults(query, SearchContentType.ALL))
                            .distinctBy { it.searchIdentity(SearchContentType.ALL) }
                    ) }
                }
            )
            if (current()) _state.update { it.copy(
                searchProviderMessage = if (batch.failures > 0) "Some provider pages failed. Local results remain available; use Search provider to retry." else null
            ) }
        } finally {
            if (current()) _state.update { it.copy(searchServerLoading = false, searchHasMore = pager.hasMore,
                searchActivityTitle = null, searchActivityDetail = null, searchActivityProgress = null) }
        }
    }

    private suspend fun fetchSearchPage(
        query: String,
        type: SearchContentType,
        categoryId: String,
        page: Int,
        existing: List<MediaItem>
    ) {
        _state.update {
            it.copy(
                searchServerLoading = true,
                searchLocalLoading = false,
                searchProviderMessage = null,
                searchActivityTitle = "Searching IPTV provider",
                searchActivityDetail = "Requesting ${type.title.lowercase()} from the selected provider scope",
                searchActivityProgress = null
            )
        }

        val session = requireNotNull(_state.value.session)
        val profileKey = session.profile.cacheKey()

        runCatching {
            val direct = withAutomaticSessionRetry(session) { activeSession ->
                searchProviderPage(activeSession, type, query, page, categoryId)
            }
            if (session.profile.portalType == PortalType.XTREAM || categoryId != "*" || direct.items.isNotEmpty()) {
                direct
            } else {
                // Some Stalker portals accept the wildcard but silently return an
                // empty page. Search each enabled category and publish matches as
                // they arrive so All categories remains useful without a long blank wait.
                var discovered = emptyList<MediaItem>()
                var anyHasMore = false
                val cachedCategoryIds = _state.value.browseCachesByType[
                    catalogTypeForSearch(type)
                ]?.itemsByCategory?.keys.orEmpty()
                val categories = _state.value.searchCategories
                    .filter { it.id != "*" }
                    .sortedByDescending { it.id in cachedCategoryIds }

                // A small parallel window cuts latency without flooding the
                // IPTV portal. Stop as soon as an exact title is found; users
                // can request another page if they need broader matches.
                val batches = categories.chunked(SEARCH_CATEGORY_CONCURRENCY)
                for ((batchIndex, batch) in batches.withIndex()) {
                    _state.update { current ->
                        current.copy(
                            searchActivityTitle = "Searching IPTV categories",
                            searchActivityDetail =
                                "Categories ${batchIndex * SEARCH_CATEGORY_CONCURRENCY + 1}–" +
                                    "${minOf((batchIndex + 1) * SEARCH_CATEGORY_CONCURRENCY, categories.size)} of ${categories.size}",
                            searchActivityProgress =
                                batchIndex.toFloat() / batches.size.coerceAtLeast(1)
                        )
                    }
                    val pages = coroutineScope {
                        batch.map { category ->
                            async {
                                runCatching {
                                    withAutomaticSessionRetry(session) { activeSession ->
                                        portal.search(activeSession, type, query, page, category.id)
                                    }
                                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                            }
                        }.awaitAll().filterNotNull()
                    }
                    pages.forEach { categoryPage ->
                        anyHasMore = anyHasMore || categoryPage.hasMore
                        val before = discovered.size
                        discovered = (discovered + categoryPage.items).distinctBy { it.id }
                        if (discovered.size > before) {
                            _state.update { current ->
                                if (current.searchType == type &&
                                    current.searchCategoryId == "*" &&
                                    current.searchQuery.trim().equals(query, true)
                                ) {
                                    current.copy(
                                        searchResults = (existing + discovered).distinctBy { it.id },
                                        searchUsedServer = true
                                    )
                                } else current
                            }
                        }
                    }
                    if (discovered.any {
                            it.title.normalizedSearchQuery() == query.normalizedSearchQuery()
                        } || discovered.size >= SEARCH_ALL_CATEGORY_RESULT_LIMIT
                    ) break
                }
                direct.copy(items = discovered, hasMore = anyHasMore)
            }
        }.onSuccess { result ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val combined =
                (existing + result.items)
                    .distinctBy { it.id }

            val actuallyAdded = combined.size > existing.size
            val hasMore =
                result.hasMore &&
                    (page == 1 || actuallyAdded)

            val cache =
                SearchResultCache(
                    profileKey,
                    type,
                    query,
                    categoryId,
                    result.page,
                    hasMore,
                    combined
                )

            store.savePagedSearch(cache)

            _state.update { current ->
                val stillCurrent =
                    current.searchOpen && current.session?.profile?.cacheKey() == profileKey &&
                        current.searchType == type &&
                        current.searchCategoryId == categoryId &&
                        current.searchQuery.trim().equals(query, true)

                if (!stillCurrent) {
                    current
                } else {
                    current.copy(
                        searchResults = combined,
                        searchServerLoading = false,
                        searchUsedServer = true,
                        searchPage = result.page,
                        searchHasMore = hasMore
                        ,searchActivityTitle = null
                        ,searchActivityDetail = null
                        ,searchActivityProgress = null
                    )
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            _state.update { current ->
                val stillCurrent =
                    current.searchOpen && current.session?.profile?.cacheKey() == profileKey &&
                        current.searchType == type &&
                        current.searchCategoryId == categoryId &&
                        current.searchQuery.trim().equals(query, true)

                if (!stillCurrent) {
                    current
                } else {
                    current.copy(
                        searchServerLoading = false,
                        searchActivityTitle = null,
                        searchActivityDetail = null,
                        searchActivityProgress = null,
                        searchProviderMessage = "Provider unavailable. Local results remain available; use Search provider to retry."
                    )
                }
            }
        }
    }

    private fun publishLocalSearchPartial(
        profileKey: String, type: SearchContentType, query: String, categoryId: String, items: List<MediaItem>
    ) {
        _state.update { current ->
            if (current.searchOpen && current.session?.profile?.cacheKey() == profileKey &&
                current.searchType == type && current.searchQuery.trim() == query && current.searchCategoryId == categoryId) {
                current.copy(searchResults = items)
            } else current
        }
    }

    private suspend fun localSearch(
        type: SearchContentType,
        query: String,
        categoryId: String,
        onPartial: (List<MediaItem>) -> Unit = {}
    ): List<MediaItem> = withContext(Dispatchers.Default) {
        val selectedProfile = _state.value
        if (selectedProfile.savedProfile?.cacheKey() != selectedProfile.session?.profile?.cacheKey()) {
            throw kotlinx.coroutines.CancellationException("Profile is changing")
        }
        if (type == SearchContentType.ALL) {
            val profileKey = _state.value.session?.profile?.cacheKey()
            val saved = store.pagedSearches.first().filter {
                it.profileKey == profileKey && it.query.equals(query, true)
            }
            val found = mutableListOf<MediaItem>()
            for (child in globalSearchTypes) {
                found += (localSearch(child, query, "*") + saved.filter { it.type == child }.flatMap { it.items })
                    .map { it.copy(searchResultType = child) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                onPartial(found.rankSearchResults(query, type))
            }
            return@withContext found.rankSearchResults(query, type)
        }
        val catalogType = catalogTypeForSearch(type)
        val snapshot = _state.value
        val profileKey = snapshot.session?.profile?.cacheKey()

        fun scopedItems(cache: BrowseCatalogCache?): List<MediaItem> {
            return cache.searchItemsForProfile(profileKey, categoryId)
        }

        val memoryBrowse =
            scopedItems(snapshot.browseCachesByType[catalogType])

        val startedAt = SystemClock.elapsedRealtime()
        val matchesTitle = titleKeywordMatcher(query)
        // Already-loaded matches are usable immediately while the larger saved
        // index is read. Continue below so partial caches never hide other matches.
        val earlyCandidates = (memoryBrowse + snapshot.items.takeIf {
            snapshot.selectedType == catalogType && snapshot.selectedSeries == null
        }.orEmpty().filter { categoryId == "*" || it.portalCategoryId == categoryId })
            .distinctBy { it.id }
        val early = if (type == SearchContentType.EPISODES) emptyList() else
            (catalogueTitleIndex?.takeIf { it.isScopedTo(profileKey, catalogType) }?.findCandidates(earlyCandidates, query)
                ?: earlyCandidates.filter { matchesTitle(it.title) }).rankSearchResults(query, type)
        if (early.isNotEmpty()) onPartial(early)

        val indexCache = store.searchCatalog(catalogType, profileKey).first()
            ?.takeIf { it.profileKey == profileKey && it.type == catalogType }
        val cacheReadyAt = SystemClock.elapsedRealtime()
        // Both established disk caches can contain the same full catalogue.
        // Decode the browse cache only when the dedicated search cache is not
        // available, rather than parsing the catalogue twice per keystroke.
        val persistedBrowse = if (indexCache?.items.isNullOrEmpty()) {
            scopedItems(store.browseCatalog(catalogType, profileKey).first())
        } else {
            emptyList()
        }
        val previousSearchItems = store.pagedSearches.first()
            .cachedSearchItems(profileKey, type, categoryId)
        val indexed = indexCache?.items.orEmpty()
        val titleIndex = catalogueTitleIndex?.takeIf { it.isFor(indexed) } ?: if (
            indexed.isNotEmpty() && indexed.sumOf { it.title.length.toLong() } <= Runtime.getRuntime().maxMemory() / 64
        ) CatalogueTitleIndex(indexed, profileKey, catalogType).also { catalogueTitleIndex = it } else null
        val indexedMatches = titleIndex?.find(indexed, query, categoryId) ?: indexed.filter {
            (categoryId == "*" || it.portalCategoryId == categoryId) && matchesTitle(it.title)
        }

        val visible =
            if (
                snapshot.selectedType == catalogType &&
                snapshot.selectedSeries == null &&
                (
                    categoryId == "*" ||
                        snapshot.selectedCategory?.id == categoryId
                    )
            ) {
                snapshot.items
            } else {
                emptyList()
            }

        val episodes =
            if (type == SearchContentType.EPISODES) {
                (
                    snapshot.favorites
                        .filter { it.kind == FavoriteKind.EPISODE }
                        .map { it.media } +
                        snapshot.recentlyPlayed
                            .filter { it.kind == FavoriteKind.EPISODE }
                            .map { it.media } +
                        snapshot.items
                            .filter { it.episodeNumber != null }
                    )
                    .filter {
                        categoryId == "*" ||
                            it.portalCategoryId == categoryId
                    }
            } else {
                emptyList()
            }

        val source =
            if (type == SearchContentType.EPISODES) {
                episodes
            } else {
                early +
                    visible.filter { matchesTitle(it.title) } +
                    indexedMatches +
                    persistedBrowse.filter { matchesTitle(it.title) } +
                    previousSearchItems.filter { matchesTitle(it.title) }
            }

        val results = source
            .distinctBy { it.id }
            .filter { matchesTitle(it.title) }
            .rankSearchResults(query, type)
        Log.i("NikTvSearchTiming", "$type local: memory=${memoryBrowse.size}, index=${indexed.size}, cache=${cacheReadyAt - startedAt}ms, match=${SystemClock.elapsedRealtime() - cacheReadyAt}ms, results=${results.size}")
        results
    }

    private suspend fun rememberSearch(
        query: String,
        type: SearchContentType
    ) {
        val snapshot = _state.value
        val categoryId = snapshot.searchCategoryId
        val categoryTitle =
            snapshot.searchCategories
                .firstOrNull { it.id == categoryId }
                ?.title
                ?: if (categoryId == "*") {
                    "All categories"
                } else {
                    categoryId
                }

        store.addRecentSearch(
            RecentSearch(
                query = query,
                type = type,
                categoryId = categoryId,
                categoryTitle = categoryTitle,
                profileKey =
                    snapshot.session?.profile?.cacheKey().orEmpty()
            )
        )
    }

    fun useRecentSearch(search: RecentSearch) {
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()

        val snapshot = _state.value
        val effectiveType =
            if (snapshot.searchScopeLocked) {
                snapshot.searchType
            } else {
                search.type
            }
        val effectiveCategoryId =
            if (
                snapshot.searchScopeLocked &&
                search.type != snapshot.searchType
            ) {
                snapshot.searchCategoryId
            } else {
                search.categoryId
            }

        _state.update {
            it.copy(
                searchQuery = search.query,
                searchServerLoading = false,
                searchType = effectiveType,
                searchOpen = true,
                searchCategoryId = effectiveCategoryId,
                searchCategories = emptyList(),
                searchResults = emptyList(),
                searchLocalLoading = search.query.isNotBlank(),
                searchUsedServer = false,
                searchPage = 0,
                searchHasMore = false
            )
        }

        loadSearchCategories(effectiveType)

        scheduleSearchPreview(
            query = search.query,
            type = effectiveType,
            categoryId = effectiveCategoryId
        )
    }

    fun deleteRecentSearch(search: RecentSearch) = viewModelScope.launch {
        store.removeRecentSearch(search)
    }

    fun openSearchResult(item: MediaItem) {
        val type = when (item.searchResultType ?: _state.value.searchType) {
            SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            SearchContentType.MOVIES -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        if (gateAdultAction(item, type) { openSearchResult(item) }) return
        when (item.searchResultType ?: _state.value.searchType) {
            SearchContentType.ALL -> return
            SearchContentType.LIVE_TV -> play(item, CatalogType.LIVE_TV)
            SearchContentType.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                searchSeriesReturn = SearchSeriesReturn.capture(_state.value, item)
                searchPreviewJob?.cancel()
                searchServerJob?.cancel()
                _state.update {
                    it.copy(
                        searchOpen = false,
                        homeOpen = false,
                        favoritesOpen = false,
                        selectedType = CatalogType.SERIES,
                        selectedSeries = item,
                        seriesOpenedFromHome = false,
                        seriesOpenedFromFavorites = false,
                        seriesOpenedFromModernSection = false,
                        playbackReturnFocusId = null,
                        items = emptyList(),
                        availableSeriesSeasons = emptyList(),
                        selectedSeriesSeason = null
                    )
                }
                loadSeriesEpisodes(item)
            }
            SearchContentType.MOVIES -> play(item, CatalogType.MOVIES)
            SearchContentType.EPISODES -> play(item, CatalogType.SERIES)
        }
    }
    private val alternateIdentity by lazy { com.nikhil.niktv.data.AlternateTmdbIdentity(
        fetch = { path, query -> tmdb.discoveryJson(path, query) }, configured = { tmdb.configured }) }
    private val alternateResolver by lazy { com.nikhil.niktv.data.AlternateSourceResolver(identityLookup = alternateIdentity::resolve) }
    internal var alternateSourceLookup: suspend (com.nikhil.niktv.data.AlternateSourceTarget) -> List<com.nikhil.niktv.data.AlternateStream> =
        { target -> alternateResolver.resolve(target) }
    private var alternateLookupJob: Job? = null
    private var alternateLookupGeneration = 0L
    // Holds a player only while its source picker is open. Never serialize the
    // supplier, and never read a decoder belonging to an obsolete playback attempt.
    private data class AlternatePositionReader(val attemptId: String, val progressKey: String, val read: () -> Long)
    private var alternatePositionReader: AlternatePositionReader? = null
    internal fun showTmdbIptvLookupMiss(request: AlternateSourceRequest, profileKey: String, requestId: Long? = null) {
        if (requestId != null && !latestPlaybackRequest.isCurrent(requestId)) return
        if (profileKey != alternateProfile() || request.type !in setOf(CatalogType.MOVIES, CatalogType.SERIES)) return
        pendingDiscoveryEpisode = null
        _state.update { it.copy(error = null, tmdbIptvLookupMiss = TmdbIptvLookupMiss(request, profileKey)) }
    }

    fun dismissTmdbIptvLookupMiss() = _state.update { it.copy(tmdbIptvLookupMiss = null) }

    fun findAlternateSourcesAfterIptvMiss() {
        val miss = _state.value.tmdbIptvLookupMiss ?: return
        dismissTmdbIptvLookupMiss()
        if (miss.profileKey == alternateProfile()) openAlternateSources(miss.request)
    }

    private fun alternateProfile() = _state.value.session?.profile?.cacheKey() ?: _state.value.savedProfile?.cacheKey().orEmpty()

    fun closeAlternateSources() {
        alternatePositionReader = null
        alternateLookupGeneration++
        alternateLookupJob?.cancel(); alternateLookupJob = null
        _state.update { if (it.alternateSources == null) it else it.copy(alternateSources = null) }
    }

    fun openAlternateSources(request: AlternateSourceRequest) = openAlternateSourcesInternal(request, null)

    private fun openAlternateSourcesInternal(request: AlternateSourceRequest, resumeTarget: com.nikhil.niktv.data.AlternateSourceTarget?) {
        if (request.type !in setOf(CatalogType.MOVIES, CatalogType.SERIES)) return
        val protected = alternateProtectedMedia(request)
        if (gateAdultAction(protected, request.type) { openAlternateSourcesInternal(request, resumeTarget) }) return
        closeAlternateSources()
        val target = resumeTarget ?: alternateTargetFor(request)
        _state.update { it.copy(alternateSources = AlternateSourcePickerState(request, target, alternateProfile())) }
        // Series tiles ask for the episode first. Exact episode/movie actions resolve immediately.
        if (!target.series || request.episode != null || request.item.episodeNumber != null) findAlternateSources(target, resumeAutomatically = resumeTarget != null)
    }

    fun openPlayingAlternateSources(position: Long, playing: PlayingMedia, positionReader: (() -> Long)? = null) {
        if (!samePlaybackAttempt(_state.value.nowPlaying ?: return, playing)) return
        val source = playing.alternateSource ?: return
        closeAlternateSources()
        alternatePositionReader = positionReader?.let { AlternatePositionReader(playing.playbackAttemptId,
            alternateProgressKey(source.profileKey, source.target), it) }
        val request = AlternateSourceRequest(playing.media, playing.catalogType, playing.series,
            source.target.season, source.target.episode)
        _state.update { it.copy(alternateSources = AlternateSourcePickerState(request, source.target, source.profileKey,
            streams = source.streams, resumePositionMillis = position.coerceAtLeast(0), playWhenReady = playing.playWhenReady,
            selectedId = source.selectedId)) }
    }

    fun findAlternateSources(target: com.nikhil.niktv.data.AlternateSourceTarget, resumeAutomatically: Boolean = false) {
        val picker = _state.value.alternateSources ?: return
        if (picker.profileKey != alternateProfile()) { closeAlternateSources(); return }
        val protected = alternateProtectedMedia(picker.request).copy(title = target.title)
        if (gateAdultAction(protected, picker.request.type) { findAlternateSources(target) }) return
        alternateLookupJob?.cancel()
        val generation = ++alternateLookupGeneration
        _state.update { it.copy(alternateSources = picker.copy(target = target, loading = true, streams = emptyList(), error = null,
            resumePositionMillis = picker.resumePositionMillis.takeIf { alternateProgressKey(picker.profileKey, picker.target) == alternateProgressKey(picker.profileKey, target) },
            selectedId = picker.selectedId.takeIf { picker.target == target })) }
        alternateLookupJob = viewModelScope.launch {
            try {
                alternateResolver.invalidate(target)
                val identified = if (target.provider != com.nikhil.niktv.data.AlternateSourceProvider.AUTO && target.tmdbId == null)
                    alternateIdentity.resolve(target) else target
                val streams = alternateSourceLookup(identified)
                if (generation != alternateLookupGeneration || alternateProfile() != picker.profileKey) return@launch
                _state.update { current -> current.copy(alternateSources = current.alternateSources?.copy(
                    target = identified, loading = false, streams = streams, error = if (streams.isEmpty()) "No playable sources found for this title." else null)) }
                if (resumeAutomatically && streams.isNotEmpty()) chooseAlternateStream(alternateResumeStream(streams, identified.preferredHeight)!!.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (generation == alternateLookupGeneration && alternateProfile() == picker.profileKey)
                    _state.update { current -> current.copy(alternateSources = current.alternateSources?.copy(
                        loading = false, error = error.message ?: "Source lookup failed. Try again.")) }
            }
        }
    }

    fun chooseAlternateStream(id: String) {
        val picker = _state.value.alternateSources ?: return
        val stream = picker.streams.firstOrNull { it.id == id } ?: return
        if (picker.profileKey != alternateProfile()) { closeAlternateSources(); return }
        val protected = alternateProtectedMedia(picker.request).copy(title = picker.target.title)
        if (gateAdultAction(protected, picker.request.type) { chooseAlternateStream(id) }) return
        val active = _state.value.nowPlaying
        if (active?.alternateSource?.target == picker.target && active.alternateSource.selectedId == id &&
            (active.url == stream.url || (stream.abibli?.videoId != null && alternatePlaybackLease?.sourceKey == stream.abibli.videoId))) {
            closeAlternateSources(); return
        }
        val currentKey = alternateProgressKey(picker.profileKey, picker.target)
        val sameActiveTitle = active?.alternateSource?.let {
            alternateProgressKey(it.profileKey, it.target) == currentKey
        } == true
        // Playback continues during source lookup. Sample only at the user's
        // final selection, before beginPlaybackRequest closes and releases the
        // supplier, so changing quality does not rewind by the dialog dwell time.
        val currentReader = alternatePositionReader?.takeIf {
            it.attemptId == active?.playbackAttemptId && it.progressKey == currentKey && sameActiveTitle
        }
        val currentPosition = currentReader?.let { reader -> runCatching { reader.read().coerceAtLeast(0L) }.getOrNull() }
        val selection = picker.copy(resumePositionMillis = currentPosition ?: picker.resumePositionMillis,
            playWhenReady = active?.playWhenReady?.takeIf { sameActiveTitle } ?: picker.playWhenReady)
        val requestId = beginPlaybackRequest()
        playbackTask(requestId) { latestPlaybackRequest.run(requestId) {
            val current = _state.value.nowPlaying
            val committedSelection = if (sameActiveTitle && current?.playbackAttemptId == active?.playbackAttemptId)
                selection.copy(playWhenReady = current?.playWhenReady ?: selection.playWhenReady) else selection
            commitAlternateStream(committedSelection, stream, requestId, currentReader?.read)
        } }
    }

    internal var cinejoyNativeLookup: suspend (com.nikhil.niktv.data.AlternateStream) -> List<com.nikhil.niktv.data.AlternateStream> =
        { com.nikhil.niktv.data.validateCinejoyNativeStream(it) }
    private var cinejoyHandoffJob: Job? = null
    fun playDetectedCinejoyStream(candidate: com.nikhil.niktv.data.AlternateStream, position: Long,
        playing: PlayingMedia, failed: (String) -> Unit) {
        val source = playing.alternateSource ?: return
        if (source.target.provider !in setOf(com.nikhil.niktv.data.AlternateSourceProvider.CINEJOY, com.nikhil.niktv.data.AlternateSourceProvider.CNO) ||
            !samePlaybackAttempt(_state.value.nowPlaying ?: return, playing)) return
        cinejoyHandoffJob?.cancel()
        cinejoyHandoffJob = viewModelScope.launch {
            try {
                val streams = cinejoyNativeLookup(candidate)
                if (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != true) return@launch
                if (!samePlaybackAttempt(_state.value.nowPlaying ?: return@launch, playing) || source.profileKey != alternateProfile()) return@launch
                cinejoyHandoffJob = null
                openPlayingAlternateSources(position, playing)
                _state.update { it.copy(alternateSources = it.alternateSources?.copy(streams = source.streams + streams)) }
                chooseAlternateStream(streams.first().id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) {
                if (_state.value.nowPlaying?.playbackAttemptId == playing.playbackAttemptId)
                    failed("This stream could not be opened natively. Retry or choose another source.")
            }
        }
    }

    private fun alternateProtectedMedia(request: AlternateSourceRequest): MediaItem = request.item.copy(
        isAdult = isAdultMedia(request.item, request.type) || request.series?.let { isAdultMedia(it, CatalogType.SERIES) } == true)

    private fun alternateSameTitle(request: AlternateSourceRequest, target: com.nikhil.niktv.data.AlternateSourceTarget): Boolean {
        val original = alternateTargetFor(request)
        return original.title == target.title &&
            (original.tmdbId == null || target.tmdbId == null || original.tmdbId == target.tmdbId) &&
            (original.year == null || target.year == null || original.year == target.year)
    }

    private fun alternateSeriesMedia(request: AlternateSourceRequest, target: com.nikhil.niktv.data.AlternateSourceTarget): MediaItem? {
        if (!target.series) return null
        if (alternateSameTitle(request, target)) return request.series ?: request.item
        return MediaItem(id = alternateProgressKey("", target.copy(season = null, episode = null)),
            title = target.title, logo = null, command = null, externalTmdbId = target.tmdbId,
            isAdult = alternateProtectedMedia(request).isAdult)
    }

    private fun alternateEpisodeMedia(request: AlternateSourceRequest, target: com.nikhil.niktv.data.AlternateSourceTarget): MediaItem {
        if (!target.series) return if (alternateSameTitle(request, target)) request.item else
            MediaItem(alternateProgressKey("", target), target.title, null, null,
                externalTmdbId = target.tmdbId, isAdult = alternateProtectedMedia(request).isAdult)
        val series = requireNotNull(alternateSeriesMedia(request, target))
        val sameEpisode = alternateSameTitle(request, target) && request.item.seasonNumber == target.season && request.item.episodeNumber == target.episode
        val episode = if (sameEpisode) request.item else MediaItem("", "", series.logo, null, isAdult = series.isAdult)
        return episode.copy(id = "${series.id}:alternate:${target.season}:${target.episode}",
            title = "${target.title} · S${target.season} E${target.episode}",
            seasonNumber = target.season, episodeNumber = target.episode, externalTmdbId = target.tmdbId)
    }

    private var alternatePlaybackLease: com.nikhil.niktv.data.AlternatePlaybackLease? = null
    internal var alternatePlaybackPreparation: suspend (com.nikhil.niktv.data.AlternateStream) -> com.nikhil.niktv.data.AlternatePlaybackLease? =
        { stream -> stream.abibli?.let { com.nikhil.niktv.data.AbibliPlaybackLease.open(it) } }
    private var alternatePlaybackLeaseWatcher: Job? = null
    private fun releaseAlternatePlaybackLease() {
        alternatePlaybackLeaseWatcher?.cancel(); alternatePlaybackLeaseWatcher = null
        alternatePlaybackLease?.close(); alternatePlaybackLease = null
    }
    private fun adoptAlternatePlaybackLease(lease: com.nikhil.niktv.data.AlternatePlaybackLease?) {
        val previous = alternatePlaybackLease
        alternatePlaybackLease = lease
        if (previous !== lease) previous?.close()
        if (lease != null && alternatePlaybackLeaseWatcher?.isActive != true) {
            // The ViewModel owns this resource across rotation/PiP/engine changes. It is
            // released for every transition away, including ordinary IPTV/profile paths.
            alternatePlaybackLeaseWatcher = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                _state.map { it.nowPlaying?.url }.distinctUntilChanged().collect { url ->
                    val owned = alternatePlaybackLease
                    if (owned == null || url != owned.url) {
                        alternatePlaybackLease = null
                        owned?.close()
                        kotlinx.coroutines.currentCoroutineContext()[Job]?.cancel()
                    }
                }
            }
        }
    }

    private suspend fun commitAlternateStream(picker: AlternateSourcePickerState, stream: com.nikhil.niktv.data.AlternateStream, requestId: Long,
        readLatestPosition: (() -> Long)? = null) {
        if (!latestPlaybackRequest.isCurrent(requestId) || picker.profileKey != alternateProfile()) return
        val target = picker.target
        val beforePreparation = _state.value.nowPlaying
        val lease = try { alternatePlaybackPreparation(stream) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { throw com.nikhil.niktv.data.AlternateSourceException("Abibli could not start playback. Retry or choose another source.") }
        if (!latestPlaybackRequest.isCurrent(requestId) || picker.profileKey != alternateProfile()) { lease?.close(); return }
        val item = alternateEpisodeMedia(picker.request, target)
        val key = alternateProgressKey(picker.profileKey, target)
        val active = _state.value.nowPlaying?.takeIf { it.playbackAttemptId == beforePreparation?.playbackAttemptId && it.progressKey == key }
        val position = active?.let { runCatching { readLatestPosition?.invoke() }.getOrNull() }?.coerceAtLeast(0L)
            ?: picker.resumePositionMillis ?: _state.value.playbackProgress.firstOrNull { it.key == key }?.positionMillis ?: 0L
        val source = AlternatePlaybackSource(target, picker.streams, stream.id, picker.profileKey)
        liveGuideScheduler.pause()
        channelScheduleJob?.cancel(); playbackQueuePrefetches.cancelUnfinished()
        _state.update { it.copy(nowPlaying = PlayingMedia(item, lease?.url ?: stream.url, picker.request.type,
            series = alternateSeriesMedia(picker.request, target),
            episodeQueue = listOf(item),
            previousEpisode = target.episode?.takeIf { target.series && it > 1 }?.let { number -> alternateEpisodeMedia(picker.request, target.copy(episode = number - 1)) },
            nextEpisode = target.episode?.takeIf { target.series }?.let { number -> alternateEpisodeMedia(picker.request, target.copy(episode = number + 1)) },
            resumePositionMillis = position, progressKey = key, directFullscreen = true,
            streamRequestHeaders = if (lease == null) stream.headers else emptyMap(), playbackFormat = stream.format,
            playWhenReady = active?.playWhenReady ?: picker.playWhenReady, alternateSource = source, profileKey = picker.profileKey),
            error = null, playbackReturnFocusId = if (it.nowPlaying != null)
                it.playbackReturnFocusId ?: picker.request.item.id else picker.request.item.id,
            playbackQueueScope = null, playbackQueueHasMore = false, playbackQueueLoadingMore = false) }
        adoptAlternatePlaybackLease(lease)
        val series = alternateSeriesMedia(picker.request, target)
        val recent = RecentItem(if (target.series) FavoriteKind.SERIES else FavoriteKind.MOVIE,
            series ?: item, lastPlayed = item.takeIf { target.series }, profileKey = picker.profileKey,
            alternateTarget = target.copy(provider = alternateHistoryProvider(target.provider, stream.label), preferredHeight = stream.height))
        val updated = (listOf(recent) + _state.value.recentlyPlayed).distinctBy { it.key }.take(MAX_RECENT_ITEMS)
        _state.update { it.copy(recentlyPlayed = updated) }
        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == picker.profileKey } + updated
        val persisted = allRecentlyPlayed
        viewModelScope.launch { store.saveRecentlyPlayed(persisted) }

    }

    private fun retryAlternatePlayback(position: Long, playing: PlayingMedia, target: com.nikhil.niktv.data.AlternateSourceTarget? = null) {
        val source = playing.alternateSource ?: return
        val requested = target ?: source.target
        if (!samePlaybackAttempt(_state.value.nowPlaying ?: return, playing)) return
        val protected = alternateProtectedMedia(AlternateSourceRequest(playing.media, playing.catalogType, playing.series)).copy(title = requested.title)
        if (gateAdultAction(protected, playing.catalogType) { retryAlternatePlayback(position, playing, target) }) return
        val requestId = beginPlaybackRequest()
        playbackTask(requestId) { latestPlaybackRequest.run(requestId) {
            alternateResolver.invalidate(requested)
            val streams = alternateSourceLookup(requested)
            if (!latestPlaybackRequest.isCurrent(requestId) || source.profileKey != alternateProfile()) return@run
            val selected = alternateRetryStream(source, streams)
                ?: error("No playable alternate source is currently available.")
            val request = AlternateSourceRequest(playing.media, playing.catalogType, playing.series, requested.season, requested.episode)
            val active = _state.value.nowPlaying?.takeIf { samePlaybackAttempt(it, playing) } ?: return@run
            commitAlternateStream(AlternateSourcePickerState(request, requested, source.profileKey, streams = streams,
                resumePositionMillis = position, playWhenReady = active.playWhenReady), selected, requestId)
        } }
    }

    private fun play(item: MediaItem, type: CatalogType, series: MediaItem? = null, episodes: List<MediaItem> = emptyList()) {
        val requestId = beginPlaybackRequest()
        playbackNavigationIndex = null
        navigationCursor.select(requestId, episodes.map { it.id }, item.id)
        playbackTask(requestId) { playInternal(item, type, series, episodes, requestId = requestId) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloadForOffline(item: MediaItem, type: CatalogType, series: MediaItem? = null) {
        if (gateAdultAction(series ?: item, type) { downloadForOffline(item, type, series) }) return
        if (type == CatalogType.LIVE_TV) {
            _state.update { it.copy(error = "Live channels require a timed recording and cannot be saved as an offline download.") }
            return
        }
        viewModelScope.launch {
            runCatching {
                val session = requireNotNull(_state.value.session)
                val key = "${session.profile.cacheKey()}:${type.name}:${item.id}"
                val existing = _state.value.offlineDownloads.firstOrNull { it.key == key }
                if (existing != null && withContext(Dispatchers.IO) { OfflineMediaDownloads.status(getApplication(), existing.requestId, existing.downloadId) } !in
                    setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)) return@runCatching
                existing?.let { OfflineMediaDownloads.remove(getApplication(), it.requestId, it.downloadId) }
                val url = portal.playableUrl(session, item, type)
                val handle = OfflineMediaDownloads.enqueue(getApplication(), key, item.title, url)
                val entry = OfflineMediaDownload(downloadId = handle.downloadId, requestId = handle.requestId, sourceUrl = url, profileKey = session.profile.cacheKey(), catalogType = type, media = item.copy(isAdult = isAdultMedia(series ?: item, type)), series = series, fileType = handle.fileType)
                val updated = listOf(entry) + _state.value.offlineDownloads.filterNot { it.key == key }
                _state.update { it.copy(offlineDownloads = updated, offlineDownloadRevision = it.offlineDownloadRevision + 1L) }
                store.saveOfflineDownloads(updated)
            }.onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "Unable to start offline download") }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloadNowPlaying() {
        if (_state.value.nowPlaying?.alternateSource != null) return
        val playing = _state.value.nowPlaying ?: return
        if (playing.catalogType == CatalogType.LIVE_TV) {
            _state.update { it.copy(error = "Live recording needs a start/stop or duration and is not available as an offline download.") }
            return
        }
        val profileKey = _state.value.session?.profile?.cacheKey() ?: _state.value.savedProfile?.cacheKey() ?: return
        val existingKey = "$profileKey:${playing.catalogType.name}:${playing.media.id}"
        viewModelScope.launch {
            _state.value.offlineDownloads.firstOrNull { it.key == existingKey }?.let { existing ->
                if (withContext(Dispatchers.IO) { OfflineMediaDownloads.status(getApplication(), existing.requestId, existing.downloadId) } !in
                    setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)
                ) {
                    removeOfflineDownload(playing.media, playing.catalogType)
                    return@launch
                }
            }
            val session = _state.value.session ?: return@launch
            runCatching {
                val key = existingKey
                val existing = _state.value.offlineDownloads.firstOrNull { it.key == key }
                if (existing != null && withContext(Dispatchers.IO) { OfflineMediaDownloads.status(getApplication(), existing.requestId, existing.downloadId) } !in
                    setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)) return@runCatching
                existing?.let { OfflineMediaDownloads.remove(getApplication(), it.requestId, it.downloadId) }
                val handle = OfflineMediaDownloads.enqueue(getApplication(), key, playing.media.title, playing.url)
                val entry = OfflineMediaDownload(downloadId = handle.downloadId, requestId = handle.requestId, sourceUrl = playing.url, profileKey = session.profile.cacheKey(), catalogType = playing.catalogType, media = playing.media, series = playing.series, fileType = handle.fileType)
                val updated = listOf(entry) + _state.value.offlineDownloads.filterNot { it.key == key }
                _state.update { it.copy(offlineDownloads = updated, offlineDownloadRevision = it.offlineDownloadRevision + 1L) }
                store.saveOfflineDownloads(updated)
            }.onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: "Unable to start offline download") }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun removeOfflineDownload(item: MediaItem, type: CatalogType) {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: _state.value.savedProfile?.cacheKey() ?: return
        val key = "$profileKey:${type.name}:${item.id}"
        val entry = _state.value.offlineDownloads.firstOrNull { it.key == key } ?: return
        val playing = _state.value.nowPlaying
        val removingActiveOfflinePlayback =
            playing?.offlinePlayback == true && playing.catalogType == type && playing.media.id == item.id

        viewModelScope.launch {
            var onlineUrl: String? = null
            if (removingActiveOfflinePlayback) {
                val session = _state.value.session
                if (session == null) {
                    _state.update { it.copy(error = "Connect to the IPTV provider before deleting a download that is currently playing.") }
                    return@launch
                }
                onlineUrl = runCatching {
                    withAutomaticSessionRetry(session) { activeSession ->
                        portal.playableUrl(activeSession, item, type)
                    }
                }
                    .getOrElse { failure ->
                        _state.update { it.copy(error = failure.message ?: "Could not switch playback to the IPTV stream. The offline download was kept.") }
                        return@launch
                    }
            }

            val updated = _state.value.offlineDownloads.filterNot { it.key == key }
            val resumePosition = playing?.let { current ->
                _state.value.playbackProgress.firstOrNull { it.key == current.progressKey }?.positionMillis
                    ?: current.resumePositionMillis
            } ?: 0L
            _state.update { current ->
                current.copy(
                    offlineDownloads = updated,
                    offlineDownloadRevision = current.offlineDownloadRevision + 1L,
                    nowPlaying = if (removingActiveOfflinePlayback && onlineUrl != null) {
                        playing.copy(
                            url = onlineUrl,
                            resumePositionMillis = resumePosition,
                            offlinePlayback = false
                        )
                    } else current.nowPlaying
                )
            }
            store.saveOfflineDownloads(updated)
            OfflineMediaDownloads.remove(getApplication(), entry.requestId, entry.downloadId)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun removeAllOfflineDownloads() {
        val downloads = _state.value.offlineDownloads
        if (downloads.isEmpty()) return
        val playing = _state.value.nowPlaying
        val activeDownload = playing?.takeIf { it.offlinePlayback }?.let { active ->
            downloads.firstOrNull {
                it.catalogType == active.catalogType && it.media.id == active.media.id
            }
        }
        viewModelScope.launch {
            var onlineUrl: String? = null
            if (activeDownload != null) {
                val session = _state.value.session
                if (session == null) {
                    _state.update { it.copy(error = "Connect to the IPTV provider before clearing downloads while offline media is playing.") }
                    return@launch
                }
                onlineUrl = runCatching {
                    portal.playableUrl(session, playing.media, playing.catalogType)
                }.getOrElse { failure ->
                    _state.update { it.copy(error = failure.message ?: "Could not switch active playback online. Downloads were kept.") }
                    return@launch
                }
            }

            _state.update { current ->
                current.copy(
                    offlineDownloads = emptyList(),
                    offlineDownloadRevision = current.offlineDownloadRevision + 1L,
                    nowPlaying = if (activeDownload != null && onlineUrl != null) {
                        playing.copy(url = onlineUrl, offlinePlayback = false)
                    } else current.nowPlaying
                )
            }
            store.saveOfflineDownloads(emptyList())
            downloads.forEach { OfflineMediaDownloads.remove(getApplication(), it.requestId, it.downloadId) }
        }
    }

    private val latestPlaybackRequest = LatestPlaybackRequest()
    private val navigationCursor = PlaybackNavigationCursor()
    private val pendingNavigationId: String? get() = navigationCursor.pendingId
    private var playbackNavigationIndex: Int? = null
    private var channelScheduleJob: Job? = null
    private var selectedLiveGuideTarget: LiveGuideTarget? = null
    private var autoAdvanceAfterQueueLoadJob: Job? = null

    private fun beginPlaybackRequest(): Long {
        movieStructureJob?.cancel()
        categoryMetadataRefreshJobs.values.toList().forEach { it.cancel() }
        categoryMetadataRefreshJobs.clear()
        movieMatchLookupJob?.cancel(); movieMatchLookupJob = null
        cinejoyHandoffJob?.cancel(); cinejoyHandoffJob = null
        closeAlternateSources()
        discoveryMetadataRequests.cancel()
        pendingDiscoveryEpisode = null
        portal.prioritizePlayback()
        navigationCursor.cancel()
        return latestPlaybackRequest.begin()
    }

    private suspend fun playInternal(
        item: MediaItem,
        type: CatalogType,
        series: MediaItem?,
        episodes: List<MediaItem>,
        forceFreshUrl: Boolean = false,
        authorizationRetryCount: Int = 0,
        resumePositionOverride: Long? = null,
        directFullscreen: Boolean = false,
        requestId: Long,
        reauthenticate: Boolean = false,
        queueFilter: PlaybackQueueFilter? = null
    ) = latestPlaybackRequest.run(requestId) {
        if (navigationCursor.pendingRequest != requestId) navigationCursor.select(requestId,
            _state.value.nowPlaying?.let(::playbackNavigationQueue).orEmpty().map { it.id }, item.id,
            playbackNavigationIndex)
        try {
            if ((isAdultMedia(item, type) || series?.let { isAdultMedia(it, CatalogType.SERIES) } == true) && !authorizeParental()) return@run
            if (type == CatalogType.LIVE_TV) _state.update { it.copy(liveGuidePriorityId = item.id) }
            if (_state.value.categoryFindSearching) cancelCategoryFind()
            liveGuideScheduler.pause()
            channelScheduleJob?.cancel()
            // Keep completed warm pages, but let the selected stream precede unfinished prefetches.
            playbackQueuePrefetches.cancelUnfinished()
            // Coalesce a burst of remote presses before opening another stream.
            if (type == CatalogType.LIVE_TV && _state.value.nowPlaying != null) delay(120L)
            val session = requireNotNull(_state.value.session)
            if (reauthenticate) {
                _state.update { it.copy(reauthenticating = true) }
                val refreshed = try {
                    sessionRefreshMutex.withLock {
                        _state.value.session?.takeIf { it.profile.cacheKey() == session.profile.cacheKey() && it.token != session.token }
                            ?: refreshSession(session.profile)
                    }
                } finally {
                    if (latestPlaybackRequest.isCurrent(requestId)) _state.update { it.copy(reauthenticating = false) }
                }
                resolvePlayback(item, type, series, episodes, forceFreshUrl,
                    authorizationRetryCount, resumePositionOverride, directFullscreen, refreshed, requestId, queueFilter)
            } else {
                withAutomaticSessionRetry(session, playbackRequestId = requestId) { activeSession ->
                    resolvePlayback(item, type, series, episodes, forceFreshUrl,
                        authorizationRetryCount, resumePositionOverride, directFullscreen, activeSession, requestId, queueFilter)
                }
            }
        } catch (container: com.nikhil.niktv.data.PortalVodSeriesContainerException) {
            if (latestPlaybackRequest.isCurrent(requestId) && type == CatalogType.MOVIES) {
                movieStructureJob?.cancel()
                openVodSeriesContainer(item)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The generic task wrapper must not restart this selection after cancellation
            // or repeat the authentication retry outside LatestPlaybackRequest.
            throw PlaybackRequestException(error)
        } finally {
            navigationCursor.finish(requestId)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun resolvePlayback(
        requestedItem: MediaItem,
        type: CatalogType,
        series: MediaItem?,
        episodes: List<MediaItem>,
        forceFreshUrl: Boolean = false,
        authorizationRetryCount: Int = 0,
        resumePositionOverride: Long? = null,
        directFullscreen: Boolean = false,
        session: PortalSession,
        requestId: Long,
        queueFilter: PlaybackQueueFilter?
    ) {
        val playbackStartedAt = SystemClock.elapsedRealtime()
        val offlineEntry = _state.value.offlineDownloads
            .firstOrNull { it.key == "${session.profile.cacheKey()}:${type.name}:${requestedItem.id}" }
        val offlineUrl = withContext(Dispatchers.IO) { offlineEntry
            ?.let { OfflineMediaDownloads.playableUri(getApplication(), it.requestId, it.sourceUrl, it.downloadId) } }
        // Browse tiles and loaded pages already contain the provider command.
        // Do not search the provider again before every play; create_link is
        // the validation step for Stalker. Only rebuild the item after an
        // authorization/playback failure or when the command is absent.
        var item = if (forceFreshUrl) {
            portal.refreshPlaybackItem(session, requestedItem, type, series)
        } else {
            requestedItem.takeIf { offlineUrl != null || !it.command.isNullOrBlank() }
                ?: portal.refreshPlaybackItem(session, requestedItem, type, series)
        }
        // Xtream URLs are cheaply built from current credentials. Stalker links are session-bound.
        // Offline downloads never require a provider request, even when provider-first is selected.
        val url = offlineUrl ?: try {
            portal.playableUrl(session, item, type)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (container: com.nikhil.niktv.data.PortalVodSeriesContainerException) { throw container }
        catch (failure: Exception) {
            if (forceFreshUrl) throw failure
            item = portal.refreshPlaybackItem(session, item, type, series)
            portal.playableUrl(session, item, type)
        }
        Log.i(
            "NikTvPlayback",
            "Resolved ${type.name} item=${item.id} in ${SystemClock.elapsedRealtime() - playbackStartedAt}ms"
        )
        val activeQueueOwner = _state.value.nowPlaying?.takeIf { active ->
            active.catalogType == type && active.series?.id == series?.id &&
                active.media.seasonNumber == item.seasonNumber &&
                (queueFilter ?: active.queueFilter)?.copy(selectedOrdinal = 0) == active.queueFilter?.copy(selectedOrdinal = 0)
        }
        val playbackQueue = withContext(Dispatchers.Default) {
            val supplied = reconcilePlaybackQueueSnapshot(episodes, activeQueueOwner?.episodeQueue.orEmpty(), item.id)
            when (type) {
            CatalogType.SERIES -> supplied.filter {
                item.seasonNumber == null || it.seasonNumber == null || it.seasonNumber == item.seasonNumber
            }.sortedWith(
                compareBy<MediaItem>(
                    { it.seasonNumber ?: Int.MAX_VALUE },
                    { it.episodeNumber ?: it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE },
                    { it.title.lowercase() }
                )
            )
            CatalogType.LIVE_TV,
            CatalogType.MOVIES,
            CatalogType.RADIO -> playbackCatalogQueue(session, item, type, supplied)
        } }
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

        /*
         * PLAYER_PAGINATION_INIT_V16
         *
         * Preserve pagination while moving within the same playback queue.
         * If playback came from Home/sections rather than the selected browse
         * category, Stalker starts at page 1 and exposes Load More until the
         * server reports that no additional unique items exist.
         */
        val paginationSnapshot = _state.value

        val playbackScopeItem = if (item.portalCategoryId.isNullOrBlank()) {
            playbackQueue.firstOrNull { !it.portalCategoryId.isNullOrBlank() } ?: item
        } else item
        val newPlaybackScope =
            playbackQueueScope(
                session = session,
                item = playbackScopeItem,
                type = type,
                series = series,
                selectedSeriesSeason =
                    paginationSnapshot.selectedSeriesSeason
            )

        val preservePlaybackPagination =
            newPlaybackScope != null &&
                paginationSnapshot.playbackQueueScope ==
                    newPlaybackScope

        val selectedSeriesMatches =
            type == CatalogType.SERIES &&
                series != null &&
                paginationSnapshot.selectedSeries?.id ==
                    series.id

        val selectedCatalogMatches =
            type in setOf(
                CatalogType.LIVE_TV,
                CatalogType.MOVIES
            ) &&
                item.portalCategoryId != null &&
                paginationSnapshot.selectedType == type &&
                paginationSnapshot.selectedCategory?.id ==
                    item.portalCategoryId

        val cachedSeriesSeason = if (type == CatalogType.SERIES && series != null) {
            val sNum = item.seasonNumber ?: paginationSnapshot.selectedSeriesSeason
            episodeSeasonCaches.firstOrNull {
                it.profileKey == session.profile.cacheKey() &&
                    it.seriesId == series.id &&
                    (sNum == null || it.season == sNum)
            }
        } else null

        val hasEarlierEpisodes = type == CatalogType.SERIES &&
            playbackQueue.mapNotNull { it.episodeNumber }.minOrNull()?.let { it > 1 } == true

        val initialPlaybackPage =
            when {
                preservePlaybackPagination ->
                    paginationSnapshot.playbackQueuePage

                selectedSeriesMatches ->
                    paginationSnapshot.episodePage

                cachedSeriesSeason != null ->
                    cachedSeriesSeason.page

                selectedCatalogMatches ->
                    paginationSnapshot.catalogPage

                else ->
                    1
            }

        val initialPlaybackHasMore = queueFilter?.fixedIds == null &&
            when {
                preservePlaybackPagination ->
                    paginationSnapshot.playbackQueueHasMore

                selectedSeriesMatches ->
                    paginationSnapshot.episodeHasMore

                cachedSeriesSeason != null ->
                    cachedSeriesSeason.hasMore || hasEarlierEpisodes

                hasEarlierEpisodes ->
                    true

                selectedCatalogMatches ->
                    paginationSnapshot.catalogHasMore

                type == CatalogType.SERIES &&
                    session.profile.portalType ==
                        PortalType.STALKER &&
                    playbackQueue.isNotEmpty() ->
                    true

                type in setOf(
                    CatalogType.LIVE_TV,
                    CatalogType.MOVIES
                ) &&
                    session.profile.portalType ==
                        PortalType.STALKER &&
                    !item.portalCategoryId.isNullOrBlank() &&
                    playbackQueue.isNotEmpty() ->
                    true

                else ->
                    false
            }

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!latestPlaybackRequest.isCurrent(requestId) ||
            _state.value.session?.profile?.cacheKey() != session.profile.cacheKey() ||
            _state.value.session != session) return
        channelScheduleJob?.cancel()
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
                    queueFilter = queueFilter ?: paginationSnapshot.nowPlaying?.takeIf {
                        it.catalogType == type && it.episodeQueue.any { queued -> queued.id == item.id }
                    }?.queueFilter,
                    resumePositionMillis = resumePosition,
                    progressKey = progressKey,
                    profileKey = session.profile.cacheKey(),
                    authorizationRetryCount = authorizationRetryCount,
                    playWhenReady = if (forceFreshUrl) paginationSnapshot.nowPlaying?.playWhenReady ?: true else true,
                    decoderSessionId = paginationSnapshot.nowPlaying?.takeIf {
                        forceFreshUrl && it.media.id == item.id && it.catalogType == type
                    }?.decoderSessionId ?: java.util.UUID.randomUUID().toString(),
                    decoderRecoveryCount = paginationSnapshot.nowPlaying?.takeIf {
                        forceFreshUrl && it.media.id == item.id && it.catalogType == type
                    }?.decoderRecoveryCount ?: 0,
            directFullscreen = directFullscreen,
            offlinePlayback = offlineUrl != null,
            playbackFormat = offlineEntry?.fileType.orEmpty().ifBlank { mediaFormatLabel(url) }
                ),
                playbackReturnFocusId =
                    if (
                        forceFreshUrl ||
                        paginationSnapshot.nowPlaying != null
                    ) {
                        paginationSnapshot.playbackReturnFocusId
                            ?: item.id
                    } else {
                        item.id
                    },
                playbackQueueScope = newPlaybackScope,
                playbackQueuePage = initialPlaybackPage,
                playbackQueueHasMore = initialPlaybackHasMore,
                playbackQueueLoadingMore = false
            )
        }
        val committedIds = _state.value.nowPlaying?.let(::playbackNavigationQueue).orEmpty().map { it.id }
        if (queueFilter != null) navigationCursor.select(requestId, committedIds, item.id,
            committedIds.withIndex().filter { it.value == item.id }
                .getOrNull(queueFilter.selectedOrdinal)?.index)
        navigationCursor.commit(requestId, committedIds, item.id)
        playbackNavigationIndex = null
        if (type == CatalogType.LIVE_TV) requestSelectedLiveGuide(session, item)
        if (
            newPlaybackScope != null &&
            initialPlaybackHasMore &&
            type in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES)
        ) {
            val categoryId = playbackScopeItem.portalCategoryId
            if (!categoryId.isNullOrBlank()) {
                val category =
                    paginationSnapshot.rawCategoriesByType[type]
                        .orEmpty()
                        .firstOrNull { it.id == categoryId }
                        ?: Category(categoryId, "Playback queue", type)
                schedulePlaybackQueuePrefetch(
                    session = session,
                    scope = newPlaybackScope,
                    category = category,
                    currentPage = initialPlaybackPage,
                    existingIds = playbackQueue.mapTo(mutableSetOf()) { it.id }
                )
            }
        }
        recordRecent(item, type, series)
    }

    private suspend fun playbackCatalogQueue(
        session: PortalSession,
        item: MediaItem,
        type: CatalogType,
        supplied: List<MediaItem>
    ): List<MediaItem> {
        if (supplied.isNotEmpty()) return orderedPlayerQueue(supplied)

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
                    withAutomaticSessionRetry(session) { activeSession ->
                        portal.categories(activeSession, type).firstOrNull { it.id == categoryId }
                    }
                }.getOrNull()
            if (category != null) {
                val loaded = runCatching {
                    withAutomaticSessionRetry(session) { activeSession ->
                        portal.catalog(activeSession, category)
                    }
                }.getOrDefault(emptyList())
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

    /**
     * Replays a request once with a newly authenticated Stalker session.
     * Callers pass the active session into the operation so the replay cannot
     * accidentally reuse the rejected token captured by the first attempt.
     */
    private suspend fun <T> withAutomaticSessionRetry(
        initialSession: PortalSession,
        playbackRequestId: Long? = null,
        operation: suspend (PortalSession) -> T
    ): T {
        // A background coroutine may have captured the previous token before
        // another request refreshed it. Prefer the newest session for the same profile.
        val activeSession = _state.value.session
            ?.takeIf { it.profile.cacheKey() == initialSession.profile.cacheKey() }
            ?: initialSession
        return try {
            operation(activeSession)
        } catch (firstError: Throwable) {
            if (firstError is kotlinx.coroutines.CancellationException) throw firstError
            val snapshot = _state.value
            if (!snapshot.automaticReauthentication ||
                activeSession.profile.portalType != PortalType.STALKER ||
                !firstError.isAuthenticationFailure()
            ) {
                throw firstError
            }

            if (playbackRequestId == null || latestPlaybackRequest.isCurrent(playbackRequestId))
                _state.update { it.copy(reauthenticating = true) }
            val refreshed = try {
                sessionRefreshMutex.withLock {
                    _state.value.session
                        ?.takeIf {
                            it.profile.cacheKey() == activeSession.profile.cacheKey() &&
                                it.token != activeSession.token
                        }
                        ?: refreshSession(activeSession.profile)
                }
            } finally {
                if (playbackRequestId == null || latestPlaybackRequest.isCurrent(playbackRequestId))
                    _state.update { it.copy(reauthenticating = false) }
            }
            operation(refreshed)
        }
    }

    fun savePlaybackProgress(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank()) return
        val snapshot = _state.value
        val playing = snapshot.nowPlaying?.takeIf { it.progressKey == key }
        val profile = snapshot.session?.profile?.cacheKey()
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
            if (com.nikhil.niktv.data.meaningfulViewing(positionMillis, durationMillis)) {
                val previous = recommendationProgress.firstOrNull { it.key == key }
                if (previous == null || positionMillis / 60_000 != previous.positionMillis / 60_000 ||
                    (durationMillis > 0 && positionMillis >= durationMillis - 5_000)) {
                    store.saveRecommendationProgress(PlaybackProgress(key, positionMillis, durationMillis))
                }
                if (playing != null && playing.alternateSource == null && profile != null && dismissedRecommendationCategories[profile].orEmpty().isNotEmpty()) {
                    try {
                        val identity = when (playing.catalogType) {
                            CatalogType.MOVIES -> mediaIdentities.resolve(profile, CatalogType.MOVIES, playing.media)
                            CatalogType.SERIES -> playing.series?.let { mediaIdentities.resolve(profile, CatalogType.SERIES, it) }
                            CatalogType.LIVE_TV -> com.nikhil.niktv.data.liveProgrammeRecommendationItem(playing.media, System.currentTimeMillis())?.let { programme ->
                                com.nikhil.niktv.data.resolveLiveProgrammeRecommendation(programme) { type, item -> mediaIdentities.resolve(profile, type, item) }
                            }
                            else -> null
                        }
                        identity?.tmdbId?.let { store.restoreRecommendation(profile, "${identity.type}:$it") }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (error: Exception) { Log.w("NikTvRecommendations", "Could not restore watched recommendation source", error) }
                }
            }
        }
    }

    /*
     * MOVIE_LIVE_NAVIGATION_QUEUE_V8
     *
     * PlayingMedia.episodeQueue owns the selected browse context. Append matching
     * pages and reconcile metadata by ID, without adopting an unrelated browse list.
     */
    private fun playbackNavigationQueue(
        playing: PlayingMedia
    ): List<MediaItem> {
        val snapshot = _state.value
        // The committed queue owns navigation, not whichever browse screen updated last.
        val providerOrder = playing.episodeQueue.ifEmpty { listOf(playing.media) }.distinctBy { it.id }
        if (playing.catalogType != CatalogType.LIVE_TV) return providerOrder
        val profileKey = snapshot.session?.profile?.cacheKey()
            ?: snapshot.savedProfile?.cacheKey()
            ?: return providerOrder
        val categoryId = playing.media.portalCategoryId
            ?.takeIf { it.isNotBlank() }
            ?: snapshot.selectedCategory?.id
            ?: return providerOrder
        val pinnedOrder = playing.queueFilter?.pinnedIds ?: IptvPinPreferences.pinnedChannelOrder(
            getApplication(), profileKey, categoryId
        )
        return orderedPlayerQueue(providerOrder, pinnedOrder)
    }

    /**
     * Replace stale provider commands in a player queue after a catalog refresh.
     * The current resolved URL is deliberately retained; only a subsequent channel
     * selection requests a new signed playback URL.
     */
    private fun PlayingMedia.withRefreshedQueueItems(
        refreshedItemsById: Map<String, MediaItem>
    ): PlayingMedia {
        val refreshedQueue = episodeQueue.map { queued ->
            refreshedItemsById[queued.id] ?: queued
        }
        val refreshedCurrent = refreshedItemsById[media.id] ?: media
        val currentIndex = refreshedQueue.indexOfFirst { it.id == media.id }
        val wrap = catalogType == CatalogType.LIVE_TV && refreshedQueue.size > 1
        return copy(
            media = refreshedCurrent,
            episodeQueue = refreshedQueue,
            previousEpisode = if (currentIndex >= 0) {
                refreshedQueue.getOrNull(currentIndex - 1)
                    ?: refreshedQueue.lastOrNull().takeIf { wrap }
            } else {
                previousEpisode?.let { refreshedItemsById[it.id] ?: it }
            },
            nextEpisode = if (currentIndex >= 0) {
                refreshedQueue.getOrNull(currentIndex + 1)
                    ?: refreshedQueue.firstOrNull().takeIf { wrap }
            } else {
                nextEpisode?.let { refreshedItemsById[it.id] ?: it }
            }
        )
    }

    fun playNextEpisode() {
        val snapshot = _state.value
        val playing = snapshot.nowPlaying ?: return
        playing.alternateSource?.let { source ->
            source.target.episode?.takeIf { source.target.series }?.let { episode ->
                retryAlternatePlayback(0, playing, source.target.copy(episode = episode + 1))
            }
            return
        }
        val queue = playbackNavigationQueue(playing)

        val index = navigationCursor.index(queue.map { it.id }, playing.media.id)

        if (index < 0) return

        if (index == queue.lastIndex && snapshot.playbackQueueHasMore) {
            navigateAfterQueueBoundary(playing, queue, 1)
            return
        }

        val nextIndex = adjacentQueueIndex(index, queue.size, 1, playing.catalogType == CatalogType.LIVE_TV) ?: return
        val next = queue[nextIndex]

        playbackNavigationIndex = nextIndex
        val requestId = beginPlaybackRequest()
        navigationCursor.select(requestId, queue.map { it.id }, next.id, nextIndex)
        playbackTask(requestId) {
            playInternal(
                item = next,
                type = playing.catalogType,
                series = playing.series,
                episodes = playing.episodeQueue.ifEmpty { queue.distinctBy { it.id } },
                directFullscreen = playing.directFullscreen,
                requestId = requestId
            )
        }
    }

    fun playPreviousEpisode() {
        val playing = _state.value.nowPlaying ?: return
        playing.alternateSource?.let { source ->
            source.target.episode?.takeIf { source.target.series && it > 1 }?.let { episode ->
                retryAlternatePlayback(0, playing, source.target.copy(episode = episode - 1))
            }
            return
        }
        val queue = playbackNavigationQueue(playing)

        val index = navigationCursor.index(queue.map { it.id }, playing.media.id)

        if (index < 0) return

        if (playing.catalogType == CatalogType.SERIES && index == 0 &&
            _state.value.playbackQueueHasMore &&
            (queue.firstOrNull()?.let { it.episodeNumber ?: it.title.episodeOrderFromTitle() } ?: Int.MAX_VALUE) > 1) {
            navigateAfterQueueBoundary(playing, queue, -1)
            return
        }

        val previousIndex = adjacentQueueIndex(index, queue.size, -1, playing.catalogType == CatalogType.LIVE_TV) ?: return
        val previous = queue[previousIndex]

        playbackNavigationIndex = previousIndex
        val requestId = beginPlaybackRequest()
        navigationCursor.select(requestId, queue.map { it.id }, previous.id, previousIndex)
        playbackTask(requestId) {
            playInternal(
                item = previous,
                type = playing.catalogType,
                series = playing.series,
                episodes = playing.episodeQueue.ifEmpty { queue.distinctBy { it.id } },
                directFullscreen = playing.directFullscreen,
                requestId = requestId
            )
        }
    }

    /** A page result may extend the context, but only this still-current intent may navigate. */
    private fun navigateAfterQueueBoundary(playing: PlayingMedia, queue: List<MediaItem>, direction: Int) {
        val boundary = queueOccurrences(queue.map { it.id })
            .getOrNull(navigationCursor.index(queue.map { it.id }, playing.media.id)) ?: return
        autoAdvanceAfterQueueLoadJob?.cancel()
        val requestId = beginPlaybackRequest()
        navigationCursor.select(requestId, queue.map { it.id }, boundary.id,
            queueOccurrences(queue.map { it.id }).indexOf(boundary))
        val started = _state.value.playbackQueueLoadingMore || loadMorePlaybackQueue()
        autoAdvanceAfterQueueLoadJob = viewModelScope.launch {
            try {
                if (!started) return@launch
                _state.first { !it.playbackQueueLoadingMore }
                val snapshot = _state.value
                val active = snapshot.nowPlaying ?: return@launch
                if (!latestPlaybackRequest.isCurrent(requestId) ||
                    active.playbackAttemptId != playing.playbackAttemptId ||
                    active.media.seasonNumber != playing.media.seasonNumber) return@launch
                val updated = playbackNavigationQueue(active)
                val index = navigationCursor.index(updated.map { it.id }, active.media.id)
                val occurrence = queueOccurrences(updated.map { it.id }).getOrNull(index)
                if (occurrence != boundary) return@launch
                val canMove = adjacentQueueIndex(index, updated.size, direction,
                    active.catalogType == CatalogType.LIVE_TV && !snapshot.playbackQueueHasMore) != null
                if (canMove) {
                    if (direction > 0) playNextEpisode() else playPreviousEpisode()
                }
            } finally {
                if (latestPlaybackRequest.isCurrent(requestId)) {
                    latestPlaybackRequest.cancel()
                    _state.update { it.copy(loading = false) }
                }
                navigationCursor.finish(requestId)
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
            }, item, series, profileKey = profileKey,
                categoryTitle = _state.value.rawCategoriesByType[type]
                    .orEmpty().firstOrNull { it.id == item.portalCategoryId }?.title))
        }
        val updated = (additions + _state.value.recentlyPlayed.filterNot { it.kind == FavoriteKind.EPISODE })
            .distinctBy { it.key }.take(MAX_RECENT_ITEMS)
        _state.update { it.copy(recentlyPlayed = updated) }
        allRecentlyPlayed = allRecentlyPlayed.filterNot { it.profileKey == profileKey } + updated
        store.saveRecentlyPlayed(allRecentlyPlayed)
        if (type in setOf(CatalogType.MOVIES, CatalogType.SERIES)) viewModelScope.launch {
            try {
                mediaIdentities.resolve(profileKey, type, if (type == CatalogType.SERIES) series ?: item else item)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { /* Retry on a later metadata/history request. */ }
        }
        scheduleHistoryRecommendations()
    }

    fun closeSeries() {
        val returned = searchSeriesReturn?.restore(_state.value)
        searchSeriesReturn = null
        if (returned != null) {
            _state.value = returned
            return
        }
        closeSeriesBrowse()
    }

    private fun closeSeriesBrowse() = task {
        if (_state.value.seriesOpenedFromModernSection) {
            _state.update { current ->
                val category = current.modernIptvCategory
                val restoredItems = category?.let {
                    current.browseCachesByType[it.type]
                        ?.itemsByCategory
                        ?.get(it.id)
                }.orEmpty()
                current.copy(
                    selectedSeries = null,
                    useTmdbEpisodeMetadata = true,
                    seriesOpenedFromModernSection = false,
                    homeOpen = current.modernSectionOriginHome,
                    items = restoredItems,
                    availableSeriesSeasons = emptyList(),
                    selectedSeriesSeason = null
                )
            }
            return@task
        }
        if (_state.value.seriesOpenedFromHome) {
            _state.update { it.copy(selectedSeries = null, useTmdbEpisodeMetadata = true, seriesOpenedFromHome = false, homeOpen = true) }
            return@task
        }
        if (_state.value.seriesOpenedFromFavorites) {
            _state.update { it.copy(selectedSeries = null, useTmdbEpisodeMetadata = true, seriesOpenedFromFavorites = false, favoritesOpen = true) }
            return@task
        }
        val category = requireNotNull(_state.value.selectedCategory)
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedSeries = null, useTmdbEpisodeMetadata = true, items = emptyList()) }
        _state.update { it.copy(items = portal.catalog(session, category)) }
    }

    private fun cancelPendingPlayback(cancelGuide: Boolean = true) {
        closeAlternateSources()
        val hadPendingPlayback = latestPlaybackRequest.isRunning
        latestPlaybackRequest.cancel()
        if (hadPendingPlayback) _state.update { it.copy(loading = false) }
        navigationCursor.cancel()
        playbackNavigationIndex = null
        if (cancelGuide) channelScheduleJob?.cancel()
        autoAdvanceAfterQueueLoadJob?.cancel()
    }

    fun closePlayer() {
        releaseAlternatePlaybackLease()
        cinejoyHandoffJob?.cancel(); cinejoyHandoffJob = null
        // A guide can finish into the browse cache after Back; it never starts playback.
        cancelPendingPlayback(cancelGuide = false)
        val snapshot = _state.value
        val session = snapshot.session

        _state.update {
            it.copy(
                nowPlaying = null,
                playbackQueueScope = null,
                playbackQueuePage = 1,
                playbackQueueHasMore = false,
                playbackQueueLoadingMore = false
            )
        }


    }
    fun setPlaybackIntent(attemptId: String, requested: Boolean) {
        _state.update { state ->
            val playing = state.nowPlaying
            if (playing?.playbackAttemptId != attemptId || playing.playWhenReady == requested) state
            else state.copy(nowPlaying = playing.copy(playWhenReady = requested))
        }
    }

    fun reauthenticatePlayback(positionMillis: Long, failedPlayback: PlayingMedia) =
        retryPlayback(positionMillis, failedPlayback, reauthenticate = true)

    fun retryPlayback(positionMillis: Long, failedPlayback: PlayingMedia, reauthenticate: Boolean = false) {
        val playing = _state.value.nowPlaying ?: return
        if (pendingNavigationId != null || latestPlaybackRequest.isRunning || !samePlaybackAttempt(playing, failedPlayback)) return
        // An explicit Retry can start playback; a later pause while resolving is retained at commit.
        _state.update { it.copy(nowPlaying = playing.copy(playWhenReady = true)) }
        if (playing.alternateSource != null) { retryAlternatePlayback(positionMillis.coerceAtLeast(0), playing); return }
        val requestId = beginPlaybackRequest()
        viewModelScope.launch {
            if (!latestPlaybackRequest.isCurrent(requestId)) return@launch
            _state.update { it.copy(error = null) }
            runCatching {
                playInternal(
                    playing.media,
                    playing.catalogType,
                    playing.series,
                    playing.episodeQueue,
                    forceFreshUrl = true,
                    resumePositionOverride = positionMillis.coerceAtLeast(0L),
                    reauthenticate = reauthenticate,
                    directFullscreen = playing.directFullscreen,
                    requestId = requestId
                )
            }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException || !latestPlaybackRequest.isCurrent(requestId)) return@onFailure
                    _state.update { it.copy(error = error.message ?: "Playback retry failed") }
                }
        }
    }
    fun retryPlaybackWithAlternateDecoder(
        positionMillis: Long,
        failedPlayback: PlayingMedia
    ) {
        val playing =
            _state.value.nowPlaying
                ?: return

        if (pendingNavigationId != null || latestPlaybackRequest.isRunning ||
            !samePlaybackAttempt(playing, failedPlayback)) return

        if (!allowDecoderRecovery(playing.decoderRecoveryCount)) return
        val requestId = beginPlaybackRequest()
        viewModelScope.launch {
            latestPlaybackRequest.run(requestId) {
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
                                playbackAttemptId = java.util.UUID.randomUUID().toString(),
                                decoderRecoveryCount = playing.decoderRecoveryCount + 1,
                                resumePositionMillis =
                                    positionMillis
                                        .coerceAtLeast(0L)
                            )
                    )
                }
            }
        }
    }
    fun retryPlaybackAfterAuthorizationFailure(positionMillis: Long, failedPlayback: PlayingMedia) {
        val playing = _state.value.nowPlaying ?: return
        if (pendingNavigationId != null || latestPlaybackRequest.isRunning ||
            !samePlaybackAttempt(playing, failedPlayback)) return
        // Alternate hosts must never trigger portal reauthentication.
        if (playing.alternateSource != null) return
        if (playing.authorizationRetryCount > 0) return
        if (!_state.value.automaticReauthentication) {
            _state.update { it.copy(error = "Authorization failed.") }
            return
        }
        val requestId = beginPlaybackRequest()
        viewModelScope.launch {
            if (!latestPlaybackRequest.isCurrent(requestId)) return@launch
            /*
             * Keep PlayerScreen mounted while a rejected provider link is
             * refreshed. Clearing nowPlaying here returned the user to the
             * catalog for several seconds, so a successful automatic retry
             * looked like an ignored channel tap. playInternal replaces the
             * failed media URL when reauthentication completes, which already
             * recreates the player through its media.url key.
             */
            _state.update { it.copy(error = null) }
            runCatching {
                playInternal(
                    item = playing.media,
                    type = playing.catalogType,
                    series = playing.series,
                    episodes = playing.episodeQueue,
                    forceFreshUrl = true,
                    authorizationRetryCount = playing.authorizationRetryCount + 1,
                    resumePositionOverride = positionMillis,
                    directFullscreen = playing.directFullscreen,
                    requestId = requestId,
                    reauthenticate = true
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException || !latestPlaybackRequest.isCurrent(requestId)) return@onFailure
                _state.update { it.copy(error = error.message ?: "Could not refresh stream authorization") }
            }
        }
    }
    private var settingsReturnSurface: SettingsReturnSurface? = null
    fun openSettings() {
        if (!_state.value.settingsOpen) settingsReturnSurface = SettingsReturnSurface.capture(_state.value)
        cancelPendingPlayback()
        _state.update {
            it.copy(settingsOpen = true, offlineDownloadsOpen = false, searchOpen = false, favoritesOpen = false, homeOpen = false)
        }
    }
    fun openSettingsFromProfileChooser(profile: PortalProfile? = null) {
        settingsReturnSurface = null
        _state.update {
            it.copy(savedProfile = profile, settingsOpen = true, profileEditorOpen = false,
                searchOpen = false, favoritesOpen = false, offlineDownloadsOpen = false, homeOpen = false)
        }
    }

    fun setPreconfiguredProfileEnabled(profile: PortalProfile, enabled: Boolean) = viewModelScope.launch {
        if (enabled) store.addProfile(profile) else store.removeProfile(profile)
        val profiles = store.profiles.first()
        _state.update { current ->
            current.copy(
                profiles = profiles,
                savedProfile = current.savedProfile?.takeIf { active ->
                    profiles.any { it.cacheKey() == active.cacheKey() }
                }
            )
        }
    }
    fun closeSettings() {
        val origin = settingsReturnSurface
        settingsReturnSurface = null
        _state.update { origin?.restore(it) ?: it.copy(settingsOpen = false) }
    }
    fun reauthenticate() {
        val profile = _state.value.savedProfile ?: return
        if (_state.value.reauthenticating) return
        viewModelScope.launch {
            _state.update { it.copy(reauthenticating = true) }
            runCatching { refreshSession(profile) }
                .onSuccess {
                    // Refreshing a token must not rebuild catalogs, TMDB rows,
                    // or navigation state. Removing the overlay reveals the
                    // exact screen that was underneath it.
                    _state.update { current -> current.copy(error = null, reauthenticating = false) }
                }
                .onFailure { failure ->
                    _state.update { current ->
                        current.copy(
                            error = failure.message ?: "Re-authentication failed",
                            reauthenticating = false
                        )
                    }
                }
        }
    }
    fun editProfile() = _state.update { it.copy(session = null, settingsOpen = false, profileEditorOpen = true) }
    fun addProfile() = _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false, profileEditorOpen = true) }
    fun openProfileSwitcher() { _state.update { it.copy(profileSwitcherOpen = true) } }
    fun closeProfileSwitcher() { _state.update { it.copy(profileSwitcherOpen = false) } }
    /** Return to the same chooser used on launch; keep saved profiles, sessions and libraries intact. */
    fun openProfileChooser() {
        closePlayer()
        profilePreparationJob?.cancel()
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()
        movieStructureJob?.cancel()
        cancelEpisodeDestination()
        episodePageJob?.cancel()
        episodeMetadataRefreshJob?.cancel()
        singleEpisodeMetadataJob?.cancel()
        discoveryMetadataRequests.cancel()
        _state.update { it.copy(session = null, profileSwitcherOpen = false, profileEditorOpen = false,
            settingsOpen = false, offlineDownloadsOpen = false, searchOpen = false, favoritesOpen = false,
            selectedSeries = null, providerMovieDetails = null, nowPlaying = null, loading = false,
            restoring = false, error = null, profileLoadProgress = null, episodeTmdbRefresh = null) }
    }
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching {
            com.nikhil.niktv.data.BackupActivityLog.track(getApplication(), "Profile export · Device") {

            val content = store.exportBackup()
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                it.write(content)
            }
            }
        }.onSuccess {
            _state.update { it.copy(backupMessage = "NikTV backup exported") }
        }.onFailure { error ->
            _state.update { it.copy(backupMessage = "Could not export backup: ${error.message}") }
        }
    }
    fun importBackup(uri: Uri) = viewModelScope.launch {
        runCatching {
            com.nikhil.niktv.data.BackupActivityLog.track(getApplication(), "Profile restore · Device") {

            val content = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            store.importBackup(content)
            }
        }.onSuccess {
            _state.value = restoredDeviceBackupState()
        }.onFailure { error ->
            _state.update { it.copy(backupMessage = "Could not import backup: ${error.message}") }
        }
    }
    fun importBackupContent(content: String) = viewModelScope.launch {
        runCatching {
            com.nikhil.niktv.data.BackupActivityLog.track(getApplication(), "Profile restore · GitHub") {

            store.importBackup(content)
            }
        }.onSuccess {
            _state.value = restoredDeviceBackupState()
        }.onFailure { error ->
            _state.update {
                it.copy(
                    backupMessage = "Could not import backup: ${error.message}"
                )
            }
        }
    }
    private suspend fun restoredDeviceBackupState(): NikTvState {
        val app = getApplication<Application>()
        com.nikhil.niktv.data.GitHubBackupScheduler.initialize(app)
        com.nikhil.niktv.data.SearchMetadataSyncScheduler.initialize(app)
        AppUpdates.refreshPreferences()
        val profiles = store.profiles.first()
        return NikTvState(
            parentalControlsEnabled = com.nikhil.niktv.data.ParentalControls.enabled(app),
            parentalRelockMinutes = com.nikhil.niktv.data.ParentalControls.relockMinutes(app),
            profiles = profiles, savedProfile = store.activeProfile.first(), profileEditorOpen = false, restoring = false,
            favorites = store.favorites.first(), recentlyPlayed = store.recentlyPlayed.first(),
            playbackProgress = store.playbackProgress.first(), playbackEngine = store.playbackEngine.first(),
            seriesStartSeason = store.seriesStartSeason.first(), cacheIntervalMinutes = store.cacheIntervalMinutes.first(),
            initialCatalogItems = store.initialCatalogItems.first(), playerControlsTimeoutSeconds = store.playerControlsTimeoutSeconds.first(),
            automaticReauthentication = store.automaticReauthentication.first(),
            keepAwakeOnlyDuringPlayback = store.keepAwakeOnlyDuringPlayback.first(),
            backupMessage = "Setup restored. Choose a profile to authenticate; caches will rebuild."
        )
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
        if (snapshot.rawCategoriesByType[type]?.isNotEmpty() == true) {
            if (categoryMetadataExpired(snapshot.browseCachesByType[type]?.categoriesCachedAtMillis ?: 0L,
                    System.currentTimeMillis(), snapshot.cacheIntervalMinutes)) refreshProviderCategories(type, userRequested = false)
            return
        }
        val session = snapshot.session ?: return
        viewModelScope.launch {
            val cached = store.browseCatalog(type, session.profile.cacheKey()).first()?.categories.orEmpty()
            val categories = if (cached.isNotEmpty()) cached else runCatching {
                withAutomaticSessionRetry(session) { activeSession ->
                    portal.categories(activeSession, type)
                }
            }.getOrDefault(emptyList())
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
        store.saveCategoryFilter(profileKey, type, enabledCategoryIds.distinct())
    }

    fun applyCategoryFilters(selections: Map<CatalogType, List<String>>) = viewModelScope.launch {
        val snapshot = _state.value
        val session = snapshot.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val normalized = selections.mapValues { (_, ids) ->
            ids.distinct()
        }
        if (normalized.isEmpty()) return@launch

        val filterUpdates =
            normalized.mapKeys { (type, _) ->
                filterKey(profileKey, type)
            }

        // Modern Home can configure Movies/Series while selectedType points at
        // another catalog. Update every submitted filter immediately instead
        // of waiting for the DataStore collector/active-type branch.
        _state.update {
            it.copy(
                categoryFilters = it.categoryFilters + filterUpdates,
                feedRefreshing = false,
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
        val currentEnabled = _state.value.categoryFilters[filterKey] ?: raw.take(10).map { it.id }
        val updated = if (categoryId in currentEnabled) {
            currentEnabled - categoryId
        } else {
            currentEnabled + categoryId
        }
        setCategoryFilter(type, updated)
    }
    fun removeCategoryFilter(type: CatalogType, categoryId: String) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val raw = _state.value.rawCategoriesByType[type].orEmpty()
        val key = filterKey(profileKey, type)
        val currentEnabled = _state.value.categoryFilters[key] ?: raw.take(10).map { it.id }
        if (categoryId !in currentEnabled) return@launch
        val updated = currentEnabled - categoryId
        _state.update { it.copy(categoryFilters = it.categoryFilters + (key to updated)) }
        store.saveCategoryFilter(profileKey, type, updated)
    }
    fun selectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val allCategories = _state.value.rawCategoriesByType[type].orEmpty().map { it.id }
        store.saveCategoryFilter(profileKey, type, allCategories)
    }
    fun deselectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.saveCategoryFilter(profileKey, type, emptyList())
    }

    override fun onCleared() {
        releaseAlternatePlaybackLease()
        portal.close()
        com.nikhil.niktv.data.CatalogPlaybackActivity.playing = false
        super.onCleared()
    }

    private suspend fun authenticateProfileForOpening(profile: PortalProfile): PortalSession =
        withTimeoutOrNull(20_000L) { portal.authenticate(profile) }
            ?: throw IllegalStateException("${profile.name} did not finish authentication within 20 seconds. Check the provider connection and try again.")

    private suspend fun localFirstSession(profile: PortalProfile): PortalSession {
        val cached = if (com.nikhil.niktv.data.CatalogPreferences.preferLocal(getApplication())) store.sessionFor(profile) else null
        if (cached != null) {
            viewModelScope.launch {
                try {
                    val fresh = portal.authenticate(profile)
                    if (_state.value.savedProfile?.cacheKey() == profile.cacheKey()) {
                        store.save(fresh)
                        _state.update { if (it.session?.profile?.cacheKey() == profile.cacheKey()) it.copy(session = fresh) else it }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Browsing remains available; playback can reauthenticate. */ }
            }
            return cached.copy(profile = profile)
        }
        updateProfileLoad(0.12f, "Connecting to ${profile.name}; waiting for provider authentication…")
        return authenticateProfileForOpening(profile).also { store.save(it) }
    }
    fun switchProfile(profile: PortalProfile) = task {
        movieStructureJob?.cancel()
        categoryMetadataRefreshJobs.values.toList().forEach { it.cancel() }
        categoryMetadataRefreshJobs.clear()
        searchSeriesReturn = null
        closeAlternateSources()
        closeProfileSwitcher()
        profilePreparationJob?.cancel()
        searchPreviewJob?.cancel()
        searchServerJob?.cancel()
        catalogueTitleIndex = null
        _state.update { it.copy(savedProfile = profile, providerMovieDetails = null) }
        updateProfileLoad(0.04f, "Loading ${profile.name}…")
        store.activate(profile)
        // Reuse a saved session for local browsing while renewing it in the background.
        updateProfileLoad(0.08f, "Reading saved session for ${profile.name}…")
        val session = localFirstSession(profile)
        updateProfileLoad(0.24f, "Authentication complete")
        _state.update { current -> current.copy(session = session, savedProfile = profile, settingsOpen = false, profileEditorOpen = false,
            modernIptvCategory = null, modernTmdbSection = null, selectedCategory = null, playbackReturnFocusId = null,
            searchQuery = "", searchResults = emptyList(), searchLocalLoading = false, searchServerLoading = false,
            searchOpen = false, favoritesOpen = false, homeOpen = true,
            watchProvidersOpen = false, discoverySurface = DashboardSurface.HOME, offlineDownloadsOpen = false,
            categories = emptyList(), rawCategoriesByType = emptyMap(),
            categoryManagerOpen = false, items = emptyList(),
            selectedSeries = null, browseCache = null, browseCachesByType = emptyMap(), fullSearchItems = null, playbackUrls = emptyList(),
            trendingMovies = emptyList(), trendingMoviesLoading = false, trendingMoviesError = null,
            trendingSeries = emptyList(), trendingSeriesLoading = false, trendingSeriesError = null,
            thrillerMovies = emptyList(), thrillerMoviesLoading = false, thrillerMoviesError = null) }
        updateProfileLoad(0.26f, "Loading saved favorites and watch history…")
        loadProfileLibrary(session.profile.cacheKey())
        prepareDashboardInBackground(session)
        SearchMetadataSyncScheduler.configureProfile(getApplication(), session.profile)
    }
    fun removeProfile(profile: PortalProfile) = viewModelScope.launch {
        store.removeProfile(profile)
        if (_state.value.savedProfile?.cacheKey() == profile.cacheKey()) {
            _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false) }
        }
    }
    fun openFavorites() {
        searchSeriesReturn = null
        cancelPendingPlayback()
        _state.update {
            it.copy(
                favoritesOpen = true,
                homeOpen = false,
                settingsOpen = false,
                searchOpen = false
                ,offlineDownloadsOpen = false
            )
        }
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return
        viewModelScope.launch {
            enrichFavoriteSeriesPresentation(profileKey)
        }
        viewModelScope.launch {
            refreshFavoriteEpisodeCachesIfDue(profileKey)
        }
    }
    fun closeFavorites() = _state.update { it.copy(favoritesOpen = false) }
    fun openOfflineDownloads() {
        searchSeriesReturn = null
        cancelPendingPlayback()
        _state.update {
            it.copy(
                offlineDownloadsOpen = true,
                favoritesOpen = false,
                homeOpen = false,
                settingsOpen = false,
                searchOpen = false,
                selectedSeries = null
            )
        }
    }
    fun closeOfflineDownloads() = _state.update { it.copy(offlineDownloadsOpen = false) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playOfflineDownload(entry: OfflineMediaDownload) {
        val protectedMedia = entry.media.copy(isAdult = entry.media.isAdult || entry.series?.isAdult == true ||
            com.nikhil.niktv.data.ParentalControls.adultCategory(getApplication(), entry.profileKey, entry.catalogType,
                entry.series?.portalCategoryId ?: entry.media.portalCategoryId))
        if (gateAdultAction(protectedMedia, entry.catalogType) { playOfflineDownload(entry) }) return
        cancelPendingPlayback()
        val uri = OfflineMediaDownloads.playableUri(getApplication(), entry.requestId, entry.sourceUrl, entry.downloadId)
        if (uri == null) {
            _state.update { it.copy(error = "This download is not complete yet.") }
            return
        }
        val progressKey = "${entry.catalogType.name}:${entry.media.id}"
        val savedProgress = _state.value.playbackProgress.firstOrNull { it.key == progressKey }
        _state.update {
            it.copy(
                nowPlaying = PlayingMedia(
                    media = entry.media,
                    url = uri,
                    catalogType = entry.catalogType,
                    series = entry.series,
                    resumePositionMillis = savedProgress?.positionMillis ?: 0L,
                    progressKey = progressKey,
                    directFullscreen = true,
                    offlinePlayback = true,
                    profileKey = entry.profileKey
                    ,playbackFormat = entry.fileType.ifBlank { mediaFormatLabel(entry.sourceUrl) }
                )
            )
        }
    }
    fun playRecording(recording: com.nikhil.niktv.data.RecordedLiveTvMedia) {
        val media = MediaItem("recording:${recording.uri}", recording.title, null, null,
            isAdult = com.nikhil.niktv.data.adultContentLabel(recording.title))
        if (gateAdultAction(media, CatalogType.MOVIES) { playRecording(recording) }) return
        cancelPendingPlayback()
        _state.update { it.copy(nowPlaying = PlayingMedia(
            media = media, url = recording.uri.toString(), catalogType = CatalogType.MOVIES,
            directFullscreen = true, offlinePlayback = true, playbackFormat = "MPEG-TS"
        )) }
    }
    fun openHome() = openDiscovery(DashboardSurface.HOME)
    fun closeHome() = _state.update { it.copy(homeOpen = false) }

    /** Discovery navigation never resolves provider titles in the background. */

    fun openDiscovery(surface: DashboardSurface) {
        searchSeriesReturn = null
        cancelPendingPlayback()
        _state.update { it.copy(discoverySurface = surface, watchProvidersOpen = false,
            discoveryNavigationId = it.discoveryNavigationId + 1, homeOpen = true, favoritesOpen = false,
            settingsOpen = false, searchOpen = false, offlineDownloadsOpen = false,
            selectedSeries = null, modernTmdbSection = null, modernIptvCategory = null) }
    }
    fun openWatchProviders() {
        openDiscovery(DashboardSurface.HOME)
        _state.update { it.copy(watchProvidersOpen = true) }
    }
    fun closeDiscovery() = _state.update { it.copy(discoverySurface = null) }

    private data class PendingDiscoveryEpisode(val card: com.nikhil.niktv.discovery.DiscoveryCard,
        val season: Int, val number: Int, val profile: String, val request: Long, val download: Boolean = false)
    private var pendingDiscoveryEpisode: PendingDiscoveryEpisode? = null

    internal fun openDiscoveryEpisode(card: com.nikhil.niktv.discovery.DiscoveryCard, season: Int, episode: Int) =
        requestDiscoveryEpisode(card, season, episode, false)
    internal fun downloadDiscoveryEpisode(card: com.nikhil.niktv.discovery.DiscoveryCard, season: Int, episode: Int) =
        requestDiscoveryEpisode(card, season, episode, true)
    private fun requestDiscoveryEpisode(card: com.nikhil.niktv.discovery.DiscoveryCard, season: Int, episode: Int, download: Boolean) {
        val session = _state.value.session ?: return
        val requestId = beginPlaybackRequest()
        val intent = PendingDiscoveryEpisode(card, season, episode, session.profile.cacheKey(), requestId, download)
        pendingDiscoveryEpisode = intent
        playbackTask(requestId) {
            val saved = store.tmdbMappings.first().firstOrNull {
                it.profileKey == intent.profile && it.type == CatalogType.SERIES && it.tmdbId == card.id &&
                    (it.confirmedByUser || it.media.externalTmdbId == card.id)
            }
            val candidates = saved?.let { listOf(it.media) }
                ?: resolveInitialTmdbSeriesCandidates(session, TrendingSeries(card.show()))
            if (!latestPlaybackRequest.isCurrent(intent.request) || _state.value.session?.profile?.cacheKey() != intent.profile) return@playbackTask
            if (candidates.size > 1) {
                _state.update { it.copy(seriesMatchSelection = card.show(), seriesMatchCandidates = candidates) }
            } else {
                val series = candidates.singleOrNull() ?: resolveTmdbSeriesMovieFallback(session, card.show())
                if (series == null) {
                    val show = card.show().asMediaItem()
                    showTmdbIptvLookupMiss(AlternateSourceRequest(show.copy(seasonNumber = season, episodeNumber = episode),
                        CatalogType.SERIES, show, season, episode), intent.profile, intent.request)
                    return@playbackTask
                }
                playDiscoveryEpisode(intent, series, confirmedByUser = saved?.confirmedByUser == true)
            }
        }
    }

    private suspend fun playDiscoveryEpisode(intent: PendingDiscoveryEpisode, resolved: MediaItem, confirmedByUser: Boolean) {
        if (!latestPlaybackRequest.isCurrent(intent.request)) return
        val session = _state.value.session?.takeIf { it.profile.cacheKey() == intent.profile } ?: return
        if (isAdultMedia(resolved, CatalogType.SERIES) && !authorizeParental()) return
        if (!latestPlaybackRequest.isCurrent(intent.request) || _state.value.session?.profile?.cacheKey() != intent.profile) return
        val series = resolved.withPreferredSeriesPresentation(intent.card.show())
        val episodes = withContext(Dispatchers.IO) {
            portal.episodeSeason(session, series, _state.value.seriesStartSeason, intent.season).episodes
        }
        val selected = com.nikhil.niktv.discovery.discoveryEpisodeMatch(episodes, intent.season, intent.number)
            ?: error("Season ${intent.season} episode ${intent.number} could not be matched uniquely. Open Episodes to choose the provider episode.")
        if (!latestPlaybackRequest.isCurrent(intent.request) || _state.value.session?.profile?.cacheKey() != intent.profile) return
        pendingDiscoveryEpisode = null
        _state.update { it.copy(seriesMatchSelection = null, seriesMatchCandidates = emptyList()) }
        store.saveTmdbMapping(TmdbIptvMapping(intent.profile, CatalogType.SERIES, intent.card.id, series, confirmedByUser = confirmedByUser))
        if (!latestPlaybackRequest.isCurrent(intent.request) || _state.value.session?.profile?.cacheKey() != intent.profile) return
        if (intent.download) downloadForOffline(selected, CatalogType.SERIES, series)
        else playInternal(selected, CatalogType.SERIES, series, episodes, requestId = intent.request)
    }

    fun toggleFavorite(item: MediaItem) {
        val snapshot = _state.value
        val kind = when {
            snapshot.selectedType == CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
            snapshot.selectedType == CatalogType.MOVIES -> FavoriteKind.MOVIE
            snapshot.selectedSeries?.id == item.id -> FavoriteKind.SERIES
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

    private fun openFavoriteQueue(item: MediaItem, type: CatalogType, queue: List<MediaItem>) {
        val requestId = beginPlaybackRequest()
        playbackNavigationIndex = null
        playbackTask(requestId) {
            playInternal(item, type, null, queue, requestId = requestId,
                queueFilter = PlaybackQueueFilter(fixedIds = queue.mapTo(mutableSetOf()) { it.id }, pinnedIds = emptyList()))
        }
    }

    fun openFavorite(favorite: FavoriteItem) {
        searchSeriesReturn = null
        val type = when (favorite.kind) {
            FavoriteKind.CHANNEL -> CatalogType.LIVE_TV
            FavoriteKind.MOVIE -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        val protectedMedia = favorite.media.copy(isAdult = favorite.media.isAdult ||
            com.nikhil.niktv.data.adultContentLabel(favorite.categoryTitle.orEmpty()) || favorite.series?.isAdult == true)
        if (gateAdultAction(protectedMedia, type) { openFavorite(favorite) }) return
        if (favorite.source == FavoriteSource.TMDB && favorite.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES) && favorite.media.id.startsWith("tmdb-")) {
            val id = favorite.media.externalTmdbId ?: favorite.media.id.substringAfterLast(':').toIntOrNull()
            if (id != null) {
                if (favorite.kind == FavoriteKind.MOVIE) openTrendingMovie(TrendingMovie(TmdbMovie(id,
                    favorite.media.title, favorite.media.title, favorite.media.description, favorite.media.logo,
                    null, null, null)))
                else openTrendingSeries(TrendingSeries(TmdbSeries(id, favorite.media.title, favorite.media.title,
                    favorite.media.description, favorite.media.logo, null, null, null)))
                return
            }
        }
        when (favorite.kind) {
            FavoriteKind.CHANNEL, FavoriteKind.MOVIE -> {
                val queue = _state.value.favorites.filter { it.kind == favorite.kind }.map { it.media }
                openFavoriteQueue(favorite.media, type, queue)
            }
            FavoriteKind.EPISODE -> {
                val requestId = beginPlaybackRequest()
                playbackTask(requestId) {
                val episodes = favorite.series?.let { portal.episodeSeason(requireNotNull(_state.value.session), it, _state.value.seriesStartSeason, favorite.media.seasonNumber).episodes }.orEmpty()
                playInternal(favorite.media, CatalogType.SERIES, favorite.series, episodes, requestId = requestId)
                }
            }
            FavoriteKind.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                val profileKey = session.profile.cacheKey()
                val tmdbId = favorite.media.favoriteTmdbSeriesId()
                val mapping = store.tmdbMappings.first().firstOrNull { saved ->
                    saved.profileKey == profileKey &&
                        saved.type == CatalogType.SERIES &&
                        (saved.media.id == favorite.media.id ||
                            (tmdbId != null && saved.tmdbId == tmdbId))
                }
                val providerSeries = (mapping?.media ?: favorite.media)
                    .withFavoriteSeriesPresentation(favorite.media)
                _state.update { it.copy(favoritesOpen = false, selectedType = CatalogType.SERIES, selectedSeries = providerSeries, playbackReturnFocusId = null, seriesOpenedFromFavorites = true, items = emptyList(), availableSeriesSeasons = emptyList(), selectedSeriesSeason = null) }
                loadSeriesEpisodes(providerSeries)
            }
        }
    }

    fun openSeriesFromHome(series: MediaItem) = task {
        searchSeriesReturn = null
        discoveryMetadataRequests.cancel()
        if (isAdultMedia(series, CatalogType.SERIES) && !authorizeParental()) return@task
        val session = requireNotNull(_state.value.session)
        val profileKey = session.profile.cacheKey()
        val tmdbId = series.favoriteTmdbSeriesId()
        val mapping = store.tmdbMappings.first().firstOrNull { saved ->
            saved.profileKey == profileKey &&
                saved.type == CatalogType.SERIES &&
                (saved.media.id == series.id ||
                    (tmdbId != null && saved.tmdbId == tmdbId))
        }
        val providerSeries = (mapping?.media ?: series)
            .withFavoriteSeriesPresentation(series)
        _state.update {
            it.copy(
                homeOpen = false,
                favoritesOpen = false,
                selectedType = CatalogType.SERIES,
                selectedSeries = providerSeries,
                playbackReturnFocusId = null,
                seriesOpenedFromHome = true,
                seriesOpenedFromFavorites = false,
                items = emptyList(),
                availableSeriesSeasons = emptyList(),
                selectedSeriesSeason = null
            )
        }
        loadSeriesEpisodes(providerSeries)
    }

    /** Returns a playable season queue immediately from cache, fetching it only on a cache miss. */
    private suspend fun recentEpisodeQueue(
        session: PortalSession,
        series: MediaItem,
        season: Int?
    ): List<MediaItem> {
        val cached = episodeSeasonCaches.firstOrNull { cache ->
            cache.profileKey == session.profile.cacheKey() &&
                cache.seriesId == series.id &&
                (season == null || cache.season == season)
        }
        if (cached != null) return cached.episodesForDisplay(_state.value.useTmdbEpisodeMetadata)

        val loaded = portal.episodeSeason(session, series, _state.value.seriesStartSeason, season)
        val raw = loaded.episodes.distinctBy { it.id }
        val cache = EpisodeSeasonCache(
            profileKey = session.profile.cacheKey(),
            seriesId = series.id,
            season = loaded.selectedSeason,
            availableSeasons = loaded.availableSeasons,
            episodes = raw,
            page = loaded.page,
            hasMore = loaded.hasMore,
            metadataVersion = EPISODE_METADATA_VERSION,
            iptvEpisodes = raw,
            seasonIndexValidated = true
        )
        cacheSeason(cache)
        launchEpisodeMetadataEnrichment(series, cache, force = false)
        return raw
    }

    fun openRecent(recent: RecentItem) {
        recent.alternateTarget?.let { target ->
            if (recent.profileKey != alternateProfile()) return
            openAlternateSourcesInternal(AlternateSourceRequest(recent.lastPlayed ?: recent.media,
                if (target.series) CatalogType.SERIES else CatalogType.MOVIES,
                recent.media.takeIf { target.series }, target.season, target.episode), target)
            return
        }

        val type = when (recent.kind) {
            FavoriteKind.CHANNEL -> CatalogType.LIVE_TV
            FavoriteKind.MOVIE -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        if (gateAdultAction(recent.series ?: recent.media, type) { openRecent(recent) }) return
        val requestId = beginPlaybackRequest()
        when (recent.kind) {
            FavoriteKind.CHANNEL -> playbackTask(requestId) {
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
                    playInternal(recent.media, CatalogType.LIVE_TV, null, channels, requestId = requestId)
                } else {
                    playInternal(recent.media, CatalogType.LIVE_TV, null, listOf(recent.media), requestId = requestId)
                }
            }
            FavoriteKind.MOVIE -> play(recent.media, CatalogType.MOVIES)
            FavoriteKind.EPISODE -> playbackTask(requestId) {
                val session = requireNotNull(_state.value.session)
                val episodes = recent.series?.let { series ->
                    runCatching { recentEpisodeQueue(session, series, recent.media.seasonNumber) }
                        .getOrDefault(emptyList())
                }.orEmpty().ifEmpty { listOf(recent.media) }
                playInternal(recent.media, CatalogType.SERIES, recent.series, episodes, requestId = requestId)
            }
            FavoriteKind.SERIES -> playbackTask(requestId) {
                val session = requireNotNull(_state.value.session)
                val savedEpisode = recent.lastPlayed
                val episodes = savedEpisode?.let { episode ->
                    runCatching { recentEpisodeQueue(session, recent.media, episode.seasonNumber) }
                        .getOrDefault(emptyList())
                }.orEmpty().ifEmpty { listOfNotNull(savedEpisode) }
                val resumeEpisode = savedEpisode?.let { saved -> episodes.firstOrNull { it.id == saved.id } ?: saved }
                if (resumeEpisode != null) playInternal(resumeEpisode, CatalogType.SERIES, recent.media, episodes, requestId = requestId)
                else {
                    _state.update { it.copy(homeOpen = false, selectedType = CatalogType.SERIES, selectedSeries = recent.media, playbackReturnFocusId = null, seriesOpenedFromFavorites = false, seriesOpenedFromHome = true, items = episodes) }
                }
            }
        }
    }
    fun removeRecent(recent: RecentItem) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        if (recent.profileKey != profileKey) return@launch
        val updated = _state.value.recentlyPlayed.filterNot { it.contentIdentity() == recent.contentIdentity() }
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

        if (existing != null) {
            val updated = snapshot.watchedSeries.filterNot { it.key == existing.key }
            _state.update { it.copy(watchedSeries = updated) }
            allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
            store.saveWatchedSeries(allWatchedSeries)
            return@launch
        }

        // Make the bell/watchlist state responsive immediately. The current
        // episode list is a safe local baseline while the latest-season
        // snapshot is refreshed from the provider in the background.
        val now = System.currentTimeMillis()
        val optimistic = WatchedSeries(
            profileKey = profileKey,
            series = series,
            categoryTitle = snapshot.selectedCategory?.title,
            knownEpisodeIds = snapshot.items.map { it.id }.toSet(),
            checkedAtMillis = now
        )
        val updated = snapshot.watchedSeries + optimistic
        _state.update { it.copy(watchedSeries = updated) }
        allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
        store.saveWatchedSeries(allWatchedSeries)

        val latest = runCatching {
            portal.episodeSeason(session, series, SeriesStartSeason.LAST)
        }.getOrNull() ?: return@launch

        // Do not revive the watch entry if the user toggled it off or changed
        // profiles while the provider request was in flight.
        if (_state.value.session?.profile?.cacheKey() != profileKey) return@launch
        val current = _state.value.watchedSeries.firstOrNull { it.key == optimistic.key }
            ?: return@launch
        val refreshed = current.copy(
            knownEpisodeIds = current.knownEpisodeIds + latest.episodes.map { it.id },
            checkedAtMillis = System.currentTimeMillis()
        )
        val refreshedList = _state.value.watchedSeries.map { watched ->
            if (watched.key == optimistic.key) refreshed else watched
        }
        _state.update { it.copy(watchedSeries = refreshedList) }
        allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + refreshedList
        store.saveWatchedSeries(allWatchedSeries)
    }

    private suspend fun refreshWatchedSeriesIfDue(force: Boolean = false) {
        if (!watchRefreshMutex.tryLock()) return
        try {
        val session = _state.value.session ?: return
        val profileKey = session.profile.cacheKey()
        // New-episode notifications should not wait for a long catalogue cache
        // lifetime. Keep the user's shorter setting, but check subscriptions at
        // least every fifteen minutes while the profile is active.
        val interval = minOf(
            _state.value.cacheIntervalMinutes * 60_000L,
            WATCHED_SERIES_REFRESH_MILLIS
        )
        val now = System.currentTimeMillis()
        val scoped = allWatchedSeries.filter { it.profileKey == profileKey }
        scoped.forEachIndexed { index, watched ->
            if (!force && now - watched.checkedAtMillis < interval) return@forEachIndexed
            val latest = runCatching {
                withAutomaticSessionRetry(session) { activeSession ->
                    portal.episodeSeason(activeSession, watched.series, SeriesStartSeason.LAST)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(
                    "NikTvWatchedSeries",
                    "Could not check ${watched.series.id} for new episodes; it remains due for retry",
                    error
                )
            }.getOrNull()
                ?: return@forEachIndexed
            val discovery = store.recordEpisodeDiscovery(watched.key, latest.episodes, now)
                ?: return@forEachIndexed
            val discovered = discovery.added
            Log.i(
                "NikTvWatchedSeries",
                "Checked ${watched.series.id}: latest=${latest.episodes.size} new=${discovered.size} forced=$force"
            )
            com.nikhil.niktv.data.NewEpisodeNotifications.post(getApplication(), session.profile.name, discovery)
            if (index < scoped.lastIndex) delay(1_200L)
        }
        } finally {
            watchRefreshMutex.unlock()
        }
    }

    fun openWatchedEpisode(watched: WatchedSeries, episode: MediaItem) {
        if (gateAdultAction(watched.series, CatalogType.SERIES) { openWatchedEpisode(watched, episode) }) return
        val requestId = beginPlaybackRequest()
        playbackTask(requestId) {
            val session = requireNotNull(_state.value.session)
            val queue = portal.episodeSeason(session, watched.series, _state.value.seriesStartSeason, episode.seasonNumber).episodes
            val updated = _state.value.watchedSeries.map {
                if (it.key == watched.key) it.copy(newEpisodes = it.newEpisodes.filter { candidate -> candidate.isAfter(episode) }) else it
            }
            val profileKey = session.profile.cacheKey()
            allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
            _state.update { it.copy(watchedSeries = updated) }
            store.saveWatchedSeries(allWatchedSeries)
            playInternal(episode, CatalogType.SERIES, watched.series, queue, requestId = requestId)
        }
    }

    fun markEpisodeWatched(watched: WatchedSeries, episode: MediaItem) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        if (watched.profileKey != profileKey) return@launch
        val updated = _state.value.watchedSeries.map {
            if (it.key == watched.key) it.copy(
                knownEpisodeIds = it.knownEpisodeIds + episode.id,
                newEpisodes = it.newEpisodes.filterNot { candidate -> candidate.id == episode.id }
            ) else it
        }
        allWatchedSeries = allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
        _state.update { it.copy(watchedSeries = updated) }
        store.saveWatchedSeries(allWatchedSeries)
    }

    fun dismissWatchedEpisode(watched: WatchedSeries, episode: MediaItem) =
        viewModelScope.launch {
            val profileKey = _state.value.session?.profile?.cacheKey()
                ?: return@launch
            val updated = _state.value.watchedSeries.map {
                if (it.key == watched.key) {
                    it.copy(
                        newEpisodes = it.newEpisodes.filter { candidate -> candidate.isAfter(episode) }
                    )
                } else {
                    it
                }
            }
            allWatchedSeries =
                allWatchedSeries.filterNot { it.profileKey == profileKey } + updated
            _state.update { it.copy(watchedSeries = updated) }
            store.saveWatchedSeries(allWatchedSeries)
        }

    private fun MediaItem.isAfter(other: MediaItem): Boolean {
        val thisSeason = seasonNumber ?: 0
        val otherSeason = other.seasonNumber ?: 0
        if (thisSeason != otherSeason) return thisSeason > otherSeason
        val thisEpisode = episodeNumber ?: title.episodeOrderFromTitle() ?: 0
        val otherEpisode = other.episodeNumber ?: other.title.episodeOrderFromTitle() ?: 0
        return thisEpisode > otherEpisode
    }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun logout() = viewModelScope.launch {
        searchSeriesReturn = null
        recommendationJob?.cancel()
        recommendationFingerprint = null
        recommendationProgress = emptyList()
        mediaIdentities.clear()
        store.clear()
        _state.value = NikTvState(restoring = false)
    }

    private fun playbackTask(requestId: Long, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!latestPlaybackRequest.isCurrent(requestId)) return@launch
            _state.update { it.copy(loading = true, error = null, tmdbIptvLookupMiss = null) }
            try {
                withTimeout(90_000L) { latestPlaybackRequest.run(requestId) { block() } }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                if (latestPlaybackRequest.isCurrent(requestId))
                    _state.update { it.copy(error = "Playback resolution timed out. Retry or switch player.") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (latestPlaybackRequest.isCurrent(requestId))
                    _state.update { it.copy(error = failure.message ?: "Could not open media") }
            } finally {
                if (latestPlaybackRequest.isCurrent(requestId)) _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, tmdbIptvLookupMiss = null) }
            try {
                withTimeout(90_000L) {
                    try {
                        block()
                    } catch (firstError: Throwable) {
                        if (firstError is kotlinx.coroutines.CancellationException) throw firstError
                        val snapshot = _state.value
                        val profile = snapshot.savedProfile
                        if (firstError is PlaybackRequestException || !snapshot.automaticReauthentication ||
                            profile?.portalType != PortalType.STALKER ||
                            !firstError.isAuthenticationFailure()
                        ) {
                            throw firstError
                        }

                        // Refresh only the rejected portal session, then replay the interrupted
                        // operation exactly once. A second rejection falls through to the existing
                        // Session expired dialog rather than creating a retry loop.
                        _state.update { it.copy(reauthenticating = true) }
                        try {
                            refreshSession(profile)
                        } finally {
                            _state.update { it.copy(reauthenticating = false) }
                        }
                        block()
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(error = "The portal did not respond within 90 seconds. Please try again.") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Unexpected error") }
            } finally {
                _state.update { it.copy(loading = false, profileLoadProgress = null, profileLoadMessage = "Preparing profile…") }
            }
        }
    }

    private fun filterKey(profileKey: String, type: CatalogType): String = "$profileKey|${type.name}"
    private fun filterCategories(raw: List<Category>, profileKey: String?, type: CatalogType, filters: Map<String, List<String>>): List<Category> {
        if (profileKey != null) com.nikhil.niktv.data.ParentalControls.rememberCategories(getApplication(), profileKey, type, raw)
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
        private const val PLAYBACK_QUEUE_LOG_TAG = "NikTvQueue"
        private const val MAX_RECENT_ITEMS = 100
        private const val MAX_PROGRESS_ITEMS = 200
        private const val MAX_PLAYBACK_URLS = 500
        private const val MODERN_TMDB_PAGE_SIZE = 20
        private const val EPISODE_METADATA_VERSION = 4
        private const val SEASON_DISCOVERY_RECHECK_MILLIS = 5 * 60_000L
        private const val WATCHED_SERIES_REFRESH_MILLIS = 15 * 60_000L
        private const val MODERN_TMDB_MAX_PAGES = 5
        private const val STALKER_SECTION_PAGE_SIZE = 14
        private val INITIAL_CATALOG_OPTIONS = setOf(14, 28, 42, 56)
        private const val INITIAL_EPISODE_BATCH_LIMIT = 30
        private const val MOVIE_MATCH_PREVIEW_THRESHOLD = 5
        private const val SEARCH_PREVIEW_DEBOUNCE_MS = 220L
        private const val SEARCH_PROVIDER_FALLBACK_DELAY_MS = 900L
        private const val SEARCH_CATEGORY_CONCURRENCY = 3
        private const val SEARCH_ALL_CATEGORY_RESULT_LIMIT = 24
        private const val METADATA_SCAN_REQUEST_DELAY_MS = 500L
    }
}
