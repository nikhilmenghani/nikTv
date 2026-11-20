package com.nikhil.niktv.discovery

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.data.adultContentLabel
import com.nikhil.niktv.discovery.components.modernVisibleIptvCategories
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.ViewingAction
import com.nikhil.niktv.model.WatchedSeries
import com.nikhil.niktv.ui.LocalRemoteFocusMargin
import com.nikhil.niktv.ui.LocalRemoteFocusColor
import com.nikhil.niktv.ui.LocalMarkUnwatched
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.episodeRemainingLabel
import com.nikhil.niktv.ui.seriesEpisodeCountLabel
import com.nikhil.niktv.ui.seriesEpisodeProgress
import com.nikhil.niktv.ui.episodeTitleWithoutIdentity
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
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 440
    val separateTitle = back != null && configuration.screenWidthDp < 520 * configuration.fontScale
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
                        if (separateTitle) Spacer(Modifier.weight(1f))
                        else EasyPageTitle(title, compact, Modifier.weight(1f))
                        NikTvSecondaryActionButton(home, Modifier.heightIn(min = 48.dp).focusRequester(homeRequester)) {
                            Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Home")
                        }
                    }
                    if (separateTitle) EasyPageTitle(title, compact)
                    content()
                }
            }
        }
    }
}

@Composable
private fun EasyPageTitle(title: String, compact: Boolean, modifier: Modifier = Modifier) {
    Text(title, modifier, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
}

internal data class EasyBrowseEntry(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val opensList: Boolean = false,
    val progress: Float? = null,
    val status: String? = null,
    val kindLabel: String? = null
)

internal data class EasyMediaAction(
    val label: String,
    val icon: ImageVector,
    val removesTile: Boolean = false,
    val run: () -> Unit
)

internal fun easySaveAction(isSaved: Boolean, removesTile: Boolean = false, toggle: () -> Unit) = EasyMediaAction(
    if (isSaved) "Remove from My List" else "Add to My List",
    if (isSaved) Icons.Default.HeartBroken else Icons.Default.FavoriteBorder,
    removesTile = isSaved && removesTile, run = toggle
)

/** Move to an attached neighbour while the source tile still exists. */
internal fun focusEasyNeighbour(keys: List<String>, removing: String, request: (String) -> Boolean, fallback: () -> Unit) {
    val index = keys.indexOf(removing)
    if (index < 0 || listOfNotNull(keys.getOrNull(index + 1), keys.getOrNull(index - 1)).none(request)) fallback()
}

@Composable
internal fun EasyBrowseTile(
    entry: EasyBrowseEntry,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    onHold: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val compact = LocalConfiguration.current.screenHeightDp < 440
    val palette = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false).clip(shape)
            .remoteCombinedClickable(open, onHold, showIndication = false),
        shape = shape, color = palette.surfaceVariant,
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            if (focused) LocalRemoteFocusColor.current else palette.onSurface.copy(alpha = .12f))
    ) {
        Row(Modifier.heightIn(min = if (compact) 48.dp else 58.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = palette.onSurface.copy(alpha = .08f),
                contentColor = palette.onSurfaceVariant) {
                Box(Modifier.size(if (compact) 32.dp else 38.dp), contentAlignment = Alignment.Center) {
                    Icon(entry.icon, null, Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    color = palette.onBackground)
                if (entry.subtitle != null || entry.status != null || entry.kindLabel != null) FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    entry.kindLabel?.let { EasyMediaBadge(it, emphasized = false) }
                    entry.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = palette.onSurfaceVariant) }
                    entry.status?.let { EasyMediaBadge(it, emphasized = entry.progress != null) }
                }
                entry.progress?.let { progress ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp))
                }
            }
            if (entry.opensList) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EasyMediaBadge(label: String, emphasized: Boolean) {
    val palette = MaterialTheme.colorScheme
    val color = if (emphasized) palette.primary.copy(alpha = .22f) else palette.onSurface.copy(alpha = .1f)
    Surface(shape = RoundedCornerShape(8.dp), color = color, contentColor = palette.onBackground) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun EasyMediaTile(entry: EasyBrowseEntry, open: () -> Unit, actions: List<EasyMediaAction>,
    modifier: Modifier = Modifier, beforeRemoval: () -> Unit = {}) {
    if (actions.isEmpty()) {
        EasyBrowseTile(entry, open, modifier)
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<EasyMediaAction?>(null) }
    val menuItem = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val focusBeforeRemoval by rememberUpdatedState(beforeRemoval)
    LaunchedEffect(pending, windowFocused) {
        val action = pending ?: return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        pending = null
        if (action.removesTile) focusBeforeRemoval()
        action.run()
    }
    Box {
        EasyBrowseTile(entry, open, modifier, onHold = { menuOpen = true })
        DropdownMenu(menuOpen, { menuOpen = false }, shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.initialFocusEntry(menuItem)) {
            actions.forEachIndexed { index, action ->
                NikDropdownMenuItem(text = { Text(action.label) },
                    onClick = { menuOpen = false; pending = action },
                    modifier = if (index == 0) Modifier.focusRequester(menuItem) else Modifier,
                    leadingIcon = { Icon(action.icon, null) })
            }
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
    pagingScope: String = title,
    page: Int = 0,
    pagingError: Boolean = false,
    summary: String? = null,
    summaryAction: (@Composable () -> Unit)? = null,
    open: (String) -> Unit,
    actions: ((String) -> List<EasyMediaAction>)? = null,
    tools: (@Composable RowScope.() -> Unit)? = null
) {
    val homeRequester = remember { FocusRequester() }
    val listEntry = remember { FocusRequester() }
    val list = key(pagingScope) { rememberLazyListState() }
    var lastOpened by rememberSaveable(pagingScope) { mutableStateOf<String?>(null) }
    val entryKey = remember {
        val index = entries.indexOfFirst { it.key == lastOpened }
        // A saved viewport can start partway through a long list.
        entries.getOrNull(if (index >= list.firstVisibleItemIndex) index else list.firstVisibleItemIndex)?.key
            ?: entries.firstOrNull()?.key
    }
    val remote = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current) ||
        LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val entryRequester = if (entryKey != null) listEntry else homeRequester
    val tileRequesters = remember(pagingScope) { mutableMapOf<String, FocusRequester>() }
    val keys = remember(entries) { entries.map(EasyBrowseEntry::key) }
    var usedPaging by rememberSaveable(pagingScope) { mutableStateOf(false) }
    var footerFocused by remember { mutableStateOf(false) }
    var pageStart by remember(pagingScope) { mutableStateOf<Int?>(null) }
    var requestedPage by rememberSaveable(pagingScope) { mutableIntStateOf(-1) }
    val pageItem = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val requestMore by rememberUpdatedState(loadMore)
    LaunchedEffect(pagingScope, page, entries.size, hasMore, loading, pagingError) {
        if (!hasMore || loading || pagingError || page == requestedPage || entries.isEmpty()) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { it >= (entries.lastIndex - 2).coerceAtLeast(0) }
        requestedPage = page
        requestMore()
    }
    LaunchedEffect(entries.size, loading) {
        val start = pageStart ?: return@LaunchedEffect
        if (loading || entries.size <= start) return@LaunchedEffect
        if (footerFocused) {
            list.scrollToItem(start)
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == start } }.first { it }
            if (footerFocused) pageItem.requestFocus()
        }
        pageStart = null
    }
    EasyPage(title, home, back, homeRequester, Modifier.initialFocusEntry(entryRequester, remote)) {
        tools?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = it) }
        if (summary != null || summaryAction != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (summary == null) Spacer(Modifier.weight(1f))
            else Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            summaryAction?.invoke()
        }
        if (entries.isEmpty() && !hasMore) {
            Text(emptyMessage, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val background = MaterialTheme.colorScheme.background
        LazyColumn(Modifier.fillMaxWidth().weight(1f).drawWithContent {
            drawContent()
            val viewport = list.layoutInfo
            val partial = viewport.visibleItemsInfo.lastOrNull()?.takeIf {
                it.offset + it.size > viewport.viewportEndOffset && it.offset < viewport.viewportEndOffset
            }
            if (partial != null && list.canScrollForward) {
                val fadeHeight = 22.dp.toPx().coerceAtMost((viewport.viewportEndOffset - partial.offset).toFloat())
                val start = size.height - fadeHeight
                drawRect(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), start, size.height),
                    topLeft = Offset(0f, start), size = Size(size.width, fadeHeight))
            }
        }, state = list, contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = EasyBrowseEntry::key) { entry ->
                val openEntry = {
                    lastOpened = entry.key
                    open(entry.key)
                }
                val modifier = Modifier
                    .then(if (entry.key == entryKey) Modifier.focusRequester(listEntry) else Modifier)
                    .then(if (entries.getOrNull(pageStart ?: -1)?.key == entry.key) Modifier.focusRequester(pageItem) else Modifier)
                if (actions == null) EasyBrowseTile(entry, openEntry, modifier)
                else {
                    val requester = remember { FocusRequester() }
                    DisposableEffect(entry.key, requester) {
                        tileRequesters[entry.key] = requester
                        onDispose { tileRequesters.remove(entry.key) }
                    }
                    EasyMediaTile(entry, openEntry, actions(entry.key), modifier.focusRequester(requester)) {
                        if (remote) focusEasyNeighbour(keys, entry.key, { tileRequesters[it]?.requestFocus() == true }) {
                            homeRequester.requestFocus()
                        }
                    }
                }
            }
            if (hasMore || usedPaging) item(key = "easy-pagination") {
                NikTvSecondaryActionButton({
                    if (hasMore) {
                        if (!loading) {
                            usedPaging = true
                            pageStart = entries.size
                            requestedPage = page
                            loadMore()
                        }
                    } else {
                        homeRequester.requestFocus()
                        scope.launch { list.scrollToItem(0) }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { footerFocused = it.isFocused }) {
                    Text(when {
                        !hasMore -> "Back to top"
                        loading -> "Loading…"
                        pagingError -> "Retry loading episodes"
                        else -> "Show more"
                    })
                }
            }
        }
    }
}

@Composable
internal fun EasyCategoriesPage(state: NikTvState, type: CatalogType, home: () -> Unit, refresh: () -> Unit,
    edit: () -> Unit, select: (Category) -> Unit) {
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
        NikTvSecondaryActionButton(edit, Modifier.weight(1f)) { Text("Edit categories") }
        NikTvSecondaryActionButton({ if (!loading) refresh() }, Modifier.weight(1f)) {
            Text(if (loading) "Refreshing…" else "Refresh categories")
        }
    }
}

internal fun MediaItem.easyEpisodeLabel(): String? = episodeNumber?.let { episode ->
    seasonNumber?.let { "Season $it, episode $episode" } ?: "Episode $episode"
}

internal fun PlaybackProgress?.easyProgressFraction(): Float? =
    this?.takeIf { it.action != ViewingAction.COMPLETED && it.action != ViewingAction.MARK_UNWATCHED &&
        it.positionMillis > 0L && it.durationMillis > 0L && episodeRemainingLabel(it) != "Watched" }
        ?.let { (it.positionMillis.toDouble() / it.durationMillis).toFloat().coerceIn(0f, 1f) }

@Composable
internal fun EasyTitlesPage(state: NikTvState, category: Category, home: () -> Unit, back: () -> Unit,
    play: (MediaItem) -> Unit, refresh: () -> Unit, loadMore: () -> Unit, toggleFavorite: (MediaItem) -> Unit,
    download: (MediaItem, CatalogType, MediaItem?) -> Unit) {
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
    val saved = remember(state.favorites, favoriteKind) {
        state.favorites.filter { it.kind == favoriteKind }.mapTo(mutableSetOf()) { it.media.id }
    }
    EasyBrowsePage(category.title, items.map { media ->
        EasyBrowseEntry(media.id, media.title, if (opensList) destination.icon else Icons.Default.PlayArrow,
            opensList = opensList)
    }, if (state.loading) "Loading ${destination.label.lowercase()}…" else "No titles available in this category.",
        home = home, back = back, loading = state.loading || state.catalogLoadingMore,
        hasMore = state.catalogHasMore, loadMore = loadMore,
        pagingScope = "${category.type}:${category.id}", page = state.catalogPage,
        open = { id -> items.firstOrNull { it.id == id }?.let(play) },
        actions = { id -> items.firstOrNull { it.id == id }?.let { item ->
            buildList {
                add(easySaveAction(id in saved) { toggleFavorite(item) })
                if (category.type == CatalogType.MOVIES) add(EasyMediaAction("Download", Icons.Default.Download) {
                    download(item, category.type, null)
                })
            }
        }.orEmpty() }) {
        NikTvSecondaryActionButton({ if (!state.loading) refresh() }) {
            Text(if (state.loading) "Loading…" else "Refresh")
        }
    }
}

@Composable
internal fun EasySeriesPage(state: NikTvState, series: MediaItem, home: () -> Unit, back: () -> Unit,
    selectSeason: (Int) -> Unit, play: (MediaItem) -> Unit, refresh: () -> Unit, loadMore: () -> Unit,
    toggleFollow: () -> Unit, toggleFavorite: (MediaItem) -> Unit, markWatched: (WatchedSeries, MediaItem) -> Unit,
    download: (MediaItem, CatalogType, MediaItem?) -> Unit) {
    val currentSeason = state.selectedSeriesSeason
    val seasons = state.availableSeriesSeasons
    val profile = state.session?.profile?.cacheKey().orEmpty()
    val followed = state.watchedSeries.firstOrNull { it.profileKey == profile && it.series.id == series.id }
    val markUnwatched = LocalMarkUnwatched.current
    val saved = remember(state.favorites) {
        state.favorites.filter { it.kind == FavoriteKind.EPISODE }.mapTo(mutableSetOf()) { it.media.id }
    }
    val counts = state.seriesSeasonEpisodeCounts.takeIf { state.seriesSeasonEpisodeCountsKey == "$profile|${series.id}" }.orEmpty()
    val episodes = remember(state.items) { state.items.filter(MediaItem::isEasyIptvTitle).distinctBy(MediaItem::id) }
    val progress = remember(state.playbackProgress, profile, series.id, episodes) {
        episodes.associate { it.id to seriesEpisodeProgress(state.playbackProgress, profile, series, it) }
    }
    val count = counts[currentSeason]
    val summary = if (state.loading && episodes.isEmpty()) null else if (state.episodeHasMore && count != null && count > episodes.size) {
        "${episodes.size} of $count episodes"
    } else seriesEpisodeCountLabel(episodes.size, state.episodeHasMore, filtered = false)
    EasyBrowsePage(series.title, episodes.map { episode ->
        val saved = progress[episode.id]
        val status = episodeRemainingLabel(saved)
        val episodeTitle = episode.episodeTitleWithoutIdentity()
        val label = episode.easyEpisodeLabel()
        val heading = episodeTitle.ifBlank { label ?: episode.title }
        EasyBrowseEntry(episode.id, heading, if (status == "Watched") Icons.Default.Check else Icons.Default.PlayArrow,
            label?.takeUnless { it == heading }, progress = saved.easyProgressFraction(), status = status)
    }, if (state.loading) "Loading episodes…" else "No episodes available for this season.",
        home = home, back = back, loading = state.loading || state.episodeLoadingMore,
        hasMore = state.episodeHasMore, loadMore = loadMore,
        pagingScope = "$profile:${series.id}:$currentSeason", page = state.episodePage,
        pagingError = state.episodePagingError?.let { it.profile == profile && it.series == series.id && it.season == currentSeason } == true,
        summary = summary,
        summaryAction = {
            NikTvSecondaryActionButton(toggleFollow, Modifier.semantics { selected = followed != null }) {
                Icon(if (followed == null) Icons.Default.NotificationsNone else Icons.Default.NotificationsActive,
                    null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (followed == null) "Follow series" else "Following")
            }
        },
        open = { id -> episodes.firstOrNull { it.id == id }?.let(play) },
        actions = { id -> episodes.firstOrNull { it.id == id }?.let { episode ->
            buildList {
                add(easySaveAction(id in saved) { toggleFavorite(episode) })
                if (episodeRemainingLabel(progress[id]) == "Watched") {
                    markUnwatched?.let { action -> add(EasyMediaAction("Mark as unwatched", Icons.Default.Replay) {
                        action(episode, CatalogType.SERIES, series)
                    }) }
                } else add(EasyMediaAction("Mark as watched", Icons.Default.Check) {
                    markWatched(followed ?: WatchedSeries(profile, series), episode)
                })
                add(EasyMediaAction("Download", Icons.Default.Download) { download(episode, CatalogType.SERIES, series) })
            }
        }.orEmpty() }) {
        if (seasons.size > 1 || (seasons.isEmpty() && currentSeason != null)) {
            EasySeasonPicker(seasons, currentSeason, counts, selectSeason)
        } else if (currentSeason != null || seasons.isNotEmpty()) {
            Text("Season ${currentSeason ?: seasons.first()}",
                Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium)
        }
        NikTvSecondaryActionButton({ if (!state.loading) refresh() }, Modifier.weight(1f)) {
            Text("Refresh episodes")
        }
    }
}

@Composable
private fun EasySeasonPicker(seasons: List<Int>, current: Int?, counts: Map<Int, Int>, select: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NikTvSecondaryActionButton({ open = true }) {
        Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(current?.let { "Season $it" } ?: "Choose season")
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        val options = (seasons + listOfNotNull(current)).distinct().sorted()
        val index = options.indexOf(current).coerceAtLeast(0)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
        val entry = remember { FocusRequester() }
        val height = (LocalConfiguration.current.screenHeightDp * .65f).dp
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(12.dp).initialFocusEntry(entry),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose season", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.heightIn(max = height), state = list, contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it }) { season ->
                        val selected = season == current
                        EasyBrowseTile(EasyBrowseEntry("season-$season", "Season $season",
                            if (selected) Icons.Default.Check else Icons.Default.Tv,
                            counts[season]?.let { seriesEpisodeCountLabel(it, hasMore = false, filtered = false) }),
                            open = { open = false; if (!selected) select(season) },
                            modifier = Modifier.semantics { this.selected = selected }
                                .then(if (season == options.getOrNull(index)) Modifier.focusRequester(entry) else Modifier))
                    }
                }
                NikTvSecondaryActionButton({ open = false },
                    if (options.isEmpty()) Modifier.focusRequester(entry) else Modifier) { Text("Close") }
            }
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
            favorite.media.easyEpisodeLabel(), opensList, kindLabel = destination.label)
    }, "No saved IPTV titles yet.", home = home,
        open = { key -> saved.firstOrNull { it.key == key }?.let(openFavorite) },
        actions = { key -> saved.firstOrNull { it.key == key }?.let { favorite ->
            listOf(easySaveAction(isSaved = true, removesTile = true) { toggleFavorite(favorite) })
        }.orEmpty() }) {
        NikTvSecondaryActionButton(downloads) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Downloads")
        }
    }
}
