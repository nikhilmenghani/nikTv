package com.nikhil.niktv.ui

// MODERN_TILE_BROWSE_V1
//
// Tile-first destination browsing for Home / Movies / Series.
// The existing ModernBrowseScreen is still used when modernUiEnabled is false.

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.nikhil.niktv.model.FavoriteSource
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

private data class ModernCollectionViewport(val index: Int, val offset: Int)

/** Keeps category viewports alive while the fullscreen player replaces browse UI. */
private object ModernCollectionViewportMemory {
    private val viewports = mutableMapOf<String, ModernCollectionViewport>()

    fun get(key: String): ModernCollectionViewport? = viewports[key]

    fun put(key: String, index: Int, offset: Int) {
        viewports[key] = ModernCollectionViewport(
            index = index.coerceAtLeast(0),
            offset = offset.coerceAtLeast(0)
        )
    }
}
private val ModernBrandAccent = Color(0xFF7C8CFF)
private val ModernBrandViolet = Color(0xFFA275FF)

@Composable
private fun modernTvTileTitleStyle(shadowed: Boolean = false): TextStyle =
    MaterialTheme.typography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 14.sp,
        shadow = if (shadowed) Shadow(
            color = Color.Black.copy(alpha = 0.95f),
            offset = Offset(1f, 1f),
            blurRadius = 4f
        ) else null
    )

@Composable
private fun modernTvTileSubtitleStyle(shadowed: Boolean = false): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        shadow = if (shadowed) Shadow(
            color = Color.Black,
            offset = Offset(1f, 1f),
            blurRadius = 3f
        ) else null
    )

// touchTileShadow is shared in NikTvApp.kt so TV focus never installs
// a zero-elevation shadow layer while touch layouts keep their existing lift.

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
    openSeries: (MediaItem) -> Unit,
    clearRecentChannels: () -> Unit,
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
    enrichFocusedCatalogMetadata: suspend (MediaItem, CatalogType) -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    removeIptvCategory: (CatalogType, String) -> Unit,
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
                        enrichFocusedMetadata = enrichFocusedCatalogMetadata,
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
                        enrichFocusedMetadata = enrichFocusedCatalogMetadata,
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
                            openSeries = openSeries,
                            clearRecentChannels = clearRecentChannels,
                            openWatchedEpisode = openWatchedEpisode,
                            dismissWatchedEpisode = dismissWatchedEpisode,
                            toggleFavorite = toggleFavorite,
                            openTmdbSection = openTmdbSection,
                            openIptvCategory = openIptvCategory,
                            openSearch = openSearch,
                            configureTmdb = configureTmdb,
                            configureIptv = configureIptv,
                            removeIptvCategory = removeIptvCategory,
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

internal fun Context.isModernTileTv(configuration: Configuration): Boolean =
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
    val isPhone = LocalConfiguration.current.smallestScreenWidthDp < 600
    var profileMenuOpen by remember { mutableStateOf(false) }
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
            if (!isPhone) {
                IconButton(onClick = openProfileSwitcher) {
                    Icon(Icons.Default.AccountCircle, state.savedProfile?.name ?: "Profile")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "NikTV",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                if (isPhone) {
                    Text(
                        state.savedProfile?.name ?: "Profile",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = openSearch) {
                Icon(Icons.Default.Search, "Search")
            }
            if (!isPhone) {
                IconButton(onClick = openFavorites) {
                    Icon(Icons.Default.FavoriteBorder, "My List")
                }
            }
            if (isPhone) {
                Box {
                    IconButton(onClick = { profileMenuOpen = true }) {
                        Icon(Icons.Default.AccountCircle, "Profile menu")
                    }
                    DropdownMenu(
                        expanded = profileMenuOpen,
                        onDismissRequest = { profileMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Switch profile") },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, null) },
                            onClick = { profileMenuOpen = false; openProfileSwitcher() }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { profileMenuOpen = false; openSettings() }
                        )
                    }
                }
            } else {
                IconButton(onClick = openSettings) {
                    Icon(Icons.Default.Settings, "Settings")
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
    NikTvTextActionButton(onClick = onClick) {
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
    openSeries: (MediaItem) -> Unit,
    clearRecentChannels: () -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    dismissWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    openTmdbSection: (TmdbHomeSection) -> Unit,
    openIptvCategory: (Category) -> Unit,
    openSearch: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    removeIptvCategory: (CatalogType, String) -> Unit,
    resetSurface: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val profileKey = state.savedProfile?.cacheKey().orEmpty()
    val screenTitle = when (dashboardSurface) {
        DashboardSurface.HOME -> "Home"
        DashboardSurface.MOVIES -> "Movies"
        DashboardSurface.SERIES -> "Series"
        DashboardSurface.LIVE_TV -> "Live TV"
    }
    var recentChannelsOpen by rememberSaveable(profileKey, dashboardSurface) { mutableStateOf(false) }
    var confirmClearChannels by remember(profileKey) { mutableStateOf(false) }
    if (confirmClearChannels) {
        ProjectCardConfirmationDialog(
            title = "Clear recently played channels?",
            message = "This removes the channel history for this profile. Your channels and My List are kept.",
            confirmLabel = "Clear history",
            close = { confirmClearChannels = false },
            confirm = { clearRecentChannels(); confirmClearChannels = false }
        )
    }
    val recentChannels = state.recentlyPlayed
        .filter { it.kind == FavoriteKind.CHANNEL && it.profileKey == profileKey }
        .sortedByDescending { it.playedAtMillis }
        .distinctBy { it.media.id }
    if (recentChannelsOpen && dashboardSurface == DashboardSurface.LIVE_TV) {
        ModernRecentChannelsCollection(
            recents = recentChannels,
            favorites = state.favorites,
            categories = state.rawCategoriesByType[CatalogType.LIVE_TV].orEmpty(),
            clear = removeRecent,
            clearAll = { confirmClearChannels = true },
            open = openRecent,
            toggleFavorite = toggleFavorite,
            close = { recentChannelsOpen = false },
            isTv = isTv,
            modifier = modifier
        )
        return
    }
    var customizeHomeOpen by rememberSaveable(profileKey, dashboardSurface) { mutableStateOf(false) }
    if (customizeHomeOpen && dashboardSurface == DashboardSurface.HOME) {
        ModernCustomizeHomeDialog(
            dismiss = { customizeHomeOpen = false },
            configureTmdb = configureTmdb,
            configureIptv = configureIptv,
            resetSurface = resetSurface
        )
    }
    if (customizeHomeOpen && dashboardSurface != DashboardSurface.HOME) {
        ModernBrowseOptionsDialog(
            dashboardSurface = dashboardSurface,
            screenTitle = screenTitle,
            recentChannelCount = recentChannels.size,
            dismiss = { customizeHomeOpen = false },
            openSearch = openSearch,
            openRecentChannels = { recentChannelsOpen = true },
            clearRecentChannels = { confirmClearChannels = true },
            configureTmdb = configureTmdb,
            configureIptv = configureIptv,
            resetSurface = resetSurface
        )
    }
    var pinnedLiveCategories by remember(profileKey) {
        mutableStateOf(IptvPinPreferences.pinnedCategories(context, profileKey, CatalogType.LIVE_TV))
    }
    var pinnedMovieCategories by remember(profileKey) {
        mutableStateOf(IptvPinPreferences.pinnedCategories(context, profileKey, CatalogType.MOVIES))
    }
    var pinnedSeriesCategories by remember(profileKey) {
        mutableStateOf(IptvPinPreferences.pinnedCategories(context, profileKey, CatalogType.SERIES))
    }
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
            ).sortedBy { it.id !in pinnedLiveCategories }
        } else {
            emptyList()
        }
    val movieCategories =
        if (dashboardSurface in setOf(DashboardSurface.HOME, DashboardSurface.MOVIES)) {
            state.modernVisibleIptvCategories(
                CatalogType.MOVIES,
                requireExplicitSelection = dashboardSurface == DashboardSurface.HOME
            ).sortedBy { it.id !in pinnedMovieCategories }
        } else {
            emptyList()
        }
    val seriesCategories =
        if (dashboardSurface in setOf(DashboardSurface.HOME, DashboardSurface.SERIES)) {
            state.modernVisibleIptvCategories(
                CatalogType.SERIES,
                requireExplicitSelection = dashboardSurface == DashboardSurface.HOME
            ).sortedBy { it.id !in pinnedSeriesCategories }
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
        if (dashboardSurface == DashboardSurface.HOME) {
            item("home-header", span = fullSpan) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    NikTvSecondaryActionButton(
                        onClick = { customizeHomeOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.DashboardCustomize, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Customize Home")
                    }
                }
            }
        }
        if (dashboardSurface != DashboardSurface.HOME) {
            item("hub-header", span = fullSpan) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            screenTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        NikTvSecondaryActionButton(
                            onClick = { customizeHomeOpen = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.DashboardCustomize, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Browse options")
                        }
                    }
                    Text(
                        "Choose what to browse",
                        color = Color(0xFFA7ABB5),
                        style = MaterialTheme.typography.bodyMedium
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
                        openSeries = openSeries,
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
                        openSeries = openSeries,
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
                    pinned = category.id in pinnedLiveCategories,
                    onTogglePin = {
                        pinnedLiveCategories = IptvPinPreferences.toggleCategory(
                            context, profileKey, CatalogType.LIVE_TV, category.id
                        )
                    },
                    onRemove = { removeIptvCategory(CatalogType.LIVE_TV, category.id) },
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
                    pinned = category.id in pinnedMovieCategories,
                    onTogglePin = {
                        pinnedMovieCategories = IptvPinPreferences.toggleCategory(
                            context, profileKey, CatalogType.MOVIES, category.id
                        )
                    },
                    onRemove = { removeIptvCategory(CatalogType.MOVIES, category.id) },
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
                    pinned = category.id in pinnedSeriesCategories,
                    onTogglePin = {
                        pinnedSeriesCategories = IptvPinPreferences.toggleCategory(
                            context, profileKey, CatalogType.SERIES, category.id
                        )
                    },
                    onRemove = { removeIptvCategory(CatalogType.SERIES, category.id) },
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
                            if (dashboardSurface == DashboardSurface.HOME)
                                "Add collections and provider categories to make Home your own."
                            else "Choose collections or provider categories using the controls above.",
                            color = Color(0xFFB9B9B9)
                        )
                        if (dashboardSurface == DashboardSurface.HOME) {
                            NikTvSecondaryActionButton(
                                onClick = { customizeHomeOpen = true }
                            ) {
                                Text("Choose what appears on Home")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernRecentChannelsCollection(
    recents: List<RecentItem>,
    favorites: List<FavoriteItem>,
    categories: List<Category>,
    clear: (RecentItem) -> Unit,
    clearAll: () -> Unit,
    open: (RecentItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    close: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = close)
    val configuration = LocalConfiguration.current
    val columns = when {
        !isTv && configuration.smallestScreenWidthDp < 600 -> 1
        isTv -> 2
        configuration.screenWidthDp >= 800 -> 3
        else -> 2
    }
    var categoryToClear by remember { mutableStateOf<Pair<String?, String>?>(null) }
    val grouped = recents.groupBy { recent ->
        recent.media.portalCategoryId to (recent.categoryTitle
            ?: categories.firstOrNull { it.id == recent.media.portalCategoryId }?.title
            ?: "Unknown category")
    }
    categoryToClear?.let { category ->
        ProjectCardConfirmationDialog(
            title = "Clear ${category.second} history?",
            message = "Remove recently played channels in this category. Other categories and My List are kept.",
            confirmLabel = "Clear category",
            close = { categoryToClear = null },
            confirm = {
                grouped[category].orEmpty().forEach(clear)
                categoryToClear = null
            }
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(if (isTv) 24.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("header", span = { GridItemSpan(maxLineSpan) }) {
            ModernCollectionHeader(
                title = "Recently Played",
                subtitle = "Live TV · Grouped by category · Most recent first",
                close = close,
                action = {
                    if (recents.isNotEmpty()) {
                        NikTvTextActionButton(onClick = clearAll) { Text("Clear channel history") }
                    }
                }
            )
        }
        if (recents.isEmpty()) {
            item("empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("Watch a live TV channel to find it here next time.", color = Color(0xFFAFAFAF))
            }
        }
        grouped.forEach { (category, channels) ->
        item("recent-category-${category.first}-${category.second}", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    category.second,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                NikTvTextActionButton(onClick = { categoryToClear = category }) {
                    Text("Clear category")
                }
            }
        }
        gridItems(channels, key = { it.key }) { recent ->
            ModernLiveChannelTile(
                item = recent.media,
                categoryTitle = category.second,
                isFavorite = favorites.any { it.key == recent.key },
                onFavorite = {
                    toggleFavorite(FavoriteItem(
                        kind = recent.kind,
                        media = recent.media,
                        profileKey = recent.profileKey
                    ))
                },
                isPinned = false,
                onTogglePin = null,
                onClear = { clear(recent) },
                onClick = { open(recent) },
                isTv = isTv
            )
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
    openSeries: (MediaItem) -> Unit,
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
                onOpenSeries = { openSeries(watched.series) },
                onClear = { clear(watched, episode) },
                homeCardScale = 0.82f
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
private fun ModernCustomizeHomeDialog(
    dismiss: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit
) {
    val firstAction = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.smallestScreenWidthDp < 600 &&
        configuration.screenWidthDp > configuration.screenHeightDp
    val bodyMaxHeight =
        (configuration.screenHeightDp * if (compactLandscape) .55f else .68f).dp
    AlertDialog(
        onDismissRequest = dismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Customize Home", color = Color.White)
                Text(
                    "Choose the collections and categories that appear on Home.",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp)
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val actions = listOf<Pair<String, () -> Unit>>(
                    "TMDB sections" to configureTmdb,
                    "Live TV categories" to { configureIptv(CatalogType.LIVE_TV) },
                    "Movie categories" to { configureIptv(CatalogType.MOVIES) },
                    "Series categories" to { configureIptv(CatalogType.SERIES) },
                    "Restore Home defaults" to resetSurface
                )
                actions.forEachIndexed { index, (label, action) ->
                    if (index == actions.lastIndex) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                    }
                    NikTvSecondaryActionButton(
                        onClick = { dismiss(); action() },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                    ) {
                        Text(label, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            NikTvTextActionButton(
                onClick = dismiss
            ) { Text("Done") }
        }
    )
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstAction.requestFocus()
    }
}

@Composable
private fun ModernBrowseOptionsDialog(
    dashboardSurface: DashboardSurface,
    screenTitle: String,
    recentChannelCount: Int,
    dismiss: () -> Unit,
    openSearch: () -> Unit,
    openRecentChannels: () -> Unit,
    clearRecentChannels: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit
) {
    val firstAction = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.smallestScreenWidthDp < 600 &&
        configuration.screenWidthDp > configuration.screenHeightDp
    val bodyMaxHeight =
        (configuration.screenHeightDp * if (compactLandscape) .55f else .68f).dp
    val catalogType = when (dashboardSurface) {
        DashboardSurface.LIVE_TV -> CatalogType.LIVE_TV
        DashboardSurface.MOVIES -> CatalogType.MOVIES
        DashboardSurface.SERIES -> CatalogType.SERIES
        DashboardSurface.HOME -> CatalogType.LIVE_TV
    }
    val actions = buildList<Pair<String, () -> Unit>> {
        add("Search" to openSearch)
        if (dashboardSurface == DashboardSurface.LIVE_TV) {
            add("Recently played · $recentChannelCount" to openRecentChannels)
        } else {
            add("TMDB sections" to configureTmdb)
        }
        add("IPTV categories" to { configureIptv(catalogType) })
        if (dashboardSurface == DashboardSurface.LIVE_TV && recentChannelCount > 0) {
            add("Clear recently played channels" to clearRecentChannels)
        }
        add("Restore $screenTitle defaults" to resetSurface)
    }

    AlertDialog(
        onDismissRequest = dismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$screenTitle options", color = Color.White)
                Text(
                    "Search, browse collections, or configure provider categories.",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp)
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEachIndexed { index, (label, action) ->
                    if (index == actions.lastIndex || label.startsWith("Clear recently")) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                    }
                    NikTvSecondaryActionButton(
                        onClick = { dismiss(); action() },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                    ) {
                        Text(label, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            NikTvTextActionButton(onClick = dismiss) { Text("Done") }
        }
    )
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstAction.requestFocus()
    }
}

@Composable
private fun ModernHubQuickActions(
    dashboardSurface: DashboardSurface,
    openSearch: () -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit,
    openRecentChannels: () -> Unit,
    clearRecentChannels: () -> Unit,
    recentChannelCount: Int,
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp >= 720) 3 else 2
    val actions = buildList {
        add(ModernQuickAction("search", "Search", Icons.Default.Search, ModernBrandAccent, openSearch))
        if (dashboardSurface == DashboardSurface.LIVE_TV) {
            add(
                ModernQuickAction(
                    "recent",
                    "Recently played · $recentChannelCount",
                    Icons.Default.History,
                    ModernBrandViolet,
                    openRecentChannels,
                    clearRecentChannels
                )
            )
        } else {
            add(ModernQuickAction("tmdb", "TMDB sections", Icons.Default.DashboardCustomize, ModernBrandViolet, configureTmdb))
        }
        val catalogType = when (dashboardSurface) {
            DashboardSurface.LIVE_TV -> CatalogType.LIVE_TV
            DashboardSurface.MOVIES -> CatalogType.MOVIES
            DashboardSurface.SERIES -> CatalogType.SERIES
            DashboardSurface.HOME -> CatalogType.LIVE_TV
        }
        val accent = when (catalogType) {
            CatalogType.LIVE_TV -> Color(0xFFE65D68)
            CatalogType.MOVIES -> Color(0xFF55B8FF)
            CatalogType.SERIES -> Color(0xFF9A80FF)
            CatalogType.RADIO -> ModernBrandAccent
        }
        add(ModernQuickAction("iptv", "IPTV categories", Icons.Default.Tune, accent, { configureIptv(catalogType) }))
        add(ModernQuickAction("reset", "Reset layout", Icons.Default.RestartAlt, Color(0xFFA7ADB8), resetSurface))
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.chunked(columns).forEach { rowActions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowActions.forEach { action ->
                    ModernQuickActionCell(action, isTv, Modifier.weight(1f))
                }
                repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class ModernQuickAction(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit,
    val onClear: (() -> Unit)? = null
)

@Composable
private fun ModernQuickActionCell(action: ModernQuickAction, isTv: Boolean, modifier: Modifier) {
    var menuOpen by remember(action.key) { mutableStateOf(false) }
    Box(modifier) {
        ModernQuickActionTile(
            title = action.title,
            icon = action.icon,
            accent = action.accent,
            isTv = isTv,
            modifier = Modifier.fillMaxWidth(),
            onClick = action.onClick,
            onLongClick = action.onClear?.let { { menuOpen = true } }
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(12.dp)
        ) {
            action.onClear?.let { clear ->
                NikDropdownMenuItem(
                    text = { Text("Clear recently played channels") },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    onClick = { menuOpen = false; clear() }
                )
            }
        }
    }
}

@Composable
private fun ModernQuickActionTile(
    title: String,
    icon: ImageVector,
    accent: Color,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
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
    val tileHeight = when {
        isTv -> 82.dp
        isPhone -> 72.dp
        else -> 78.dp
    }

    Surface(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .remoteCombinedClickable(interactionSource = interactionSource, onClick = onClick, onLongClick = onLongClick)
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
            ),
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
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = if (isTv || isPhone) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
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
    var pinMenuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Surface(
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
                }
                .remoteCombinedClickable(
                    interactionSource = interactionSource,
                    onClick = returningTile.open,
                    onLongClick = if (onTogglePin != null || onRemove != null) ({ pinMenuOpen = true }) else null
                ),
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
                    .fillMaxWidth()
                    .padding(end = if (onTogglePin != null) 38.dp else 0.dp),
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
                            isTv -> MaterialTheme.typography.labelMedium
                            isPhone -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight =
                            if (active) FontWeight.Black
                            else FontWeight.Bold,
                        color = Color.White,
                        maxLines = if (isTv) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
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

            if (pinned) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        "Pinned $title",
                        modifier = Modifier.size(18.dp),
                        tint = ModernBrandAccent
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
        if (onTogglePin != null || onRemove != null) {
            DropdownMenu(
                expanded = pinMenuOpen,
                onDismissRequest = { pinMenuOpen = false },
                containerColor = Color(0xFF202020),
                shape = RoundedCornerShape(12.dp)
            ) {
                onTogglePin?.let { togglePin ->
                    NikDropdownMenuItem(
                        text = { Text(if (pinned) "Unpin category" else "Pin category to top") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { pinMenuOpen = false; togglePin() }
                    )
                }
                onRemove?.let { remove ->
                    NikDropdownMenuItem(
                        text = { Text("Remove from this tab") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = { pinMenuOpen = false; remove() }
                    )
                }
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
    openSeries: (MediaItem) -> Unit,
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
                onOpenSeries = if (recent.kind == FavoriteKind.SERIES) {
                    { openSeries(recent.media) }
                } else {
                    null
                },
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
                homeCardScale = 0.82f,
                modifier = Modifier.focusRequester(
                    requesters.getOrPut(focusId) { FocusRequester() }
                )
            )
        }
    }
    }

}

@Composable
internal fun ModernCompactMediaCard(
    item: MediaItem,
    subtitle: String,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onClear: (() -> Unit)? = null,
    onOpenSeries: (() -> Unit)? = null,
    progress: PlaybackProgress? = null,
    sourceLabel: String? = null,
    homeCardScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isModernTileTv(configuration)
    val remoteNavigationActive = context.usesRemoteNavigation(configuration) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val isTablet = !isTv && configuration.screenWidthDp >= 600
    val collectionPosterColumns = modernPosterColumns(configuration, isTv)
    val collectionPosterHorizontalPaddingDp = if (isTv) 24f else 18f
    val collectionPosterHorizontalSpacingDp = if (isTv) 20f else 12f
    val collectionPosterWidth = ((
        (
            configuration.screenWidthDp.toFloat() -
                (collectionPosterHorizontalPaddingDp * 2f) -
                (collectionPosterHorizontalSpacingDp * (collectionPosterColumns - 1).toFloat())
        ) / collectionPosterColumns.toFloat()
    ) * if (isTv) homeCardScale else 1f).coerceAtLeast(1f).dp
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
    val remoteFocusScale by animateFloatAsState(
        targetValue = if (remoteNavigationActive && focused) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "modernCompactTvFocusScale"
    )
    // Reserve the focused card's maximum footprint in the row. The animated
    // surface can grow without changing focus bounds, pushing the next Home
    // section, or expanding beyond the leading edge of the LazyRow.
    val focusEnvelopeScale = if (remoteNavigationActive) 1.06f else 1f
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
        if (sourceLabel == null) Color(0xFF30343B) else Color(0xFF4D7C91),
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
     * HOME_MEDIA_READABLE_METADATA_V53
     *
     * Keep metadata inside the poster, but do not force episode details into
     * one line. Responsive compact type gives phones, tablets and TVs enough
     * room for the series title and the episode name without hiding artwork.
     */
    Box(
        modifier = modifier.then(returningTile.modifier)
            .width(collectionPosterWidth * focusEnvelopeScale)
            .aspectRatio(2f / 3f)
            .zIndex(if (focused) 2f else visualProgress)
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
                .align(Alignment.Center)
                .width(collectionPosterWidth)
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    scaleX = remoteFocusScale
                    scaleY = remoteFocusScale
                },
            shape = shape,
            color = Color(0xFF202020),
            border = BorderStroke(
                when {
                    remoteNavigationActive && focused -> if (isTv) 3.dp else 2.dp
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

                sourceLabel?.let { label ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding((10f * if (isTv) homeCardScale else 1f).dp),
                        shape = RoundedCornerShape(7.dp),
                        color = Color(0xFF28556A),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(
                                horizontal = (8f * if (isTv) homeCardScale else 1f).dp,
                                vertical = (4f * if (isTv) homeCardScale else 1f).dp
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(
                            start = (9f * if (isTv) homeCardScale else 1f).dp,
                            end = (9f * if (isTv) homeCardScale else 1f).dp,
                            bottom = (
                                (if (watchedFraction > 0f) 11f else 8f) *
                                    if (isTv) homeCardScale else 1f
                            ).dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(
                        (2f * if (isTv) homeCardScale else 1f).dp
                    )
                ) {
                    Text(
                        item.title,
                        style = when {
                            isTv -> modernTvTileTitleStyle(shadowed = true).copy(
                                fontSize = (12f * homeCardScale).sp,
                                lineHeight = (14f * homeCardScale).sp
                            )
                            isTablet -> MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.sp,
                                lineHeight = 14.sp
                            )
                            else -> MaterialTheme.typography.labelLarge.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp
                            )
                        },
                        fontWeight =
                            if (active) FontWeight.Bold
                            else FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.76f),
                            style = when {
                                isTv -> modernTvTileSubtitleStyle(shadowed = true).copy(
                                    fontSize = (10f * homeCardScale).sp,
                                    lineHeight = (12f * homeCardScale).sp
                                )
                                isTablet -> MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                                else -> MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp
                                )
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (watchedFraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(if (isTv) (5f * homeCardScale).dp else 4.dp)
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
            openSeries = onOpenSeries,
            clear = onClear
        )
    }
}
private fun MediaItem.compactEpisodeTitle(): String = title
    .replaceFirst(Regex("^\\s*(?:S\\d+\\s*[:._-]?\\s*E(?:P(?:ISODE)?)?\\s*\\d+|(?:EPISODE|EP|E)\\s*#?\\s*\\d+)\\s*[. :|\\-–—]*\\s*", RegexOption.IGNORE_CASE), "")
    .trim()

@Composable
internal fun ModernTileActionsMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    dismiss: () -> Unit,
    toggleFavorite: () -> Unit,
    openSeries: (() -> Unit)? = null,
    viewDescription: (() -> Unit)? = null,
    clear: (() -> Unit)?,
    isPinned: Boolean = false,
    togglePin: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = dismiss,
        modifier = Modifier,
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(12.dp)
    ) {
        viewDescription?.let { descriptionAction ->
            NikDropdownMenuItem(
                text = { Text("View description") },
                leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = {
                    dismiss()
                    descriptionAction()
                }
            )
        }
        openSeries?.let { openAction ->
            NikDropdownMenuItem(
                text = { Text("Open series") },
                leadingIcon = { Icon(Icons.Default.Tv, null) },
                onClick = {
                    dismiss()
                    openAction()
                }
            )
        }
        NikDropdownMenuItem(
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
        togglePin?.let { pinAction ->
            NikDropdownMenuItem(
                text = { Text(if (isPinned) "Unpin" else "Pin to top") },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = {
                    dismiss()
                    pinAction()
                }
            )
        }
        clear?.let { clearAction ->
            NikDropdownMenuItem(
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

/*
 * TV_COLLECTION_INITIAL_FOCUS_V80
 *
 * Category grids are composed after the rail already owns focus. Give the
 * existing per-tile return-focus hook time to restore playback focus first;
 * otherwise focus the first available tile. Pagination is not keyed here, so
 * appending a page cannot steal focus from the stable Load More flow.
 */
@Composable
private fun TvCollectionInitialFocus(
    enabled: Boolean,
    focusKey: String,
    hasItems: Boolean,
    focusedIndex: () -> Int,
    moveFocus: (Int) -> Unit
) {
    var seenWithItems by rememberSaveable(focusKey) {
        mutableStateOf(false)
    }
    LaunchedEffect(enabled, focusKey, hasItems) {
        if (!enabled || !hasItems) return@LaunchedEffect
        val restoringExistingCollection = seenWithItems
        seenWithItems = true
        withFrameNanos { }
        if (restoringExistingCollection) {
            delay(420L)
        } else {
            delay(80L)
        }
        if (focusedIndex() < 0) {
            moveFocus(0)
        }
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
    enrichFocusedMetadata: suspend (MediaItem, CatalogType) -> Unit,
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val columns = modernCollectionPosterColumns(configuration, isTv)
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
    val focusedTmdbDetails: Pair<MediaItem, String>? = if (section.series) {
        state.modernTmdbSeries.getOrNull(focusedPosterIndex)?.let { entry ->
            entry.tmdb.asMediaItem() to buildList {
                entry.tmdb.firstAirYear?.let { add(it.toString()) }
                entry.tmdb.voteAverage?.takeIf { it > 0.0 }?.let {
                    add("★ ${String.format(java.util.Locale.US, "%.1f", it)}")
                }
                add("Series")
            }.joinToString(" · ")
        }
    } else {
        state.modernTmdbMovies.getOrNull(focusedPosterIndex)?.let { entry ->
            entry.tmdb.asMediaItem() to buildList {
                entry.tmdb.releaseYear?.let { add(it.toString()) }
                entry.tmdb.voteAverage?.takeIf { it > 0.0 }?.let {
                    add("★ ${String.format(java.util.Locale.US, "%.1f", it)}")
                }
                add("Movie")
            }.joinToString(" · ")
        }
    }
    LaunchedEffect(section, focusedTmdbDetails?.first?.id) {
        val media = focusedTmdbDetails?.first ?: return@LaunchedEffect
        delay(450L)
        enrichFocusedMetadata(
            media,
            if (section.series) CatalogType.SERIES else CatalogType.MOVIES
        )
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
                val targetGridIndex = targetIndex
                val targetInfo = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetGridIndex }
                val fullyVisible = targetInfo != null &&
                    targetInfo.offset.y >= gridState.layoutInfo.viewportStartOffset &&
                    targetInfo.offset.y + targetInfo.size.height <= gridState.layoutInfo.viewportEndOffset
                if (!fullyVisible) {
                    // Reveal only the clipped part of an already composed
                    // row. This avoids snapping the destination to the top.
                    if (targetInfo != null) {
                        val viewportStart = gridState.layoutInfo.viewportStartOffset
                        val viewportEnd = gridState.layoutInfo.viewportEndOffset
                        val itemStart = targetInfo.offset.y
                        val itemEnd = itemStart + targetInfo.size.height
                        val delta = when {
                            itemStart < viewportStart -> itemStart - viewportStart
                            itemEnd > viewportEnd -> itemEnd - viewportEnd
                            else -> 0
                        }
                        if (delta != 0) gridState.scrollBy(delta.toFloat())
                    } else {
                        gridState.scrollToItem(targetGridIndex)
                    }
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
                delay(40L)
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

    TvCollectionInitialFocus(
        enabled = isTv,
        focusKey = "tmdb:${section.name}",
        hasItems = focusIds.isNotEmpty(),
        focusedIndex = { focusedPosterIndex },
        moveFocus = moveFocusToIndex
    )
    Column(Modifier.fillMaxSize()) {
        ModernCollectionHeader(
            title = section.title,
            subtitle =
                "TMDB · " +
                    if (section.series) {
                        "Series · $count loaded"
                    } else {
                        "Movies · $count loaded"
                    },
            close = close,
            modifier = Modifier.padding(
                start = if (isTv) 24.dp else 18.dp,
                end = if (isTv) 24.dp else 18.dp,
                top = 16.dp
            )
        )

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
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
                        toggleFavorite(FavoriteItem(FavoriteKind.SERIES, media, source = FavoriteSource.TMDB))
                    },
                    onRequestDescriptionMetadata = {
                        focusScope.launch {
                            enrichFocusedMetadata(media, CatalogType.SERIES)
                        }
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
                        toggleFavorite(FavoriteItem(FavoriteKind.MOVIE, media, source = FavoriteSource.TMDB))
                    },
                    onRequestDescriptionMetadata = {
                        focusScope.launch {
                            enrichFocusedMetadata(media, CatalogType.MOVIES)
                        }
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
    enrichFocusedMetadata: suspend (MediaItem, CatalogType) -> Unit,
    isTv: Boolean
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isLiveTv = category.type == CatalogType.LIVE_TV
    val profileKey = state.savedProfile?.cacheKey().orEmpty()
    var pinnedChannelIds by remember(profileKey, category.id) {
        mutableStateOf(IptvPinPreferences.pinnedChannelOrder(context, profileKey, category.id))
    }
    val pinnedItems = remember(state.items, pinnedChannelIds) {
        pinnedChannelIds.mapNotNull { id -> state.items.firstOrNull { it.id == id } }
    }
    val displayedItems = remember(state.items, pinnedItems) {
        pinnedItems + state.items
    }
    val isPhone = !isTv && configuration.smallestScreenWidthDp < 600
    val columns = when {
        isLiveTv && isPhone -> 1
        isLiveTv && isTv -> 2
        isLiveTv && configuration.screenWidthDp >= 800 -> 3
        isLiveTv -> 2
        else -> modernCollectionPosterColumns(configuration, isTv)
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
    val focusIds = displayedItems.mapIndexed { index, media -> "$index:${media.id}" }
    var focusedPosterIndex by remember(category.id) {
        mutableIntStateOf(-1)
    }
    val focusedMedia = displayedItems.getOrNull(focusedPosterIndex)
    LaunchedEffect(category.id, category.type, focusedMedia?.id) {
        val media = focusedMedia ?: return@LaunchedEffect
        if (category.type in setOf(CatalogType.MOVIES, CatalogType.SERIES)) {
            // D-pad users often cross several cards quickly. Only enrich the
            // title they settle on, and cancellation follows focus changes.
            delay(450L)
            enrichFocusedMetadata(media, category.type)
        }
    }
    val viewportKey = "$profileKey:${category.type.name}:${category.id}"
    val restoredViewport = remember(viewportKey) {
        ModernCollectionViewportMemory.get(viewportKey)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = restoredViewport?.index
            ?.coerceAtMost(displayedItems.size)
            ?: 0,
        initialFirstVisibleItemScrollOffset = restoredViewport?.offset ?: 0
    )
    LaunchedEffect(viewportKey, gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            ModernCollectionViewportMemory.put(viewportKey, index, offset)
        }
    }
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
                val targetGridIndex = targetIndex
                val targetInfo = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetGridIndex }
                val fullyVisible = targetInfo != null &&
                    targetInfo.offset.y >= gridState.layoutInfo.viewportStartOffset &&
                    targetInfo.offset.y + targetInfo.size.height <= gridState.layoutInfo.viewportEndOffset
                if (!fullyVisible) {
                    if (targetInfo != null) {
                        val viewportStart = gridState.layoutInfo.viewportStartOffset
                        val viewportEnd = gridState.layoutInfo.viewportEndOffset
                        val itemStart = targetInfo.offset.y
                        val itemEnd = itemStart + targetInfo.size.height
                        val delta = when {
                            itemStart < viewportStart -> itemStart - viewportStart
                            itemEnd > viewportEnd -> itemEnd - viewportEnd
                            else -> 0
                        }
                        if (delta != 0) gridState.scrollBy(delta.toFloat())
                    } else {
                        gridState.scrollToItem(targetGridIndex)
                    }
                    withTimeoutOrNull(1_000L) {
                        snapshotFlow {
                            gridState.layoutInfo.visibleItemsInfo.any {
                                it.index == targetGridIndex
                            }
                        }.first { it }
                    }
                }
                withFrameNanos { }
                delay(40L)
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

    TvCollectionInitialFocus(
        enabled = isTv,
        focusKey = "${category.type.name}:${category.id}",
        hasItems = focusIds.isNotEmpty(),
        focusedIndex = { focusedPosterIndex },
        moveFocus = moveFocusToIndex
    )

    val appendPage = rememberCollectionPagination(
        displayedItems.map { it.id }, state.catalogLoadingMore, gridState,
        itemFocusRequesters, loadMore
    )

    Column(Modifier.fillMaxSize()) {
        ModernCollectionHeader(
            title = category.title,
            subtitle = "IPTV · ${category.type.title} · ${state.items.size} loaded",
            close = close,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = refresh,
                        enabled = !state.loading && !state.catalogLoadingMore && !state.categoryRefreshing,
                        label = { Text(if (state.categoryRefreshing) "Refreshing…" else "Refresh") },
                        leadingIcon = { Icon(Icons.Default.RestartAlt, null, Modifier.size(17.dp)) }
                    )
                }
            },
            modifier = Modifier.padding(
                start = if (isTv) 24.dp else 18.dp,
                end = if (isTv) 24.dp else 18.dp,
                top = 16.dp
            )
        )

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
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
        gridItemsIndexed(
            items = displayedItems,
            key = { index, media ->
                "modern-iptv-${category.type.name}-$index-${media.id}"
            }
        ) { index, media ->
            val tileModifier = Modifier
                .onFocusChanged {
                    if (it.hasFocus) {
                        focusedPosterIndex = index
                    } else if (focusedPosterIndex == index) {
                        focusedPosterIndex = -1
                    }
                }
                .focusRequester(
                    itemFocusRequesters.getOrPut(focusIds[index]) {
                        FocusRequester()
                    }
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
                    isFavorite = favorite,
                    onFavorite = favoriteAction,
                    isPinned = media.id in pinnedChannelIds,
                    onTogglePin = {
                        pinnedChannelIds = IptvPinPreferences.toggleChannel(
                            context, profileKey, category.id, media.id
                        )
                    },
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
                onRequestDescriptionMetadata = {
                    focusScope.launch {
                        enrichFocusedMetadata(media, category.type)
                    }
                },
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
}

internal fun modernPosterColumns(
    configuration: Configuration,
    isTv: Boolean
): Int = when {
    // TV's 960dp viewport used to force six narrow posters while retaining tablet-size text.
    // Use the same width breakpoints as tablets to preserve the artwork-to-caption proportions.
    configuration.screenWidthDp >= 1400 -> 6
    configuration.screenWidthDp >= 1100 -> 5
    configuration.screenWidthDp >= 760 -> 4
    configuration.screenWidthDp >= 430 -> 3
    else -> 2
}

private fun modernCollectionPosterColumns(
    configuration: Configuration,
    isTv: Boolean
): Int = when {
    !isTv -> modernPosterColumns(configuration, false)
    configuration.screenWidthDp >= 1400 -> 7
    configuration.screenWidthDp >= 1100 -> 6
    configuration.screenWidthDp >= 760 -> 5
    else -> 4
}

@Composable
private fun ModernCollectionHeader(
    title: String,
    subtitle: String,
    close: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
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
private fun FullDescriptionDialog(
    title: String,
    description: String,
    cast: List<String> = emptyList(),
    dismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(title) {
        withFrameNanos { }
        runCatching { closeFocusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (cast.isNotEmpty()) {
                    Text(
                        "Cast · ${cast.take(6).joinToString(", ")}",
                        color = Color(0xFFADB3BF),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState)
                    .focusRequester(scrollFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = 150
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (scrollState.value >= scrollState.maxValue) false
                                else {
                                    scope.launch {
                                        scrollState.animateScrollTo(
                                            (scrollState.value + step).coerceAtMost(scrollState.maxValue)
                                        )
                                    }
                                    true
                                }
                            }
                            Key.DirectionUp -> {
                                if (scrollState.value <= 0) false
                                else {
                                    scope.launch {
                                        scrollState.animateScrollTo(
                                            (scrollState.value - step).coerceAtLeast(0)
                                        )
                                    }
                                    true
                                }
                            }
                            else -> false
                        }
                    }
            ) {
                Text(
                    description,
                    color = Color(0xFFD5D7DC),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = dismiss,
                modifier = Modifier
                    .focusRequester(closeFocusRequester)
                    .remoteFocusFrame(RoundedCornerShape(8.dp))
            ) {
                Text("Close")
            }
        },
        containerColor = Color(0xFF17191F),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFD5D7DC)
    )
}

@Composable
private fun ModernLiveChannelTile(
    item: MediaItem,
    categoryTitle: String,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    isPinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
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
        categoryTitle,
        isTv
    ) {
        buildList {
            item.channelNumber?.let { add("CH $it") }
            if (!isTv) add(categoryTitle)
            item.streamType?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            if (item.catchupAvailable == true) add("Catch-up")
            if (!item.epgChannelId.isNullOrBlank()) add("EPG")
        }.distinct().joinToString("  •  ")
    }

    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = modifier.then(returningTile.modifier)
                .fillMaxWidth()
                .heightIn(min = if (isPhone) 102.dp else if (isTv) 106.dp else 118.dp)
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
                    .background(Brush.linearGradient(listOf(palette.first, palette.second)))
                    .padding(if (isPhone) 10.dp else if (isTv) 11.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 11.dp else 14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(if (isPhone) 58.dp else if (isTv) 60.dp else 72.dp),
                    shape = RoundedCornerShape(if (isPhone) 11.dp else 13.dp),
                    color = Color.Black.copy(alpha = .30f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.LiveTv,
                            null,
                            Modifier.size(if (isPhone) 27.dp else 32.dp),
                            tint = Color.White
                        )
                    }
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = when {
                            isTv -> modernTvTileTitleStyle()
                            isPhone -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
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
                            style = if (isTv) modernTvTileSubtitleStyle() else MaterialTheme.typography.bodySmall,
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
                                style = if (isTv) modernTvTileSubtitleStyle() else MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    scheduleText?.let {
                        Text(
                            it,
                            color = Color.White.copy(alpha = .68f),
                            style = if (isTv) modernTvTileSubtitleStyle() else MaterialTheme.typography.labelSmall
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
                            style = if (isTv) modernTvTileSubtitleStyle() else MaterialTheme.typography.labelSmall,
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
                        style = if (isTv) modernTvTileSubtitleStyle() else MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!isTv && onTogglePin != null) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(40.dp).remoteFocusFrame(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            if (isPinned) "Unpin ${item.title}" else "Pin ${item.title}",
                            tint = if (isPinned) ModernBrandAccent else Color.White.copy(alpha = .68f)
                        )
                    }
                }
                if (isPinned && isTv) {
                    Icon(
                        Icons.Default.PushPin,
                        "Pinned channel",
                        modifier = Modifier.size(18.dp),
                        tint = ModernBrandAccent
                    )
                }
            }
        }
        ModernTileActionsMenu(
            expanded = menuOpen,
            isFavorite = isFavorite,
            dismiss = { menuOpen = false },
            toggleFavorite = onFavorite,
            clear = onClear,
            isPinned = isPinned,
            togglePin = onTogglePin
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
    onRequestDescriptionMetadata: (() -> Unit)? = null,
    compactLandscape: Boolean = false,
    isTv: Boolean
) {
    val returningTile = rememberReturningTile(onClick)

    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var descriptionDialogOpen by remember(item.id) { mutableStateOf(false) }
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
    val remoteNavigationActive = context.usesRemoteNavigation(configuration) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "modernPosterProfileFocus"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (!remoteNavigationActive && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "modernPosterTouchPress"
    )
    val visualProgress =
        if (remoteNavigationActive) focusProgress else pressProgress
    val remoteFocusScale by animateFloatAsState(
        targetValue = if (remoteNavigationActive && focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 170),
        label = "modernCollectionRemoteFocusScale"
    )
    val focusEnvelopeScale = if (remoteNavigationActive) 1.06f else 1f
    val active = if (remoteNavigationActive) focused else pressed
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
            remoteNavigationActive -> Color(0xFFF2F3F5)
            focused -> Color(0xFFBFC3CA)
            else -> Color(0xFF555A63)
        },
        visualProgress
    )

    Column(
        modifier.then(returningTile.modifier)
            .fillMaxWidth()
            .zIndex(if (focused) 2f else visualProgress),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        /*
         * SINGLE_ACTIVATION_SURFACE_V26
         *
         * Surface(onClick) already owns click and focus semantics. Adding a
         * second explicit focus node here can consume the first remote
         * activation before the click callback is dispatched.
         */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (compactLandscape) 4f / 3f else 2f / 3f),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(1f / focusEnvelopeScale)
                .graphicsLayer {
                    scaleX = remoteFocusScale
                    scaleY = remoteFocusScale
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
                        {
                            onRequestDescriptionMetadata?.invoke()
                            menuOpen = true
                        }
                    } else {
                        null
                    }
                ),
            shape = shape,
            color = Color(0xFF202020),
            border = BorderStroke(
                when {
                    remoteNavigationActive && focused -> if (isTv) 3.dp else 2.dp
                    else -> 1.dp
                },
                borderColor
            )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
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

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.58f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.94f)
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 9.dp, end = 9.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = when {
                            isTv -> modernTvTileTitleStyle(shadowed = true)
                            isTablet -> MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.sp,
                                lineHeight = 14.sp
                            )
                            else -> MaterialTheme.typography.labelLarge.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp
                            )
                        },
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.76f),
                            style = when {
                                isTv -> modernTvTileSubtitleStyle(shadowed = true)
                                else -> MaterialTheme.typography.labelSmall.copy(
                                    fontSize = if (isTablet) 10.sp else 9.sp,
                                    lineHeight = if (isTablet) 12.sp else 11.sp
                                )
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

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

                if (remoteNavigationActive && focused) {
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
        }

        onFavorite?.let { favoriteAction ->
            ModernTileActionsMenu(
                expanded = menuOpen,
                isFavorite = isFavorite,
                dismiss = { menuOpen = false },
                toggleFavorite = favoriteAction,
                viewDescription = item.description?.takeIf(String::isNotBlank)?.let {
                    { descriptionDialogOpen = true }
                },
                clear = null
            )
        }
        item.description?.takeIf(String::isNotBlank)?.let { description ->
            if (descriptionDialogOpen) {
                FullDescriptionDialog(
                    title = item.title,
                    description = description,
                    cast = item.cast,
                    dismiss = { descriptionDialogOpen = false }
                )
            }
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
    val context = LocalContext.current
    val remoteNavigationActive =
        context.usesRemoteNavigation(LocalConfiguration.current)
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(
        targetValue = if (remoteNavigationActive && focused) 1.09f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "loadMoreFocusScale"
    )
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        NikTvPrimaryActionButton(
            onClick = onClick,
            modifier = Modifier
                .zIndex(if (focused) 1f else 0f)
                .graphicsLayer {
                    scaleX = focusScale
                    scaleY = focusScale
                }
                .onFocusChanged { focused = it.isFocused },
            // Retain focus while pagination is running. Disabling this
            // button removes it from the TV focus graph and lets focus jump
            // to the navigation rail before the new tiles are composed.
            enabled = true,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (remoteNavigationActive && focused) {
                    Color(0xFFFF2532)
                } else {
                    Color(0xFFE50914)
                },
                contentColor = Color.White
            ),
            shape = shape,
            border = if (remoteNavigationActive && focused) {
                BorderStroke(3.dp, Color.White)
            } else {
                null
            },
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 13.dp)
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
