package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.launch

@Composable
internal fun WatchProvidersScreen(app: NikTvState, back: () -> Unit, select: (DashboardSurface) -> Unit,
    navigate: (String) -> Unit, playCard: (DiscoveryCard) -> Unit, detailVisibilityChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val profile = app.savedProfile?.cacheKey().orEmpty()
    val preferences = remember(context) { context.getSharedPreferences("watch_providers", 0) }
    val holder: WatchProvidersViewModel = viewModel(key = "watch-providers:$profile")
    val model = holder.model
    val state by model.state.collectAsState()
    val cardSaver = remember { Saver<DiscoveryCard?, String>(
        save = { it?.let { card -> kotlinx.serialization.json.Json.encodeToString(DiscoveryCard.serializer(), card) } ?: "" },
        restore = { value -> value.takeIf(String::isNotBlank)?.let { kotlinx.serialization.json.Json.decodeFromString(DiscoveryCard.serializer(), it) } }) }
    var selected by rememberSaveable(profile, stateSaver = cardSaver) { mutableStateOf<DiscoveryCard?>(null) }
    val providerSaver = remember { Saver<WatchProvider?, String>(
        save = { it?.let { provider -> kotlinx.serialization.json.Json.encodeToString(WatchProvider.serializer(), provider) } ?: "" },
        restore = { value -> value.takeIf(String::isNotBlank)?.let { kotlinx.serialization.json.Json.decodeFromString(WatchProvider.serializer(), it) } }) }
    var collection by rememberSaveable(profile, stateSaver = providerSaver) { mutableStateOf<WatchProvider?>(null) }
    val pages = rememberSaveableStateHolder()
    val allRequesters = remember(profile) { mutableMapOf<Int, FocusRequester>() }
    val gridRequesters = remember(profile) { mutableMapOf<String, FocusRequester>() }
    var originKey by rememberSaveable(profile) { mutableStateOf<String?>(null) }
    var collectionOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    var regions by remember { mutableStateOf(false) }
    var query by rememberSaveable(profile) { mutableStateOf("") }
    var search by rememberSaveable(profile) { mutableStateOf(false) }
    val regionFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var originFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val scopeForFocus = rememberCoroutineScope()
    val host = LocalView.current
    fun restore() { scopeForFocus.launch {
        repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { (if (collection != null) gridRequesters[originKey] else originFocus)?.requestFocus() ?: false }.getOrDefault(false)) return@launch }
    } }
    LaunchedEffect(model) { model.resume() }
    DisposableEffect(model) { onDispose { model.stop() } }
    fun closeCollection() {
        val providerId = collection?.id
        collection = null
        scopeForFocus.launch { repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { (allRequesters[providerId] ?: collectionOrigin)?.requestFocus() ?: false }.getOrDefault(false)) return@launch } }
    }
    BackHandler { if (collection != null) closeCollection() else if (search) { search = false; query = "" } else back() }
    DiscoveryLibraryScreen(app, DiscoveryLibraryDestination.PROVIDERS, { if (collection != null) closeCollection() else back() }, select, navigate) {
        val provider = collection
        if (provider != null) key(profile, state.region, state.series, provider.id) {
            WatchProviderCollection(provider, state.region, state.series, state.rows[provider.id] ?: WatchProviderRow(loading = true),
                load = { more -> model.load(provider.id, more) }, refresh = { model.refreshProvider(provider.id) },
                close = ::closeCollection, metadataActive = selected == null,
                open = { card, requester -> originKey = card.key; originFocus = requester; selected = card }, focusRegistry = gridRequesters, cancel = { model.leave(provider.id) })
        } else pages.SaveableStateProvider("provider-rows") { WatchProviderRows(state, model::load, model::leave, query, search, { query = it }, searchFocus,
            regionFocus, { regions = true }, { search = !search; if (!search) query = "" },
            { series -> preferences.edit().putBoolean("series", series).apply(); model.select(series = series) },
            model::refresh,
            open = { card, requester -> originKey = card.key; originFocus = requester; selected = card }, metadataActive = selected == null,
            viewAll = { provider, requester -> collectionOrigin = requester; collection = provider }, viewAllRequesters = allRequesters) }
    }
    if (regions) {
        WatchRegionDialog(state.regions, state.region, dismiss = { regions = false }, choose = { region ->
            preferences.edit().putString("region", region.code).apply()
            model.select(region = region.code); regions = false
        })
        DisposableEffect(Unit) { onDispose { scopeForFocus.launch {
            repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { regionFocus.requestFocus() }.getOrDefault(false)) return@launch }
        } } }
    }
    selected?.let { card ->
        val nav = LocalProviderDetailNavigation.current
        if (nav != null) Dialog(onDismissRequest = { selected = null; restore() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val vm: DiscoveryViewModel = viewModel(key = "watch-provider-detail:$profile")
            var initialized by remember(card.key) { mutableStateOf(false) }
            val current by vm.state.collectAsState()
            val surface = if (card.series) DashboardSurface.SERIES else DashboardSurface.MOVIES
            LaunchedEffect(card.key) { vm.bind(profile, surface, emptyList()); vm.openRootDetails(card); initialized = true }
            LaunchedEffect(initialized, current.detail) { if (initialized && current.detail == null && vm.state.value.detail == null) { selected = null; restore() } }
            DiscoveryPreviewScreen(app, surface, selectSurface = { selected = null; select(it) },
                exit = { selected = null; restore() }, navigate = { selected = null; navigate(it) },
                playCard = playCard, playEpisode = nav.playEpisode, openRecent = nav.recent, openWatched = nav.watched,
                toggleFavorite = nav.favorite, openCategory = nav.category, openSection = nav.section,
                removeRecent = nav.removeRecent, markWatched = nav.markWatched, dismissEpisode = nav.dismissEpisode,
                vm = vm, detailVisibilityChanged = detailVisibilityChanged)
        }
    }
}

/** Lazy provider sections: no title/availability request for every entry at profile startup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchProviderRows(state: WatchProvidersState, load: (Int, Boolean) -> Unit, leave: (Int) -> Unit,
    query: String, search: Boolean, changeQuery: (String) -> Unit, searchFocus: FocusRequester,
    regionFocus: FocusRequester, chooseRegion: () -> Unit, toggleSearch: () -> Unit,
    selectType: (Boolean) -> Unit, retry: () -> Unit, open: (DiscoveryCard, FocusRequester) -> Unit, metadataActive: Boolean = true, viewAll: (WatchProvider, FocusRequester) -> Unit = { _, _ -> }, viewAllRequesters: MutableMap<Int, FocusRequester>? = null) {
    val config = LocalConfiguration.current
    val reducedMotion = android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val phone = usesDiscoveryPhoneLayout(config.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(config))
    val list = rememberLazyListState()
    val rowStates = rememberSaveableStateHolder()
    val filtered = remember(state.providers, query) { state.providers.filter { it.name.contains(query, true) } }
    LaunchedEffect(state.region, state.series, query) { list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "filters") {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Browse providers", color = Color.White, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscoveryAction("Movies", Icons.Default.Movie, selected = !state.series) { selectType(false) }
                    DiscoveryAction("Series", Icons.Default.Tv, selected = state.series) { selectType(true) }
                    DiscoveryAction(state.region, Icons.Default.Public, modifier = Modifier.focusRequester(regionFocus), onClick = chooseRegion)
                    DiscoveryAction("Filter providers", Icons.Default.Search, iconOnly = true, modifier = Modifier.focusRequester(searchFocus), onClick = toggleSearch)
                    DiscoveryAction("Refresh providers", Icons.Default.Refresh, iconOnly = true, onClick = retry)
                }
                if (search) WatchProviderFilterField(query, changeQuery, "Filter providers", Modifier.fillMaxWidth())
                Text("Availability: JustWatch via TMDB · ${state.region}. IPTV availability is checked when you play.",
                    color = Color(0xFFA8ACB6), fontSize = 12.sp)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = Color(0xFFFFAAB4)) }
                if (!state.loading && state.error == null && filtered.isEmpty()) Text("No providers found for this selection.", color = Color.LightGray)
            }
        }
        items(filtered, key = { "${state.region}:${state.series}:${it.id}" }) { provider ->
            LaunchedEffect(provider.id, state.region, state.series, state.revision, metadataActive) { if (metadataActive) load(provider.id, false) else leave(provider.id) }
            DisposableEffect(provider.id, state.region, state.series, state.revision) { onDispose { leave(provider.id) } }
            val row = state.rows[provider.id] ?: WatchProviderRow(loading = true)
            val allFocus = remember(provider.id) { viewAllRequesters?.getOrPut(provider.id) { FocusRequester() } ?: FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(horizontal = if (phone) 16.dp else 28.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    provider.logo?.let { AsyncImage(it, null, Modifier.size(30.dp)) }
                    Text(provider.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    DiscoveryAction("View all ${provider.name}", visualLabel = "View all",
                        modifier = Modifier.focusRequester(allFocus).semantics { contentDescription = "View all ${provider.name}" }) { viewAll(provider, allFocus) }
                }
                rowStates.SaveableStateProvider("${state.region}:${state.series}:${provider.id}") {
                    val horizontal = rememberLazyListState()
                    val firstNew = remember { FocusRequester() }
                    val moreFocus = remember { FocusRequester() }
                    var pendingIndex by remember { mutableIntStateOf(-1) }
                    var pendingPage by remember { mutableIntStateOf(-1) }
                    LaunchedEffect(pendingPage, row.page, row.loading, row.error) {
                        if (pendingPage >= 0 && !row.loading && (row.page >= pendingPage || row.error != null)) {
                            val target = if (row.error != null || row.cards.isEmpty()) moreFocus else firstNew
                            if (row.error == null && row.cards.isNotEmpty()) horizontal.scrollToItem(pendingIndex.coerceIn(0, row.cards.lastIndex))
                            repeat(8) { withFrameNanos { }; if (runCatching { target.requestFocus() }.getOrDefault(false)) { pendingPage = -1; return@LaunchedEffect } }
                            pendingPage = -1
                        }
                    }
                    val width = if (phone) phoneDiscoveryPosterWidth(config.screenWidthDp).dp else ((config.screenWidthDp - 100) / 7f).coerceIn(90f, 156f).dp
                    LazyRow(state = horizontal, contentPadding = PaddingValues(horizontal = if (phone) 16.dp else 28.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(min = width * 1.5f + 24.dp)) {
                        itemsIndexed(row.cards, key = { _, card -> card.key }) { index, card ->
                            val requester = remember { FocusRequester() }
                            val actualFocus = if (pendingPage >= 0 && index == pendingIndex.coerceAtMost(row.cards.lastIndex)) firstNew else requester
                            DiscoveryPosterTile(card.title, card.poster, card.date?.take(4).orEmpty(), width,
                                modifier = Modifier.focusRequester(actualFocus), focusVerticalInset = 4.dp, reduceMotion = reducedMotion,
                                source = DiscoveryTileSource.TMDB, onClick = { open(card, actualFocus) })
                        }
                        if (row.loading && row.cards.isEmpty()) items(3) {
                            Box(Modifier.width(width).padding(vertical = 4.dp).height(width * 1.5f).background(Color(0xFF1C222C), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)))
                        }
                        item(key = "provider-more") {
                            Box(Modifier.width(width).height(width * 1.5f + 8.dp), contentAlignment = Alignment.Center) {
                                when {
                                    row.loading || row.error != null || row.more -> DiscoveryAction(
                                        if (row.loading) "Loading…" else if (row.error != null) "Retry" else "More",
                                        if (row.loading) Icons.Default.HourglassTop else if (row.error != null) Icons.Default.Refresh else Icons.Default.ChevronRight,
                                        modifier = Modifier.focusRequester(moreFocus)) {
                                            if (!row.loading) {
                                                pendingIndex = row.cards.size
                                                pendingPage = row.page + 1
                                                load(provider.id, row.page > 0)
                                            }
                                        }
                                    row.cards.isEmpty() -> Text("No released titles", color = Color.LightGray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Focus alone must not open the IME over country/provider navigation. */
@Composable
private fun WatchProviderFilterField(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var imeSeen by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(editing) {
        if (editing) { imeSeen = false; withFrameNanos { }; runCatching { field.requestFocus() }; keyboard?.show() }
    }
    LaunchedEffect(editing, imeVisible) {
        if (editing && imeVisible) imeSeen = true
        else if (editing && imeSeen) { editing = false; imeSeen = false }
    }
    BackHandler(editing) { editing = false; keyboard?.hide() }
    OutlinedTextField(value, change, modifier.heightIn(min = 48.dp).focusRequester(field)
        .onFocusChanged { if (!it.hasFocus && editing) { editing = false; keyboard?.hide() } }
        .onPreviewKeyEvent { event ->
            if (!editing && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                if (event.type == KeyEventType.KeyUp) editing = true
                true
            } else false
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) editing = true
            }
        }, readOnly = !editing, singleLine = true,
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false), label = { Text(label) })
}

@Composable
internal fun WatchRegionDialog(regions: List<WatchRegion>, selected: String, dismiss: () -> Unit, choose: (WatchRegion) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val current = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val visible = remember(regions, filter) { regions.filter { it.name.contains(filter, true) || it.code.contains(filter, true) } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = regions.indexOfFirst { it.code == selected }.coerceAtLeast(0))
    LaunchedEffect(regions, selected) {
        repeat(8) { withFrameNanos { }; if (runCatching { (if (regions.any { it.code == selected }) current else close).requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    AlertDialog(onDismissRequest = dismiss, containerColor = Color(0xFF15171D), title = { Text("Provider region") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchProviderFilterField(filter, { filter = it }, "Filter countries", Modifier.fillMaxWidth())
                LazyColumn(state = list, modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visible, key = { it.code }) { region ->
                        DiscoveryAction("${region.name} (${region.code})", selected = selected == region.code,
                            modifier = Modifier.fillMaxWidth().then(if (region.code == selected) Modifier.focusRequester(current) else Modifier)) { choose(region) }
                    }
                }
                if (regions.isEmpty()) Text("Country list is unavailable. Retry to refresh.")
            }
        }, confirmButton = { NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.focusRequester(close)) { Text("Close") } })
}
