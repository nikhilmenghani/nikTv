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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.model.TmdbHomeSection
import com.nikhil.niktv.model.WatchedSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090909)
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
            .background(Color(0xFF0B0B0C))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFE50914)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    Modifier.padding(5.dp).size(20.dp),
                    tint = Color.White
                )
            }
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
            IconButton(onClick = openProfileSwitcher) {
                Icon(
                    Icons.Default.AccountCircle,
                    state.savedProfile?.name ?: "Profile"
                )
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

        HorizontalDivider(color = Color(0xFF242424))
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

    val tmdbSections = state.tmdbSectionsBySurface[dashboardSurface]
        .orEmpty()
        .filter { section ->
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

    val screenTitle = when (dashboardSurface) {
        DashboardSurface.HOME -> "Explore"
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
        item("hub-header", span = fullSpan) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    screenTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    if (dashboardSurface == DashboardSurface.HOME) {
                        "Choose a destination first. NikTV loads titles only after you open it."
                    } else {
                        "Pick a TMDB collection or provider category."
                    },
                    color = Color(0xFFB9B9B9),
                    style = MaterialTheme.typography.bodyMedium
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    item("search-this-tab") {
                        AssistChip(
                            onClick = openSearch,
                            label = {
                                Text(
                                    if (dashboardSurface == DashboardSurface.HOME) "Search"
                                    else "Search ${screenTitle}"
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, null, Modifier.size(17.dp))
                            }
                        )
                    }
                    item("configure-tmdb") {
                        AssistChip(
                            onClick = configureTmdb,
                            label = { Text("TMDB sections") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DashboardCustomize,
                                    null,
                                    Modifier.size(17.dp)
                                )
                            }
                        )
                    }

                    when (dashboardSurface) {
                        DashboardSurface.HOME -> {
                            item("configure-live") {
                                AssistChip(
                                    onClick = {
                                        configureIptv(CatalogType.LIVE_TV)
                                    },
                                    label = { Text("Live TV categories") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.LiveTv,
                                            null,
                                            Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                            item("configure-movies") {
                                AssistChip(
                                    onClick = {
                                        configureIptv(CatalogType.MOVIES)
                                    },
                                    label = { Text("Movie categories") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.SmartDisplay,
                                            null,
                                            Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                            item("configure-series") {
                                AssistChip(
                                    onClick = {
                                        configureIptv(CatalogType.SERIES)
                                    },
                                    label = { Text("Series categories") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Tv,
                                            null,
                                            Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                        }

                        DashboardSurface.MOVIES -> {
                            item("configure-iptv") {
                                AssistChip(
                                    onClick = {
                                        configureIptv(CatalogType.MOVIES)
                                    },
                                    label = { Text("IPTV categories") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Tune,
                                            null,
                                            Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                        }

                        DashboardSurface.SERIES -> {
                            item("configure-iptv") {
                                AssistChip(
                                    onClick = {
                                        configureIptv(CatalogType.SERIES)
                                    },
                                    label = { Text("IPTV categories") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Tune,
                                            null,
                                            Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                        }

                        DashboardSurface.LIVE_TV -> {
                            item("configure-iptv") {
                                AssistChip(
                                    onClick = { configureIptv(CatalogType.LIVE_TV) },
                                    label = { Text("IPTV categories") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Tune, null, Modifier.size(17.dp))
                                    }
                                )
                            }
                        }
                    }

                    item("reset") {
                        AssistChip(
                            onClick = resetSurface,
                            label = { Text("Reset") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.RestartAlt,
                                    null,
                                    Modifier.size(17.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

        if (dashboardSurface == DashboardSurface.HOME) {
            val newEpisodes = state.watchedSeries.flatMap { watched ->
                watched.newEpisodes.map { episode -> watched to episode }
            }
            val recents = state.recentlyPlayed
                .filter {
                    it.kind == FavoriteKind.MOVIE ||
                        it.kind == FavoriteKind.SERIES
                }
                .take(12)

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

            if (recents.isNotEmpty()) {
                item("continue-header", span = fullSpan) {
                    ModernHubSectionHeading(
                        "Continue Watching",
                        "Jump back in without browsing a destination."
                    )
                }
                item("continue-row", span = fullSpan) {
                    ModernContinueRow(
                        recents = recents,
                        favorites = state.favorites,
                        returnFocusId = state.playbackReturnFocusId,
                        open = openRecent,
                        clear = removeRecent,
                        toggleFavorite = toggleFavorite
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
                    color = Color(0xFF151515),
                    border = BorderStroke(
                        1.dp,
                        Color(0xFF343434)
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
                            tint = Color(0xFFE50914)
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
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        items(
            items = entries,
            key = { (watched, episode) ->
                "modern-new-episode-${watched.series.id}-${episode.id}"
            }
        ) { (watched, episode) ->
            val displayMedia = if (
                episode.logo.isNullOrBlank() &&
                !watched.series.logo.isNullOrBlank()
            ) {
                episode.copy(logo = watched.series.logo)
            } else {
                episode
            }
            val episodeLabel = listOfNotNull(
                episode.seasonNumber?.let { "S$it" },
                episode.episodeNumber?.let { "E$it" }
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
                subtitle = listOf(watched.series.title, episodeLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onClick = { open(watched, episode) },
                isFavorite = favorites.any { it.key == favorite.key },
                onFavorite = { toggleFavorite(favorite) },
                onClear = { clear(watched, episode) }
            )
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
            color = Color(0xFF9D9D9D)
        )
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
                isTv -> 0.09f
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
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation =
                    (
                        when {
                            isTv -> 22f
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
                    if (isTv) Color(0x77E50914)
                    else Color(0x33E50914)
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
                    color = Color.Black.copy(alpha = 0.30f)
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
                            tint = Color.White
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
                        style = if (isPhone) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight =
                            if (active) FontWeight.Black
                            else FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = if (isPhone) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
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
                        .background(Color(0xFFE50914))
                )
            }
        }
    }
}

private fun destinationPalette(
    seed: String
): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF1A2332) to Color(0xFF461217),
        Color(0xFF25172B) to Color(0xFF101116),
        Color(0xFF122B2A) to Color(0xFF111218),
        Color(0xFF332115) to Color(0xFF151015),
        Color(0xFF16233A) to Color(0xFF11131C),
        Color(0xFF2D1820) to Color(0xFF171116)
    )
    return palettes[
        (seed.hashCode() and Int.MAX_VALUE) %
            palettes.size
    ]
}

@Composable
private fun ModernContinueRow(
    recents: List<RecentItem>,
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
    val returnIndex = focusIds.indexOf(returnFocusId)

    LaunchedEffect(returnFocusId, returnIndex) {
        if (returnFocusId != null && returnIndex >= 0) {
            listState.scrollToItem(returnIndex)
            withFrameNanos { }
            // Navigation and lazy content establish their initial focus in
            // separate frames. Reassert briefly so a later nav request cannot
            // steal focus from the tile that launched playback.
            repeat(6) { attempt ->
                runCatching {
                    requesters.getOrPut(returnFocusId) { FocusRequester() }
                        .requestFocus()
                }
                delay(70L + 25L * attempt)
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Focus scales TV cards beyond their layout bounds. Horizontal inset
        // keeps the first and last cards from being clipped by the viewport.
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
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
                        recent.lastPlayed?.title ?: "Series"
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
                modifier = Modifier.focusRequester(
                    requesters.getOrPut(focusId) { FocusRequester() }
                )
            )
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
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isModernTileTv(configuration)
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernCompactProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!isTv && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernCompactTouchPress"
    )
    val visualProgress =
        if (isTv) focusProgress
        else maxOf(focusProgress, pressProgress)
    val active = focused || pressed
    val scale =
        1f + (
            when {
                isTv -> 0.08f
                isTablet -> 0.035f
                else -> 0.025f
            } * visualProgress
        )
    val shape = RoundedCornerShape(14.dp)
    val backgroundColor = lerp(
        Color(0xFF15171B),
        if (isTv) Color(0xFF22252B) else Color(0xFF20242B),
        visualProgress
    )
    val borderColor = lerp(
        Color(0xFF30343B),
        when {
            isTv -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )

    Box(
        modifier = modifier
            .width(
                when {
                    isTv -> 158.dp
                    isTablet -> 168.dp
                    else -> 148.dp
                }
            )
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation =
                    (
                        when {
                            isTv -> 18f
                            isTablet -> 9f
                            else -> 5f
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
                onClick = onClick,
                onLongClick = { menuOpen = true }
            )
    ) {
        Surface(
            shape = shape,
            color = backgroundColor,
            border = BorderStroke(
                when {
                    isTv && focused -> 3.dp
                    !isTv && focused -> 2.dp
                    else -> 1.dp
                },
                borderColor
            )
        ) {
          Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Color(0xFF222222))
            ) {
                ModernPosterImage(
                    item,
                    context,
                    Modifier.fillMaxSize()
                )
            }
            Column(
                Modifier.padding(if (isTv) 10.dp else 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (focused) Color.White else Color(0xFFD4D7DC)
                        )
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (active) Color(0xFFBFC3CA) else Color(0xFF858B94),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
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
    val columns = modernPosterColumns(
        configuration,
        isTv
    )
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

    /*
     * PLAYBACK_RETURN_FOCUS_V22
     *
     * Direct-fullscreen playback replaces this composition. When Back closes
     * the player, rebuild the collection at the poster that launched playback
     * instead of returning to the header/top-left item.
     */
    val gridState = rememberLazyGridState()
    val returnFocusRequester = remember(
        section,
        state.playbackReturnFocusId
    ) {
        FocusRequester()
    }
    val returnFocusId = state.playbackReturnFocusId
    val returnIndex = focusIds.indexOf(returnFocusId)
    val itemFocusRequesters =
        remember(section) {
            mutableMapOf<String, FocusRequester>()
        }
    if (returnFocusId != null) {
        itemFocusRequesters[returnFocusId] =
            returnFocusRequester
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

    LaunchedEffect(
        returnFocusId,
        returnIndex,
        section,
        isTv
    ) {
        if (returnFocusId != null && returnIndex >= 0) {
            // Header is lazy-grid item zero; posters begin at item one.
            gridState.scrollToItem(returnIndex + 1)
            // A tablet or touch device may still be controlled by a remote.
            // Always restore the exact launching tile instead of leaving focus
            // on the navigation rail when fullscreen playback closes.
            withFrameNanos { }
            delay(120L)
            repeat(6) { attempt ->
                runCatching { returnFocusRequester.requestFocus() }
                delay(70L + 25L * attempt)
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

        if (state.modernTmdbLoading) {
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

        if (
            state.modernTmdbHasMore &&
            !state.modernTmdbLoading
        ) {
            item("tmdb-load-more", span = fullSpan) {
                ModernLoadMoreButton(
                    label = "Load 20 more",
                    loading = false,
                    onClick = loadMore
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
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val columns = modernPosterColumns(
        configuration,
        isTv
    )
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
    var loadMoreStartCount by remember(category.id) {
        mutableIntStateOf(-1)
    }

    val gridState = rememberLazyGridState()
    val returnFocusRequester = remember(
        category.id,
        state.playbackReturnFocusId
    ) {
        FocusRequester()
    }
    val returnFocusId = state.playbackReturnFocusId
    val returnIndex = focusIds.indexOf(returnFocusId)
    val itemFocusRequesters =
        remember(category.id) {
            mutableMapOf<String, FocusRequester>()
        }
    if (returnFocusId != null) {
        itemFocusRequesters[returnFocusId] =
            returnFocusRequester
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

    LaunchedEffect(
        returnFocusId,
        returnIndex,
        category.id,
        isTv
    ) {
        if (returnFocusId != null && returnIndex >= 0) {
            // Header is lazy-grid item zero; posters begin at item one.
            gridState.scrollToItem(returnIndex + 1)
            withFrameNanos { }
            delay(120L)
            repeat(6) { attempt ->
                runCatching { returnFocusRequester.requestFocus() }
                delay(70L + 25L * attempt)
            }
        }
    }

    LaunchedEffect(
        state.items.size,
        state.catalogLoadingMore,
        loadMoreStartCount
    ) {
        if (loadMoreStartCount < 0) return@LaunchedEffect

        if (state.items.size > loadMoreStartCount) {
            val newIndex = loadMoreStartCount
            val newItem = state.items[newIndex]
            gridState.scrollToItem(newIndex + 1)
            withFrameNanos { }
            delay(120L)
            repeat(5) { attempt ->
                if (runCatching {
                        itemFocusRequesters.getOrPut(newItem.id) {
                            FocusRequester()
                        }.requestFocus()
                    }.getOrDefault(false)
                ) {
                    focusedPosterIndex = newIndex
                    loadMoreStartCount = -1
                    return@LaunchedEffect
                }
                delay(50L * (attempt + 1))
            }
        } else if (!state.catalogLoadingMore) {
            loadMoreStartCount = -1
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
                close = close
            )
        }

        gridItemsIndexed(
            items = state.items,
            key = { _, media ->
                "modern-iptv-${category.type.name}-${media.id}"
            }
        ) { index, media ->
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
                        itemCount = focusIds.size,
                        moveFocus = moveFocusToIndex
                    ),
                subtitle = media.description.orEmpty(),
                onClick = {
                    openItem(media)
                },
                isFavorite = state.favorites.any {
                    it.kind == kind &&
                        it.media.id == media.id
                },
                onFavorite = {
                    toggleFavorite(
                        FavoriteItem(
                            kind = kind,
                            media = media,
                            categoryTitle = category.title
                        )
                    )
                },
                compactLandscape = category.type == CatalogType.LIVE_TV,
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

        if (state.catalogHasMore) {
            item("iptv-load-more", span = fullSpan) {
                ModernLoadMoreButton(
                    label =
                        if (state.catalogLoadingMore) {
                            "Loading titles…"
                        } else {
                            "Load more"
                        },
                    loading = state.catalogLoadingMore,
                    onClick = {
                        if (!state.catalogLoadingMore) {
                            loadMoreStartCount = state.items.size
                            loadMore()
                        }
                    }
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
    close: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalIconButton(
            onClick = close
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back"
            )
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
}

@Composable
private fun ModernCollectionPoster(
    item: MediaItem,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    compactLandscape: Boolean = false,
    isTv: Boolean
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
                isTv -> 0.085f
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
        modifier
            .fillMaxWidth()
            .zIndex(visualProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
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
                .shadow(
                    elevation =
                        (
                            when {
                                isTv -> 20f
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
                    onClick = onClick,
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
                    .background(Color(0xFF222222))
            ) {
                ModernPosterImage(
                    item = item,
                    context = context,
                    modifier = Modifier.fillMaxSize()
                )

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
                label,
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
