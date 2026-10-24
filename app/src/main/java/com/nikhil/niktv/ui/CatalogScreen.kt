package com.nikhil.niktv.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.R
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.discovery.DiscoveryPhoneBottomNavigation
import com.nikhil.niktv.discovery.DiscoveryPhoneDestination
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.RecordedLiveTvMedia
import com.nikhil.niktv.data.OfflineDownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private enum class LibraryPane { MY_LIST, DOWNLOADS, STORAGE }

@Composable
internal fun CatalogScreen(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    selectCategory: (Category) -> Unit,
    play: (MediaItem) -> Unit,
    openTrendingMovie: (TrendingMovie) -> Unit,
    selectTmdbMovieMatch: (MediaItem) -> Unit,
    closeTmdbMovieMatches: () -> Unit,
    openTrendingSeries: (TrendingSeries) -> Unit,
    selectTmdbSeriesMatch: (MediaItem) -> Unit,
    closeTmdbSeriesMatches: () -> Unit,
    closeSeries: () -> Unit,
    refreshCatalog: () -> Unit,
    openFavorites: () -> Unit,
    closeFavorites: () -> Unit,
    openHome: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    openSeriesFromHome: (MediaItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    dismissRecommendation: (com.nikhil.niktv.data.HistoryRecommendationRow) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    markEpisodeWatched: (WatchedSeries, MediaItem) -> Unit,
    clearRecent: (FavoriteKind) -> Unit,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    openSettings: () -> Unit,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    logout: () -> Unit,
    setCacheIntervalMinutes: (Int) -> Unit,
    setInitialCatalogItems: (Int) -> Unit,
    setPlayerControlsTimeoutSeconds: (Int) -> Unit,
    setKeepAwakeOnlyDuringPlayback: (Boolean) -> Unit,
    setAutomaticReauthentication: (Boolean) -> Unit,
    setModernUiEnabled: (Boolean) -> Unit,
    setPlaybackEngine: (PlaybackEngine) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    setUseTmdbEpisodeMetadata: (Boolean) -> Unit,
    refreshTmdbEpisodeCache: () -> Unit,
    downloadForOffline: (MediaItem, CatalogType, MediaItem?) -> Unit,
    removeOfflineDownload: (MediaItem, CatalogType) -> Unit,
    removeAllOfflineDownloads: () -> Unit,
    toggleSeriesWatch: () -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    setBrowseLayout: (BrowseLayout) -> Unit,
    openSearch: () -> Unit,
    closeSearch: () -> Unit,
    setSearchType: (SearchContentType) -> Unit,
    setSearchQuery: (String) -> Unit,
    search: (Boolean) -> Unit,
    useRecentSearch: (RecentSearch) -> Unit,
    deleteRecentSearch: (RecentSearch) -> Unit,
    openSearchResult: (MediaItem) -> Unit,
    loadMoreSearch: () -> Unit,
    loadMoreCatalog: () -> Unit,
    loadMoreEpisodes: () -> Unit,
    setSearchCategory: (String) -> Unit,
    addProfile: () -> Unit,
    exportBackup: (android.net.Uri) -> Unit,
    importBackup: (android.net.Uri) -> Unit,
    importBackupContent: (String) -> Unit,
    openProfileSwitcher: () -> Unit,
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    setPreconfiguredProfileEnabled: (PortalProfile, Boolean) -> Unit,
    openCategoryManager: (CatalogType) -> Unit,
    removeCategoryFilter: (CatalogType, String) -> Unit,
    loadMoreCategorySection: (Category) -> Unit,
    setTmdbSections: (DashboardSurface, List<TmdbHomeSection>) -> Unit,
    resetScreenConfiguration: (DashboardSurface) -> Unit,
    openModernTmdbSection: (TmdbHomeSection) -> Unit,
    openModernIptvCategory: (Category) -> Unit,
    closeModernSection: () -> Unit,
    loadMoreModernTmdbSection: () -> Unit,
    enrichFocusedCatalogMetadata: suspend (MediaItem, CatalogType) -> Unit,
    enrichVisibleLiveGuides: (List<MediaItem>) -> Unit,
    refreshLiveProgramme: (MediaItem) -> Unit,
    refreshTmdbMatches: (Int, CatalogType) -> Unit,
    findChannelsInCategory: (String) -> Unit,
    cancelCategoryFind: () -> Unit,
    openOfflineDownloads: () -> Unit,
    closeOfflineDownloads: () -> Unit,
    playOfflineDownload: (OfflineMediaDownload) -> Unit,
    playRecording: (RecordedLiveTvMedia) -> Unit,
    playInContext: ((MediaItem, List<MediaItem>, com.nikhil.niktv.model.PlaybackQueueFilter) -> Unit)? = null,
    playProviderMovie: () -> Unit,
    closeProviderMovie: () -> Unit,
    openGlobalSearch: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activity = context.findHostActivity()
    val isTv = context.isTvLikeDevice(configuration)
    val phoneExperience = state.discoveryExperienceEnabled && com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, isTv)
    var phoneDetailOpen by remember(state.savedProfile?.cacheKey()) { mutableStateOf(false) }
    val wide = configuration.screenWidthDp >= 720
    val mobileUiDesign by rememberMobileUiDesign()
    var exitConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    var libraryPane by rememberSaveable { mutableStateOf(LibraryPane.MY_LIST) }
    val exitFocusRequester = remember { FocusRequester() }
    val modernSectionOpen =
        state.modernUiEnabled &&
            (state.modernTmdbSection != null || state.modernIptvCategory != null)
    val mobileMainPage = state.mobileMainPage()
    val openTrial = com.nikhil.niktv.discovery.LocalOpenDiscovery.current
    fun landingHome() { if (state.discoveryExperienceEnabled) openTrial(DashboardSurface.HOME) else openHome() }
    val playTrialEpisode = com.nikhil.niktv.discovery.LocalPlayDiscoveryEpisode.current
    val previewVisible = state.discoverySurface != null && !state.settingsOpen && !state.searchOpen &&
        !state.favoritesOpen && !state.offlineDownloadsOpen && state.selectedSeries == null &&
        state.movieMatchSelection == null && state.seriesMatchSelection == null && !modernSectionOpen
    val previewProviderCategory = state.discoverySurface != null && state.modernIptvCategory?.type in setOf(CatalogType.MOVIES, CatalogType.SERIES) &&
        state.selectedSeries == null && !state.settingsOpen && !state.searchOpen && !state.favoritesOpen && !state.offlineDownloadsOpen
    val discoverySearch = com.nikhil.niktv.discovery.usesDiscoverySearchShell(
        state.discoveryExperienceEnabled, state.searchOpen, state.settingsOpen, state.offlineDownloadsOpen,
        state.movieMatchSelection != null || state.seriesMatchSelection != null)
    val discoveryLibrary = com.nikhil.niktv.discovery.discoveryLibraryDestination(
        state.discoveryExperienceEnabled, state.homeOpen && state.modernIptvCategory?.type != CatalogType.LIVE_TV, state.selectedType, state.favoritesOpen,
        state.settingsOpen || state.searchOpen || state.offlineDownloadsOpen || state.movieMatchSelection != null || state.seriesMatchSelection != null,
        state.selectedSeries != null && !state.favoritesOpen,
        discoveryPageVisible = previewVisible || previewProviderCategory)
    // Keep established Live TV browsing on every device, within modern chrome.
    val useEstablishedLibrary = com.nikhil.niktv.discovery.useEstablishedLibraryBrowser(discoveryLibrary, phoneExperience)
    var libraryMenuEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var libraryBrowseAll by remember(state.savedProfile?.cacheKey(), state.favoritesOpen, state.discoverySurface) { mutableStateOf(false) }
    var libraryKind by remember { mutableStateOf<FavoriteKind?>(null) }
    var libraryPresentation by remember { mutableStateOf<com.nikhil.niktv.discovery.DiscoveryCard?>(null) }
    val navigateDiscovery: (String) -> Unit = { label -> when (label) {
        "Home" -> openTrial(DashboardSurface.HOME)
        "Movies" -> openTrial(DashboardSurface.MOVIES)
        "Series" -> openTrial(DashboardSurface.SERIES)
        "Live TV" -> { libraryMenuEpoch++; if (!state.searchOpen && libraryBrowseAll && !state.favoritesOpen) libraryBrowseAll = false else if (state.searchOpen || state.homeOpen || state.favoritesOpen || state.selectedType != CatalogType.LIVE_TV || modernSectionOpen) selectType(CatalogType.LIVE_TV) }
        "My List" -> { libraryMenuEpoch++; if (!state.searchOpen && libraryBrowseAll && state.favoritesOpen) libraryBrowseAll = false else if (state.searchOpen || !state.favoritesOpen) openFavorites() }
        "Search" -> if (state.searchOpen) closeSearch() else (openGlobalSearch ?: openSearch)()
        "Settings" -> openSettings()
        "Offline" -> openOfflineDownloads()
        "Profile" -> openProfileSwitcher()
    } }


    fun openDownloadsPane() {
        libraryPane = LibraryPane.DOWNLOADS
        openOfflineDownloads()
    }

    fun openStoragePane() {
        libraryPane = LibraryPane.STORAGE
        openOfflineDownloads()
    }

    fun selectMobileMainPage(page: MobileMainPage) {
        when (page) {
            MobileMainPage.HOME -> openHome()
            MobileMainPage.LIVE -> selectType(CatalogType.LIVE_TV)
            MobileMainPage.MOVIES -> selectType(CatalogType.MOVIES)
            MobileMainPage.SERIES -> selectType(CatalogType.SERIES)
            MobileMainPage.LIBRARY -> openFavorites()
            MobileMainPage.DOWNLOADS -> openDownloadsPane()
        }
    }

    LaunchedEffect(state.favoritesOpen) {
        if (state.favoritesOpen) libraryPane = LibraryPane.MY_LIST
    }

    // One owner follows MainContent's visible-screen precedence. Editors and
    // dialogs composed below keep their own Back override before this handler.
    val backAction = catalogBackAction(
        movieMatchesOpen = state.movieMatchSelection != null,
        seriesMatchesOpen = state.seriesMatchSelection != null,
        downloadsOpen = state.offlineDownloadsOpen,
        settingsOpen = state.settingsOpen,
        searchOpen = state.searchOpen,
        favoritesOpen = state.favoritesOpen,
        seriesOpen = state.selectedSeries != null,
        modernSectionOpen = modernSectionOpen,
        homeOpen = state.homeOpen
    )
    BackHandler(enabled = !exitConfirmationOpen && !previewVisible) {
        if (libraryBrowseAll && discoveryLibrary != null && !modernSectionOpen) libraryBrowseAll = false else when (backAction) {
            CatalogBackAction.CLOSE_MOVIE_MATCHES -> closeTmdbMovieMatches()
            CatalogBackAction.CLOSE_SERIES_MATCHES -> closeTmdbSeriesMatches()
            CatalogBackAction.CLOSE_DOWNLOADS -> closeOfflineDownloads()
            CatalogBackAction.CLOSE_SETTINGS -> closeSettings()
            CatalogBackAction.CLOSE_SEARCH -> closeSearch()
            CatalogBackAction.CLOSE_FAVORITES -> closeFavorites()
            CatalogBackAction.CLOSE_SERIES -> closeSeries()
            CatalogBackAction.CLOSE_MODERN_SECTION -> closeModernSection()
            CatalogBackAction.OPEN_HOME -> landingHome()
            CatalogBackAction.SHOW_EXIT_CONFIRMATION -> exitConfirmationOpen = true
        }
    }

    LaunchedEffect(exitConfirmationOpen) {
        if (exitConfirmationOpen) {
            withFrameNanos { }
            runCatching { exitFocusRequester.requestFocus() }
        }
    }

    // Keep browse state when details/settings replace MainContent. Modern
    // destinations have their own nested keys; category selection must not
    // discard the Home hub's saved viewport.
    val browseStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val browseStateKey = when {
        state.modernUiEnabled -> "modern"
        state.homeOpen -> "home"
        else -> "${state.selectedType}:${state.selectedCategory?.id}"
    }

    // Determine the current content to show in the main pane
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        Box(modifier) {
            when {
                state.movieMatchSelection != null -> TmdbMatchScreen(
                    title = state.movieMatchSelection.title,
                    year = state.movieMatchSelection.releaseYear,
                    candidates = state.movieMatchCandidates,
                    loadingMore = state.movieMatchLoadingMore,
                    select = selectTmdbMovieMatch,
                    close = closeTmdbMovieMatches
                )
                state.seriesMatchSelection != null -> TmdbMatchScreen(
                    title = state.seriesMatchSelection.name,
                    year = state.seriesMatchSelection.firstAirYear,
                    candidates = state.seriesMatchCandidates,
                    loadingMore = false,
                    select = selectTmdbSeriesMatch,
                    close = closeTmdbSeriesMatches
                )
                state.offlineDownloadsOpen -> if (libraryPane == LibraryPane.STORAGE) {
                    OfflineStorageScreen(
                        state = state,
                        removeAll = removeAllOfflineDownloads,
                        close = closeOfflineDownloads
                    )
                } else {
                    OfflineDownloadsScreen(
                        state = state,
                        play = playOfflineDownload,
                        playRecording = playRecording,
                        remove = removeOfflineDownload,
                        close = closeOfflineDownloads
                    )
                }
                state.settingsOpen -> ModernSettingsScreen(
                    state = state,
                    closeSettings = closeSettings,
                    reauthenticate = reauthenticate,
                    editProfile = editProfile,
                    addProfile = addProfile,
                    exportBackup = exportBackup,
                    importBackup = importBackup,
                    importBackupContent = importBackupContent,
                    switchProfile = switchProfile,
                    removeProfile = removeProfile,
                    setPreconfiguredProfileEnabled = setPreconfiguredProfileEnabled,
                    logout = logout,
                    setCacheIntervalMinutes = setCacheIntervalMinutes,
                    setInitialCatalogItems = setInitialCatalogItems,
                    setPlayerControlsTimeoutSeconds = setPlayerControlsTimeoutSeconds,
                    setKeepAwakeOnlyDuringPlayback = setKeepAwakeOnlyDuringPlayback,
                    setAutomaticReauthentication = setAutomaticReauthentication,
                    setModernUiEnabled = setModernUiEnabled,
                    setPlaybackEngine = setPlaybackEngine,
                    setSeriesStartSeason = setSeriesStartSeason,
                    setBrowseLayout = setBrowseLayout,
                    openCategoryManager = openCategoryManager
                )
                state.searchOpen -> ModernSearchScreen(
                    state = state,
                    close = closeSearch,
                    setType = setSearchType,
                    setCategory = setSearchCategory,
                    setQuery = setSearchQuery,
                    search = search,
                    useRecent = useRecentSearch,
                    deleteRecent = deleteRecentSearch,
                    openResult = openSearchResult,
                    loadMore = loadMoreSearch,
                    toggleFavorite = toggleFavoriteEntry,
                    discoveryChrome = discoverySearch,
                    headerFocusRequester = com.nikhil.niktv.discovery.LocalDiscoverySearchHeaderFocus.current,
                    queryFocusRequester = com.nikhil.niktv.discovery.LocalDiscoverySearchQueryFocus.current,
                    headerHasFocus = com.nikhil.niktv.discovery.LocalDiscoverySearchHeaderFocused.current
                )
                state.favoritesOpen -> StableTileFocusViewport { ModernFavoritesScreen(
                    state = if (libraryBrowseAll && libraryKind != null) state.copy(favorites = state.favorites.filter { it.kind == libraryKind }) else state,
                    openFavorite = openFavorite,
                    toggleFavorite = toggleFavoriteEntry,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    closeFavorites = closeFavorites,
                    discoveryChrome = discoveryLibrary != null,
                    heroPresentation = { libraryPresentation = it },
                    refreshLiveProgramme = refreshLiveProgramme
                ) }
                state.selectedSeries != null -> StableTileFocusViewport { ModernSeriesDetailScreen(
                    state = state,
                    play = play,
                    closeSeries = closeSeries,
                    toggleFavorite = toggleFavorite,
                    toggleSeriesWatch = toggleSeriesWatch,
                    loadSeriesSeason = loadSeriesSeason,
                    setUseTmdbEpisodeMetadata = setUseTmdbEpisodeMetadata,
                    refreshTmdbEpisodeCache = refreshTmdbEpisodeCache,
                    downloadForOffline = downloadForOffline,
                    removeOfflineDownload = removeOfflineDownload,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    refreshCatalog = refreshCatalog,
                    loadMoreEpisodes = loadMoreEpisodes,
                    openTmdbMovie = openTrendingMovie, openTmdbSeries = openTrendingSeries
                ) }
                previewVisible -> browseStateHolder.SaveableStateProvider("discovery2:${state.savedProfile?.cacheKey()}:${state.discoverySurface}") {
                    com.nikhil.niktv.discovery.DiscoveryPreviewScreen(
                    app = state, surface = requireNotNull(state.discoverySurface), selectSurface = openTrial,
                    exit = openHome, classic = { label -> when (label) {
                        "Home" -> openHome(); "Live TV" -> selectType(CatalogType.LIVE_TV)
                        "Movies" -> openTrial(DashboardSurface.MOVIES); "Series" -> openTrial(DashboardSurface.SERIES)
                        "My List" -> openFavorites(); "Offline" -> openOfflineDownloads()
                        "Search" -> openSearch(); "Settings" -> openSettings(); "Profile" -> openProfileSwitcher()
                    } }, playCard = { card -> if (card.series) openTrendingSeries(com.nikhil.niktv.data.TrendingSeries(card.show()))
                        else openTrendingMovie(com.nikhil.niktv.data.TrendingMovie(card.movie())) },
                    playEpisode = playTrialEpisode, openRecent = openRecent, openWatched = openWatchedEpisode,
                    toggleFavorite = toggleFavoriteEntry, openCategory = openModernIptvCategory, openSection = openModernTmdbSection,
                    removeRecent = removeRecent, markWatched = markEpisodeWatched, dismissEpisode = dismissWatchedEpisode,
                    detailVisibilityChanged = { phoneDetailOpen = it }, dismissRecommendation = dismissRecommendation
                    )
                }
                previewProviderCategory -> browseStateHolder.SaveableStateProvider("provider-trial:${state.savedProfile?.cacheKey()}:${state.modernIptvCategory?.id}:${state.modernIptvCategoryOpenId}") {
                    com.nikhil.niktv.discovery.DiscoveryProviderCategory(state, requireNotNull(state.modernIptvCategory),
                        close = closeModernSection, selectSurface = openTrial,
                        classic = { label -> when (label) {
                            "Home" -> openHome(); "Movies" -> openTrial(DashboardSurface.MOVIES); "Series" -> openTrial(DashboardSurface.SERIES)
                            "Live TV" -> selectType(CatalogType.LIVE_TV); "My List" -> openFavorites(); "Offline" -> openOfflineDownloads()
                            "Search" -> openSearch(); "Settings" -> openSettings(); "Profile" -> openProfileSwitcher()
                        } }, open = { item, queue, filter -> playInContext?.invoke(item, queue, filter) ?: play(item) },
                        favorite = toggleFavoriteEntry, more = loadMoreCatalog, refresh = refreshCatalog, download = downloadForOffline)
                }
                else -> browseStateHolder.SaveableStateProvider(browseStateKey) { ModernBrowseScreen(
                    state = state,
                    playInContext = playInContext,
                    selectType = selectType,
                    selectCategory = selectCategory,
                    play = play,
                    openTrendingMovie = openTrendingMovie,
                    openTrendingSeries = openTrendingSeries,
                    openHome = openHome,
                    openRecent = openRecent,
                    openSeriesFromHome = openSeriesFromHome,
                    removeRecent = removeRecent,
                    dismissRecommendation = dismissRecommendation,
                            dismissWatchedEpisode = dismissWatchedEpisode,
                            markEpisodeWatched = markEpisodeWatched,
                    clearRecent = clearRecent,
                    openWatchedEpisode = openWatchedEpisode,
                    toggleFavorite = toggleFavorite,
                    toggleFavoriteEntry = toggleFavoriteEntry,
                    setBrowseLayout = setBrowseLayout,
                    openFavorite = openFavorite,
                    openFavorites = openFavorites,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    openProfileSwitcher = openProfileSwitcher,
                    refreshCatalog = refreshCatalog,
                    openCategoryManager = openCategoryManager,
                    removeCategoryFilter = removeCategoryFilter,
                    loadMoreCategorySection = loadMoreCategorySection,
                    setTmdbSections = setTmdbSections,
                    resetScreenConfiguration = resetScreenConfiguration,
                    loadMoreCatalog = loadMoreCatalog,
                    openModernTmdbSection = openModernTmdbSection,
                    openModernIptvCategory = openModernIptvCategory,
                    closeModernSection = closeModernSection,
                    loadMoreModernTmdbSection = loadMoreModernTmdbSection,
                    enrichFocusedCatalogMetadata = enrichFocusedCatalogMetadata,
                    enrichVisibleLiveGuides = enrichVisibleLiveGuides,
                    refreshLiveProgramme = refreshLiveProgramme,
            refreshTmdbMatches = refreshTmdbMatches,
                    findChannelsInCategory = findChannelsInCategory,
                    cancelCategoryFind = cancelCategoryFind
                )
                }
            }
        }
    }

    @Composable fun LibraryContent(modifier: Modifier = Modifier) {
        Box(modifier) {
            if (discoverySearch) com.nikhil.niktv.discovery.DiscoverySearchScreen(
                state, back = closeSearch, select = openTrial, navigate = navigateDiscovery
            ) { MainContent(Modifier.fillMaxSize()) }
            else if (discoveryLibrary != null) com.nikhil.niktv.discovery.DiscoveryLibraryScreen(
                state, discoveryLibrary,
                back = { if (libraryBrowseAll) libraryBrowseAll = false else if (state.favoritesOpen) closeFavorites() else if (modernSectionOpen) closeModernSection() else landingHome() },
                select = openTrial, navigate = navigateDiscovery,
                presentation = libraryPresentation.takeIf { (!useEstablishedLibrary || state.favoritesOpen) && !modernSectionOpen && !libraryBrowseAll }) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (!useEstablishedLibrary && !modernSectionOpen && !libraryBrowseAll) browseStateHolder.SaveableStateProvider("library-feed:${state.savedProfile?.cacheKey()}:$discoveryLibrary") {
                            com.nikhil.niktv.discovery.DiscoveryLibraryFeed(
                                state, discoveryLibrary, libraryMenuEpoch, openFavorite, toggleFavoriteEntry, openRecent,
                                openChannel = { item, queue, filter -> playInContext?.invoke(item, queue, filter) ?: play(item) },
                                openCategory = openModernIptvCategory,
                                browseCategories = { libraryBrowseAll = true },
                                configureCategories = { openCategoryManager(CatalogType.LIVE_TV) },
                                browseSaved = { libraryKind = it; libraryBrowseAll = true }, search = openSearch,
                                refreshProgramme = refreshLiveProgramme, enrichGuides = enrichVisibleLiveGuides,
                                background = { libraryPresentation = it })
                            } else MainContent(Modifier.fillMaxSize())
                        }
                        if (state.favoritesOpen && !phoneExperience) MobileLibrarySwitcher(
                            downloadsSelected = false, storageSelected = false,
                            openFavorites = openFavorites, openOfflineDownloads = ::openDownloadsPane,
                            openStorage = ::openStoragePane)
                    }
                }
            else MainContent(Modifier.fillMaxSize())
        }
    }
    if (phoneExperience) {
        val showPhoneBar = !phoneDetailOpen && state.selectedSeries == null && state.providerMovieDetails == null &&
            state.movieMatchSelection == null && state.seriesMatchSelection == null && !state.categoryManagerOpen
        val destination = when {
            state.searchOpen -> null
            state.favoritesOpen || state.offlineDownloadsOpen -> DiscoveryPhoneDestination.LIBRARY
            state.settingsOpen -> null
            previewVisible || previewProviderCategory -> when (state.discoverySurface) {
                DashboardSurface.MOVIES -> DiscoveryPhoneDestination.MOVIES
                DashboardSurface.SERIES -> DiscoveryPhoneDestination.SERIES
                else -> DiscoveryPhoneDestination.HOME
            }
            !state.homeOpen && state.selectedType == CatalogType.LIVE_TV -> DiscoveryPhoneDestination.LIVE
            else -> DiscoveryPhoneDestination.HOME
        }
        @Composable fun PhoneBottomBar() {
            @OptIn(ExperimentalLayoutApi::class)
            val keyboardVisible = WindowInsets.isImeVisible
            DiscoveryPhoneBottomNavigation(destination, select = { page -> when (page) {
                DiscoveryPhoneDestination.HOME -> openTrial(DashboardSurface.HOME)
                DiscoveryPhoneDestination.LIVE -> selectType(CatalogType.LIVE_TV)
                DiscoveryPhoneDestination.MOVIES -> openTrial(DashboardSurface.MOVIES)
                DiscoveryPhoneDestination.SERIES -> openTrial(DashboardSurface.SERIES)
                DiscoveryPhoneDestination.LIBRARY -> openFavorites()
            } }, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).then(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    // Observe IME in this child, keeping the search composition
                    // and focused editor alive while reclaiming the bar's space.
                    layout(placeable.width, if (keyboardVisible) 0 else placeable.height) {
                        if (!keyboardVisible) placeable.placeRelative(0, 0)
                    }
                }))
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
            Column(Modifier.fillMaxSize()
                .then(if (previewVisible || previewProviderCategory || state.selectedSeries != null || discoveryLibrary != null || discoverySearch) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                LibraryContent(Modifier.weight(1f).fillMaxWidth())
                if (!state.searchOpen && (state.favoritesOpen || state.offlineDownloadsOpen)) MobileLibrarySwitcher(
                    downloadsSelected = state.offlineDownloadsOpen, storageSelected = libraryPane == LibraryPane.STORAGE,
                    openFavorites = openFavorites, openOfflineDownloads = ::openDownloadsPane, openStorage = ::openStoragePane)
                if (showPhoneBar && !previewVisible) PhoneBottomBar()
            }
            if (showPhoneBar && previewVisible) Box(Modifier.align(Alignment.BottomCenter)) { PhoneBottomBar() }
        }
    } else if (previewVisible || previewProviderCategory || state.selectedSeries != null || discoveryLibrary != null || discoverySearch) {
        LibraryContent(Modifier.fillMaxSize())
    } else if (wide || isTv) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF090909))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            ModernSideRail(
                state = state,
                selectType = selectType,
                openHome = openHome,
                openFavorites = openFavorites,
                openOfflineDownloads = openOfflineDownloads,
                openSearch = openSearch,
                openSettings = openSettings,
                openProfileSwitcher = openProfileSwitcher,
                // TABLET_NAVIGATION_TITLES_V12
                expanded = true,
                modifier = Modifier
                    .width(if (isTv) 196.dp else 176.dp)
                    .fillMaxHeight()
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                MainContent(Modifier.weight(1f).fillMaxWidth())
                if (state.favoritesOpen || state.offlineDownloadsOpen) {
                    MobileLibrarySwitcher(
                        downloadsSelected = state.offlineDownloadsOpen,
                        storageSelected = libraryPane == LibraryPane.STORAGE,
                        openFavorites = openFavorites,
                        openOfflineDownloads = ::openDownloadsPane,
                        openStorage = ::openStoragePane
                    )
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val showYouTubeNavigation = mobileUiDesign.usesYouTubeOn(configuration) && !state.settingsOpen && !state.searchOpen
            val mainTabSwipeEnabled =
                showYouTubeNavigation &&
                    state.movieMatchSelection == null &&
                    state.seriesMatchSelection == null &&
                    state.selectedSeries == null &&
                    !modernSectionOpen
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showYouTubeNavigation) 72.dp else 0.dp)
            ) {
                MainContent(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .mobileMainTabSwipe(
                            enabled = mainTabSwipeEnabled,
                            currentPage = mobileMainPage,
                            onPageSelected = ::selectMobileMainPage
                        )
                )
                if (showYouTubeNavigation && (state.favoritesOpen || state.offlineDownloadsOpen)) {
                    MobileLibrarySwitcher(
                        downloadsSelected = state.offlineDownloadsOpen,
                        storageSelected = libraryPane == LibraryPane.STORAGE,
                        openFavorites = openFavorites,
                        openOfflineDownloads = ::openDownloadsPane,
                        openStorage = ::openStoragePane
                    )
                }
            }
            if (showYouTubeNavigation) {
                YouTubeStyleBottomBar(
                    currentPage = mobileMainPage,
                    selectPage = ::selectMobileMainPage,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    state.providerMovieDetails?.takeIf { it.profile == state.savedProfile?.cacheKey() && it.profile == state.session?.profile?.cacheKey() && state.nowPlaying == null &&
        state.movieMatchSelection == null && state.seriesMatchSelection == null && (state.selectedSeries == null || state.searchOpen || state.favoritesOpen) }?.let { selected ->
        com.nikhil.niktv.discovery.ProviderMovieDetailDialog(state, selected, playProviderMovie, closeProviderMovie,
            favorite = toggleFavoriteEntry, download = downloadForOffline, removeDownload = removeOfflineDownload,
            openTmdbMovie = openTrendingMovie, openTmdbSeries = openTrendingSeries)
    }
    if (exitConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { exitConfirmationOpen = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color(0xFFE50914)) },
            title = { Text("Exit NikTV?") },
            text = { Text("Are you sure you want to close the app?") },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NikTvSecondaryActionButton(
                        onClick = { exitConfirmationOpen = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                    NikTvPrimaryActionButton(
                        onClick = { activity?.finishAffinity() },
                        modifier = Modifier.weight(1f).focusRequester(exitFocusRequester)
                    ) {
                        Text("Exit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF181818),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}
