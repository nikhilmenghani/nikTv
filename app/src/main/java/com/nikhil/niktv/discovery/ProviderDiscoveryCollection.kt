package com.nikhil.niktv.discovery

import com.nikhil.niktv.discovery.components.*

import com.nikhil.niktv.data.providerDisplayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import android.provider.Settings
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val LocalOpenProviderDiscovery = staticCompositionLocalOf<(CatalogType, MediaItem, List<MediaItem>) -> Unit> { { _, _, _ -> } }
internal val LocalConfigureProviderDiscovery = staticCompositionLocalOf<(CatalogType) -> Unit> { {} }
internal val LocalPlayProviderDiscovery = staticCompositionLocalOf<(CatalogType, MediaItem, List<MediaItem>) -> Unit> { { _, _, _ -> } }
internal val LocalDownloadProviderDiscovery = staticCompositionLocalOf<(CatalogType, MediaItem) -> Unit> { { _, _ -> } }
internal val LocalProviderDiscoveryPageLoader = staticCompositionLocalOf<ProviderDiscoveryPageLoader?> { null }

internal fun providerDiscoveryProgress(app: NikTvState, type: CatalogType, item: MediaItem): Float? {
    val profile = app.savedProfile?.cacheKey() ?: return null
    val recent = app.recentlyPlayed.firstOrNull { it.profileKey == profile &&
        it.kind == (if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE) &&
        (it.media.id == item.id || (type == CatalogType.SERIES && it.series?.id == item.id)) } ?: return null
    return discoveryProgressFraction(discoveryRecentProgress(recent, app.playbackProgress))
}

/** Loading changes the VM binding identity; a collection visit keeps the same saved UI identity. */
internal data class ProviderCollectionUiIdentity(val profile: String, val type: CatalogType, val categoryIds: List<String>)
internal fun providerCollectionUiIdentity(state: ProviderDiscoveryState, type: CatalogType) =
    ProviderCollectionUiIdentity(state.profile, type, state.categories[type].orEmpty().map { it.id })

@Composable
internal fun ProviderDiscoveryCollectionRoute(type: CatalogType, categories: List<Category>, title: String,
    app: NikTvState, close: () -> Unit, favorite: (FavoriteItem) -> Unit) {
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val pageLoader = LocalProviderDiscoveryPageLoader.current
    val vm: ProviderDiscoveryViewModel = viewModel(key = "provider-collection:$profile:$type:${categories.map { it.id }}")
    val loaded by vm.state.collectAsStateWithLifecycle()
    val selected = remember(type, categories) { mapOf(type to providerDiscoverySources(categories)).filterValues { it.isNotEmpty() } }
    val state = loaded.takeIf { it.profile == profile && it.categories == selected }
        ?: ProviderDiscoveryState(profile = profile, categories = selected)
    LaunchedEffect(app.session, selected, app.cacheIntervalMinutes) {
        vm.bind(app.session, selected, app.cacheIntervalMinutes.coerceAtLeast(1) * 60_000L, pageLoader = pageLoader)
    }
    DisposableEffect(vm) { onDispose { vm.pause(release = true) } }
    ProviderDiscoveryCollection(type, state, app, { vm.load(type, it) }, close, favorite, title)
}

@Composable
internal fun ProviderDiscoveryCollection(type: CatalogType, state: ProviderDiscoveryState, app: NikTvState,
    load: (Boolean) -> Unit, close: () -> Unit, favorite: (FavoriteItem) -> Unit,
    title: String = "Your IPTV ${type.title}", focusObserved: ((String) -> Unit)? = null) {
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(configuration))
    val remote = LocalContext.current.usesRemoteNavigation(configuration) || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val open = LocalOpenProviderDiscovery.current
    val play = LocalPlayProviderDiscovery.current
    val download = LocalDownloadProviderDiscovery.current
    val uiIdentity = remember(state.profile, type, state.categories[type]) { providerCollectionUiIdentity(state, type) }
    var query by rememberSaveable(uiIdentity) { mutableStateOf("") }
    var selected by rememberSaveable(uiIdentity) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    var contentPositionEstablished by rememberSaveable(uiIdentity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) { DiscoveryScrollCoordinator(scope) }
    val context = LocalContext.current
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val tileWidth = if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp)
        else discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    DisposableEffect(coordinator) { onDispose { coordinator.cancel() } }
    val back = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var entryHandled by remember(uiIdentity) { mutableStateOf(false) }
    var entryPositionReady by remember(uiIdentity, remote) { mutableStateOf(!remote) }
    // Capture only the selection restored for this visit. A transient framework focus
    // fallback during cache arrival must not become the new entry destination.
    val restoredEntrySelection = remember(uiIdentity) { selected }
    val all = state.items[type].orEmpty()
    val progressLookup = rememberDiscoveryProgressLookup(app.savedProfile?.cacheKey(), app.recentlyPlayed, app.playbackProgress)
    val items by produceState(all, all, query) {
        if (query.isNotBlank()) delay(100)
        value = withContext(Dispatchers.Default) { searchProviderDiscovery(all, query) }
    }
    LaunchedEffect(items.isNotEmpty(), remote) {
        if (!remote && items.isNotEmpty() && !contentPositionEstablished) {
            coordinator.cancel()
            grid.scrollToItem(0)
            contentPositionEstablished = true
        }
    }
    val categoryTitles = remember(state.categories, type) { state.categories[type].orEmpty().associate { it.id to it.title } }
    val entryTarget = if (entryHandled) null else restoredEntrySelection?.takeIf { saved -> items.any { it.id == saved } }
        ?: items.firstOrNull()?.id.takeIf { !entryHandled && (restoredEntrySelection == null || state.ready) }
    val headerFocusAllowed = !remote || entryHandled
    val fallbackReady = state.ready && items.isEmpty() && (!state.hasMore(type) || type in state.errors)
    val currentMore by rememberUpdatedState { load(false) }
    val canAutoLoad = state.ready && type !in state.loading && type !in state.errors && state.hasMore(type) && query.isBlank() &&
        app.nowPlaying == null && app.providerMovieDetails == null && app.selectedSeries == null && !app.loading
    LaunchedEffect(grid, canAutoLoad, all.size) {
        if (canAutoLoad) snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last -> if (last >= all.size - 8) currentMore() }
    }
    BackHandler(onBack = close)
    DiscoveryVerticalFocusViewport(coordinator) {
        Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                    modifier = Modifier.focusRequester(back).focusProperties {
                        canFocus = headerFocusAllowed || (entryPositionReady && fallbackReady)
                    }, onFocus = { focusObserved?.invoke("header:back") }, onClick = close)
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(top = 6.dp))
                Box(Modifier.focusProperties { canFocus = headerFocusAllowed }) {
                    DiscoveryCollectionSearch(query, { query = it }, compact = phone, onActivate = { entryHandled = true })
                }
                DiscoveryAction("Refresh", Icons.Default.Refresh, iconOnly = true, enabled = type !in state.loading,
                    modifier = Modifier.focusProperties { canFocus = headerFocusAllowed }) { load(true) }
            }
            Text("${items.size} titles · ${state.categories[type].orEmpty().size} categories" +
                if (state.hasMore(type)) " · More titles available" else "",
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.LightGray,
                style = MaterialTheme.typography.labelMedium)
            if (type in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = discoveryCollectionColumns(maxWidth.value, tileWidth)
            LazyVerticalGrid(if (phone) GridCells.Adaptive(tileWidth.dp) else GridCells.FixedSize(tileWidth.dp),
                Modifier.fillMaxSize().alpha(if (remote && !entryHandled && !entryPositionReady) 0f else 1f).onPreviewKeyEvent { event ->
                    if (!remote || event.type != KeyEventType.KeyDown) false else {
                        val index = items.indexOfFirst { it.id == selected }
                        val delta = when (event.key) {
                            Key.DirectionUp -> -columns
                            Key.DirectionDown -> columns
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            else -> 0
                        }
                        val target = if (delta == 0) null else discoveryGridTarget(index, delta, columns, items.size)
                        if (target == null) false else {
                            val id = items[target].id
                            selected = id
                            coordinator.moveGrid(grid, target, columns, index / columns != target / columns, reduceMotion) {
                                runCatching { requesters[id]?.requestFocus() == true }.getOrDefault(false)
                            }
                            true
                        }
                    }
                }, state = grid, contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (phone) 16.dp else 0.dp)) {
                items(items, key = { it.id }) { item ->
                    val requester = remember(item.id) { requesters.getOrPut(item.id) { FocusRequester() } }
                    DisposableEffect(requester) { onDispose { requesters.remove(item.id) } }
                    var popup by remember { mutableStateOf(false) }
                    val saved = FavoriteItem(if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE,
                        item, profileKey = state.profile, categoryTitle = categoryTitles[item.portalCategoryId])
                    BoxWithConstraints {
                        val click = { selected = item.id; open(type, item, items) }
                        val subtitle = categoryTitles[item.portalCategoryId].orEmpty()
                        val progress = progressLookup?.provider(type, item.id)
                        val entryFocus = Modifier.focusRequester(requester).focusProperties {
                            canFocus = !remote || entryHandled || (entryPositionReady && item.id == entryTarget)
                        }
                        val itemFocused: () -> Unit = {
                            selected = item.id
                            if (entryPositionReady && item.id == entryTarget) entryHandled = true
                            focusObserved?.invoke(item.id)
                        }
                        if (phone) DiscoveryPhoneMediaCard(providerDisplayTitle(item.title, type), item.logo, subtitle, maxWidth, false,
                            source = DiscoveryTileSource.IPTV,
                            modifier = entryFocus, progress = progress, onClick = click, onLongClick = { popup = true }, onFocus = itemFocused)
                        else DiscoveryPosterTile(providerDisplayTitle(item.title, type), item.logo, subtitle, maxWidth, reduceMotion = reduceMotion,
                            source = DiscoveryTileSource.IPTV,
                            focusVerticalInset = discoveryPosterFocusInset(maxWidth.value).dp,
                            modifier = entryFocus, progress = progress, onClick = click, onLongClick = { popup = true }, onFocus = itemFocused)
                        ModernTileActionsMenu(popup, app.favorites.any { it.key == saved.key },
                            dismiss = { popup = false; runCatching { requester.requestFocus() } },
                            toggleFavorite = { favorite(saved) }, clear = null, trailerItem = item, trailerSeries = type == CatalogType.SERIES,
                            recommendationItem = item, recommendationType = type, playAction = { play(type, item, items) },
                            downloadAction = if (type == CatalogType.MOVIES) ({ download(type, item) }) else null,
                            onTrailerReturned = { runCatching { requester.requestFocus() }.getOrDefault(false) })
                    }
                }
                item("status", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.errors[type]?.let { Text(it, color = Color.LightGray) }
                        if (type in state.capped) Text("Showing ${ProviderDiscoveryViewModel.MAX_ITEMS} cached titles. Select fewer categories to narrow this collection.", color = Color.LightGray)
                        if (items.isEmpty() && type !in state.loading) Text(if (query.isBlank()) "No titles available yet" else "No matches in the loaded titles", color = Color.LightGray)
                        if ((state.hasMore(type) || type in state.errors) && type !in state.loading)
                            DiscoveryAction(if (type in state.errors) "Retry" else "Load more titles",
                                modifier = Modifier.focusProperties { canFocus = headerFocusAllowed }, onClick = { load(false) })
                    }
                }
            }
            }
        }
    }
    LaunchedEffect(uiIdentity, state.ready, items.isNotEmpty(), remote) {
        if (!remote || entryHandled || !state.ready || (items.isEmpty() && state.hasMore(type) && type !in state.errors)) return@LaunchedEffect
        val targetId = entryTarget
        val index = items.indexOfFirst { it.id == targetId }
        coordinator.cancel()
        // Removing the native rail footer can leave an implicit relocation in flight.
        // Position the new grid before exposing it, and suppress that old reveal until
        // its first focused item has been laid out in the collection's own viewport.
        coordinator.withAutomaticRevealSuppressed {
            if (index >= 0) {
                grid.scrollToItem(index)
                withFrameNanos { }
                contentPositionEstablished = true
            }
            entryPositionReady = true
            repeat(12) {
                withFrameNanos { }
                if (entryHandled) return@withAutomaticRevealSuppressed
                val target = if (targetId == null) back else requesters[targetId]
                if (target != null && runCatching { target.requestFocus() }.getOrDefault(false)) {
                    entryHandled = true
                    withFrameNanos { }
                    return@withAutomaticRevealSuppressed
                }
            }
        }
    }
}
