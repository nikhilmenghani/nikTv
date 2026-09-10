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
internal fun ModernBrowseScreen(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    selectCategory: (Category) -> Unit,
    play: (MediaItem) -> Unit,
    openTrendingMovie: (TrendingMovie) -> Unit,
    openTrendingSeries: (TrendingSeries) -> Unit,
    openHome: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    clearRecent: (FavoriteKind) -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    setBrowseLayout: (BrowseLayout) -> Unit,
    openFavorite: (FavoriteItem) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    refreshCatalog: () -> Unit,
    openCategoryManager: (CatalogType) -> Unit,
    loadMoreCategorySection: (Category) -> Unit,
    setTmdbSections: (DashboardSurface, List<TmdbHomeSection>) -> Unit,
    resetScreenConfiguration: (DashboardSurface) -> Unit,
    loadMoreCatalog: () -> Unit,
    openModernTmdbSection: (TmdbHomeSection) -> Unit,
    openModernIptvCategory: (Category) -> Unit,
    closeModernSection: () -> Unit,
    loadMoreModernTmdbSection: () -> Unit
) {
    val home = state.homeOpen
    val layoutToggleRequester = remember { FocusRequester() }
    val firstChannelRequester = remember { FocusRequester() }
    var tmdbSetupOpen by rememberSaveable { mutableStateOf(false) }
    var resetConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    val dashboardSurface = if (home) DashboardSurface.HOME else when (state.selectedType) {
        CatalogType.LIVE_TV -> DashboardSurface.LIVE_TV
        CatalogType.MOVIES -> DashboardSurface.MOVIES
        CatalogType.SERIES -> DashboardSurface.SERIES
        CatalogType.RADIO -> DashboardSurface.LIVE_TV
    }
    val selectedTmdbSections = state.tmdbSectionsBySurface[dashboardSurface].orEmpty()

    // Tile-first sections are the sole browse interface for every destination.
    run {
        ModernTileBrowseScreen(
            state = state,
            dashboardSurface = dashboardSurface,
            openHome = openHome,
            selectType = selectType,
                    openFavorites = openFavorites,
            openSearch = openSearch,
            openSettings = openSettings,
            openProfileSwitcher = openProfileSwitcher,
            openRecent = openRecent,
            removeRecent = removeRecent,
            openWatchedEpisode = openWatchedEpisode,
            dismissWatchedEpisode = dismissWatchedEpisode,
            openTmdbSection = openModernTmdbSection,
            openIptvCategory = openModernIptvCategory,
            closeSection = closeModernSection,
            openTmdbMovie = openTrendingMovie,
            openTmdbSeries = openTrendingSeries,
            openIptvItem = play,
            toggleFavorite = toggleFavoriteEntry,
            loadMoreTmdb = loadMoreModernTmdbSection,
            loadMoreIptv = loadMoreCatalog,
            refreshIptv = refreshCatalog,
            configureTmdb = { tmdbSetupOpen = true },
            configureIptv = openCategoryManager,
            resetSurface = { resetConfirmationOpen = true }
        )

        if (tmdbSetupOpen) {
            TmdbHomeSectionsDialog(
                selected = selectedTmdbSections,
                surface = dashboardSurface,
                close = { tmdbSetupOpen = false },
                save = {
                    setTmdbSections(dashboardSurface, it)
                    tmdbSetupOpen = false
                }
            )
        }
        if (resetConfirmationOpen) {
            ProjectCardConfirmationDialog(
                title = "Reset ${dashboardSurface.displayTitle()}?",
                message = "This clears every IPTV and TMDB selection for this screen so you can configure it again from nothing.",
                confirmLabel = "Reset screen",
                close = { resetConfirmationOpen = false },
                confirm = {
                    resetScreenConfiguration(dashboardSurface)
                    resetConfirmationOpen = false
                }
            )
        }
        return
    }

    val tmdbLayoutLoading = selectedTmdbSections.any { it in state.tmdbSectionsLoading }
    val tmdbLoadingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(tmdbLayoutLoading) {
        if (tmdbLayoutLoading) {
            withFrameNanos { }
            runCatching { tmdbLoadingFocusRequester.requestFocus() }
        }
    }

    // MOVIE_DASHBOARD_PAGINATION_FOCUS_V2
    //
    // Movies pagination owns focus explicitly. The Load More item remains
    // focusable during the request, then focus is handed to the first movie
    // appended by that request instead of falling back to the Profile control.
    val catalogGridState = rememberLazyGridState()
    val movieFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    /*
     * LIVE_TV_DASHBOARD_PAGINATION_FOCUS_V13
     *
     * Live TV uses the same focus-handoff principle as Movies. The focused
     * Load More button remains alive during the request, then focus moves to
     * the first newly appended channel.
     */
    val liveTvFocusRequesters =
        remember { mutableMapOf<String, FocusRequester>() }

    var liveTvLoadMorePending by remember {
        mutableStateOf(false)
    }
    var liveTvLoadMoreObservedLoading by remember {
        mutableStateOf(false)
    }
    var liveTvLoadMoreStartItemCount by remember {
        mutableIntStateOf(0)
    }
    var liveTvLoadMoreFirstVisibleItemIndex by remember {
        mutableIntStateOf(0)
    }
    var liveTvLoadMoreFirstVisibleItemScrollOffset by remember {
        mutableIntStateOf(0)
    }

    var movieLoadMorePending by remember { mutableStateOf(false) }
    var movieLoadMoreObservedLoading by remember { mutableStateOf(false) }
    var movieLoadMoreStartItemCount by remember { mutableIntStateOf(0) }

    /*
     * Preserve the exact grid viewport while a Movies page is loading.
     * This lets pagination feel like new tiles were simply appended below
     * the current content instead of causing the existing grid to jump.
     */
    var movieLoadMoreFirstVisibleItemIndex by remember {
        mutableIntStateOf(0)
    }
    var movieLoadMoreFirstVisibleItemScrollOffset by remember {
        mutableIntStateOf(0)
    }

    val hero = if (home) state.recentlyPlayed.firstOrNull()?.media ?: state.favorites.firstOrNull()?.media else state.items.firstOrNull()
    val configuration = LocalConfiguration.current
    val browseContext = LocalContext.current
    val isTv = browseContext.isTvLikeDevice(configuration)
    val isWide = configuration.screenWidthDp >= 720 || isTv
    val mobileUiDesign by rememberMobileUiDesign()
    val profileKey = state.savedProfile?.cacheKey().orEmpty()

    var liveTvColumns by rememberSaveable {
        mutableIntStateOf(
            if (isWide) 2 else 1
        )
    }

    /*
     * LIVE_TV_CARD_HIERARCHY_V33
     *
     * Four Fire TV columns leave too little room for real-world station names.
     * Three is the TV maximum; the selector still supports 1-3 columns.
     *
     * ADAPTIVE_LIVE_TV_DENSITY_V34
     *
     * Touch devices prioritize readable station identity over raw density:
     * phones stay one-column and tablets stay at a two-column maximum.
     */
    val maxLiveTvColumns = when {
        isTv -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    LaunchedEffect(maxLiveTvColumns) {
        if (liveTvColumns > maxLiveTvColumns) {
            liveTvColumns = maxLiveTvColumns
        }
    }

    val maxMovieColumns = when {
        isTv || configuration.screenWidthDp >= 1400 -> 8
        configuration.screenWidthDp >= 1200 -> 7
        configuration.screenWidthDp >= 1000 -> 6
        configuration.screenWidthDp >= 800 -> 5
        configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }

    val defaultMovieColumns = when {
        isTv -> 6
        configuration.screenWidthDp >= 1000 -> 5
        configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }

    val storedMovieColumns by rememberCatalogColumns(
        profileKey = profileKey,
        type = CatalogType.MOVIES,
        defaultValue = defaultMovieColumns
    )

    val movieColumns = storedMovieColumns.coerceIn(2, maxMovieColumns)

    val columns = when {
        state.selectedType == CatalogType.LIVE_TV -> liveTvColumns
        state.selectedType == CatalogType.MOVIES -> movieColumns
        isWide -> 6
        else -> 3
    }

    val aspectRatio =
        if (state.selectedType == CatalogType.MOVIES) 1f else 16f / 9f
    val gridSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    var initialChannelFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.selectedType, state.selectedCategory?.id, state.items.firstOrNull()?.id) {
        if (!initialChannelFocused && !home && state.selectedType == CatalogType.LIVE_TV && state.items.isNotEmpty()) {
            initialChannelFocused = true
            delay(180L)
            runCatching { firstChannelRequester.requestFocus() }
        }
    }

    /*
     * Seamless Movies pagination.
     *
     * Keep the focused Load More item in the composition while loading.
     * Once state.items grows, scroll to the actual first appended movie,
     * wait until Compose lays it out, then request focus on that tile.
     */
    LaunchedEffect(
        movieLoadMorePending,
        state.catalogLoadingMore,
        state.items.size,
        state.selectedType,
        state.selectedCategory?.id
    ) {
        if (!movieLoadMorePending) {
            return@LaunchedEffect
        }

        if (state.selectedType !in setOf(CatalogType.MOVIES, CatalogType.SERIES)) {
            movieLoadMorePending = false
            movieLoadMoreObservedLoading = false
            return@LaunchedEffect
        }

        if (state.catalogLoadingMore) {
            movieLoadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewMovies =
            state.items.size > movieLoadMoreStartItemCount

        val loadFinished =
            movieLoadMoreObservedLoading || receivedNewMovies

        if (!loadFinished) {
            return@LaunchedEffect
        }

        /*
         * Normally this is the first newly loaded movie.
         * If the portal returns an empty final page, move focus to the
         * last existing movie before removing Load More so focus still
         * cannot escape to Profile.
         */
        val targetMovieIndex =
            if (receivedNewMovies) {
                movieLoadMoreStartItemCount
            } else {
                state.items.lastIndex
            }

        val targetMovie = state.items.getOrNull(targetMovieIndex)

        if (targetMovie != null) {
            val requester =
                movieFocusRequesters.getOrPut(targetMovie.id) {
                    FocusRequester()
                }

            /*
             * LazyVerticalGrid indexes include the fixed full-width items
             * above the catalog cards.
             */
            val catalogItemOffset =
                if (isWide) 3 else 4

            val targetGridIndex =
                catalogItemOffset + targetMovieIndex

            /*
             * MOVIE_DASHBOARD_SMOOTH_APPEND_V6
             *
             * First restore the exact pre-request viewport. This neutralizes
             * any LazyGrid anchor movement caused by the full-width Load More
             * item moving after newly appended movies.
             */
            catalogGridState.scrollToItem(
                movieLoadMoreFirstVisibleItemIndex,
                movieLoadMoreFirstVisibleItemScrollOffset
            )

            withFrameNanos { }

            val targetAlreadyVisible =
                catalogGridState
                    .layoutInfo
                    .visibleItemsInfo
                    .any { visible ->
                        visible.index == targetGridIndex
                    }

            if (!targetAlreadyVisible) {
                /*
                 * Do not align the new tile hard to the top. Keep roughly
                 * two-thirds of a viewport of previous-page context above it,
                 * which makes the append read as a continuation rather than
                 * a page jump.
                 */
                val layoutInfo =
                    catalogGridState.layoutInfo

                val viewportHeight =
                    (
                        layoutInfo.viewportEndOffset -
                            layoutInfo.viewportStartOffset
                        )
                        .coerceAtLeast(1)

                val contextualScrollOffset =
                    -(viewportHeight * 2 / 3)

                catalogGridState.animateScrollToItem(
                    index = targetGridIndex,
                    scrollOffset = contextualScrollOffset
                )
            }

            withTimeoutOrNull(1_500L) {
                snapshotFlow {
                    catalogGridState
                        .layoutInfo
                        .visibleItemsInfo
                        .any { visible ->
                            visible.index == targetGridIndex
                        }
                }.first { it }
            }

            withFrameNanos { }

            val focused = runCatching {
                requester.requestFocus()
            }.isSuccess

            if (!focused) {
                delay(60L)
                runCatching {
                    requester.requestFocus()
                }
            }
        }

        /*
         * Clear pending only AFTER focus was moved away from Load More.
         * On the final page this allows the button to disappear safely.
         */
        movieLoadMorePending = false
        movieLoadMoreObservedLoading = false
    }

    /*
     * LIVE_TV_SMOOTH_APPEND_V13
     *
     * Preserve the viewport while channels append, then smoothly reveal and
     * focus the first new channel. If the final page is empty, focus the last
     * existing channel before the Load More item disappears.
     */
    LaunchedEffect(
        liveTvLoadMorePending,
        state.catalogLoadingMore,
        state.items.size,
        state.selectedType,
        state.selectedCategory?.id
    ) {
        if (!liveTvLoadMorePending) {
            return@LaunchedEffect
        }

        if (state.selectedType != CatalogType.LIVE_TV) {
            liveTvLoadMorePending = false
            liveTvLoadMoreObservedLoading = false
            return@LaunchedEffect
        }

        if (state.catalogLoadingMore) {
            liveTvLoadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewChannels =
            state.items.size > liveTvLoadMoreStartItemCount

        val loadFinished =
            liveTvLoadMoreObservedLoading ||
                receivedNewChannels

        if (!loadFinished) {
            return@LaunchedEffect
        }

        val targetChannelIndex =
            if (receivedNewChannels) {
                liveTvLoadMoreStartItemCount
            } else {
                state.items.lastIndex
            }

        val targetChannel =
            state.items.getOrNull(targetChannelIndex)

        if (targetChannel != null) {
            val requester =
                if (targetChannelIndex == 0) {
                    firstChannelRequester
                } else {
                    liveTvFocusRequesters
                        .getOrPut(targetChannel.id) {
                            FocusRequester()
                        }
                }

            val catalogItemOffset =
                if (isWide) 3 else 4

            val targetGridIndex =
                catalogItemOffset +
                    targetChannelIndex

            catalogGridState.scrollToItem(
                liveTvLoadMoreFirstVisibleItemIndex,
                liveTvLoadMoreFirstVisibleItemScrollOffset
            )

            withFrameNanos { }

            val targetAlreadyVisible =
                catalogGridState
                    .layoutInfo
                    .visibleItemsInfo
                    .any { visible ->
                        visible.index ==
                            targetGridIndex
                    }

            if (!targetAlreadyVisible) {
                val layoutInfo =
                    catalogGridState.layoutInfo

                val viewportHeight =
                    (
                        layoutInfo.viewportEndOffset -
                            layoutInfo.viewportStartOffset
                        )
                        .coerceAtLeast(1)

                val contextualScrollOffset =
                    -(viewportHeight * 2 / 3)

                catalogGridState
                    .animateScrollToItem(
                        index = targetGridIndex,
                        scrollOffset =
                            contextualScrollOffset
                    )
            }

            withTimeoutOrNull(1_500L) {
                snapshotFlow {
                    catalogGridState
                        .layoutInfo
                        .visibleItemsInfo
                        .any { visible ->
                            visible.index ==
                                targetGridIndex
                        }
                }.first { it }
            }

            withFrameNanos { }

            val focused =
                runCatching {
                    requester.requestFocus()
                }.isSuccess

            if (!focused) {
                delay(60L)
                runCatching {
                    requester.requestFocus()
                }
            }
        }

        liveTvLoadMorePending = false
        liveTvLoadMoreObservedLoading = false
    }

    Box(Modifier.fillMaxSize()) {
    ModernGrid(
        columns = columns,
        state = catalogGridState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            if (!isWide) item("modern-top", span = gridSpan) {
                if (mobileUiDesign.usesYouTubeOn(configuration)) {
                    YouTubeStyleTopBar(state, openSearch, openSettings, openProfileSwitcher)
                } else {
                    ModernTopBar(state, home, openHome, selectType, openFavorites, openSearch, openSettings, openProfileSwitcher)
                }
            }
            if (!home && state.browseLayout != BrowseLayout.SECTIONS) item("modern-hero", span = gridSpan) {
                val recent = state.recentlyPlayed.firstOrNull()
                when {
                    !home && state.selectedType == CatalogType.LIVE_TV ->
                        LiveTvPreviewPlaceholder(state.selectedCategory?.title)

                    !home && state.selectedType == CatalogType.MOVIES ->
                        MovieBrowseHeaderPlaceholder(state.selectedCategory?.title)

                    else ->
                        ModernHero(
                            hero,
                            if (home && recent != null) {{ openRecent(recent) }} else null,
                            if (!home && hero != null) {{ play(hero) }} else null,
                            state.savedProfile?.name.orEmpty()
                        )
                }
            }
            if (!home && state.browseLayout != BrowseLayout.SECTIONS) item("modern-categories", span = gridSpan) {
                val profileKey = state.savedProfile?.cacheKey()
                val filterKey = "$profileKey|${state.selectedType.name}"
                val isFiltered = state.categoryFilters.containsKey(filterKey)
                Surface(color = Color(0xFF111111), shadowElevation = 8.dp) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // CATEGORY_REFRESH_FIRST_V6
                        item {
                            AssistChip(
                                onClick = refreshCatalog,
                                modifier = Modifier
                                    .remoteFocusFrame(CircleShape)
                                    .focusProperties {
                                        if (state.items.isNotEmpty()) {
                                            down = layoutToggleRequester
                                        }
                                    },
                                label = { Text("Refresh") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Refresh,
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        item {
                            AssistChip(
                                onClick = { openCategoryManager(state.selectedType) },
                                modifier = Modifier.remoteFocusFrame(CircleShape).focusProperties {
                                    if (state.items.isNotEmpty()) {
                                        down = layoutToggleRequester
                                    }
                                },
                                label = { Text(if (isFiltered) "Filtered (${state.categories.size})" else "Categories") },
                                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) }
                            )
                        }
                        if (dashboardSurface == DashboardSurface.MOVIES || dashboardSurface == DashboardSurface.SERIES) {
                            item {
                                AssistChip(
                                    onClick = { tmdbSetupOpen = true },
                                    modifier = Modifier.remoteFocusFrame(CircleShape),
                                    label = { Text("TMDB sections") },
                                    leadingIcon = {
                                        Icon(Icons.Default.DashboardCustomize, null, Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                        item {
                            AssistChip(
                                onClick = { resetConfirmationOpen = true },
                                modifier = Modifier.remoteFocusFrame(CircleShape),
                                label = { Text("Reset to defaults") },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp)) }
                            )
                        }
                        items(state.categories, key = { it.id }) { category ->
                            val selected = state.selectedCategory?.id == category.id
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(onClick = { selectCategory(category) }, modifier = Modifier.remoteFocusFrame(CircleShape).focusProperties {
                                    if (state.items.isNotEmpty()) {
                                        down = layoutToggleRequester
                                    }
                                }) {
                                    Text(category.title, color = if (selected) Color.White else Color.LightGray)
                                }
                                Box(Modifier.width(30.dp).height(3.dp).background(if (selected) Color(0xFFE50914) else Color.Transparent))
                            }
                        }
                    }
                }
            }
            if (home) {
                item("home-dashboard-setup", span = gridSpan) {
                    ModernSectionHeader(
                        "Your Home dashboard",
                        "Choose IPTV categories and TMDB discovery rows",
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { tmdbSetupOpen = true }, modifier = Modifier.remoteFocusFrame()) {
                                    Icon(Icons.Default.DashboardCustomize, null); Spacer(Modifier.width(6.dp)); Text("TMDB sections")
                                }
                                TextButton(onClick = { resetConfirmationOpen = true }, modifier = Modifier.remoteFocusFrame()) {
                                    Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset to defaults")
                                }
                            }
                        }
                    )
                }
                val recents = state.recentlyPlayed.filter {
                    it.kind == FavoriteKind.MOVIE || it.kind == FavoriteKind.SERIES
                }
                val newEpisodes = state.watchedSeries.flatMap { watched -> watched.newEpisodes.map { watched to it } }
                if (newEpisodes.isNotEmpty()) item("watch-list-updates", span = gridSpan) {
                    ModernRail(
                        "New Episodes",
                        newEpisodes,
                        media = { (watched, episode) ->
                            if (episode.logo.isNullOrBlank() && !watched.series.logo.isNullOrBlank()) episode.copy(logo = watched.series.logo)
                            else episode
                        },
                        open = { (watched, episode) -> openWatchedEpisode(watched, episode) },
                        aspectRatio = { 2f / 3f },
                        progress = { (_, episode) -> state.playbackProgress.progressFor(episode) },
                        subtitle = { (watched, episode) ->
                            listOfNotNull(
                                watched.series.title,
                                episode.seasonNumber?.let { season -> episode.episodeNumber?.let { ep -> "S${season} · E${ep}" } }
                            ).joinToString(" · ")
                        },
                        titleMaxLines = 2,
                        subtitleMaxLines = 2
                    )
                }
                if (recents.isNotEmpty()) item("continue", span = gridSpan) {
                    ModernRail(
                        "Continue Watching", recents, { it.media }, openRecent,
                        aspectRatio = { 2f / 3f },
                        progress = { recent -> state.playbackProgress.progressFor(recent.lastPlayed ?: recent.media) },
                        remove = removeRecent,
                        isFavorite = { recent -> state.favorites.any { it.kind == recent.kind && it.media.id == recent.media.id } },
                        toggleFavorite = { recent ->
                            toggleFavoriteEntry(FavoriteItem(recent.kind, recent.media, recent.series))
                        }
                    )
                }

                val showTrendingMovies = TmdbHomeSection.TRENDING_MOVIES in selectedTmdbSections && (
                    state.trendingMoviesLoading ||
                        state.trendingMovies.isNotEmpty() ||
                        state.trendingMoviesError != null)
                val showTrendingSeries = TmdbHomeSection.TRENDING_SERIES in selectedTmdbSections && (
                    state.trendingSeriesLoading ||
                        state.trendingSeries.isNotEmpty() ||
                        state.trendingSeriesError != null)
                val showThrillerMovies = TmdbHomeSection.THRILLER in selectedTmdbSections && (
                    state.thrillerMoviesLoading ||
                        state.thrillerMovies.isNotEmpty() ||
                        state.thrillerMoviesError != null)

                // Render every configured row in the same order selected in
                // Settings. The original Home implementation only inserted
                // three hard-coded discovery rows even though all selected
                // sections had already been fetched by the ViewModel.
                selectedTmdbSections.forEach { section ->
                    when (section) {
                        TmdbHomeSection.TRENDING_MOVIES -> if (showTrendingMovies) {
                            item("home-tmdb-${section.name}", span = gridSpan) {
                                DashboardMovieRail(section.title, state.trendingMovies, state.trendingMoviesLoading, state.trendingMoviesError, openTrendingMovie)
                            }
                        }
                        TmdbHomeSection.TRENDING_SERIES -> if (showTrendingSeries) {
                            item("home-tmdb-${section.name}", span = gridSpan) {
                                DashboardSeriesRail(state.trendingSeries, state.trendingSeriesLoading, state.trendingSeriesError, openTrendingSeries, title = section.title)
                            }
                        }
                        TmdbHomeSection.THRILLER -> if (showThrillerMovies) {
                            item("home-tmdb-${section.name}", span = gridSpan) {
                                DashboardMovieRail(section.title, state.thrillerMovies, state.thrillerMoviesLoading, state.thrillerMoviesError, openTrendingMovie)
                            }
                        }
                        else -> if (section.series) {
                            val row = state.tmdbHomeSeriesRows[section].orEmpty()
                            if (row.isNotEmpty()) item("home-tmdb-${section.name}", span = gridSpan) {
                                DashboardSeriesRail(row, false, null, openTrendingSeries, title = section.title)
                            }
                        } else {
                            val row = state.tmdbHomeMovieRows[section].orEmpty()
                            if (row.isNotEmpty()) item("home-tmdb-${section.name}", span = gridSpan) {
                                DashboardMovieRail(section.title, row, false, null, openTrendingMovie)
                            }
                        }
                    }
                }

                if (state.favorites.isNotEmpty()) item("my-list", span = gridSpan) {
                    ModernRail(
                        "My List", state.favorites, { it.media }, openFavorite,
                        aspectRatio = { 2f / 3f },
                        subtitle = { favorite -> listOfNotNull(favorite.kind.mediaTypeLabel(), favorite.categoryTitle).joinToString(" · ") },
                        titleMaxLines = Int.MAX_VALUE,
                        subtitleMaxLines = 2
                    )
                }
                FavoriteKind.entries.filterNot { it == FavoriteKind.EPISODE }.forEach { kind ->
                    val entries = recents.filter { it.kind == kind }
                    if (entries.isNotEmpty()) item("recent-${kind.name}", span = gridSpan) {
                        ModernRail(
                            "Recently Played ${kind.sectionTitle()}", entries, { it.media }, openRecent,
                            aspectRatio = { 2f / 3f },
                            remove = removeRecent,
                            isFavorite = { recent -> state.favorites.any { it.kind == recent.kind && it.media.id == recent.media.id } },
                            toggleFavorite = { recent ->
                                toggleFavoriteEntry(FavoriteItem(recent.kind, recent.media, recent.series))
                            },
                            clear = { clearRecent(kind) }
                        )
                    }
                }
                val discoveryVisible =
                    state.trendingMovies.isNotEmpty() ||
                        state.trendingSeries.isNotEmpty() ||
                        state.thrillerMovies.isNotEmpty() ||
                        state.trendingMoviesLoading ||
                        state.trendingSeriesLoading ||
                        state.thrillerMoviesLoading

                if (
                    recents.isEmpty() &&
                    state.favorites.isEmpty() &&
                    !discoveryVisible
                ) item("home-empty", span = gridSpan) {
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.PlayCircle, null, Modifier.size(64.dp), tint = Color.DarkGray)
                            Text("Nothing watched yet", color = Color.LightGray, style = MaterialTheme.typography.titleMedium)
                            Text("Browse Live TV, Movies, or Series to get started", color = Color.Gray)
                        }
                    }
                }
            } else if (state.categories.isEmpty()) {
                item("modern-empty-categories", span = gridSpan) {
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.FilterListOff, null, Modifier.size(48.dp), tint = Color.LightGray)
                            Text("No categories enabled for ${state.selectedType.title}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("Adjust your category filters to include content.", color = Color.LightGray)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openCategoryManager(state.selectedType) }) { Text("Manage categories") }
                                if (dashboardSurface == DashboardSurface.MOVIES || dashboardSurface == DashboardSurface.SERIES) {
                                    Button(onClick = { tmdbSetupOpen = true }) { Text("TMDB sections") }
                                }
                            }
                        }
                    }
                }
            } else if (state.browseLayout == BrowseLayout.SECTIONS) {
                val sectionCache = state.browseCachesByType[state.selectedType]
                item("section-category-controls-${state.selectedType}", span = gridSpan) {
                    ModernSectionHeader(
                        title = state.selectedType.title,
                        subtitle = "${state.categories.take(10).size} dashboard categories · up to 10",
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { openCategoryManager(state.selectedType) }, modifier = Modifier.remoteFocusFrame()) {
                                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Categories")
                                }
                                if (dashboardSurface != DashboardSurface.LIVE_TV) {
                                    TextButton(onClick = { tmdbSetupOpen = true }, modifier = Modifier.remoteFocusFrame()) {
                                        Icon(Icons.Default.DashboardCustomize, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("TMDB")
                                    }
                                }
                                TextButton(onClick = { resetConfirmationOpen = true }, modifier = Modifier.remoteFocusFrame()) {
                                    Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reset to defaults")
                                }
                            }
                        }
                    )
                }

                selectedTmdbSections.filterNot { it.series }.forEach { section ->
                        val row = state.tmdbHomeMovieRows[section].orEmpty()
                        if (row.isNotEmpty()) item("home-tmdb-${section.name}", span = gridSpan) {
                            DashboardMovieRail(section.title, row, false, null, openTrendingMovie)
                        }
                    }
                selectedTmdbSections.filter { it.series }.forEach { section ->
                    val row = state.tmdbHomeSeriesRows[section].orEmpty()
                    if (row.isNotEmpty()) item("screen-tmdb-${section.name}", span = gridSpan) {
                        DashboardSeriesRail(row, false, null, openTrendingSeries, title = section.title)
                    }
                }
                state.categories.forEach { category ->
                    val sectionItems = sectionCache?.itemsByCategory?.get(category.id).orEmpty()
                    if (sectionItems.isNotEmpty()) {
                        item("section-${state.selectedType}-${category.id}", span = gridSpan) {
                            ModernRail(
                                title = category.title,
                                entries = sectionItems,
                                media = { it },
                                open = { play(it) },
                                aspectRatio = {
                                    if (state.selectedType == CatalogType.LIVE_TV) 16f / 9f else 2f / 3f
                                },
                                cardWidth = if (state.selectedType == CatalogType.LIVE_TV) 156.dp else 144.dp,
                                initialDisplayCount = 10,
                                maximumDisplayCount = 50,
                                loadMore = { loadMoreCategorySection(category) },
                                isFavorite = { item -> state.favorites.any { it.media.id == item.id } },
                                toggleFavorite = { toggleFavorite(it) }
                            )
                        }
                    }
                }
            } else if (state.items.isNotEmpty()) {
                item("catalog-header", span = gridSpan) {
                    ModernSectionHeader(
                        state.selectedCategory?.title ?: state.selectedType.title,
                        when (state.selectedType) {
                            CatalogType.MOVIES ->
                                "${state.items.size} movies · ←/→ browse · OK play · Hold OK My List"
                            else ->
                                "${state.items.size} ${state.selectedType.itemLabel(state.items.size)}"
                        },
                        action = when (state.selectedType) {
                            CatalogType.LIVE_TV -> {
                                {
                                    LiveTvColumnSelector(
                                        selectedColumns = liveTvColumns,
                                        maxColumns = maxLiveTvColumns,
                                        onColumnsChanged = {
                                            liveTvColumns = it
                                        },
                                        selectorFocusRequester = layoutToggleRequester,
                                        firstChannelFocusRequester = firstChannelRequester
                                    )
                                }
                            }

                            CatalogType.MOVIES -> {
                                {
                                    CatalogColumnSelector(
                                        selectedColumns = movieColumns,
                                        maxColumns = maxMovieColumns,
                                        onColumnsChanged = {
                                            PlaybackUiPreferences.setCatalogColumns(
                                                context = browseContext,
                                                profileKey = profileKey,
                                                type = CatalogType.MOVIES,
                                                columns = it
                                            )
                                        },
                                        selectorFocusRequester = layoutToggleRequester
                                    )
                                }
                            }

                            else -> {
                                {
                                    FilledTonalIconButton(
                                        onClick = {
                                            setBrowseLayout(
                                                if (state.browseLayout == BrowseLayout.GRID) {
                                                    BrowseLayout.LIST
                                                } else {
                                                    BrowseLayout.GRID
                                                }
                                            )
                                        },
                                        modifier = Modifier
                                            .focusRequester(layoutToggleRequester)
                                            .focusProperties {
                                                up = FocusRequester.Default
                                            }
                                            .remoteFocusFrame(CircleShape)
                                    ) {
                                        Icon(
                                            if (state.browseLayout == BrowseLayout.GRID) {
                                                Icons.AutoMirrored.Filled.ViewList
                                            } else {
                                                Icons.Default.GridView
                                            },
                                            if (state.browseLayout == BrowseLayout.GRID) {
                                                "Show as list"
                                            } else {
                                                "Show as grid"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
                itemsIndexed(
                    items = state.items,

                    key = { _, item ->
                        "catalog-${state.selectedType}-${item.id}"
                    },

                    span = { _, _ ->
                        when {
                            state.selectedType == CatalogType.LIVE_TV ->
                                GridItemSpan(1)

                            state.selectedType == CatalogType.MOVIES ->
                                GridItemSpan(1)

                            state.browseLayout == BrowseLayout.LIST ->
                                GridItemSpan(maxLineSpan)

                            else ->
                                GridItemSpan(1)
                        }
                    }
                ) { index, item ->

                    when {

                        state.selectedType == CatalogType.LIVE_TV -> {

                            val liveTvRequester =
                                if (index == 0) {
                                    null
                                } else {
                                    liveTvFocusRequesters
                                        .getOrPut(item.id) {
                                            FocusRequester()
                                        }
                                }

                            LiveTvChannelCard(
                                item = item,
                                index = index,
                                columnCount = liveTvColumns,
                                firstChannelFocusRequester = firstChannelRequester,
                                itemFocusRequester = liveTvRequester,
                                columnSelectorFocusRequester = layoutToggleRequester,

                                isFavorite = state.favorites.any {
                                    it.kind == FavoriteKind.CHANNEL &&
                                            it.media.id == item.id
                                },

                                onPlay = {
                                    play(item)
                                },

                                onToggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }

                        state.selectedType == CatalogType.MOVIES -> {

                            val movieRequester =
                                movieFocusRequesters.getOrPut(item.id) {
                                    FocusRequester()
                                }

                            ModernPosterCard(
                                item = item,
                                aspectRatio = aspectRatio,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                        it.kind == FavoriteKind.MOVIE
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                },
                                focusRequester = movieRequester,
                                unfocusedScale = 0.90f,
                                focusedScale = 1.06f
                            )
                        }

                        state.browseLayout == BrowseLayout.LIST -> {

                            ModernMediaListCard(
                                item = item,
                                modifier = Modifier.focusRequester(movieFocusRequesters.getOrPut(item.id) { FocusRequester() }),
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                            it.kind == state.selectedType.favoriteKind()
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }

                        else -> {

                            ModernPosterCard(
                                item = item,
                                focusRequester = movieFocusRequesters.getOrPut(item.id) { FocusRequester() },
                                aspectRatio = aspectRatio,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                            it.kind == state.selectedType.favoriteKind()
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }
                    }
                }
                if (
                    state.selectedType in setOf(
                        CatalogType.LIVE_TV,
                        CatalogType.MOVIES,
                        CatalogType.SERIES
                    ) &&
                    (
                        state.catalogHasMore ||
                            (
                                state.selectedType != CatalogType.LIVE_TV &&
                                    movieLoadMorePending
                            ) ||
                            (
                                state.selectedType == CatalogType.LIVE_TV &&
                                    liveTvLoadMorePending
                            )
                    )
                ) {
                    item("catalog-load-more", span = gridSpan) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            var loadMoreFocused by remember {
                                mutableStateOf(false)
                            }

                            val movieLoadMoreScale by
                                androidx.compose.animation.core.animateFloatAsState(
                                    targetValue =
                                        if (isTv) {
                                            1f
                                        } else if (
                                            state.selectedType == CatalogType.MOVIES &&
                                            loadMoreFocused
                                        ) {
                                            1.10f
                                        } else if (
                                            state.selectedType == CatalogType.MOVIES
                                        ) {
                                            0.94f
                                        } else {
                                            1f
                                        },
                                    animationSpec =
                                        androidx.compose.animation.core.tween(
                                            durationMillis = 140
                                        ),
                                    label = "movieDashboardLoadMoreScale"
                                )

                            Button(
                                onClick = {
                                    if (state.catalogLoadingMore) {
                                        return@Button
                                    }

                                    if (
                                        state.selectedType !=
                                            CatalogType.LIVE_TV
                                    ) {
                                        if (movieLoadMorePending) {
                                            return@Button
                                        }

                                        movieLoadMoreStartItemCount =
                                            state.items.size

                                        movieLoadMoreFirstVisibleItemIndex =
                                            catalogGridState
                                                .firstVisibleItemIndex

                                        movieLoadMoreFirstVisibleItemScrollOffset =
                                            catalogGridState
                                                .firstVisibleItemScrollOffset

                                        movieLoadMoreObservedLoading =
                                            false
                                        movieLoadMorePending =
                                            true
                                    } else if (
                                        state.selectedType ==
                                            CatalogType.LIVE_TV
                                    ) {
                                        if (liveTvLoadMorePending) {
                                            return@Button
                                        }

                                        liveTvLoadMoreStartItemCount =
                                            state.items.size

                                        liveTvLoadMoreFirstVisibleItemIndex =
                                            catalogGridState
                                                .firstVisibleItemIndex

                                        liveTvLoadMoreFirstVisibleItemScrollOffset =
                                            catalogGridState
                                                .firstVisibleItemScrollOffset

                                        liveTvLoadMoreObservedLoading =
                                            false
                                        liveTvLoadMorePending =
                                            true
                                    }

                                    loadMoreCatalog()
                                },

                                /*
                                 * Movies deliberately remain enabled while
                                 * loading so the focused button stays a valid
                                 * focus owner. Other catalog types retain the
                                 * existing behavior.
                                 */
                                enabled =
                                    state.selectedType in setOf(
                                        CatalogType.MOVIES,
                                        CatalogType.LIVE_TV
                                    ) ||
                                        !state.catalogLoadingMore,

                                modifier = Modifier
                                    .height(48.dp)
                                    .graphicsLayer {
                                        scaleX = movieLoadMoreScale
                                        scaleY = movieLoadMoreScale
                                    }
                                    .onFocusChanged {
                                        loadMoreFocused = it.isFocused
                                    }
                                    .remoteFocusFrame(
                                        RoundedCornerShape(10.dp)
                                    ),

                                shape = RoundedCornerShape(10.dp),

                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE50914),
                                    contentColor = Color.White
                                )
                            ) {
                                val loading =
                                    state.catalogLoadingMore ||
                                        (
                                            state.selectedType ==
                                                CatalogType.MOVIES &&
                                                movieLoadMorePending
                                            ) ||
                                        (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV &&
                                                liveTvLoadMorePending
                                            )

                                if (loading) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV
                                        ) {
                                            "Loading channels…"
                                        } else {
                                            "Loading titles…"
                                        },
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                        Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV
                                        ) {
                                            "Load more channels"
                                        } else {
                                            "Load more titles"
                                        },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else item("modern-empty", span = gridSpan) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing to show in this category", color = Color.White.copy(alpha = .72f))
                }
            }
    }
        AnimatedVisibility(
            visible = tmdbLayoutLoading,
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                color = Color(0xFF090909),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(tmdbLoadingFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { true }
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(18.dp))
                    Text("Updating TMDB sections…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Preparing ${state.tmdbSectionsLoading.count { it in selectedTmdbSections }} selected rows",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
    if (tmdbSetupOpen) {
        TmdbHomeSectionsDialog(
            selected = selectedTmdbSections,
            surface = dashboardSurface,
            close = { tmdbSetupOpen = false },
            save = {
                setTmdbSections(dashboardSurface, it)
                tmdbSetupOpen = false
            }
        )
    }
    if (resetConfirmationOpen) {
        ProjectCardConfirmationDialog(
            title = "Reset ${dashboardSurface.displayTitle()}?",
            message = "This clears every IPTV and TMDB selection for this screen so you can configure it again from nothing.",
            confirmLabel = "Reset screen",
            close = { resetConfirmationOpen = false },
            confirm = {
                resetScreenConfiguration(dashboardSurface)
                resetConfirmationOpen = false
            }
        )
    }
}
