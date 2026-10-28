package com.nikhil.niktv.discovery

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nikhil.niktv.ui.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

/** Same posters, grid navigation and search control as the existing full collections. */
@Composable
internal fun WatchProviderCollection(provider: WatchProvider, region: String, series: Boolean,
    row: WatchProviderRow, load: (Boolean) -> Unit, refresh: () -> Unit, close: () -> Unit,
    metadataActive: Boolean, open: (DiscoveryCard, FocusRequester) -> Unit,
    focusRegistry: MutableMap<String, FocusRequester>? = null, cancel: () -> Unit = {}) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
    val remote = context.isTvLikeDevice(configuration) || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var entered by rememberSaveable { mutableStateOf(false) }
    val shown = remember(row.cards, query) { row.cards.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) { DiscoveryScrollCoordinator(scope) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val footer = remember { FocusRequester() }
    var footerFocused by remember { mutableStateOf(false) }
    var requestedIndex by remember { mutableIntStateOf(0) }
    var requestedPage by remember { mutableIntStateOf(-1) }
    var focusNew by remember { mutableStateOf(false) }
    var autoCount by remember { mutableIntStateOf(-1) }
    val currentLoad by rememberUpdatedState(load)
    fun request(more: Boolean, explicit: Boolean) {
        requestedIndex = shown.size; requestedPage = if (more) row.page + 1 else 1
        focusNew = explicit || footerFocused; autoCount = row.cards.size
        currentLoad(more)
    }
    LaunchedEffect(metadataActive) { if (metadataActive) { autoCount = -1; currentLoad(false) } else cancel() }
    DisposableEffect(Unit) { onDispose { cancel() } }
    DisposableEffect(coordinator) { onDispose { coordinator.cancel() } }
    LaunchedEffect(query) { coordinator.cancel(); grid.scrollToItem(0); selected = null }
    val canLoad = metadataActive && query.isBlank() && row.more && !row.loading && row.error == null && row.cards.isNotEmpty()
    LaunchedEffect(grid, canLoad, row.cards.size, row.page) {
        if (canLoad) snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (last >= row.cards.size - 8 && autoCount != row.cards.size) request(true, false)
            }
    }
    LaunchedEffect(requestedPage, row.page, row.loading, row.error, focusNew) {
        if (focusNew && requestedPage >= 0 && !row.loading && (row.page >= requestedPage || row.error != null)) {
            coordinator.cancel()
            val index = requestedIndex.coerceAtMost(shown.lastIndex)
            if (row.error == null && index >= 0) {
                grid.scrollToItem(index); selected = shown[index].key
                repeat(30) { withFrameNanos { }; if (runCatching { requesters[shown[index].key]?.requestFocus() ?: false }.getOrDefault(false)) { focusNew = false; return@LaunchedEffect } }
            } else repeat(8) { withFrameNanos { }; if (runCatching { footer.requestFocus() }.getOrDefault(false)) return@repeat }
            focusNew = false
        }
    }
    LaunchedEffect(row.cards.isNotEmpty(), metadataActive) {
        if (!entered && metadataActive && row.cards.isNotEmpty()) {
            grid.scrollToItem(0); entered = true
            if (remote) repeat(30) { withFrameNanos { }; if (runCatching { requesters[row.cards.first().key]?.requestFocus() ?: false }.getOrDefault(false)) return@LaunchedEffect }
        }
    }
    BackHandler(onBack = close)
    DiscoveryVerticalFocusViewport(coordinator) {
        Column(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                provider.logo?.let { AsyncImage(it, null, Modifier.size(32.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("${if (series) "Series" else "Movies"} · $region", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                }
                DiscoveryCollectionSearch(query, { query = it }, compact = phone, onActivate = { entered = true })
                DiscoveryAction("Refresh provider titles", Icons.Default.Refresh, iconOnly = true) {
                    coordinator.cancel(); entered = false; selected = null; autoCount = -1; refresh()
                }
            }
            Text(if (query.isBlank()) "${row.cards.size} titles loaded${if (row.more) " · More load as you scroll" else ""}"
                else "${shown.size} matches in ${row.cards.size} loaded titles", color = Color.LightGray,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            // Reserve progress space so tile positions do not shift on each page request.
            Box(Modifier.fillMaxWidth().height(4.dp)) { if (row.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (phone) 3 else discoveryCollectionColumns(maxWidth.value,
                    discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat()))
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                        val index = shown.indexOfFirst { it.key == selected }
                        val delta = when (event.key) { Key.DirectionUp -> -columns; Key.DirectionDown -> columns; Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                        val target = if (!remote || event.type != KeyEventType.KeyDown || delta == 0) null else discoveryGridTarget(index, delta, columns, shown.size)
                        if (target == null) false else {
                            selected = shown[target].key
                            coordinator.moveGrid(grid, target, columns, index / columns != target / columns, reduceMotion) {
                                runCatching { requesters[shown[target].key]?.requestFocus() ?: false }.getOrDefault(false)
                            }; true
                        }
                    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.key }) { card ->
                        val requester = remember(card.key) { FocusRequester().also { requesters[card.key] = it; focusRegistry?.set(card.key, it) } }
                        DisposableEffect(card.key) { onDispose { requesters.remove(card.key); if (focusRegistry?.get(card.key) === requester) focusRegistry?.remove(card.key) } }
                        BoxWithConstraints {
                            DiscoveryPosterTile(card.title, card.poster, card.date?.take(4).orEmpty(), maxWidth,
                                modifier = Modifier.focusRequester(requester), reduceMotion = reduceMotion,
                                focusVerticalInset = discoveryPosterFocusInset(maxWidth.value).dp, source = DiscoveryTileSource.TMDB,
                                onFocus = { selected = card.key }, onClick = { selected = card.key; open(card, requester) })
                        }
                    }
                    item(key = "provider-page-footer", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (shown.isEmpty() && !row.loading) Text(if (row.error != null) "Could not load titles" else if (query.isNotBlank()) "No matching loaded titles" else "No released titles available", color = Color.LightGray)
                            if (row.more || row.loading || row.error != null) DiscoveryAction(
                                if (row.loading) "Loading…" else if (row.error != null) "Retry titles" else "Load more titles",
                                modifier = Modifier.focusRequester(footer).onFocusChanged {
                                    footerFocused = it.hasFocus
                                    if (it.hasFocus && row.loading && requestedPage >= 0) focusNew = true
                                }) { if (!row.loading) request(row.page > 0, true) }
                        }
                    }
                }
            }
        }
    }
}
