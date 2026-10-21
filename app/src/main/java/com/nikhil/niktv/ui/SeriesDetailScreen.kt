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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ModernSeriesDetailScreen(
    state: NikTvState,
    play: (MediaItem) -> Unit,
    closeSeries: () -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleSeriesWatch: () -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    setUseTmdbEpisodeMetadata: (Boolean) -> Unit,
    refreshTmdbEpisodeCache: () -> Unit,
    downloadForOffline: (MediaItem, CatalogType, MediaItem?) -> Unit,
    removeOfflineDownload: (MediaItem, CatalogType) -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    refreshCatalog: () -> Unit,
    loadMoreEpisodes: () -> Unit,
    openTmdbMovie: (TrendingMovie) -> Unit,
    openTmdbSeries: (TrendingSeries) -> Unit
) {
    val series = state.selectedSeries ?: return
    var episodeSortDescending by rememberSaveable(series.id) { mutableStateOf(true) }
    var searchQuery by rememberSaveable(series.id) { mutableStateOf("") }
    var episodeTextEditing by rememberSaveable(series.id) { mutableStateOf(false) }
    var initialEpisodeFocusComplete by rememberSaveable(series.id) { mutableStateOf(false) }
    var focusedEpisodeId by remember(series.id) { mutableStateOf<String?>(null) }
    var handledPlaybackReturnFocusId by remember(series.id) { mutableStateOf<String?>(null) }
    // Playback return is an entry event. Keep its target even if a background
    // state update clears the shared marker before the episode list is ready.
    val entryPlaybackReturnFocusId = remember(series.id) { state.playbackReturnFocusId }
    var moreActionsExpanded by remember { mutableStateOf(false) }
    var expandedPanel by rememberSaveable(state.savedProfile?.cacheKey(), series.id) { mutableStateOf<SeriesDetailPanel?>(null) }
    val castButtonRequester = remember(series.id) { FocusRequester() }
    val relatedButtonRequester = remember(series.id) { FocusRequester() }
    var inlinePanelFocused by remember { mutableStateOf(false) }
    var focusedPanel by remember { mutableStateOf<SeriesDetailPanel?>(null) }
    var seasonMenuExpanded by remember(series.id) { mutableStateOf(false) }
    val seasonButtonRequester = remember(series.id) { FocusRequester() }
    val selectedSeasonRequester = remember(series.id) { FocusRequester() }
    val episodeSearchRequester = remember(series.id) { FocusRequester() }
    val entryEpisodeRequester = remember(series.id) { FocusRequester() }
    val firstEpisodeRequester = remember(series.id) { FocusRequester() }
    val detailPlayRequester = remember(series.id) { FocusRequester() }
    val toolbarRequester = remember(series.id) { FocusRequester() }
    var toolbarHeightPx by remember { mutableIntStateOf(0) }
    var paginationAttempt by remember(series.id, state.selectedSeriesSeason) { mutableStateOf<Int?>(null) }
    val profile = state.savedProfile?.cacheKey().orEmpty()
    val reducedMotion = android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val detailScope = rememberCoroutineScope()
    val episodeFocusParkingRequester = remember(series.id) { FocusRequester() }
    val heroActionRequesters = remember(series.id) { List(3) { FocusRequester() } }
    val episodeListState = key(series.id) { rememberLazyListState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var searchImeWasVisible by remember { mutableStateOf(false) }
    val episodeContext = LocalContext.current
    val episodeConfiguration = LocalConfiguration.current
    val episodeIsTv = episodeContext.isTvLikeDevice(episodeConfiguration)
    val remoteNavigationActive = episodeContext.usesRemoteNavigation(episodeConfiguration) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val compactPortrait = episodeConfiguration.screenWidthDp < 600 &&
        episodeConfiguration.orientation == Configuration.ORIENTATION_PORTRAIT
    val mobileEpisodeLayout =
        !episodeIsTv &&
            episodeConfiguration.smallestScreenWidthDp < 600

    val episodeRowState = key(profile, series.id, state.selectedSeriesSeason) { rememberLazyListState() }
    val landscapeRequesters = remember(profile, series.id) { mutableMapOf<String, FocusRequester>() }
    val sortRequester = remember(series.id) { FocusRequester() }
    val synopsisRequester = remember(series.id) { FocusRequester() }
    val wideFocusCoordinator = remember(series.id) { com.nikhil.niktv.discovery.DiscoveryScrollCoordinator(detailScope) }
    val moreRequester = remember(series.id) { FocusRequester() }
    var episodeMove by remember { mutableStateOf<Job?>(null) }

    fun closeEpisodeSearch() {
        searchImeWasVisible = false
        episodeTextEditing = false
        expandedPanel = null
        keyboardController?.hide()
        focusManager.clearFocus()
        if (remoteNavigationActive) detailScope.launch {
            withFrameNanos { }; runCatching { toolbarRequester.requestFocus() }
        }
    }

    BackHandler(enabled = expandedPanel == SeriesDetailPanel.SEARCH) { closeEpisodeSearch() }

    LaunchedEffect(imeVisible, expandedPanel) {
        if (expandedPanel == SeriesDetailPanel.SEARCH) {
            if (imeVisible) searchImeWasVisible = true
            // The IME consumes the first system Back. Collapse its panel when it closes too.
            else if (searchImeWasVisible) closeEpisodeSearch()
        } else searchImeWasVisible = false
    }
    LaunchedEffect(expandedPanel) {
        if (expandedPanel != SeriesDetailPanel.SEARCH) {
            episodeTextEditing = false
            keyboardController?.hide()
        }
        if (expandedPanel == null && inlinePanelFocused && remoteNavigationActive) {
            withFrameNanos { }
            val origin = when (focusedPanel) {
                SeriesDetailPanel.SEARCH -> toolbarRequester
                SeriesDetailPanel.RELATED -> relatedButtonRequester
                else -> castButtonRequester
            }
            runCatching { origin.requestFocus() }
        }
    }
    // Retain typed text for reopening, but a hidden search must not silently filter episodes.
    val activeSearchQuery = searchQuery.takeIf { expandedPanel == SeriesDetailPanel.SEARCH }.orEmpty()

    val isFavorite = remember(state.favorites, series) {
        state.favorites.any { it.media.id == series.id && it.kind == FavoriteKind.SERIES }
    }
    val isWatched = state.watchedSeries.any { it.series.id == series.id }

    val availableSeasons = state.availableSeriesSeasons
    val selectedSeason = state.selectedSeriesSeason

    LaunchedEffect(seasonMenuExpanded) {
        if (seasonMenuExpanded && remoteNavigationActive) repeat(6) {
            withFrameNanos { }
            if (runCatching { selectedSeasonRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val comparator = remember(episodeSortDescending) {
        episodeComparator(episodeSortDescending)
    }

    val seasonFilteredItems = remember(state.items, selectedSeason) {
        state.items
    }

    val filteredEpisodes = remember(seasonFilteredItems, activeSearchQuery, comparator, episodeSortDescending) {
        val query = activeSearchQuery.trim()
        val baseList = if (query.isBlank()) seasonFilteredItems else {
            seasonFilteredItems.filter { ep ->
                val episodeNumber = ep.episodeNumber ?: ep.title.episodeNumberFromTitle()
                val seasonNumber = ep.seasonNumber ?: ep.title.seasonNumberFromTitle()
                val episodeDescription = ep.description?.takeUnless { description ->
                    description.trim().equals(series.description?.trim(), ignoreCase = true)
                }.orEmpty()
                val searchableDetails = buildString {
                    append(ep.title)
                    append(' ')
                    append(episodeDescription)
                    episodeNumber?.let { append(" episode $it e$it") }
                    if (seasonNumber != null && episodeNumber != null) append(" s${seasonNumber}e$episodeNumber")
                }
                val numericQuery = query.toIntOrNull()
                if (numericQuery != null) episodeNumber?.toString()?.contains(query) == true
                else searchableDetails.matchesTitleKeywords(query)
            }
                .sortedByDescending { it.title.titleKeywordScore(query) }
        }
        if (selectedSeason != null) {
            baseList.sortedWith(comparator)
        } else {
            val seasonComp = if (episodeSortDescending) {
                compareByDescending<Map.Entry<Int?, List<MediaItem>>> { it.key ?: -1 }
            } else {
                compareBy<Map.Entry<Int?, List<MediaItem>>>({ it.key == null }, { it.key ?: Int.MAX_VALUE })
            }
            baseList.groupBy { it.seasonNumber }.entries
                .sortedWith(seasonComp)
                .flatMap { (_, eps) -> eps.sortedWith(comparator) }
        }
    }

    val progressByEpisode = remember(state.playbackProgress, state.items, profile, series.id) {
        state.items.associate { episode -> episode.id to seriesEpisodeProgress(state.playbackProgress, profile, series, episode) }
    }
    val recentEpisode = remember(state.recentlyPlayed, progressByEpisode, state.items, series.id) {
        val recent = state.recentlyPlayed.firstOrNull { it.kind == FavoriteKind.SERIES &&
            (it.media.id == series.id || it.series?.id == series.id) }?.lastPlayed
        state.items.firstOrNull { it.id == recent?.id }
            ?: state.items.filter { progressByEpisode[it.id] != null }.maxByOrNull { progressByEpisode[it.id]!!.updatedAtMillis }
    }

    val latestEpisode = state.items.maxWithOrNull(
        compareBy<MediaItem>({ it.seasonNumber ?: it.title.seasonNumberFromTitle() ?: 0 },
            { it.episodeNumber ?: it.title.episodeNumberFromTitle() ?: 0 }, { it.title })
    )
    val primaryEpisodeToPlay = recentEpisode ?: if (episodeSortDescending) latestEpisode ?: state.items.firstOrNull()
        else state.items.firstOrNull()

    fun focusLandscapeEpisode() {
        if (filteredEpisodes.isEmpty()) return
        val index = filteredEpisodes.indexOfFirst { it.id == focusedEpisodeId }.coerceAtLeast(0)
        episodeMove?.cancel()
        wideFocusCoordinator.cancel()
        episodeMove = detailScope.launch {
            if (episodeListState.layoutInfo.visibleItemsInfo.none { it.index == SERIES_EPISODE_START_INDEX })
                episodeListState.scrollToItem(SERIES_EPISODE_START_INDEX)
            if (episodeRowState.layoutInfo.visibleItemsInfo.none { it.index == index }) episodeRowState.scrollToItem(index)
            repeat(20) { withFrameNanos { }
                if (runCatching { landscapeRequesters[filteredEpisodes[index].id]?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    fun requestWideFocus(target: SeriesDetailFocus, preferred: FocusRequester? = null) {
        if (target == SeriesDetailFocus.EPISODES) { focusLandscapeEpisode(); return }
        episodeMove?.cancel()
        val requester = preferred ?: when (target) {
            SeriesDetailFocus.TOP -> heroActionRequesters[0]
            SeriesDetailFocus.PLAY -> detailPlayRequester
            SeriesDetailFocus.INFO -> synopsisRequester
            SeriesDetailFocus.SEASONS -> selectedSeasonRequester
            SeriesDetailFocus.SORT -> sortRequester
            else -> toolbarRequester
        }
        wideFocusCoordinator.move(episodeListState, seriesDetailFocusSlot(target), reducedMotion) {
            runCatching { requester.requestFocus() }.getOrDefault(false)
        }
    }
    fun moveWideFocus(from: SeriesDetailFocus, delta: Int) {
        adjacentSeriesDetailFocus(from, delta, availableSeasons.size > 1)?.let { requestWideFocus(it) }
    }
    DisposableEffect(profile, series.id, selectedSeason, activeSearchQuery, episodeSortDescending) {
        onDispose { episodeMove?.cancel(); wideFocusCoordinator.cancel() }
    }
    val episodeIds = remember(filteredEpisodes) { filteredEpisodes.map { it.id } }
    val pendingPlaybackReturnId = entryPlaybackReturnFocusId?.takeUnless {
        it == handledPlaybackReturnFocusId
    }
    val entryFocusTarget = seriesEpisodeEntryFocusTarget(
        episodeIds = episodeIds,
        loading = state.loading,
        returningEpisodeId = pendingPlaybackReturnId,
        initialEntryPending = !initialEpisodeFocusComplete
    )

    LaunchedEffect(remoteNavigationActive, series.id, episodeIds, state.loading, entryPlaybackReturnFocusId) {
        if (!remoteNavigationActive && pendingPlaybackReturnId == null) return@LaunchedEffect
        if (pendingPlaybackReturnId == null && !initialEpisodeFocusComplete) {
            if (!mobileEpisodeLayout && primaryEpisodeToPlay == null && state.loading) {
                withFrameNanos { }; runCatching { episodeFocusParkingRequester.requestFocus() }
                return@LaunchedEffect
            }
            repeat(12) {
                withFrameNanos { }
                if (runCatching { (if (primaryEpisodeToPlay == null) heroActionRequesters[0] else detailPlayRequester).requestFocus() }.getOrDefault(false)) {
                    initialEpisodeFocusComplete = true
                    return@LaunchedEffect
                }
            }
            return@LaunchedEffect
        }
        val target = entryFocusTarget ?: return@LaunchedEffect
        if (target is SeriesEpisodeEntryFocus.Episode && pendingPlaybackReturnId == null && focusedEpisodeId != null) {
            // Do not steal a selection the user reached before the entry effect ran.
            initialEpisodeFocusComplete = true
            return@LaunchedEffect
        }
        if (pendingPlaybackReturnId != null) {
            // Returning to an episode keeps its position without reopening the IME.
            if (expandedPanel == SeriesDetailPanel.SEARCH) expandedPanel = null
            keyboardController?.hide()
        }
        val requester = when (target) {
            SeriesEpisodeEntryFocus.Loading -> {
                if (!remoteNavigationActive) return@LaunchedEffect
                episodeFocusParkingRequester
            }
            SeriesEpisodeEntryFocus.Back -> {
                if (!remoteNavigationActive) return@LaunchedEffect
                heroActionRequesters[0]
            }
            is SeriesEpisodeEntryFocus.Episode -> {
                val listIndex = seriesEpisodeVerticalIndex(target.index, !mobileEpisodeLayout)
                if (!mobileEpisodeLayout) episodeRowState.scrollToItem(target.index)
                if (episodeListState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) {
                    episodeListState.scrollToItem(listIndex, -toolbarHeightPx)
                }
                entryEpisodeRequester
            }
        }
        repeat(6) { attempt ->
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                if (target != SeriesEpisodeEntryFocus.Loading) {
                    initialEpisodeFocusComplete = true
                    handledPlaybackReturnFocusId = pendingPlaybackReturnId
                }
                return@LaunchedEffect
            }
            delay(40L * (attempt + 1))
        }
    }

    val currentLoadMore by rememberUpdatedState(loadMoreEpisodes)
    LaunchedEffect(series.id, selectedSeason, filteredEpisodes.size, state.episodeHasMore, state.episodeLoadingMore, state.loading, activeSearchQuery) {
        if (state.episodeLoadingMore || state.loading || !state.episodeHasMore || activeSearchQuery.isNotBlank()) return@LaunchedEffect
        snapshotFlow {
            if (mobileEpisodeLayout) shouldLoadMoreSeriesEpisodes(filteredEpisodes.size,
                episodeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, state.episodeHasMore,
                state.loading, activeSearchQuery, episodeListState.isScrollInProgress || episodeListState.firstVisibleItemIndex > 1)
            else shouldLoadMoreLandscapeEpisodes(filteredEpisodes.size, episodeRowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                state.episodeHasMore, state.loading, activeSearchQuery, episodeListState.firstVisibleItemIndex > 1 || focusedEpisodeId != null)
        }
            .distinctUntilChanged().collect { nearEnd ->
                if (nearEnd && paginationAttempt != state.episodePage) {
                    paginationAttempt = state.episodePage
                    currentLoadMore()
                }
            }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF090B10))
            .focusRequester(episodeFocusParkingRequester)
            .onPreviewKeyEvent { event ->
                // Hold the entry cursor inside this screen while there is no episode
                // to select. Back continues through the normal navigation handler.
                remoteNavigationActive && pendingPlaybackReturnId != null && entryFocusTarget == SeriesEpisodeEntryFocus.Loading &&
                    event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
            }
            .focusable(remoteNavigationActive && (mobileEpisodeLayout ||
                (!initialEpisodeFocusComplete && state.loading) || entryFocusTarget == SeriesEpisodeEntryFocus.Loading))
    ) {
        com.nikhil.niktv.discovery.ProviderDetailHeader(series, CatalogType.SERIES, state,
            play = { primaryEpisodeToPlay?.let(play) }, close = closeSeries,
            favorite = { toggleFavorite(series) }, isFavorite = isFavorite,
            openTmdbMovie = openTmdbMovie, openTmdbSeries = openTmdbSeries,
            playLabel = if (primaryEpisodeToPlay == null) "Loading episodes…" else if (recentEpisode != null) "Resume ${primaryEpisodeToPlay.actionEpisodeLabel()}" else if (episodeSortDescending) "Play latest" else "Start series",
            playEnabled = primaryEpisodeToPlay != null,
            castUp = { if (!mobileEpisodeLayout) requestWideFocus(SeriesDetailFocus.TOOLS, castButtonRequester) else runCatching { castButtonRequester.requestFocus() } },
            relatedUp = { if (!mobileEpisodeLayout) requestWideFocus(SeriesDetailFocus.TOOLS, relatedButtonRequester) else runCatching { relatedButtonRequester.requestFocus() } },
            playRequester = detailPlayRequester, backRequester = heroActionRequesters[0],
            episodeDown = if (!mobileEpisodeLayout) ({ focusLandscapeEpisode() }) else null,
            heroUp = if (!mobileEpisodeLayout) ({ moveWideFocus(SeriesDetailFocus.PLAY, -1) }) else null,
            topDown = if (!mobileEpisodeLayout) ({ moveWideFocus(SeriesDetailFocus.TOP, 1) }) else null,
            synopsisRequester = synopsisRequester,
            synopsisUp = if (!mobileEpisodeLayout) ({ moveWideFocus(SeriesDetailFocus.INFO, -1) }) else null,
            synopsisDown = if (!mobileEpisodeLayout) ({ moveWideFocus(SeriesDetailFocus.INFO, 1) }) else null,
            down = {
                if (!mobileEpisodeLayout) { moveWideFocus(SeriesDetailFocus.PLAY, 1) } else detailScope.launch {
                    if (filteredEpisodes.isNotEmpty()) {
                        episodeListState.scrollToItem(SERIES_EPISODE_START_INDEX, -toolbarHeightPx)
                        repeat(12) { withFrameNanos { }; if (runCatching { firstEpisodeRequester.requestFocus() }.getOrDefault(false)) return@launch }
                    }
                }
            }, tools = {
                com.nikhil.niktv.discovery.DiscoveryAction(
                    if (isWatched) "Following" else "Notify me", Icons.Default.Notifications,
                    selected = isWatched, onClick = toggleSeriesWatch)
                Box {
                    com.nikhil.niktv.discovery.DiscoveryAction("Series options", Icons.Default.MoreHoriz, iconOnly = true) { moreActionsExpanded = true }
                    DropdownMenu(moreActionsExpanded, { moreActionsExpanded = false }) {
                        NikDropdownMenuItem(text = { Text("Refresh episodes") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { moreActionsExpanded = false; refreshCatalog() })
                        NikDropdownMenuItem(text = { Text(if (state.useTmdbEpisodeMetadata) "Use IPTV episode details" else "Use TMDB episode details") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { moreActionsExpanded = false; setUseTmdbEpisodeMetadata(!state.useTmdbEpisodeMetadata) })
                        NikDropdownMenuItem(text = { Text(if (state.tmdbEpisodeCacheRefreshing) "Refreshing episode details…" else "Refresh TMDB episode details") },
                            leadingIcon = { Icon(Icons.Default.Cached, null) }, enabled = !state.tmdbEpisodeCacheRefreshing,
                            onClick = { moreActionsExpanded = false; refreshTmdbEpisodeCache() })
                        NikDropdownMenuItem(text = { Text("Series settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { moreActionsExpanded = false; openSettings() })
                    }
                }
            }, layout = { hero, castRow, supplementary, focusCast, focusRelated ->
        val seriesContent: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            state = episodeListState,
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item("series-hero") { hero() }

            val controls: @Composable LazyItemScope.() -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth().background(Color(0xFF090B10)).onSizeChanged { toolbarHeightPx = it.height }
                        .padding(horizontal = if (mobileEpisodeLayout) 14.dp else 24.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availableSeasons.size > 1 && !mobileEpisodeLayout) {
                        LazyRow(
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) { sortRequester.requestFocus(); true }
                                else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { moveWideFocus(SeriesDetailFocus.SEASONS, -1); true } else false
                            }, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 12.dp)
                        ) {
                            items(availableSeasons.sortedDescending()) { season ->
                                val selected = season == selectedSeason
                                val seasonInteraction = remember(season) { MutableInteractionSource() }
                                val seasonFocused by seasonInteraction.collectIsFocusedAsState()
                                FilterChip(
                                    selected = selected,
                                    onClick = { loadSeriesSeason(season) },
                                    interactionSource = seasonInteraction,
                                    modifier = if (selected) Modifier.focusRequester(selectedSeasonRequester) else Modifier,
                                    shape = RoundedCornerShape(10.dp),
                                    // Draw on the chip surface, not its taller
                                    // minimum touch target, so the ring fits.
                                    border = BorderStroke(
                                        if (seasonFocused) 2.dp else 1.dp,
                                        when {
                                            seasonFocused -> Color(0xFFE7E9EF)
                                            selected -> MaterialTheme.colorScheme.primary
                                            else -> Color(0xFF484A52)
                                        }
                                    ),
                                    label = { Text("Season $season", fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                                    leadingIcon = if (selected) {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().then(if (!mobileEpisodeLayout) Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionUp -> { moveWideFocus(SeriesDetailFocus.SORT, -1); true }
                                Key.DirectionDown -> { moveWideFocus(SeriesDetailFocus.SORT, 1); true }
                                else -> false
                            }
                        } else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            if (mobileEpisodeLayout) Box {
                                SeriesToolbarButton(Icons.Default.ExpandMore, selectedSeason?.let { "Season $it" } ?: "Choose season",
                                    modifier = Modifier.focusRequester(seasonButtonRequester)) { seasonMenuExpanded = true }
                                DropdownMenu(seasonMenuExpanded, { seasonMenuExpanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                                    (availableSeasons + listOfNotNull(selectedSeason)).distinct().sortedDescending().forEach { season ->
                                        NikDropdownMenuItem(text = { Text("Season $season") },
                                            modifier = if (season == selectedSeason) Modifier.focusRequester(selectedSeasonRequester) else Modifier,
                                            leadingIcon = if (season == selectedSeason) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = {
                                                seasonMenuExpanded = false
                                                loadSeriesSeason(season)
                                                if (remoteNavigationActive) detailScope.launch {
                                                    withFrameNanos { }; runCatching { seasonButtonRequester.requestFocus() }
                                                }
                                            })
                                    }
                                    NikDropdownMenuItem(text = { Text("Refresh seasons") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = { seasonMenuExpanded = false; refreshCatalog() })
                                }
                            } else Text(
                                text = selectedSeason?.let { "Season $it" } ?: "Episodes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                        }
                        SeriesToolbarButton(
                            icon = Icons.AutoMirrored.Filled.Sort,
                            label = if (episodeSortDescending) "Latest first" else "Earliest first",
                            modifier = Modifier.focusRequester(sortRequester),
                            onClick = {
                                val latestFirst = !episodeSortDescending
                                episodeSortDescending = latestFirst
                            }
                        )
                    }
                            Text(
                                text = seriesEpisodeCountLabel(filteredEpisodes.size, state.episodeHasMore, activeSearchQuery.isNotBlank()),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray
                            )
                    FlowRow(modifier = if (!mobileEpisodeLayout) Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionUp -> { moveWideFocus(SeriesDetailFocus.TOOLS, -1); true }
                            Key.DirectionDown -> {
                                if (expandedPanel == null) { focusLandscapeEpisode(); true } else false
                            }
                            else -> false
                        }
                    } else Modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeriesToolbarButton(if (expandedPanel == SeriesDetailPanel.SEARCH) Icons.Default.ExpandLess else Icons.Default.Search, "Search",
                            modifier = Modifier.focusRequester(toolbarRequester).semantics { selected = expandedPanel == SeriesDetailPanel.SEARCH }
                                .onPreviewKeyEvent { event ->
                                    if (expandedPanel == SeriesDetailPanel.SEARCH && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        detailScope.launch {
                                            episodeListState.scrollToItem(SERIES_INLINE_DETAILS_INDEX, -toolbarHeightPx)
                                            repeat(12) { withFrameNanos { }; if (runCatching { episodeSearchRequester.requestFocus() }.getOrDefault(false)) return@launch }
                                        }; true
                                    } else false
                                }) {
                            if (expandedPanel == SeriesDetailPanel.SEARCH) closeEpisodeSearch()
                            else expandedPanel = SeriesDetailPanel.SEARCH
                        }
                        focusCast?.let { target -> SeriesToolbarButton(
                            if (expandedPanel == SeriesDetailPanel.CAST) Icons.Default.ExpandLess else Icons.Default.People, "Cast",
                            modifier = Modifier.focusRequester(castButtonRequester).semantics { selected = expandedPanel == SeriesDetailPanel.CAST }
                                .onPreviewKeyEvent { event ->
                                    if (expandedPanel == SeriesDetailPanel.CAST && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        detailScope.launch {
                                            episodeListState.scrollToItem(SERIES_INLINE_DETAILS_INDEX, -toolbarHeightPx)
                                            repeat(12) { withFrameNanos { }; if (target()) return@launch }
                                        }; true
                                    } else false
                                }) { expandedPanel = if (expandedPanel == SeriesDetailPanel.CAST) null else SeriesDetailPanel.CAST }
                        }
                        focusRelated?.let { target -> SeriesToolbarButton(
                            if (expandedPanel == SeriesDetailPanel.RELATED) Icons.Default.ExpandLess else Icons.Default.Movie, "Related",
                            modifier = Modifier.focusRequester(relatedButtonRequester).semantics { selected = expandedPanel == SeriesDetailPanel.RELATED }
                                .onPreviewKeyEvent { event ->
                                    if (expandedPanel == SeriesDetailPanel.RELATED && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        detailScope.launch {
                                            episodeListState.scrollToItem(SERIES_INLINE_DETAILS_INDEX, -toolbarHeightPx)
                                            repeat(12) { withFrameNanos { }; if (target()) return@launch }
                                        }; true
                                    } else false
                                }) { expandedPanel = if (expandedPanel == SeriesDetailPanel.RELATED) null else SeriesDetailPanel.RELATED }
                        }
                    }

                }
            }

            if (mobileEpisodeLayout) stickyHeader("series-controls") { controls() }
            else item("series-controls", content = controls)

            item("series-inline-details") {
                AnimatedContent(expandedPanel, contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        (fadeIn(tween(if (reducedMotion) 0 else 120)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 90)))
                            .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(if (reducedMotion) 0 else 180, easing = FastOutSlowInEasing) }))
                    }, label = "seriesDetailsPanel") { panel ->
                    Box(Modifier.fillMaxWidth().onFocusChanged {
                        inlinePanelFocused = it.hasFocus
                        if (it.hasFocus) focusedPanel = panel
                    }) {
                        when (panel) {
                            SeriesDetailPanel.SEARCH -> Surface(
                                Modifier.fillMaxWidth().padding(horizontal = if (mobileEpisodeLayout) 14.dp else 24.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp), color = Color(0xFF181C24)) {
                                OutlinedTextField(
                                    value = searchQuery, onValueChange = { searchQuery = it },
                                    readOnly = episodeIsTv && !episodeTextEditing,
                                    modifier = Modifier.fillMaxWidth().focusRequester(episodeSearchRequester)
                                        .onPreviewKeyEvent { event ->
                                            when {
                                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                                    searchImeWasVisible = false
                                                    episodeTextEditing = false
                                                    keyboardController?.hide()
                                                    runCatching { toolbarRequester.requestFocus() }; true
                                                }
                                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                                    searchImeWasVisible = false
                                                    episodeTextEditing = false
                                                    keyboardController?.hide()
                                                    detailScope.launch {
                                                        if (filteredEpisodes.isNotEmpty()) {
                                                            episodeListState.scrollToItem(SERIES_EPISODE_START_INDEX, -toolbarHeightPx)
                                                            repeat(12) { withFrameNanos { }; if (runCatching { firstEpisodeRequester.requestFocus() }.getOrDefault(false)) return@launch }
                                                        }
                                                    }; true
                                                }
                                                episodeIsTv && !episodeTextEditing && event.type == KeyEventType.KeyUp &&
                                                    event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                                                    episodeTextEditing = true; keyboardController?.show(); true
                                                }
                                                else -> false
                                            }
                                        },
                                    label = { Text("Episode name or number") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    trailingIcon = if (searchQuery.isNotEmpty()) {{
                                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear episode search") }
                                    }} else null,
                                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        searchImeWasVisible = false
                                        episodeTextEditing = false
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }),
                                    shape = RoundedCornerShape(10.dp))
                            }
                            SeriesDetailPanel.CAST -> castRow()
                            SeriesDetailPanel.RELATED -> supplementary()
                            null -> Unit
                        }
                    }
                }
            }

            if (state.items.isEmpty() && state.loading) {
                item("episodes-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Loading episodes from server…", color = Color.LightGray)
                        }
                    }
                }
            } else if (filteredEpisodes.isEmpty()) {
                item("episodes-empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (activeSearchQuery.isBlank()) "No episodes found for this series." else "No episodes matching “${activeSearchQuery.trim()}”",
                            color = Color.LightGray
                        )
                    }
                }
            } else {
                val episodeCard: @Composable (MediaItem) -> Unit = { episode ->
                    val progress = progressByEpisode[episode.id]
                    val isRecent = recentEpisode?.id == episode.id

                    ModernEpisodeCard(
                        episode = episode,
                        series = series,
                        progress = progress,
                        isCurrentResume = isRecent,
                        landscapePoster = !mobileEpisodeLayout,
                        onClick = { play(episode) },
                        offlineDownload = state.offlineDownloads.firstOrNull { download ->
                            download.catalogType == CatalogType.SERIES && download.media.id == episode.id
                        },
                        offlineRevision = state.offlineDownloadRevision,
                        onDownload = { downloadForOffline(episode, CatalogType.SERIES, series) },
                        onRemoveDownload = { removeOfflineDownload(episode, CatalogType.SERIES) },
                        modifier = Modifier
                            .then(if (mobileEpisodeLayout) Modifier.fillMaxWidth() else Modifier.width(200.dp).focusRequester(
                                landscapeRequesters.getOrPut(episode.id) { FocusRequester() }))
                            .then(if (episode.id == filteredEpisodes.firstOrNull()?.id) Modifier.focusRequester(firstEpisodeRequester) else Modifier)
                            .then(if ((entryFocusTarget as? SeriesEpisodeEntryFocus.Episode)?.id == episode.id) {
                                Modifier.focusRequester(entryEpisodeRequester)
                            } else Modifier)
                            .onFocusChanged { if (it.hasFocus) focusedEpisodeId = episode.id }
                            .padding(
                                horizontal = if (mobileEpisodeLayout) 14.dp else 0.dp,
                                vertical = if (mobileEpisodeLayout) 4.dp else 6.dp
                            )
                    )
                }
                if (mobileEpisodeLayout) items(filteredEpisodes, key = { it.id }) { episodeCard(it) }
                else item("episode-row") {
                    LazyRow(state = episodeRowState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionUp -> { moveWideFocus(SeriesDetailFocus.EPISODES, -1); true }
                                Key.DirectionDown -> true
                                else -> false
                            }
                        }) {
                            items(filteredEpisodes, key = { it.id }) { episodeCard(it) }
                            if ((state.episodeHasMore || state.episodeLoadingMore) && activeSearchQuery.isBlank()) item("episodes-load-more") {
                                Box(Modifier.width(200.dp).heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                                    NikTvSecondaryActionButton(onClick = loadMoreEpisodes,
                                        modifier = Modifier.fillMaxWidth().focusRequester(moreRequester).onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) { focusLandscapeEpisode(); true } else false
                                        }) {
                                        if (state.episodeLoadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (state.episodeLoadingMore) "Loading episodes…" else "Load more episodes")
                                    }
                                }
                            }
                        }
                }
                if (mobileEpisodeLayout && (state.episodeHasMore || state.episodeLoadingMore) && activeSearchQuery.isBlank()) {
                    item("episodes-load-more") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                            NikTvSecondaryActionButton(
                                onClick = loadMoreEpisodes,
                                // Keeping the button enabled preserves the current TV
                                // focus target while the next page is being appended.
                                enabled = true,
                                modifier = Modifier.heightIn(min = 48.dp).focusRequester(moreRequester)
                                    .then(if (!mobileEpisodeLayout) Modifier.onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) { focusLandscapeEpisode(); true } else false
                                    } else Modifier).remoteFocusFrame(RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (state.episodeLoadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.episodeLoadingMore) "Loading more episodes…" else "Load more episodes", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        }
        if (mobileEpisodeLayout) seriesContent()
        else com.nikhil.niktv.discovery.DiscoveryVerticalFocusViewport(wideFocusCoordinator, seriesContent)
        })

    }

}

@Composable
private fun SeriesToolbarButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    NikTvSecondaryActionButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp).widthIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModernEpisodeCard(
    episode: MediaItem,
    series: MediaItem,
    progress: PlaybackProgress?,
    isCurrentResume: Boolean,
    onClick: () -> Unit,
    offlineDownload: OfflineMediaDownload?,
    offlineRevision: Long,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    landscapePoster: Boolean = false,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    var episodeActionsExpanded by remember(episode.id) { mutableStateOf(false) }
    var descriptionDialogOpen by remember(episode.id) { mutableStateOf(false) }
    val episodeFocusRequester = remember(episode.id) { FocusRequester() }
    val episodeInteractionSource = remember(episode.id) { MutableInteractionSource() }
    val episodeContext = LocalContext.current
    val episodeConfiguration = LocalConfiguration.current
    val isTv = episodeContext.isTvLikeDevice(episodeConfiguration)
    var confirmRemoval by remember { mutableStateOf(false) }
    val episodeHost = androidx.compose.ui.platform.LocalView.current
    var hadOverlay by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(landscapePoster, episodeActionsExpanded, descriptionDialogOpen, confirmRemoval) {
        if (landscapePoster) {
            if (episodeActionsExpanded || descriptionDialogOpen || confirmRemoval) hadOverlay = true
            else if (hadOverlay) {
                hadOverlay = false
                repeat(30) { withFrameNanos { }
                    if (episodeHost.hasWindowFocus() && runCatching { episodeFocusRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
    val offlineInfo = observedOfflineDownloadInfo(offlineDownload)
    val offlineStatus = offlineInfo?.status
    var downloadRequested by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(downloadRequested, offlineDownload?.requestId) {
        when {
            offlineDownload != null -> downloadRequested = false
            downloadRequested -> {
                // URL resolution and persistence happen asynchronously. Keep the
                // row responsive immediately, but allow another attempt if they fail.
                delay(5_000L)
                downloadRequested = false
            }
        }
    }
    val displayedOfflineStatus =
        if (downloadRequested && offlineDownload == null) OfflineDownloadStatus.QUEUED else offlineStatus

    val mobileLayout =
        !isTv &&
            episodeConfiguration.smallestScreenWidthDp < 600
    val imageUrl = episode.logo ?: series.logo
    val episodeDescription = episode.description?.takeUnless { description ->
        description.trim().equals(series.description?.trim(), ignoreCase = true)
    }

    val progressFraction = remember(progress) {
        if (progress != null && progress.durationMillis > 0L) {
            (progress.positionMillis.toFloat() / progress.durationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    // Anchor the menu to this row, rather than the enclosing episode list.
    Box(modifier = modifier) {
    if (landscapePoster) com.nikhil.niktv.discovery.DiscoveryEpisodeTile(
        title = episode.displayTitle(series), artwork = imageUrl,
        label = listOfNotNull((episode.seasonNumber ?: episode.title.seasonNumberFromTitle())?.let { "S$it" },
            (episode.episodeNumber ?: episode.title.episodeNumberFromTitle())?.let { "E$it" }).joinToString(" · "),
        metadata = episode.displayAirDate().orEmpty(), progress = progressFraction,
        remaining = episodeRemainingLabel(progress), resume = isCurrentResume,
        modifier = Modifier.fillMaxWidth().focusRequester(episodeFocusRequester),
        onClick = onClick, onLongClick = { episodeActionsExpanded = true })
    else Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            focused || episodeActionsExpanded -> Color(0xFF272B33)
            isCurrentResume -> Color(0xFF1B1E24)
            else -> Color(0xFF14171D)
        },
        border = when {
            focused || episodeActionsExpanded -> BorderStroke(2.dp, Color(0xFFF1F3F5))
            isCurrentResume -> BorderStroke(1.dp, Color(0xFF6E3036))
            else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        },
        shadowElevation = if (focused) 8.dp else 0.dp,
        modifier = Modifier.fillMaxWidth()
            .focusRequester(episodeFocusRequester)
            .onFocusChanged { focused = it.isFocused }
            .remoteCombinedClickable(
                interactionSource = episodeInteractionSource,
                onClick = onClick,
                onLongClick = { episodeActionsExpanded = true }
            )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (mobileLayout) 8.dp else 10.dp,
                vertical = if (mobileLayout) 8.dp else 9.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (mobileLayout) 10.dp else 14.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (mobileLayout) {
                            Modifier
                                .width(80.dp)
                                .aspectRatio(16f / 9f)
                        } else {
                            Modifier
                                .width(120.dp)
                                .height(68.dp)
                        }
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = artworkRequest(episodeContext, episode.copy(logo = imageUrl)),
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(Color(0xFFE50914))
                        )
                    }
                }

            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val epNumber = episode.episodeNumber ?: episode.title.episodeNumberFromTitle()
                val seasonNumber = episode.seasonNumber ?: episode.title.seasonNumberFromTitle()
                val badge = when {
                    seasonNumber != null && epNumber != null -> "S${seasonNumber}:E${epNumber}"
                    epNumber != null -> "EP $epNumber"
                    else -> null
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(.3f)) { badge?.let { EpisodeMetadataBadge(it, modifier = Modifier.fillMaxWidth()) } }
                    Box(Modifier.weight(.38f)) { episode.displayAirDate()?.let { EpisodeMetadataBadge(it, modifier = Modifier.fillMaxWidth(), muted = true) } }
                    Box(Modifier.weight(.32f)) { episodeRemainingLabel(progress)?.let {
                        EpisodeMetadataBadge(it, highlighted = isCurrentResume, modifier = Modifier.fillMaxWidth())
                    } }
                }
                episode.episodeAddedAtMillis?.let { added ->
                    Text("Added ${formatEpisodeAddedTime(added)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA8ADB7))
                }

                Text(
                    text = episode.displayTitle(series),
                    style = if (mobileLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!episodeDescription.isNullOrBlank()) {
                    Text(
                        text = episodeDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray.copy(alpha = 0.8f),
                        maxLines = if (mobileLayout) 2 else 1,
                        minLines = if (mobileLayout) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

        }
    }
    DropdownMenu(
        expanded = episodeActionsExpanded,
        onDismissRequest = { episodeActionsExpanded = false },
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (offlineDownload == null || displayedOfflineStatus in setOf(
                OfflineDownloadStatus.FAILED,
                OfflineDownloadStatus.MISSING
            )
        ) {
            NikDropdownMenuItem(
                text = { Text("Download episode") },
                leadingIcon = { Icon(Icons.Default.DownloadForOffline, null) },
                enabled = !downloadRequested,
                onClick = {
                    episodeActionsExpanded = false
                    downloadRequested = true
                    onDownload()
                }
            )
        } else {
            NikDropdownMenuItem(
                text = {
                    Text(
                        if (displayedOfflineStatus == OfflineDownloadStatus.COMPLETE) {
                            "Delete download"
                        } else {
                            "Cancel download"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                onClick = {
                    episodeActionsExpanded = false
                    confirmRemoval = true
                }
            )
        }
        episodeDescription?.takeIf(String::isNotBlank)?.let {
            NikDropdownMenuItem(
                text = { Text("View description") },
                leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = {
                    episodeActionsExpanded = false
                    descriptionDialogOpen = true
                }
            )
        }
    }
    } // Episode row and menu share a fixed overlay anchor.
    episodeDescription?.takeIf(String::isNotBlank)?.let { description ->
        if (descriptionDialogOpen) {
            FullDescriptionDialog(
                title = episode.displayTitle(series),
                description = description,
                cast = episode.cast,
                dismiss = { descriptionDialogOpen = false }
            )
        }
    }
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        title = { Text(if (offlineStatus == OfflineDownloadStatus.COMPLETE) "Delete download?" else "Cancel download?") },
        text = { Text("Remove “${episode.displayTitle(series)}” from offline downloads?") },
        dismissButton = { NikTvTextActionButton(onClick = { confirmRemoval = false }) { Text("Keep") } },
        confirmButton = { NikTvPrimaryActionButton(onClick = { confirmRemoval = false; onRemoveDownload() }) { Text("Remove") } }
    )
}


@Composable
private fun EpisodeMetadataBadge(text: String, highlighted: Boolean = false, modifier: Modifier = Modifier, muted: Boolean = false) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val lines = if (fontScale > 1.25f) 2 else 1
    Surface(modifier = modifier, shape = RoundedCornerShape(5.dp),
        color = if (highlighted) Color(0xFF3A2226) else Color(0xFF292D35),
        border = BorderStroke(1.dp, if (highlighted) Color(0xFF8E4149) else Color.White.copy(alpha = .1f))) {
        Box(Modifier.height((if (lines == 2) 40.dp else 24.dp) * fontScale).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (muted) Color(0xFFA8ADB7) else Color(0xFFF2B8BD),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}
