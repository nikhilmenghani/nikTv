package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.adultContentLabel
import com.nikhil.niktv.discovery.components.modernVisibleIptvCategories
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.ui.LocalRemoteFocusMargin
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.SeriesSeasonDropdown
import com.nikhil.niktv.ui.XtreamContentGate
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.ui.rememberIptvPins
import com.nikhil.niktv.ui.remoteCombinedClickable
import com.nikhil.niktv.ui.remoteFocusFrame
import com.nikhil.niktv.ui.usesRemoteNavigation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun CatalogType.easyDestination() = when (this) {
    CatalogType.LIVE_TV, CatalogType.RADIO -> EasyHomeAction.LIVE
    CatalogType.MOVIES -> EasyHomeAction.MOVIES
    CatalogType.SERIES -> EasyHomeAction.SERIES
}

internal fun FavoriteKind.easyDestination() = when (this) {
    FavoriteKind.CHANNEL -> EasyHomeAction.LIVE
    FavoriteKind.MOVIE -> EasyHomeAction.MOVIES
    FavoriteKind.SERIES, FavoriteKind.EPISODE -> EasyHomeAction.SERIES
}

internal fun MediaItem.isEasyIptvTitle() = !isAdult && !id.startsWith("tmdb-", ignoreCase = true)

@Composable
internal fun EasyPage(
    title: String,
    home: () -> Unit,
    back: (() -> Unit)? = null,
    homeRequester: FocusRequester,
    modifier: Modifier = Modifier,
    keyboardInsets: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val compact = LocalConfiguration.current.screenHeightDp < 440
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().then(if (keyboardInsets) Modifier.imePadding() else Modifier),
            contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalRemoteFocusMargin provides 4.dp) {
                Column(
                    Modifier.widthIn(max = 960.dp).fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp)
                        .then(modifier),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (back != null) NikTvSecondaryActionButton(back, Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Back")
                        }
                        Text(title, Modifier.weight(1f),
                            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        NikTvSecondaryActionButton(home, Modifier.heightIn(min = 48.dp).focusRequester(homeRequester)) {
                            Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Home")
                        }
                    }
                    content()
                }
            }
        }
    }
}

internal data class EasyBrowseEntry(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val opensList: Boolean = false
)

@Composable
internal fun EasyBrowseTile(
    entry: EasyBrowseEntry,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    onHold: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val compact = LocalConfiguration.current.screenHeightDp < 440
    Surface(
        modifier.fillMaxWidth().remoteFocusFrame(shape).clip(shape)
            .remoteCombinedClickable(open, onHold, showIndication = false),
        shape = shape, color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.heightIn(min = if (compact) 48.dp else 58.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(entry.icon, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (entry.opensList) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun EasyFavoriteTile(entry: EasyBrowseEntry, open: () -> Unit, isSaved: Boolean,
    toggleSaved: () -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuItem = remember { FocusRequester() }
    Box {
        EasyBrowseTile(entry, open, modifier, onHold = { menuOpen = true })
        DropdownMenu(menuOpen, { menuOpen = false }, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.initialFocusEntry(menuItem)) {
            NikDropdownMenuItem(
                text = { Text(if (isSaved) "Remove from My List" else "Add to My List") },
                onClick = { menuOpen = false; toggleSaved() },
                modifier = Modifier.focusRequester(menuItem),
                leadingIcon = { Icon(if (isSaved) Icons.Default.HeartBroken else Icons.Default.FavoriteBorder, null) }
            )
        }
    }
}

@Composable
internal fun EasyBrowsePage(
    title: String,
    entries: List<EasyBrowseEntry>,
    emptyMessage: String,
    home: () -> Unit,
    back: (() -> Unit)? = null,
    loading: Boolean = false,
    hasMore: Boolean = false,
    loadMore: () -> Unit = {},
    open: (String) -> Unit,
    toggleSaved: ((String) -> Unit)? = null,
    savedKeys: Set<String> = emptySet(),
    removingFromList: Boolean = false,
    tools: (@Composable RowScope.() -> Unit)? = null
) {
    val homeRequester = remember { FocusRequester() }
    val listEntry = remember { FocusRequester() }
    val list = rememberLazyListState()
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val entryKey = remember {
        val index = entries.indexOfFirst { it.key == lastOpened }
        // A saved viewport can start partway through a long list.
        entries.getOrNull(if (index >= list.firstVisibleItemIndex) index else list.firstVisibleItemIndex)?.key
            ?: entries.firstOrNull()?.key
    }
    val remote = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val entryRequester = if (entryKey != null) listEntry else homeRequester
    var usedPaging by rememberSaveable { mutableStateOf(false) }
    var footerFocused by remember { mutableStateOf(false) }
    var pageStart by remember { mutableStateOf<Int?>(null) }
    val pageItem = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entries.size, loading) {
        val start = pageStart ?: return@LaunchedEffect
        if (loading || entries.size <= start) return@LaunchedEffect
        if (footerFocused) {
            list.scrollToItem(start)
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == start } }.first { it }
            pageItem.requestFocus()
        }
        pageStart = null
    }
    EasyPage(title, home, back, homeRequester, Modifier.initialFocusEntry(entryRequester, remote)) {
        tools?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = it) }
        if (entries.isEmpty() && !hasMore) {
            Text(emptyMessage, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
            contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = EasyBrowseEntry::key) { entry ->
                val openEntry = {
                    lastOpened = entry.key
                    open(entry.key)
                }
                val modifier = Modifier
                    .then(if (entry.key == entryKey) Modifier.focusRequester(listEntry) else Modifier)
                    .then(if (entries.getOrNull(pageStart ?: -1)?.key == entry.key) Modifier.focusRequester(pageItem) else Modifier)
                if (toggleSaved == null) EasyBrowseTile(entry, openEntry, modifier)
                else EasyFavoriteTile(entry, openEntry, entry.key in savedKeys, {
                    if (removingFromList && remote) homeRequester.requestFocus()
                    toggleSaved(entry.key)
                }, modifier)
            }
            if (hasMore || usedPaging) item(key = "easy-pagination") {
                NikTvSecondaryActionButton({
                    if (hasMore) {
                        if (!loading) {
                            usedPaging = true
                            pageStart = entries.size
                            loadMore()
                        }
                    } else {
                        homeRequester.requestFocus()
                        scope.launch { list.scrollToItem(0) }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { footerFocused = it.isFocused }) {
                    Text(if (!hasMore) "Back to top" else if (loading) "Loading…" else "Show more")
                }
            }
        }
    }
}

@Composable
internal fun EasyCategoriesPage(state: NikTvState, type: CatalogType, home: () -> Unit, refresh: () -> Unit,
    select: (Category) -> Unit) {
    val portalKey = state.session?.profile?.cacheKey().orEmpty()
    val pinned = rememberIptvPins(portalKey).pinnedCategories(type)
    val gate = XtreamContentGate(state)
    val categories = state.modernVisibleIptvCategories(type, requireExplicitSelection = false)
        .filter { !it.isAdult && !adultContentLabel(it.title) && gate.allowsCategory(it) }
        .distinctBy(Category::id).sortedBy { it.id !in pinned }
    val loading = (portalKey to type) in state.categoryRefreshes
    val destination = type.easyDestination()
    EasyBrowsePage(destination.label, categories.map { EasyBrowseEntry(it.id, it.title, destination.icon, opensList = true) },
        if (loading) "Loading categories…" else "No categories available.", home = home,
        open = { id -> categories.firstOrNull { it.id == id }?.let(select) }) {
        NikTvSecondaryActionButton({ if (!loading) refresh() }) {
            Text(if (loading) "Refreshing…" else "Refresh categories")
        }
    }
}

internal fun MediaItem.easyEpisodeLabel(): String? = episodeNumber?.let { episode ->
    seasonNumber?.let { "Season $it, episode $episode" } ?: "Episode $episode"
}

@Composable
internal fun EasyTitlesPage(state: NikTvState, category: Category, home: () -> Unit, back: () -> Unit,
    play: (MediaItem) -> Unit, refresh: () -> Unit, loadMore: () -> Unit, toggleFavorite: (MediaItem) -> Unit) {
    val destination = category.type.easyDestination()
    val opensList = category.type == CatalogType.SERIES
    val portalKey = state.session?.profile?.cacheKey().orEmpty()
    val favoriteKind = when (category.type) {
        CatalogType.LIVE_TV, CatalogType.RADIO -> FavoriteKind.CHANNEL
        CatalogType.MOVIES -> FavoriteKind.MOVIE
        CatalogType.SERIES -> FavoriteKind.SERIES
    }
    val pins = rememberIptvPins(portalKey).pinnedChannelOrder(category.id)
    val items = remember(state.items, state.xtreamContentPolicy, state.contentPolicyCategories, state.session, category, pins) {
        val gate = XtreamContentGate(state)
        val order = pins.withIndex().associate { (index, id) -> id to index }
        state.items.filter { it.isEasyIptvTitle() && gate.allows(it, category.type, category.title) }
            .distinctBy(MediaItem::id).sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }
    EasyBrowsePage(category.title, items.map { media ->
        EasyBrowseEntry(media.id, media.title, if (opensList) destination.icon else Icons.Default.PlayArrow,
            opensList = opensList)
    }, if (state.loading) "Loading ${destination.label.lowercase()}…" else "No titles available in this category.",
        home = home, back = back, loading = state.loading || state.catalogLoadingMore,
        hasMore = state.catalogHasMore, loadMore = loadMore,
        open = { id -> items.firstOrNull { it.id == id }?.let(play) },
        toggleSaved = { id -> items.firstOrNull { it.id == id }?.let(toggleFavorite) },
        savedKeys = state.favorites.filter { it.kind == favoriteKind }.mapTo(mutableSetOf()) { it.media.id }) {
        NikTvSecondaryActionButton({ if (!state.loading) refresh() }) {
            Text(if (state.loading) "Loading…" else "Refresh")
        }
    }
}

@Composable
internal fun EasySeriesPage(state: NikTvState, series: MediaItem, home: () -> Unit, back: () -> Unit,
    selectSeason: (Int) -> Unit, play: (MediaItem) -> Unit, refresh: () -> Unit, loadMore: () -> Unit) {
    val seasonRequester = remember { FocusRequester() }
    val currentSeason = state.selectedSeriesSeason
    val seasons = state.availableSeriesSeasons
    val episodes = state.items.filter(MediaItem::isEasyIptvTitle).distinctBy(MediaItem::id)
    EasyBrowsePage(series.title, episodes.map { episode ->
        EasyBrowseEntry(episode.id, episode.title, Icons.Default.PlayArrow, episode.easyEpisodeLabel())
    }, if (state.loading) "Loading episodes…" else "No episodes available for this season.",
        home = home, back = back, loading = state.loading || state.episodeLoadingMore,
        hasMore = state.episodeHasMore, loadMore = loadMore,
        open = { id -> episodes.firstOrNull { it.id == id }?.let(play) }) {
        if (seasons.size > 1 || (seasons.isEmpty() && currentSeason != null)) {
            SeriesSeasonDropdown(seasons, currentSeason, seasonRequester, selectSeason)
        } else if (currentSeason != null || seasons.isNotEmpty()) {
            Text("Season ${currentSeason ?: seasons.first()}",
                Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium)
        }
        NikTvSecondaryActionButton({ if (!state.loading) refresh() }) {
            Text(if (state.loading) "Loading…" else "Refresh episodes")
        }
    }
}

@Composable
internal fun EasyMyListPage(state: NikTvState, home: () -> Unit, downloads: () -> Unit,
    openFavorite: (FavoriteItem) -> Unit, toggleFavorite: (FavoriteItem) -> Unit) {
    val portal = state.session?.profile?.cacheKey()
    val gate = XtreamContentGate(state)
    val saved = state.favorites.filter { favorite ->
        val type = when (favorite.kind) {
            FavoriteKind.CHANNEL -> CatalogType.LIVE_TV
            FavoriteKind.MOVIE -> CatalogType.MOVIES
            FavoriteKind.SERIES, FavoriteKind.EPISODE -> CatalogType.SERIES
        }
        (favorite.profileKey == portal || favorite.profileKey.isEmpty()) && favorite.media.isEasyIptvTitle() &&
            favorite.series?.isEasyIptvTitle() != false && !adultContentLabel(favorite.categoryTitle.orEmpty()) &&
            gate.allows(favorite.series ?: favorite.media, type, favorite.categoryTitle)
    }.distinctBy(FavoriteItem::key)
    EasyBrowsePage("My List", saved.map { favorite ->
        val destination = favorite.kind.easyDestination()
        val opensList = favorite.kind == FavoriteKind.SERIES
        EasyBrowseEntry(favorite.key, favorite.media.title, if (opensList) destination.icon else Icons.Default.PlayArrow,
            favorite.media.easyEpisodeLabel() ?: destination.label, opensList)
    }, "No saved IPTV titles yet.", home = home,
        open = { key -> saved.firstOrNull { it.key == key }?.let(openFavorite) },
        toggleSaved = { key -> saved.firstOrNull { it.key == key }?.let(toggleFavorite) },
        savedKeys = saved.mapTo(mutableSetOf(), FavoriteItem::key), removingFromList = true) {
        NikTvSecondaryActionButton(downloads) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Downloads")
        }
    }
}
