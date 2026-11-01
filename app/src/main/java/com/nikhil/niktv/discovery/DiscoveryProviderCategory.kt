package com.nikhil.niktv.discovery

import com.nikhil.niktv.discovery.components.*

import com.nikhil.niktv.data.providerDisplayTitle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.ParentalControls
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** Provider pagination/cache stays in the existing catalog; presentation belongs to the trial. */
@Composable
internal fun DiscoveryProviderCategory(app: NikTvState, category: Category,
    close: () -> Unit, selectSurface: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    open: (MediaItem, List<MediaItem>, PlaybackQueueFilter) -> Unit,
    favorite: (FavoriteItem) -> Unit, more: () -> Unit, refresh: () -> Unit,
    download: (MediaItem, CatalogType, MediaItem?) -> Unit) {
    val context = LocalContext.current
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val configuration = LocalConfiguration.current
    val surface = if (category.type == CatalogType.SERIES) DashboardSurface.SERIES else DashboardSurface.MOVIES
    var query by rememberSaveable(profile, category.id) { mutableStateOf("") }
    var selected by rememberSaveable(profile, category.id, query) { mutableIntStateOf(0) }
    var entered by rememberSaveable(profile, category.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val items = remember(app.items, query, profile) {
        app.items.filter { (!it.isAdult || ParentalControls.unlocked(context)) && (query.isBlank() || it.title.matchesTitleKeywords(query.trim())) }
    }
    val selection = items.getOrNull(selected) ?: items.firstOrNull()
    val grid = rememberLazyGridState()
    val tab = remember { FocusRequester() }
    val heroPlay = remember { FocusRequester() }
    val heroInfo = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }
    val currentMore by rememberUpdatedState(more)
    val hasMore by rememberUpdatedState(app.catalogHasMore && !app.catalogLoadingMore && !app.loading && query.isBlank())
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (hasMore && last >= currentItems.size - 6) currentMore()
        }
    }
    val navigation = LocalProviderDetailNavigation.current
    fun choose(index: Int, direct: Boolean = false) {
        val item = items.getOrNull(index) ?: return
        val filter = providerCategoryQueueFilter(category, items, index, query)
        if (direct && navigation != null) navigation.playProvider(item, items, filter) else open(item, items, filter)
    }
    StableTileFocusViewport {
        Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).safeDrawingPadding()) {
            val phone = configuration.smallestScreenWidthDp < 600 && !context.isTvLikeDevice(configuration)
            if (phone) DiscoveryPhoneNavigation(surface, tab, heroPlay, close, selectSurface, navigate, menu, { menu = it })
            else DiscoveryTopNavigation(surface, app.savedProfile?.name.orEmpty(), tab, heroPlay,
                back = close, select = selectSurface, navigate = navigate, headerFocus = {},
                menu = menu, changeMenu = { menu = it }, customize = null)
            LazyVerticalGrid(GridCells.Adaptive(if (configuration.smallestScreenWidthDp >= 600) 112.dp else 92.dp),
                Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("hero", span = { GridItemSpan(maxLineSpan) }) {
                    val card = selection?.let { providerDetailFallback(it, category.type) }
                    Box {
                        Box(Modifier.matchParentSize()) { DiscoveryBackdrop(card, false) }
                        DiscoveryHero(card, null, 250.dp, landscape = configuration.smallestScreenWidthDp >= 600,
                            compact = configuration.screenHeightDp < 480, detail = false, loading = false,
                            inList = selection != null && app.favorites.any { it.profileKey == profile && it.media.id == selection.id },
                            playRequester = heroPlay, infoRequester = heroInfo,
                            play = { choose(selected, direct = true) }, add = { selection?.let {
                                favorite(FavoriteItem(if (category.type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE, it, profileKey = profile))
                            } }, info = { choose(selected) }, onFocus = {},
                            onDown = { runCatching { requesters[selected]?.requestFocus() } },
                            previous = {}, next = {}, carousel = false, index = 0, count = 0)
                    }
                }
                item("heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(category.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DiscoveryCollectionSearch(query, { query = it }, compact = phone)
                            DiscoveryAction("Refresh", Icons.Default.Refresh, iconOnly = true, onClick = refresh)
                        }
                        if (app.loading || app.catalogLoadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (app.error != null) DiscoveryAction("Retry", onClick = refresh)
                        if (!app.loading && items.isEmpty()) Text("No titles found", color = Color.LightGray)
                    }
                }
                itemsIndexed(items, key = { index, item -> "${item.id}:$index" }) { index, item ->
                    val requester = remember(index) { requesters.getOrPut(index) { FocusRequester() } }
                    DisposableEffect(index, requester) { onDispose { if (requesters[index] === requester) requesters.remove(index) } }
                    var popup by remember { mutableStateOf(false) }
                    var description by remember { mutableStateOf(false) }
                    val saved = FavoriteItem(if (category.type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE, item, profileKey = profile)
                    BoxWithConstraints {
                        val recent = app.recentlyPlayed.firstOrNull { it.profileKey == profile && it.media.id == item.id }
                        val progress = recent?.let { discoveryRecentProgress(it, app.playbackProgress) }
                        DiscoveryPosterTile(providerDisplayTitle(item.title, category.type), item.logo, "", maxWidth,
                            source = DiscoveryTileSource.IPTV,
                            progress = progress?.takeIf { it.durationMillis > 0 }?.let { (it.positionMillis.toFloat() / it.durationMillis).coerceIn(0f, 1f) },
                            modifier = Modifier.focusRequester(requester), onClick = { choose(index) },
                            onLongClick = { popup = true },
                            onFocus = { selected = index })
                        ModernTileActionsMenu(popup, app.favorites.any { it.key == saved.key }, dismiss = { popup = false },
                            toggleFavorite = { favorite(saved) }, clear = null, trailerItem = item, trailerSeries = category.type == CatalogType.SERIES,
                            recommendationItem = item, recommendationType = category.type,
                            playAction = { choose(index, direct = true) },
                            downloadAction = if (category.type == CatalogType.MOVIES) ({ download(item, category.type, null) }) else null,
                            viewDescription = item.description?.takeIf(String::isNotBlank)?.let { { description = true } },
                            onTrailerReturned = { runCatching { requester.requestFocus() }.getOrDefault(false) })
                    }
                    if (description) FullDescriptionDialog(item.title, item.description.orEmpty()) { description = false; runCatching { requester.requestFocus() } }
                }
                if (app.catalogHasMore && query.isNotBlank()) item("load-more", span = { GridItemSpan(maxLineSpan) }) {
                    DiscoveryAction(if (app.catalogLoadingMore) "Loading…" else "Load more titles", onClick = more)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(60) {
            withFrameNanos { }
            val requester = if (entered) requesters[selected] else tab
            if (runCatching { requester?.requestFocus() == true }.getOrDefault(false)) { entered = true; return@LaunchedEffect }
        }
    }
}
