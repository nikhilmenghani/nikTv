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
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    removeRecent: (RecentItem) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
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
    setPlayerControlsTimeoutSeconds: (Int) -> Unit,
    setKeepAwakeOnlyDuringPlayback: (Boolean) -> Unit,
    setAutomaticReauthentication: (Boolean) -> Unit,
    setModernUiEnabled: (Boolean) -> Unit,
    setPlaybackEngine: (PlaybackEngine) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    setUseTmdbEpisodeMetadata: (Boolean) -> Unit,
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
    loadMoreCategorySection: (Category) -> Unit,
    setTmdbSections: (DashboardSurface, List<TmdbHomeSection>) -> Unit,
    resetScreenConfiguration: (DashboardSurface) -> Unit,
    openModernTmdbSection: (TmdbHomeSection) -> Unit,
    openModernIptvCategory: (Category) -> Unit,
    closeModernSection: () -> Unit,
    loadMoreModernTmdbSection: () -> Unit,
    openOfflineDownloads: () -> Unit,
    closeOfflineDownloads: () -> Unit,
    playOfflineDownload: (OfflineMediaDownload) -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activity = context.findHostActivity()
    val isTv = context.isTvLikeDevice(configuration)
    val wide = configuration.screenWidthDp >= 720
    val mobileUiDesign by rememberMobileUiDesign()
    var exitConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    val exitFocusRequester = remember { FocusRequester() }
    val modernSectionOpen =
        state.modernUiEnabled &&
            (state.modernTmdbSection != null || state.modernIptvCategory != null)
    val mobileMainPage = state.mobileMainPage()

    fun selectMobileMainPage(page: MobileMainPage) {
        when (page) {
            MobileMainPage.HOME -> openHome()
            MobileMainPage.LIVE -> selectType(CatalogType.LIVE_TV)
            MobileMainPage.MOVIES -> selectType(CatalogType.MOVIES)
            MobileMainPage.SERIES -> selectType(CatalogType.SERIES)
            MobileMainPage.LIBRARY -> openFavorites()
            MobileMainPage.DOWNLOADS -> openOfflineDownloads()
        }
    }

    BackHandler(enabled = state.settingsOpen, onBack = closeSettings)
    BackHandler(enabled = state.movieMatchSelection != null, onBack = closeTmdbMovieMatches)
    BackHandler(enabled = state.seriesMatchSelection != null, onBack = closeTmdbSeriesMatches)
    BackHandler(enabled = !state.settingsOpen && state.searchOpen, onBack = closeSearch)
    BackHandler(enabled = !state.settingsOpen && !state.searchOpen && state.favoritesOpen, onBack = closeFavorites)
    BackHandler(enabled = !state.settingsOpen && state.offlineDownloadsOpen, onBack = closeOfflineDownloads)
    BackHandler(
        enabled = !state.settingsOpen && !state.searchOpen && !state.favoritesOpen && state.selectedSeries != null,
        onBack = closeSeries
    )
    BackHandler(
        enabled =
            !state.settingsOpen &&
                !state.searchOpen &&
                !state.favoritesOpen &&
                state.selectedSeries == null &&
                modernSectionOpen,
        onBack = closeModernSection
    )
    BackHandler(
        enabled =
            !exitConfirmationOpen &&
                state.movieMatchSelection == null &&
                state.seriesMatchSelection == null &&
                !state.settingsOpen &&
                !state.searchOpen &&
                !state.favoritesOpen &&
                state.selectedSeries == null &&
                !modernSectionOpen
    ) {
        if (state.homeOpen) exitConfirmationOpen = true else openHome()
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
                state.offlineDownloadsOpen -> OfflineDownloadsScreen(
                    state = state,
                    play = playOfflineDownload,
                    remove = removeOfflineDownload,
                    removeAll = removeAllOfflineDownloads,
                    close = closeOfflineDownloads
                )
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
                    toggleFavorite = toggleFavoriteEntry
                )
                state.favoritesOpen -> ModernFavoritesScreen(
                    state = state,
                    openFavorite = openFavorite,
                    toggleFavorite = toggleFavoriteEntry,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    closeFavorites = closeFavorites
                )
                state.selectedSeries != null -> ModernSeriesDetailScreen(
                    state = state,
                    play = play,
                    closeSeries = closeSeries,
                    toggleFavorite = toggleFavorite,
                    toggleSeriesWatch = toggleSeriesWatch,
                    loadSeriesSeason = loadSeriesSeason,
                    setUseTmdbEpisodeMetadata = setUseTmdbEpisodeMetadata,
                    downloadForOffline = downloadForOffline,
                    removeOfflineDownload = removeOfflineDownload,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    refreshCatalog = refreshCatalog,
                    loadMoreEpisodes = loadMoreEpisodes
                )
                else -> browseStateHolder.SaveableStateProvider(browseStateKey) { ModernBrowseScreen(
                    state = state,
                    selectType = selectType,
                    selectCategory = selectCategory,
                    play = play,
                    openTrendingMovie = openTrendingMovie,
                    openTrendingSeries = openTrendingSeries,
                    openHome = openHome,
                    openRecent = openRecent,
                    removeRecent = removeRecent,
                    dismissWatchedEpisode = dismissWatchedEpisode,
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
                    loadMoreCategorySection = loadMoreCategorySection,
                    setTmdbSections = setTmdbSections,
                    resetScreenConfiguration = resetScreenConfiguration,
                    loadMoreCatalog = loadMoreCatalog,
                    openModernTmdbSection = openModernTmdbSection,
                    openModernIptvCategory = openModernIptvCategory,
                    closeModernSection = closeModernSection,
                    loadMoreModernTmdbSection = loadMoreModernTmdbSection
                )
                }
            }
        }
    }

    if (wide || isTv) {
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
            MainContent(Modifier.weight(1f).fillMaxHeight())
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
            MainContent(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showYouTubeNavigation) 72.dp else 0.dp)
                    .mobileMainTabSwipe(
                        enabled = mainTabSwipeEnabled,
                        currentPage = mobileMainPage,
                        onPageSelected = ::selectMobileMainPage
                    )
            )
            if (showYouTubeNavigation) {
                YouTubeStyleBottomBar(
                    currentPage = mobileMainPage,
                    selectPage = ::selectMobileMainPage,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (exitConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { exitConfirmationOpen = false },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color(0xFFE50914)) },
            title = { Text("Exit NikTV?") },
            text = { Text("Are you sure you want to close the app?") },
            dismissButton = {
                TextButton(onClick = { exitConfirmationOpen = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = { activity?.finishAffinity() },
                    modifier = Modifier.focusRequester(exitFocusRequester),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                ) {
                    Text("Exit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF181818),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}
