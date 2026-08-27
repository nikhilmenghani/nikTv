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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.model.TmdbHomeSection
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
    openTmdbSection: (TmdbHomeSection) -> Unit,
    openIptvCategory: (Category) -> Unit,
    closeSection: () -> Unit,
    openTmdbMovie: (TrendingMovie) -> Unit,
    openTmdbSeries: (TrendingSeries) -> Unit,
    openIptvItem: (MediaItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
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
                        openTmdbSection = openTmdbSection,
                        openIptvCategory = openIptvCategory,
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
    openTmdbSection: (TmdbHomeSection) -> Unit,
    openIptvCategory: (Category) -> Unit,
    configureTmdb: () -> Unit,
    configureIptv: (CatalogType) -> Unit,
    resetSurface: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val destinationColumns = when {
        isTv || configuration.screenWidthDp >= 1200 -> 4
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
            start = 18.dp,
            end = 18.dp,
            top = 20.dp,
            bottom = 72.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
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

                        DashboardSurface.LIVE_TV -> Unit
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
            val recents = state.recentlyPlayed
                .filter {
                    it.kind == FavoriteKind.MOVIE ||
                        it.kind == FavoriteKind.SERIES
                }
                .take(12)

            if (recents.isNotEmpty()) {
                item("continue-header", span = fullSpan) {
                    ModernHubSectionHeading(
                        "Continue Watching",
                        "Jump back in without browsing a destination."
                    )
                }
                item("continue-row", span = fullSpan) {
                    ModernContinueRow(recents, openRecent)
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
                            "Add TMDB sections above. For IPTV tiles on Home, explicitly choose Movie or Series categories.",
                            color = Color(0xFFB9B9B9)
                        )
                    }
                }
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
    val scale by animateFloatAsState(
        targetValue =
            if (isTv) 1f
            else if (focused) 1.035f
            else 1f,
        animationSpec = tween(130),
        label = "modernDestinationScale"
    )
    val palette = remember(seed) {
        destinationPalette(seed)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
            },
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) {
                Color(0xFFFF3340)
            } else {
                Color(0xFF353535)
            }
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.first,
                            palette.second
                        )
                    )
                )
                .padding(15.dp)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.30f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            null,
                            Modifier.size(23.dp),
                            tint = Color.White
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
    open: (RecentItem) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(
            items = recents,
            key = { "modern-recent-${it.key}" }
        ) { recent ->
            ModernCompactMediaCard(
                item = recent.media,
                subtitle =
                    if (recent.kind == FavoriteKind.SERIES) {
                        recent.lastPlayed?.title ?: "Series"
                    } else {
                        "Movie"
                    },
                onClick = { open(recent) }
            )
        }
    }
}

@Composable
private fun ModernCompactMediaCard(
    item: MediaItem,
    subtitle: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(148.dp)
            .onFocusChanged {
                focused = it.isFocused
            },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF151515),
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) {
                Color(0xFFFF3340)
            } else {
                Color(0xFF303030)
            }
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
                Modifier.padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
            if (isTv) {
                delay(120L)
                runCatching {
                    returnFocusRequester.requestFocus()
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
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = 54.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                            if (it.isFocused) focusedPosterIndex = index
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
                            if (it.isFocused) focusedPosterIndex = index
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
    toggleFavorite: (MediaItem) -> Unit,
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
    val kind =
        if (category.type == CatalogType.MOVIES) {
            FavoriteKind.MOVIE
        } else {
            FavoriteKind.SERIES
        }
    val focusIds = state.items.map { it.id }
    var focusedPosterIndex by remember(category.id) {
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
            if (isTv) {
                delay(120L)
                runCatching {
                    returnFocusRequester.requestFocus()
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
                itemCount = focusIds.size,
                moveFocus = moveFocusToIndex
            ),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = 54.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                        if (it.isFocused) focusedPosterIndex = index
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
                    toggleFavorite(media)
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
                    onClick = loadMore
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
    isTv: Boolean
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scale by animateFloatAsState(
        targetValue =
            if (isTv) 1f
            else if (focused) 1.035f
            else 1f,
        animationSpec = tween(120),
        label = "modernPosterScale"
    )

    Column(
        Modifier
            .fillMaxWidth()
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
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                },
            shape = RoundedCornerShape(11.dp),
            color = Color(0xFF202020),
            border = BorderStroke(
                if (focused) 3.dp else 1.dp,
                if (focused) {
                    Color(0xFFFF3340)
                } else {
                    Color(0xFF303030)
                }
            )
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Color(0xFF222222))
            ) {
                ModernPosterImage(
                    item = item,
                    context = context,
                    modifier = Modifier.fillMaxSize()
                )
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
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onFavorite != null) {
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (isFavorite) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Default.FavoriteBorder
                        },
                        if (isFavorite) {
                            "Remove from My List"
                        } else {
                            "Add to My List"
                        },
                        tint =
                            if (isFavorite) Color(0xFFE50914)
                            else Color.Gray,
                        modifier = Modifier.size(19.dp)
                    )
                }
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            enabled = !loading,
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
