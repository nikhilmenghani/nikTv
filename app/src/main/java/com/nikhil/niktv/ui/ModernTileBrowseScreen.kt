package com.nikhil.niktv.ui

// MODERN_TILE_BROWSE_V1
//
// Tile-first destination browsing for Home / Movies / Series.
// The existing ModernBrowseScreen is still used when modernUiEnabled is false.

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.OfflineMediaDownload
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.R
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.model.WatchedSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val ModernAppBackground = Color(0xFF0B0B0F)
private val ModernChromeSurface = Color(0xFF101116)
private val ModernCardSurface = Color(0xFF151720)
private val ModernOutline = Color(0xFF2A2D36)
private val ModernBrandAccent = Color(0xFF7C8CFF)
private val ModernBrandViolet = Color(0xFFA275FF)

private fun Modifier.touchTileShadow(
    isTv: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    clip: Boolean,
    ambientColor: Color,
    spotColor: Color
): Modifier = if (isTv) this else shadow(
    elevation = elevation,
    shape = shape,
    clip = clip,
    ambientColor = ambientColor,
    spotColor = spotColor
)

@Composable
internal fun ModernTileBrowseScreen(
    state: NikTvState,
    dashboardSurface: DashboardSurface,
    openHome: () -> Unit,
    selectType: (CatalogType) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    openTmdbSection: (TmdbHomeSection) -> Unit,
    openIptvCategory: (Category) -> Unit,
    closeSection: () -> Unit,
    openTmdbMovie: (TrendingMovie) -> Unit,
    openTmdbSeries: (TrendingSeries) -> Unit,
    openIptvItem: (MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    loadMoreTmdb: () -> Unit,
    loadMoreIptv: () -> Unit,
    refreshIptv: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isModernTileTv(configuration)
    val wide = configuration.screenWidthDp >= 720 || isTv
    val mobileUiDesign by rememberMobileUiDesign()
    val activeTmdb = state.modernTmdbSection
    val activeIptv = state.modernIptvCategory

    val destinationStateHolder = rememberSaveableStateHolder()
    val destinationKey = "${dashboardSurface}:${activeTmdb}:${activeIptv?.id}"
    destinationStateHolder.SaveableStateProvider(destinationKey) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ModernAppBackground
        ) {
            when {
                activeTmdb != null -> {
                    ModernTmdbCollection(
                        state = state,
                        section = activeTmdb,
                        close = closeSection,
                        openMovie = openTmdbMovie,
                        openSeries = openTmdbSeries,
                        toggleFavorite = toggleFavorite,
                        loadMore = loadMoreTmdb,
                        isTv = isTv
                    )
                }

                activeIptv != null -> {
                    ModernIptvCollection(
                        state = state,
                        category = activeIptv,
                        close = closeSection,
                        openItem = openIptvItem,
                        toggleFavorite = toggleFavorite,
                        loadMore = loadMoreIptv,
                        refresh = refreshIptv,
                        isTv = isTv
                    )
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        if (!wide) {
                            ModernTilePhoneHeader(
                                state = state,
                                youtubeNavigation =
                                    mobileUiDesign.usesYouTubeOn(configuration),
                                openHome = openHome,
                                selectType = selectType,
                                openFavorites = openFavorites,
                                openSearch = openSearch,
                                openSettings = openSettings,
                                openProfileSwitcher = openProfileSwitcher
                            )
                        }

                        ModernDestinationHub(
                            state = state,
                            dashboardSurface = dashboardSurface,
                            openRecent = openRecent,
                            removeRecent = removeRecent,
                            openWatchedEpisode = openWatchedEpisode,
                            dismissWatchedEpisode = dismissWatchedEpisode,
                            toggleFavorite = toggleFavorite,
                            openTmdbSection = openTmdbSection,
                            openIptvCategory = openIptvCategory,
                            openSearch = openSearch,
                            configureTmdb = configureTmdb,
                            configureIptv = configureIptv,
                            resetSurface = resetSurface,
                            isTv = isTv,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private fun Context.isModernTileTv(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

@Composable
private fun ModernTilePhoneHeader(
    state: NikTvState,
    youtubeNavigation: Boolean,
    openHome: () -> Unit,
    selectType: (CatalogType) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ModernChromeSurface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.niktv_logo_foreground),
                contentDescription = "NikTV",
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "NikTV",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = openSearch) {
                Icon(Icons.Default.Search, "Search")
            }
            IconButton(onClick = openSettings) {
                Icon(Icons.Default.Settings, "Settings")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!youtubeNavigation) {
                    Text(state.savedProfile?.name.orEmpty(), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = openProfileSwitcher) {
                    Icon(Icons.Default.AccountCircle, state.savedProfile?.name ?: "Profile")
                }
            }
        }

        if (!youtubeNavigation) {
            LazyRow(
                contentPadding = PaddingValues(
                    horizontal = 10.dp,
                    vertical = 4.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item("home") {
                    ModernNavButton(
                        label = "Home",
                        icon = Icons.Default.Home,
                        selected = state.homeOpen && !state.favoritesOpen,
                        onClick = openHome
                    )
                }
                item("live") {
                    ModernNavButton(
                        label = "Live TV",
                        icon = Icons.Default.LiveTv,
                        selected = !state.homeOpen &&
                            !state.favoritesOpen &&
                            state.selectedType == CatalogType.LIVE_TV,
                        onClick = { selectType(CatalogType.LIVE_TV) }
                    )
                }
                item("movies") {
                    ModernNavButton(
                        label = "Movies",
                        icon = Icons.Default.SmartDisplay,
                        selected = !state.homeOpen &&
                            !state.favoritesOpen &&
                            state.selectedType == CatalogType.MOVIES,
                        onClick = { selectType(CatalogType.MOVIES) }
                    )
                }
                item("series") {
                    ModernNavButton(
                        label = "Series",
                        icon = Icons.Default.Tv,
                        selected = !state.homeOpen &&
                            !state.favoritesOpen &&
                            state.selectedType == CatalogType.SERIES,
                        onClick = { selectType(CatalogType.SERIES) }
                    )
                }
                item("library") {
                    ModernNavButton(
                        label = "My List",
                        icon = Icons.Default.FavoriteBorder,
                        selected = state.favoritesOpen,
                        onClick = openFavorites
                    )
                }
            }
        }

        HorizontalDivider(color = ModernOutline)
    }
}

@Composable
private fun ModernNavButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Icon(
            icon,
            null,
            Modifier.size(18.dp),
            tint = if (selected) Color.White else Color.Gray
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (selected) Color.White else Color.Gray,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ModernDestinationHub(
    state: NikTvState,
    dashboardSurface: DashboardSurface,
    openRecent: (RecentItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    openTmdbSection: (TmdbHomeSection) -> Unit,
    openIptvCategory: (Category) -> Unit,
    openSearch: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    /*
     * PROFILE_TILE_VISUAL_LANGUAGE_V33
     *
     * Fire TV focus must remain obvious at couch distance. Content tiles use
     * a larger profile-inspired lift, a fixed white focus ring and enough
     * spacing that scaled cards never visually collide with their neighbors.
     *
     * ADAPTIVE_TOUCH_TILES_V34
     *
     * Tablet and phone share the same visual language, but touch uses a
     * restrained press lift instead of the persistent TV focus treatment.
     */
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val destinationColumns = when {
        isTv -> 3
        configuration.smallestScreenWidthDp < 600 -> 1
        configuration.screenWidthDp >= 1200 -> 4
        configuration.screenWidthDp >= 720 -> 3
        else -> 2
    }
    val fullSpan:
        androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() ->
            GridItemSpan = {
        GridItemSpan(maxLineSpan)
    }

    val tmdbSections = if (dashboardSurface == DashboardSurface.LIVE_TV) {
        emptyList()
    } else state.tmdbSectionsBySurface[dashboardSurface]
        .orEmpty().filter { section ->
            when (dashboardSurface) {
                DashboardSurface.MOVIES -> !section.series
                DashboardSurface.SERIES -> section.series
                else -> true
            }
        }

    val liveCategories =
        if (dashboardSurface in setOf(DashboardSurface.HOME, DashboardSurface.LIVE_TV)) {
            state.modernVisibleIptvCategories(
                CatalogType.LIVE_TV,
                requireExplicitSelection = dashboardSurface == DashboardSurface.HOME
            )
        } else {
            emptyList()
        }
    val movieCategories =
        if (dashboardSurface in setOf(DashboardSurface.HOME, DashboardSurface.MOVIES)) {
            state.modernVisibleIptvCategories(
                CatalogType.MOVIES,
                requireExplicitSelection = dashboardSurface == DashboardSurface.HOME
            )
        } else {
            emptyList()
        }
    val seriesCategories =
        if (dashboardSurface in setOf(DashboardSurface.HOME, DashboardSurface.SERIES)) {
            state.modernVisibleIptvCategories(
                CatalogType.SERIES,
                requireExplicitSelection = dashboardSurface == DashboardSurface.HOME
            )
        } else {
            emptyList()
        }

    val newEpisodes = if (dashboardSurface == DashboardSurface.HOME) {
        state.watchedSeries.flatMap { watched ->
            watched.newEpisodes.map { episode -> watched to episode }
        }
    } else {
        emptyList()
    }
    val recents = if (dashboardSurface == DashboardSurface.HOME) {
        state.recentlyPlayed
            .filter {
                it.kind == FavoriteKind.MOVIE ||
                    it.kind == FavoriteKind.SERIES
            }
            .take(12)
    } else {
        emptyList()
    }

    val screenTitle = when (dashboardSurface) {
        DashboardSurface.HOME -> "Home"
        DashboardSurface.MOVIES -> "Movies"
        DashboardSurface.SERIES -> "Series"
        DashboardSurface.LIVE_TV -> "Live TV"
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(destinationColumns),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start =
                if (isTv) 32.dp
                else if (isTablet) 24.dp
                else 14.dp,
            end =
                if (isTv) 32.dp
                else if (isTablet) 24.dp
                else 14.dp,
            top =
                if (isTv) 28.dp
                else if (isTablet) 22.dp
                else 16.dp,
            bottom = 72.dp
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (isTv) 28.dp
            else if (isTablet) 20.dp
            else 12.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(
            if (isTv) 28.dp
            else if (isTablet) 20.dp
            else 12.dp
        )
    ) {
        if (dashboardSurface != DashboardSurface.HOME) {
            item("hub-header", span = fullSpan) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        screenTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        "Choose a collection or provider category to browse.",
                        color = Color(0xFFA7ABB5),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ModernHubQuickActions(
                        dashboardSurface = dashboardSurface,
                        screenTitle = screenTitle,
                        openSearch = openSearch,
                        configureTmdb = configureTmdb,
                        configureIptv = configureIptv,
                        resetSurface = resetSurface,
                        isTv = isTv
                    )
                }
            }
        }

        if (dashboardSurface == DashboardSurface.HOME) {
            if (recents.isNotEmpty()) {
                item("continue-header", span = fullSpan) {
                    ModernHubSectionHeading(
                        "Continue Watching",
                        "Pick up where you left off."
                    )
                }
                item("continue-row", span = fullSpan) {
                    ModernContinueRow(
                        recents = recents,
                        playbackProgress = state.playbackProgress,
                        favorites = state.favorites,
                        returnFocusId = state.playbackReturnFocusId,
                        open = openRecent,
                        clear = removeRecent,
                        toggleFavorite = toggleFavorite
                    )
                }
            }

            if (newEpisodes.isNotEmpty()) {
                item("new-episodes-header", span = fullSpan) {
                    ModernHubSectionHeading(
                        "New Episodes",
                        "Fresh episodes from series you follow."
                    )
                }
                item("new-episodes-row", span = fullSpan) {
                    ModernNewEpisodesRow(
                        entries = newEpisodes,
                        favorites = state.favorites,
                        open = openWatchedEpisode,
                        clear = dismissWatchedEpisode,
                        toggleFavorite = toggleFavorite
                    )
                }
            }

            item("home-browse-header", span = fullSpan) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModernHubSectionHeading(
                        "Browse",
                        "Search NikTV or choose which destinations appear on Home."
                    )
                    ModernHubQuickActions(
                        dashboardSurface = dashboardSurface,
                        screenTitle = screenTitle,
                        openSearch = openSearch,
                        configureTmdb = configureTmdb,
                        configureIptv = configureIptv,
                        resetSurface = resetSurface,
                        isTv = isTv
                    )
                }
            }
        }

        if (tmdbSections.isNotEmpty()) {
            item("tmdb-heading", span = fullSpan) {
                ModernHubSectionHeading(
                    "TMDB Discover",
                    "Curated collections; titles load on open."
                )
            }
            gridItems(
                items = tmdbSections,
                key = {
                    "tmdb-${dashboardSurface.name}-${it.name}"
                }
            ) { section ->
                ModernDestinationTile(
                    title = section.title,
                    subtitle =
                        if (section.series) "TMDB · Series"
                        else "TMDB · Movies",
                    icon =
                        if (section.series) Icons.Default.Tv
                        else Icons.Default.SmartDisplay,
                    seed = "tmdb:${section.name}",
                    isTv = isTv,
                    onClick = { openTmdbSection(section) }
                )
            }
        }

        if (liveCategories.isNotEmpty()) {
            item("iptv-live-heading", span = fullSpan) {
                ModernHubSectionHeading(
                    "Live TV",
                    "Provider categories selected for Home."
                )
            }
            gridItems(
                items = liveCategories,
                key = { "iptv-live-${it.id}" }
            ) { category ->
                ModernDestinationTile(
                    title = category.title,
                    subtitle = "IPTV · Live TV",
                    icon = Icons.Default.LiveTv,
                    seed = "live:${category.id}:${category.title}",
                    isTv = isTv,
                    onClick = { openIptvCategory(category) }
                )
            }
        }

        if (movieCategories.isNotEmpty()) {
            item("iptv-movie-heading", span = fullSpan) {
                ModernHubSectionHeading(
                    "IPTV Movies",
                    "Provider categories selected for this profile."
                )
            }
            gridItems(
                items = movieCategories,
                key = { "iptv-movie-${it.id}" }
            ) { category ->
                ModernDestinationTile(
                    title = category.title,
                    subtitle = "IPTV · Movies",
                    icon = Icons.Default.SmartDisplay,
                    seed = "movie:${category.id}:${category.title}",
                    isTv = isTv,
                    onClick = { openIptvCategory(category) }
                )
            }
        }

        if (seriesCategories.isNotEmpty()) {
            item("iptv-series-heading", span = fullSpan) {
                ModernHubSectionHeading(
                    "IPTV Series",
                    "Provider categories selected for this profile."
                )
            }
            gridItems(
                items = seriesCategories,
                key = { "iptv-series-${it.id}" }
            ) { category ->
                ModernDestinationTile(
                    title = category.title,
                    subtitle = "IPTV · Series",
                    icon = Icons.Default.Tv,
                    seed = "series:${category.id}:${category.title}",
                    isTv = isTv,
                    onClick = { openIptvCategory(category) }
                )
            }
        }

        if (
            tmdbSections.isEmpty() &&
            liveCategories.isEmpty() &&
            movieCategories.isEmpty() &&
            seriesCategories.isEmpty()
        ) {
            item("empty-hub", span = fullSpan) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = ModernCardSurface,
                    border = BorderStroke(
                        1.dp,
                        ModernOutline
                    )
                ) {
                    Column(
                        Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.DashboardCustomize,
                            null,
                            Modifier.size(30.dp),
                            tint = ModernBrandViolet
                        )
                        Text(
                            "Choose your destinations",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Add TMDB sections above. For IPTV tiles on Home, explicitly choose Live TV, Movie, or Series categories.",
                            color = Color(0xFFB9B9B9)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNewEpisodesRow(
    entries: List<Pair<WatchedSeries, MediaItem>>,
    favorites: List<FavoriteItem>,
    open: (WatchedSeries, MediaItem) -> Unit,
    clear: (WatchedSeries, MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        // This row already inherits the dashboard grid's leading inset. Adding
        // another horizontal inset made it start farther right than Live TV.
        // Keep trailing scroll room and extra bottom clearance for focused cards.
        contentPadding = PaddingValues(start = 0.dp, top = 18.dp, end = 18.dp, bottom = 26.dp)
    ) {
        items(
            items = entries,
            key = { (watched, episode) ->
                "modern-new-episode-${watched.series.id}-${episode.id}"
            }
        ) { (watched, episode) ->
            // HOME_OFFLINE_CONSISTENCY_V46: New Episodes uses the same series-first card identity
            // as Continue Watching, while preserving the episode action targets.
            val displayMedia = if (
                watched.series.logo.isNullOrBlank() &&
                !episode.logo.isNullOrBlank()
            ) {
                watched.series.copy(logo = episode.logo)
            } else {
                watched.series
            }
            val episodeDetails = listOfNotNull(
                episode.seasonNumber?.let { season ->
                    episode.episodeNumber?.let { ep -> "S$season:E$ep" }
                },
                episode.compactEpisodeTitle().takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val favorite = FavoriteItem(
                kind = FavoriteKind.EPISODE,
                media = episode,
                series = watched.series,
                profileKey = watched.profileKey,
                categoryTitle = watched.categoryTitle
            )

            ModernCompactMediaCard(
                item = displayMedia,
                subtitle = episodeDetails,
                onClick = { open(watched, episode) },
                isFavorite = favorites.any { it.key == favorite.key },
                onFavorite = { toggleFavorite(favorite) },
                onClear = { clear(watched, episode) }
            )
        }
    }
    }

}

private fun NikTvState.modernVisibleIptvCategories(
    type: CatalogType,
    requireExplicitSelection: Boolean
): List<Category> {
    val profileKey = savedProfile?.cacheKey() ?: return emptyList()
    val raw = rawCategoriesByType[type]
        .orEmpty()
        .ifEmpty {
            browseCachesByType[type]?.categories.orEmpty()
        }
    val enabledIds =
        categoryFilters["$profileKey|${type.name}"]

    if (enabledIds == null) {
        return if (requireExplicitSelection) {
            emptyList()
        } else {
            raw
        }
    }

    val enabled = enabledIds.toSet()
    return raw.filter { it.id in enabled }
}

@Composable
private fun ModernHubSectionHeading(
    title: String,
    subtitle: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFA7ABB5)
        )
    }
}

@Composable
private fun ModernHubQuickActions(
    dashboardSurface: DashboardSurface,
    screenTitle: String,
    openSearch: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit,
    isTv: Boolean
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = if (isTv) 6.dp else 0.dp,
            top = 4.dp,
            end = 8.dp,
            bottom = 10.dp
        )
    ) {
        item("quick-search") {
            ModernQuickActionTile(
                title = if (dashboardSurface == DashboardSurface.HOME) "Search" else "Search $screenTitle",
                subtitle = "Across NikTV",
                icon = Icons.Default.Search,
                accent = ModernBrandAccent,
                isTv = isTv,
                onClick = openSearch
            )
        }
        if (dashboardSurface != DashboardSurface.LIVE_TV) {
            item("quick-tmdb") {
                ModernQuickActionTile(
                    title = "TMDB sections",
                    subtitle = "Choose Discover rows",
                    icon = Icons.Default.DashboardCustomize,
                    accent = ModernBrandViolet,
                    isTv = isTv,
                    onClick = configureTmdb
                )
            }
        }

        when (dashboardSurface) {
            DashboardSurface.HOME -> {
                item("quick-live") {
                    ModernQuickActionTile(
                        title = "Live TV categories",
                        subtitle = "Choose channel groups",
                        icon = Icons.Default.LiveTv,
                        accent = Color(0xFFE65D68),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.LIVE_TV) }
                    )
                }
                item("quick-movies") {
                    ModernQuickActionTile(
                        title = "Movie categories",
                        subtitle = "Choose provider rows",
                        icon = Icons.Default.SmartDisplay,
                        accent = Color(0xFF55B8FF),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.MOVIES) }
                    )
                }
                item("quick-series") {
                    ModernQuickActionTile(
                        title = "Series categories",
                        subtitle = "Choose provider rows",
                        icon = Icons.Default.Tv,
                        accent = Color(0xFF9A80FF),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.SERIES) }
                    )
                }
            }
            DashboardSurface.MOVIES -> {
                item("quick-iptv") {
                    ModernQuickActionTile(
                        title = "IPTV categories",
                        subtitle = "Choose provider rows",
                        icon = Icons.Default.Tune,
                        accent = Color(0xFF55B8FF),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.MOVIES) }
                    )
                }
            }
            DashboardSurface.SERIES -> {
                item("quick-iptv") {
                    ModernQuickActionTile(
                        title = "IPTV categories",
                        subtitle = "Choose provider rows",
                        icon = Icons.Default.Tune,
                        accent = Color(0xFF9A80FF),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.SERIES) }
                    )
                }
            }
            DashboardSurface.LIVE_TV -> {
                item("quick-iptv") {
                    ModernQuickActionTile(
                        title = "IPTV categories",
                        subtitle = "Choose channel groups",
                        icon = Icons.Default.Tune,
                        accent = Color(0xFFE65D68),
                        isTv = isTv,
                        onClick = { configureIptv(CatalogType.LIVE_TV) }
                    )
                }
            }
        }

        item("quick-reset") {
            ModernQuickActionTile(
                title = "Reset",
                subtitle = "Restore defaults",
                icon = Icons.Default.RestartAlt,
                accent = Color(0xFFA7ADB8),
                isTv = isTv,
                onClick = resetSurface
            )
        }
    }
}

@Composable
private fun ModernQuickActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    isTv: Boolean,
    onClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val remoteNavigationActive = context.usesRemoteNavigation(configuration)
    val isPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernQuickActionFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!remoteNavigationActive && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernQuickActionPress"
    )
    val visualProgress = if (remoteNavigationActive) focusProgress else pressProgress
    val scale = 1f + (
        if (remoteNavigationActive) {
            if (isTv) 0f else 0.035f
        } else if (isTablet) {
            0.025f
        } else {
            0.018f
        }
    ) * visualProgress
    val shape = RoundedCornerShape(if (isTv) 18.dp else 16.dp)
    val tileWidth = when {
        isTv -> 240.dp
        isPhone -> 196.dp
        else -> 220.dp
    }
    val tileHeight = when {
        isTv -> 92.dp
        isPhone -> 82.dp
        else -> 88.dp
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .width(tileWidth)
            .height(tileHeight)
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .touchTileShadow(isTv = isTv,
                elevation = ((if (isTv) 0f else 8f) * visualProgress).dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.42f),
                spotColor = accent.copy(alpha = if (isTv) 0.34f else 0.18f)
            )
            .onFocusChanged { focused = it.isFocused },
        shape = shape,
        color = ModernCardSurface,
        border = BorderStroke(
            if (remoteNavigationActive && focused) {
                if (isTv) 3.dp else 2.dp
            } else {
                1.dp
            },
            if (remoteNavigationActive && focused) {
                accent.copy(alpha = 0.95f)
            } else {
                ModernOutline
            }
        )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent)
                    )
                )
                .padding(horizontal = if (isPhone) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                modifier = Modifier.size(
                    when {
                        isTv -> 46.dp
                        isPhone -> 38.dp
                        else -> 42.dp
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                color = accent.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        Modifier.size(
                            when {
                                isTv -> 25.dp
                                isPhone -> 20.dp
                                else -> 22.dp
                            }
                        ),
                        tint = accent
                    )
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = when {
                        isTv -> MaterialTheme.typography.bodyMedium
                        isPhone -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style =
                        if (isTv) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.labelSmall,
                    color = Color(0xFFA7ABB5),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ModernDestinationTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    seed: String,
    isTv: Boolean,
    onClick: () -> Unit
) {
    val returningTile = rememberReturningTile(onClick)
    var focused by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val isPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernDestinationProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!isTv && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernDestinationTouchPress"
    )
    val visualProgress =
        if (isTv) focusProgress
        else maxOf(focusProgress, pressProgress)
    val active = focused || pressed
    val scale =
        1f + (
            when {
                isTv -> 0f
                isTablet -> 0.035f
                else -> 0.025f
            } * visualProgress
        )
    val iconScale =
        1f + (
            when {
                isTv -> 0.12f
                isTablet -> 0.06f
                else -> 0.04f
            } * visualProgress
        )
    val shape = RoundedCornerShape(if (isTv) 18.dp else 16.dp)
    val borderColor = lerp(
        Color(0xFF35383F),
        when {
            isTv -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )
    val palette = remember(seed) {
        destinationPalette(seed)
    }

    Surface(
        onClick = returningTile.open,
        interactionSource = interactionSource,
        modifier = returningTile.modifier
            .fillMaxWidth()
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .touchTileShadow(isTv = isTv,
                elevation =
                    (
                        when {
                            isTv -> 0f
                            isTablet -> 10f
                            else -> 6f
                        } * visualProgress
                    ).dp,
                shape = shape,
                clip = false,
                ambientColor =
                    if (isTv) Color(0x88000000)
                    else Color(0x55000000),
                spotColor =
                    if (isTv) ModernBrandViolet.copy(alpha = 0.34f)
                    else ModernBrandAccent.copy(alpha = 0.18f)
            )
            .onFocusChanged {
                focused = it.isFocused
            },
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(
            when {
                isTv && focused -> 3.dp
                !isTv && focused -> 2.dp
                else -> 1.dp
            },
            borderColor
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (isPhone) {
                        Modifier.height(88.dp)
                    } else {
                        Modifier.aspectRatio(if (isTv) 1.72f else 16f / 9f)
                    }
                )
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.first,
                            palette.second
                        )
                    )
                )
                .padding(
                    when {
                        isTv -> 18.dp
                        isPhone -> 12.dp
                        else -> 15.dp
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .size(
                            when {
                                isTv -> 46.dp
                                isPhone -> 36.dp
                                else -> 42.dp
                            }
                        )
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    shape = RoundedCornerShape(if (isTv) 14.dp else 12.dp),
                    color = ModernBrandAccent.copy(alpha = 0.13f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            null,
                            Modifier.size(
                                when {
                                    isTv -> 25.dp
                                    isPhone -> 20.dp
                                    else -> 23.dp
                                }
                            ),
                            tint = Color(0xFFD9DEFF)
                        )
                    }
                }
                Spacer(
                    Modifier.width(
                        when {
                            isTv -> 14.dp
                            isPhone -> 10.dp
                            else -> 12.dp
                        }
                    )
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        title,
                        style = when {
                            isTv -> MaterialTheme.typography.bodyMedium
                            isPhone -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight =
                            if (active) FontWeight.Black
                            else FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!(isTv && subtitle.startsWith("IPTV ·"))) Text(
                        subtitle,
                        style = when {
                            isTv -> MaterialTheme.typography.labelSmall
                            isPhone -> MaterialTheme.typography.labelSmall
                            else -> MaterialTheme.typography.labelMedium
                        },
                        color =
                            if (active) {
                                Color(0xFFD5D7DC)
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isTv && focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .width(72.dp)
                        .height(4.dp)
                        .background(ModernBrandAccent)
                )
            }
        }
    }
}

private fun destinationPalette(
    seed: String
): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF182238) to Color(0xFF11141C),
        Color(0xFF211A35) to Color(0xFF12131A),
        Color(0xFF142A2C) to Color(0xFF111419),
        Color(0xFF282033) to Color(0xFF141219),
        Color(0xFF17263D) to Color(0xFF11151E),
        Color(0xFF202536) to Color(0xFF12141B)
    )
    return palettes[
        (seed.hashCode() and Int.MAX_VALUE) %
            palettes.size
    ]
}

@Composable
private fun ModernContinueRow(
    recents: List<RecentItem>,
    playbackProgress: List<PlaybackProgress>,
    favorites: List<FavoriteItem>,
    returnFocusId: String?,
    open: (RecentItem) -> Unit,
    clear: (RecentItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit
) {
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val focusIds = remember(recents) {
        recents.map { recent ->
            if (recent.kind == FavoriteKind.SERIES) {
                recent.lastPlayed?.id ?: recent.media.id
            } else {
                recent.media.id
            }
        }
    }



    Column(Modifier.fillMaxWidth()) {
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        // The dashboard grid supplies the leading inset, matching Live TV.
        // Preserve trailing scroll room and enough bottom space for focus scale.
        contentPadding = PaddingValues(start = 0.dp, top = 18.dp, end = 18.dp, bottom = 26.dp)
    ) {
        items(
            items = recents,
            key = { "modern-recent-${it.key}" }
        ) { recent ->
            val focusId = if (recent.kind == FavoriteKind.SERIES) {
                recent.lastPlayed?.id ?: recent.media.id
            } else {
                recent.media.id
            }
            ModernCompactMediaCard(
                item = recent.media,
                subtitle =
                    if (recent.kind == FavoriteKind.SERIES) {
                        recent.lastPlayed?.let { episode ->
                            listOfNotNull(
                                episode.seasonNumber?.let { season -> episode.episodeNumber?.let { ep -> "S$season:E$ep" } },
                                episode.compactEpisodeTitle().takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        } ?: "Series"
                    } else {
                        "Movie"
                    },
                onClick = { open(recent) },
                isFavorite = favorites.any { it.key == recent.key },
                onFavorite = {
                    toggleFavorite(
                        FavoriteItem(
                            kind = recent.kind,
                            media = recent.media,
                            series = recent.series,
                            profileKey = recent.profileKey
                        )
                    )
                },
                onClear = { clear(recent) },
                progress = playbackProgress.firstOrNull { saved ->
                    val episode = recent.lastPlayed
                    if (recent.kind == FavoriteKind.SERIES && episode != null) {
                        saved.key.contains("series:${recent.media.id}|") &&
                            saved.key.contains("|s:${episode.seasonNumber ?: -1}|") &&
                            saved.key.contains("|e:${episode.episodeNumber ?: -1}|") ||
                            saved.key == "SERIES:${episode.id}"
                    } else {
                        saved.key.contains(recent.media.id)
                    }
                },
                modifier = Modifier.focusRequester(
                    requesters.getOrPut(focusId) { FocusRequester() }
                )
            )
        }
    }
    }

}

@Composable
private fun ModernCompactMediaCard(
    item: MediaItem,
    subtitle: String,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onClear: (() -> Unit)? = null,
    progress: PlaybackProgress? = null,
    modifier: Modifier = Modifier
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isModernTileTv(configuration)
    val remoteNavigationActive = context.usesRemoteNavigation(configuration)
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val collectionPosterColumns = modernPosterColumns(configuration, isTv)
    val collectionPosterHorizontalPaddingDp = if (isTv) 24f else 18f
    val collectionPosterHorizontalSpacingDp = if (isTv) 20f else 12f
    val collectionPosterWidth = (
        (
            configuration.screenWidthDp.toFloat() -
                (collectionPosterHorizontalPaddingDp * 2f) -
                (collectionPosterHorizontalSpacingDp * (collectionPosterColumns - 1).toFloat())
        ) / collectionPosterColumns.toFloat()
    ).coerceAtLeast(1f).dp
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernCompactProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!remoteNavigationActive && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernCompactTouchPress"
    )
    val visualProgress =
        if (remoteNavigationActive) focusProgress else pressProgress
    val active =
        if (remoteNavigationActive) focused else pressed
    val artworkScale =
        1f + (
            when {
                isTv -> 0f
                isTablet -> 0.035f
                else -> 0.025f
            } * visualProgress
        )
    val shape = RoundedCornerShape(14.dp)
    val borderColor = lerp(
        Color(0xFF30343B),
        when {
            isTv -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )
    val watchedFraction =
        if (progress != null && progress.durationMillis > 0L) {
            (progress.positionMillis.toFloat() / progress.durationMillis)
                .coerceIn(0f, 1f)
        } else {
            0f
        }

    /*
     * HOME_MEDIA_COMPACT_METADATA_V52
     *
     * Home rails keep poster-first artwork and a concise two-line text
     * hierarchy. Full episode metadata remains available after opening the
     * title; Home prioritizes scanability and playback progress.
     */
    Box(
        modifier = modifier.then(returningTile.modifier)
            .width(collectionPosterWidth)
            .zIndex(visualProgress)
            .touchTileShadow(isTv = isTv,
                elevation =
                    (
                        when {
                            isTv -> 0f
                            isTablet -> 10f
                            else -> 6f
                        } * visualProgress
                    ).dp,
                shape = shape,
                clip = false,
                ambientColor =
                    if (isTv) Color(0x88000000)
                    else Color(0x55000000),
                spotColor =
                    if (isTv) ModernBrandViolet.copy(alpha = 0.32f)
                    else ModernBrandAccent.copy(alpha = 0.14f)
            )
            .onFocusChanged {
                focused = it.isFocused
            }
            .remoteCombinedClickable(
                interactionSource = interactionSource,
                onClick = returningTile.open,
                onLongClick = { menuOpen = true }
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = shape,
            color = Color(0xFF202020),
            border = BorderStroke(
                when {
                    isTv && focused -> 3.dp
                    !isTv && focused -> 2.dp
                    else -> 1.dp
                },
                borderColor
            )
        ) {
            Box(Modifier.fillMaxSize()) {
                ModernPosterImage(
                    item = item,
                    context = context,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = artworkScale
                            scaleY = artworkScale
                        }
                )

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.48f),
                                    Color.Black.copy(alpha = 0.94f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(
                            start = if (isTv) 11.dp else 9.dp,
                            end = if (isTv) 11.dp else 9.dp,
                            bottom = if (watchedFraction > 0f) 11.dp else 8.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        item.title,
                        style = if (isTv) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        fontWeight =
                            if (active) FontWeight.Bold
                            else FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = if (isTv) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank() && !(isTv && subtitle == "Movie")) {
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.76f),
                            style = if (isTv) {
                                MaterialTheme.typography.labelSmall
                            } else {
                                MaterialTheme.typography.labelSmall
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (watchedFraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(if (isTv) 5.dp else 4.dp)
                            .background(
                                Color.White.copy(alpha = 0.16f),
                                RoundedCornerShape(50)
                            )
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(watchedFraction)
                                .background(ModernBrandAccent, RoundedCornerShape(50))
                        )
                    }
                }
            }
        }
        ModernTileActionsMenu(
            expanded = menuOpen,
            isFavorite = isFavorite,
            dismiss = { menuOpen = false },
            toggleFavorite = onFavorite,
            clear = onClear
        )
    }
}
private fun MediaItem.compactEpisodeTitle(): String = title
    .replaceFirst(Regex("^\\s*(?:S\\d+\\s*[:._-]?\\s*E(?:P(?:ISODE)?)?\\s*\\d+|(?:EPISODE|EP|E)\\s*#?\\s*\\d+)\\s*[. :|\\-–—]*\\s*", RegexOption.IGNORE_CASE), "")
    .trim()

@Composable
private fun ModernTileActionsMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    dismiss: () -> Unit,
    toggleFavorite: () -> Unit,
    clear: (() -> Unit)?
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = dismiss,
        modifier = Modifier,
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(12.dp)
    ) {
        DropdownMenuItem(
            text = {
                Text(if (isFavorite) "Remove from My List" else "Add to My List")
            },
            leadingIcon = {
                Icon(
                    if (isFavorite) Icons.Default.HeartBroken
                    else Icons.Default.FavoriteBorder,
                    null
                )
            },
            onClick = {
                dismiss()
                toggleFavorite()
            }
        )
        clear?.let { clearAction ->
            DropdownMenuItem(
                text = { Text("Clear from this list") },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                onClick = {
                    dismiss()
                    clearAction()
                }
            )
        }
    }
}

/*
 * GRID_COLUMN_FOCUS_V27
 *
 * LazyGrid geometry-based focus search can change columns after scrolling
 * because only a window of rows is composed. On TV, vertical navigation is
 * therefore index-based: Up/Down target the same logical column in the
 * previous/next row. Horizontal navigation remains Compose-native.
 */
private fun Modifier.modernGridVerticalFocus(
    enabled: Boolean,
    index: Int,
    columns: Int,
    itemCount: Int,
    moveFocus: (Int) -> Unit
): Modifier {
    if (!enabled) return this

    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
        }

        val targetIndex =
            when (event.key) {
                Key.DirectionDown -> index + columns
                Key.DirectionUp -> index - columns
                else -> return@onPreviewKeyEvent false
            }

        if (targetIndex !in 0 until itemCount) {
            // Keep edge behavior native so the header / Load More controls
            // remain reachable when there is no same-column poster.
            return@onPreviewKeyEvent false
        }

        moveFocus(targetIndex)
        true
    }
}

@Composable
private fun ModernTmdbCollection(
    state: NikTvState,
    section: TmdbHomeSection,
    close: () -> Unit,
    openMovie: (TrendingMovie) -> Unit,
    openSeries: (TrendingSeries) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    loadMore: () -> Unit,
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val columns = modernPosterColumns(configuration, isTv)
    val fullSpan:
        androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() ->
            GridItemSpan = {
        GridItemSpan(maxLineSpan)
    }
    val focusIds =
        if (section.series) {
            state.modernTmdbSeries.map {
                it.tmdb.asMediaItem().id
            }
        } else {
            state.modernTmdbMovies.map {
                it.tmdb.asMediaItem().id
            }
        }
    val count = focusIds.size
    var focusedPosterIndex by remember(section) {
        mutableIntStateOf(-1)
    }

    // The destination's saveable scope retains the viewport across playback
    // and details; each tile remembers whether it launched the next screen.
    val gridState = rememberLazyGridState()
    val itemFocusRequesters =
        remember(section) {
            mutableMapOf<String, FocusRequester>()
        }

    val appendPage = rememberCollectionPagination(
        focusIds, state.modernTmdbLoading, gridState, itemFocusRequesters, loadMore
    )
    val focusScope = rememberCoroutineScope()
    val moveFocusToIndex: (Int) -> Unit = { targetIndex ->
        focusIds.getOrNull(targetIndex)?.let { targetId ->
            val targetRequester =
                itemFocusRequesters.getOrPut(targetId) {
                    FocusRequester()
                }
            focusScope.launch {
                // Header is lazy-grid item zero; posters begin at item one.
                val targetGridIndex = targetIndex + 1
                val alreadyVisible =
                    gridState.layoutInfo.visibleItemsInfo.any {
                        it.index == targetGridIndex
                    }
                if (!alreadyVisible) {
                    gridState.scrollToItem(targetGridIndex)
                    withTimeoutOrNull(1_000L) {
                        snapshotFlow {
                            gridState.layoutInfo.visibleItemsInfo.any {
                                it.index == targetGridIndex
                            }
                        }.first { it }
                    }
                }
                withFrameNanos { }
                // visibleItemsInfo updates before the focus target's modifier
                // is fully attached on some Fire TV/Compose combinations.
                delay(120L)
                repeat(3) { attempt ->
                    if (runCatching {
                            targetRequester.requestFocus()
                        }.getOrDefault(false)) {
                        return@launch
                    }
                    delay(40L * (attempt + 1))
                }
            }
        }
    }



    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .modernGridVerticalFocus(
                enabled = focusedPosterIndex >= 0,
                index = focusedPosterIndex,
                columns = columns,
                itemCount = count,
                moveFocus = moveFocusToIndex
            ),
        contentPadding = PaddingValues(
            start = if (isTv) 24.dp else 18.dp,
            end = if (isTv) 24.dp else 18.dp,
            top = 16.dp,
            bottom = 54.dp
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (isTv) 28.dp else 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(
            if (isTv) 20.dp else 12.dp
        )
    ) {
        item("collection-header", span = fullSpan) {
            ModernCollectionHeader(
                title = section.title,
                subtitle =
                    "TMDB · " +
                        if (section.series) {
                            "Series · $count loaded"
                        } else {
                            "Movies · $count loaded"
                        },
                close = close
            )
        }

        if (section.series) {
            gridItemsIndexed(
                items = state.modernTmdbSeries,
                key = { _, entry ->
                    "modern-tmdb-series-${entry.tmdb.id}"
                }
            ) { index, entry ->
                val media =
                    entry.tmdb.asMediaItem().let { tmdbMedia ->
                        if (!tmdbMedia.logo.isNullOrBlank()) {
                            tmdbMedia
                        } else {
                            tmdbMedia.copy(
                                logo = entry.iptv?.logo
                            )
                        }
                    }

                ModernCollectionPoster(
                    item = media,
                    modifier = Modifier
                        .onFocusChanged {
                            if (it.hasFocus) focusedPosterIndex = index
                        }
                        .focusRequester(
                            itemFocusRequesters.getOrPut(media.id) {
                                FocusRequester()
                            }
                        )
                        .modernGridVerticalFocus(
                            enabled = true,
                            index = index,
                            columns = columns,
                            itemCount = count,
                            moveFocus = moveFocusToIndex
                        ),
                    subtitle = buildList {
                        entry.tmdb.firstAirYear?.let {
                            add(it.toString())
                        }
                        entry.tmdb.voteAverage
                            ?.takeIf { it > 0.0 }
                            ?.let {
                                add(
                                    "★ ${
                                        String.format(
                                            java.util.Locale.US,
                                            "%.1f",
                                            it
                                        )
                                    }"
                                )
                            }
                    }.joinToString(" · "),
                    onClick = {
                        openSeries(entry)
                    },
                    isFavorite = state.favorites.any {
                        it.kind == FavoriteKind.SERIES && it.media.id == media.id
                    },
                    onFavorite = {
                        toggleFavorite(FavoriteItem(FavoriteKind.SERIES, media))
                    },
                    isTv = isTv
                )
            }
        } else {
            gridItemsIndexed(
                items = state.modernTmdbMovies,
                key = { _, entry ->
                    "modern-tmdb-movie-${entry.tmdb.id}"
                }
            ) { index, entry ->
                val media =
                    entry.tmdb.asMediaItem().let { tmdbMedia ->
                        if (!tmdbMedia.logo.isNullOrBlank()) {
                            tmdbMedia
                        } else {
                            tmdbMedia.copy(
                                logo = entry.iptv?.logo
                            )
                        }
                    }

                ModernCollectionPoster(
                    item = media,
                    offlineDownload = state.offlineDownloads.firstOrNull { download ->
                        download.catalogType == CatalogType.MOVIES &&
                            (download.media.id == media.id || download.media.id == entry.iptv?.id)
                    },
                    offlineRevision = state.offlineDownloadRevision,
                    modifier = Modifier
                        .onFocusChanged {
                            if (it.hasFocus) focusedPosterIndex = index
                        }
                        .focusRequester(
                            itemFocusRequesters.getOrPut(media.id) {
                                FocusRequester()
                            }
                        )
                        .modernGridVerticalFocus(
                            enabled = true,
                            index = index,
                            columns = columns,
                            itemCount = count,
                            moveFocus = moveFocusToIndex
                        ),
                    subtitle = buildList {
                        entry.tmdb.releaseYear?.let {
                            add(it.toString())
                        }
                        entry.tmdb.voteAverage
                            ?.takeIf { it > 0.0 }
                            ?.let {
                                add(
                                    "★ ${
                                        String.format(
                                            java.util.Locale.US,
                                            "%.1f",
                                            it
                                        )
                                    }"
                                )
                            }
                    }.joinToString(" · "),
                    onClick = {
                        openMovie(entry)
                    },
                    isFavorite = state.favorites.any {
                        it.kind == FavoriteKind.MOVIE && it.media.id == media.id
                    },
                    onFavorite = {
                        toggleFavorite(FavoriteItem(FavoriteKind.MOVIE, media))
                    },
                    isTv = isTv
                )
            }
        }

        // Initial loading needs its own placeholder. Pagination loading is
        // rendered in the stable Load More focus target below.
        if (state.modernTmdbLoading && count == 0) {
            item("tmdb-loading", span = fullSpan) {
                ModernCollectionLoading(
                    "Loading titles…"
                )
            }
        }

        state.modernTmdbError?.let { error ->
            item("tmdb-error", span = fullSpan) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF241415)
                ) {
                    Text(
                        error,
                        Modifier.padding(16.dp),
                        color = Color(0xFFFFB4AB)
                    )
                }
            }
        }

        if (state.modernTmdbHasMore || state.modernTmdbLoading || appendPage.pending) {
            item("tmdb-load-more", span = fullSpan) {
                ModernLoadMoreButton(
                    label = "Load 20 more",
                    loading = state.modernTmdbLoading || appendPage.pending,
                    onClick = appendPage.load
                )
            }
        }

        if (
            count == 0 &&
            !state.modernTmdbLoading &&
            state.modernTmdbError == null
        ) {
            item("tmdb-empty", span = fullSpan) {
                ModernEmptyCollection(
                    "No titles returned for this TMDB section."
                )
            }
        }
    }
}

@Composable
private fun ModernIptvCollection(
    state: NikTvState,
    category: Category,
    close: () -> Unit,
    openItem: (MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    loadMore: () -> Unit,
    refresh: () -> Unit,
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isLiveTv = category.type == CatalogType.LIVE_TV
    val isPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val liveTilePreferences = remember(context) {
        context.getSharedPreferences("modern_live_tv_tiles", Context.MODE_PRIVATE)
    }
    var themedLiveTiles by remember(category.type) {
        mutableStateOf(liveTilePreferences.getBoolean("themed", true))
    }
    val columns = when {
        isLiveTv && isPhone -> 1
        isLiveTv && themedLiveTiles && isTv -> 3
        isLiveTv && themedLiveTiles && configuration.screenWidthDp >= 800 -> 3
        isLiveTv && themedLiveTiles -> 2
        else -> modernPosterColumns(configuration, isTv)
    }
    val fullSpan:
        androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() ->
            GridItemSpan = {
        GridItemSpan(maxLineSpan)
    }
    val kind = when (category.type) {
        CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
        CatalogType.MOVIES -> FavoriteKind.MOVIE
        CatalogType.SERIES -> FavoriteKind.SERIES
        CatalogType.RADIO -> FavoriteKind.CHANNEL
    }
    val focusIds = state.items.map { it.id }
    var focusedPosterIndex by remember(category.id) {
        mutableIntStateOf(-1)
    }
    val gridState = rememberLazyGridState()
    val itemFocusRequesters =
        remember(category.id) {
            mutableMapOf<String, FocusRequester>()
        }

    val focusScope = rememberCoroutineScope()
    val moveFocusToIndex: (Int) -> Unit = { targetIndex ->
        focusIds.getOrNull(targetIndex)?.let { targetId ->
            val targetRequester =
                itemFocusRequesters.getOrPut(targetId) {
                    FocusRequester()
                }
            focusScope.launch {
                // Header is lazy-grid item zero; posters begin at item one.
                val targetGridIndex = targetIndex + 1
                val alreadyVisible =
                    gridState.layoutInfo.visibleItemsInfo.any {
                        it.index == targetGridIndex
                    }
                if (!alreadyVisible) {
                    gridState.scrollToItem(targetGridIndex)
                    withTimeoutOrNull(1_000L) {
                        snapshotFlow {
                            gridState.layoutInfo.visibleItemsInfo.any {
                                it.index == targetGridIndex
                            }
                        }.first { it }
                    }
                }
                withFrameNanos { }
                delay(120L)
                repeat(3) { attempt ->
                    if (runCatching {
                            targetRequester.requestFocus()
                        }.getOrDefault(false)) {
                        return@launch
                    }
                    delay(40L * (attempt + 1))
                }
            }
        }
    }



    val appendPage = rememberCollectionPagination(
        state.items.map { it.id }, state.catalogLoadingMore, gridState,
        itemFocusRequesters, loadMore
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .modernGridVerticalFocus(
                enabled = focusedPosterIndex >= 0,
                index = focusedPosterIndex,
                columns = columns,
                itemCount = focusIds.size,
                moveFocus = moveFocusToIndex
            ),
        contentPadding = PaddingValues(
            start = if (isTv) 24.dp else 18.dp,
            end = if (isTv) 24.dp else 18.dp,
            top = 16.dp,
            bottom = 54.dp
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (isTv) 28.dp else 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(
            if (isTv) 20.dp else 12.dp
        )
    ) {
        item("collection-header", span = fullSpan) {
            ModernCollectionHeader(
                title = category.title,
                subtitle =
                    "IPTV · ${category.type.title} · ${state.items.size} loaded",
                close = close,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = false,
                            onClick = refresh,
                            enabled = !state.loading && !state.catalogLoadingMore,
                            label = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null, Modifier.size(17.dp)) }
                        )
                        if (isLiveTv) {
                            FilterChip(
                                selected = themedLiveTiles,
                                onClick = {
                                    themedLiveTiles = true
                                    liveTilePreferences.edit()
                                        .putBoolean("themed", true)
                                        .apply()
                                },
                                label = { Text("Theme") },
                                leadingIcon = {
                                    Icon(Icons.Default.Tune, null, Modifier.size(17.dp))
                                }
                            )
                            FilterChip(
                                selected = !themedLiveTiles,
                                onClick = {
                                    themedLiveTiles = false
                                    liveTilePreferences.edit()
                                        .putBoolean("themed", false)
                                        .apply()
                                },
                                label = { Text("Thumbnails") }
                            )
                        }
                    }
                }
            )
        }

        gridItemsIndexed(
            items = state.items,
            key = { _, media ->
                "modern-iptv-${category.type.name}-${media.id}"
            }
        ) { index, media ->
            val tileModifier = Modifier
                .onFocusChanged {
                    if (it.hasFocus) focusedPosterIndex = index
                }
                .focusRequester(
                    itemFocusRequesters.getOrPut(media.id) {
                        FocusRequester()
                    }
                )
                .modernGridVerticalFocus(
                    enabled = true,
                    index = index,
                    columns = columns,
                    itemCount = focusIds.size,
                    moveFocus = moveFocusToIndex
                )
            val favorite = state.favorites.any {
                it.kind == kind && it.media.id == media.id
            }
            val favoriteAction = {
                toggleFavorite(
                    FavoriteItem(
                        kind = kind,
                        media = media,
                        categoryTitle = category.title
                    )
                )
            }
            if (isLiveTv) {
                ModernLiveChannelTile(
                    item = media,
                    categoryTitle = category.title,
                    themed = themedLiveTiles,
                    isFavorite = favorite,
                    onFavorite = favoriteAction,
                    onClick = { openItem(media) },
                    modifier = tileModifier,
                    isTv = isTv
                )
            } else ModernCollectionPoster(
                item = media,
                offlineDownload = state.offlineDownloads.firstOrNull { download ->
                    download.catalogType == category.type && download.media.id == media.id
                },
                offlineRevision = state.offlineDownloadRevision,
                modifier = tileModifier,
                subtitle = media.description.orEmpty(),
                onClick = {
                    openItem(media)
                },
                isFavorite = favorite,
                onFavorite = favoriteAction,
                isTv = isTv
            )
        }

        if (state.loading && state.items.isEmpty()) {
            item("iptv-loading", span = fullSpan) {
                ModernCollectionLoading(
                    "Loading ${category.title}…"
                )
            }
        }

        if (state.catalogHasMore || state.catalogLoadingMore || appendPage.pending) {
            item("iptv-load-more", span = fullSpan) {
                ModernLoadMoreButton(
                    label = "Load more",
                    loading = state.catalogLoadingMore || appendPage.pending,
                    onClick = appendPage.load
                )
            }
        }

        if (state.items.isEmpty() && !state.loading) {
            item("iptv-empty", span = fullSpan) {
                ModernEmptyCollection(
                    "Nothing to show in this provider category."
                )
            }
        }
    }
}

private fun modernPosterColumns(
    configuration: Configuration,
    isTv: Boolean
): Int = when {
    isTv || configuration.screenWidthDp >= 1400 -> 6
    configuration.screenWidthDp >= 1100 -> 5
    configuration.screenWidthDp >= 760 -> 4
    configuration.screenWidthDp >= 430 -> 3
    else -> 2
}

@Composable
private fun ModernCollectionHeader(
    title: String,
    subtitle: String,
    close: () -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalIconButton(onClick = close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAFAFAF)
                )
            }
        }
        action?.invoke()
    }
}

@Composable
private fun ModernLiveChannelTile(
    item: MediaItem,
    categoryTitle: String,
    themed: Boolean,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTv: Boolean
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val shape = RoundedCornerShape(if (isPhone) 14.dp else 16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val palette = remember(item.id, item.title) {
        destinationPalette("live:${item.id}:${item.title}")
    }
    val programme = item.liveProgramme
    val scheduleText = remember(programme?.startTimeMillis, programme?.endTimeMillis) {
        val formatter = java.text.SimpleDateFormat(
            "h:mm a",
            java.util.Locale.getDefault()
        )
        when {
            programme?.startTimeMillis != null && programme.endTimeMillis != null ->
                "${formatter.format(java.util.Date(programme.startTimeMillis))}–" +
                    formatter.format(java.util.Date(programme.endTimeMillis))
            programme?.startTimeMillis != null ->
                "From ${formatter.format(java.util.Date(programme.startTimeMillis))}"
            else -> null
        }
    }
    val programmeProgress = remember(programme?.startTimeMillis, programme?.endTimeMillis) {
        val start = programme?.startTimeMillis
        val end = programme?.endTimeMillis
        if (start != null && end != null && end > start) {
            ((System.currentTimeMillis() - start).toFloat() / (end - start))
                .coerceIn(0f, 1f)
        } else null
    }
    val upcomingProgramme = remember(item.liveSchedule, programme) {
        val now = System.currentTimeMillis()
        item.liveSchedule.firstOrNull { entry ->
            entry != programme && (entry.startTimeMillis ?: Long.MAX_VALUE) > now &&
                entry.title.isNotBlank() && !entry.title.equals(item.title, ignoreCase = true)
        }
    }
    val technicalSummary = remember(
        item.channelNumber,
        item.streamType,
        item.catchupAvailable,
        item.epgChannelId,
        categoryTitle
    ) {
        buildList {
            item.channelNumber?.let { add("CH $it") }
            add(categoryTitle)
            item.streamType?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            if (item.catchupAvailable == true) add("Catch-up")
            if (!item.epgChannelId.isNullOrBlank()) add("EPG")
        }.distinct().joinToString("  •  ")
    }

    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = modifier.then(returningTile.modifier)
                .fillMaxWidth()
                .heightIn(min = if (isPhone) 102.dp else 118.dp)
                .onFocusChanged { focused = it.isFocused }
                .remoteCombinedClickable(
                    interactionSource = interactionSource,
                    onClick = returningTile.open,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(
                if (focused) if (isTv) 3.dp else 2.dp else 1.dp,
                if (focused) Color.White else Color(0xFF35383F)
            )
        ) {
            Row(
                Modifier
                    .background(
                        if (themed) {
                            Brush.linearGradient(listOf(palette.first, palette.second))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF171A20), Color(0xFF101216)))
                        }
                    )
                    .padding(if (isPhone) 10.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 11.dp else 14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(if (isPhone) 58.dp else 72.dp),
                    shape = RoundedCornerShape(if (isPhone) 11.dp else 13.dp),
                    color = Color.Black.copy(alpha = .30f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!themed && !item.logo.isNullOrBlank()) {
                            AsyncImage(
                                model = artworkRequest(context, item),
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.LiveTv,
                                null,
                                Modifier.size(if (isPhone) 27.dp else 32.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = if (isPhone) MaterialTheme.typography.bodyLarge
                        else MaterialTheme.typography.titleMedium,
                        fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    programme?.title?.takeIf {
                        it.isNotBlank() && !it.equals(item.title, ignoreCase = true)
                    }?.let { title ->
                        Text(
                            "Now · $title",
                            color = Color(0xFFF1C7CB),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (programme == null) {
                        item.description?.takeIf {
                            it.isNotBlank() && !it.equals(item.title, ignoreCase = true)
                        }?.let { description ->
                            Text(
                                description,
                                color = Color.White.copy(alpha = .72f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    scheduleText?.let {
                        Text(
                            it,
                            color = Color.White.copy(alpha = .68f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    upcomingProgramme?.let { next ->
                        val start = next.startTimeMillis?.let {
                            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(it))
                        }
                        Text(
                            listOfNotNull("Next", start, next.title).joinToString(" · "),
                            color = Color.White.copy(alpha = .62f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    programmeProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Color(0xFFE50914),
                            trackColor = Color.White.copy(alpha = .16f)
                        )
                    }
                    Text(
                        technicalSummary,
                        color = Color.White.copy(alpha = .58f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    tint = Color.White.copy(alpha = if (focused) 1f else .62f)
                )
            }
        }
        ModernTileActionsMenu(
            expanded = menuOpen,
            isFavorite = isFavorite,
            dismiss = { menuOpen = false },
            toggleFavorite = onFavorite,
            clear = null
        )
    }
}

@Composable
private fun ModernCollectionPoster(
    item: MediaItem,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    offlineDownload: OfflineMediaDownload? = null,
    offlineRevision: Long = 0L,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    compactLandscape: Boolean = false,
    isTv: Boolean
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val offlineInfo = remember(offlineDownload, offlineRevision) {
        offlineDownload?.let {
            OfflineMediaDownloads.info(context, it.requestId, it.downloadId)
        }
    }
    val downloadActive = offlineInfo?.status in setOf(
        OfflineDownloadStatus.QUEUED,
        OfflineDownloadStatus.DOWNLOADING,
        OfflineDownloadStatus.PAUSED
    )
    val configuration = LocalConfiguration.current
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernPosterProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!isTv && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernPosterTouchPress"
    )
    val visualProgress =
        if (isTv) focusProgress
        else maxOf(focusProgress, pressProgress)
    val active = focused || pressed
    val scale =
        1f + (
            when {
                isTv -> 0f
                isTablet -> 0.035f
                else -> 0.025f
            } * visualProgress
        )
    val shape = RoundedCornerShape(11.dp)
    val borderColor = lerp(
        Color(0xFF30343B),
        when {
            isTv -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )

    Column(
        modifier.then(returningTile.modifier)
            .fillMaxWidth()
            .zIndex(visualProgress),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        /*
         * SINGLE_ACTIVATION_SURFACE_V26
         *
         * Surface(onClick) already owns click and focus semantics. Adding a
         * second explicit focus node here can consume the first remote
         * activation before the click callback is dispatched.
         */
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .touchTileShadow(isTv = isTv,
                    elevation =
                        (
                            when {
                                isTv -> 0f
                                isTablet -> 10f
                                else -> 6f
                            } * visualProgress
                        ).dp,
                    shape = shape,
                    clip = false,
                    ambientColor =
                        if (isTv) Color(0x88000000)
                        else Color(0x55000000),
                    spotColor =
                        if (isTv) Color(0x66E50914)
                        else Color(0x22E50914)
                )
                .onFocusChanged {
                    focused = it.isFocused
                }
                .remoteCombinedClickable(
                    interactionSource = interactionSource,
                    onClick = returningTile.open,
                    onLongClick = if (onFavorite != null) {
                        { menuOpen = true }
                    } else {
                        null
                    }
                ),
            shape = shape,
            color = Color(0xFF202020),
            border = BorderStroke(
                when {
                    isTv && focused -> 3.dp
                    !isTv && focused -> 2.dp
                    else -> 1.dp
                },
                borderColor
            )
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (compactLandscape) 4f / 3f else 2f / 3f)
                    // Animate only the visual artwork. The Surface above owns
                    // focus and must keep fixed bounds so lazy-grid scrolling
                    // does not chase every animation frame.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(Color(0xFF222222))
            ) {
                ModernPosterImage(
                    item = item,
                    context = context,
                    modifier = Modifier.fillMaxSize()
                )

                if (downloadActive) {
                    val percent = offlineInfo?.percent?.coerceIn(0f, 100f)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xDD111318)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Downloading,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color.White
                            )
                            Text(
                                percent?.let { "${it.toInt()}%" } ?: "Queued",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(6.dp),
                            color = Color(0xFFE50914),
                            trackColor = Color.Black.copy(alpha = 0.72f)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(6.dp),
                            color = Color(0xFFE50914),
                            trackColor = Color.Black.copy(alpha = 0.72f)
                        )
                    }
                }

                if (isTv && focused) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0xFFE50914))
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.title,
                    color =
                        if (active) Color.White
                        else Color(0xFFD4D7DC),
                    fontWeight =
                        if (focused) FontWeight.SemiBold
                        else FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color =
                            if (active) Color(0xFFBFC3CA)
                            else Color(0xFF858B94),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        onFavorite?.let { favoriteAction ->
            ModernTileActionsMenu(
                expanded = menuOpen,
                isFavorite = isFavorite,
                dismiss = { menuOpen = false },
                toggleFavorite = favoriteAction,
                clear = null
            )
        }
    }
}

@Composable
private fun ModernPosterImage(
    item: MediaItem,
    context: Context,
    modifier: Modifier
) {
    val palette = remember(
        item.id,
        item.title
    ) {
        destinationPalette(
            "${item.id}:${item.title}"
        )
    }

    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    palette.first,
                    palette.second
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            item.title.take(1).uppercase(),
            color = Color.White.copy(alpha = 0.13f),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black
        )
        if (!item.logo.isNullOrBlank()) {
            AsyncImage(
                model = artworkRequest(
                    context,
                    item
                ),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun ModernCollectionLoading(
    label: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            Modifier.size(22.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = Color.LightGray
        )
    }
}

@Composable
private fun ModernLoadMoreButton(
    label: String,
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            // Retain focus while pagination is running. Disabling this
            // button removes it from the TV focus graph and lets focus jump
            // to the navigation rail before the new tiles are composed.
            enabled = true,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE50914),
                contentColor = Color.White
            )
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (loading) "Loading…" else label,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ModernEmptyCollection(
    message: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF151515),
        border = BorderStroke(
            1.dp,
            Color(0xFF303030)
        )
    ) {
        Box(
            Modifier.padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                message,
                color = Color(0xFFAFAFAF),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
