package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.ui.*
import java.time.Instant
import kotlinx.coroutines.launch

internal val SportsSurface = Color(0xFF181D26)
internal val SportsMuted = Color(0xFFB5BBC7)
internal val SportsShape = RoundedCornerShape(10.dp)

/** Presentation only: selecting a tile loads metadata, never a decoder. */
@Composable
internal fun SportsBrowseContent(state: SportsState, now: Instant, sport: String, setSport: (String) -> Unit,
    status: String, setStatus: (String) -> Unit, query: String, setQuery: (String) -> Unit,
    back: () -> Unit, refresh: () -> Unit, open: (SportsEvent) -> Unit, play: (Int) -> Unit,
    eventsScroll: LazyGridState = rememberLazyGridState(), streamsScroll: LazyGridState = rememberLazyGridState(),
    lastSource: Int? = null, defaults: SportsBrowseDefaults = SportsBrowseDefaults(),
    saveDefaultSport: (String) -> Unit = {}, saveDefaultStatus: (String) -> Unit = {},
    selectTab: (SportsTab) -> Unit = {}, openCategory: (SportsCategory) -> Unit = {},
    openChannel: (SportsChannel) -> Unit = {}, toggleFavourite: (SportsChannel) -> Unit = {},
    toggleFavourites: () -> Unit = {},
    addPlaylist: (String, String) -> Unit = { _, _ -> }, importPlaylist: (android.net.Uri, String) -> Unit = { _, _ -> },
    removePlaylist: (SportsCategory) -> Unit = {}, dismissLibraryError: () -> Unit = {},
    toggleChannelFlag: (SportsChannel) -> Unit = {}, setAvailabilityFilter: (SportsAvailabilityFilter) -> Unit = {},
    servers: SportsServersState = SportsServersState(), checkServers: () -> Unit = {},
    selectServer: (String?) -> Unit = {}, closeServerChecks: () -> Unit = {}, portalEntryFocus: FocusRequester? = null) {
    val compact = sportsPhoneLayout()
    val scope = rememberCoroutineScope()
    val tabFocus = remember { SportsTab.entries.associateWith { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    val optionsAction = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val gridEntry = remember { FocusRequester() }
    val sourceFocus = remember(state.streams) { state.streams.map { FocusRequester() } }
    val selected = state.selected
    val browseChannels = if (state.favouritesOnly) state.favourites else if (state.collection != null) state.collectionChannels else state.channels
    val groups = remember(browseChannels) { listOf("All groups") + browseChannels.map { it.group }.filter(String::isNotBlank).distinct() }
    val sports = remember(state.events, state.filters, sport) { sportsEventFilterChoices(state.events, state.filters, sport) }
    val headerFocus = if (compact || state.tab == SportsTab.CATEGORIES) optionsAction else tabFocus.getValue(state.tab)
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var editing by remember { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var groupsOpen by remember { mutableStateOf(false) }
    var availabilityOpen by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    var sportOpen by remember { mutableStateOf(false) }
    var resolverOpen by remember { mutableStateOf(false) }
    var serversOpen by remember { mutableStateOf(false) }
    val resolverContext = LocalContext.current
    val resolverPreferences = remember(resolverContext, state.source) { SportsResolverPreferences(resolverContext, state.source.storagePrefix) }
    var resolverMode by remember { mutableStateOf(resolverPreferences.read()) }
    var group by rememberSaveable { mutableStateOf("All groups") }
    var contextItem by remember { mutableStateOf<SportsChannel?>(null) }
    var contextCategory by remember { mutableStateOf<SportsCategory?>(null) }
    var lastTile by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreTile by remember { mutableStateOf<FocusRequester?>(null) }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    val originFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun restore(requester: FocusRequester?) { scope.launch { withFrameNanos { }; requester?.let { runCatching { it.requestFocus() } } } }
    fun closeFilters(apply: () -> Unit = {}) {
        runCatching { optionsAction.requestFocus() }
        apply()
        statusOpen = false; sportOpen = false; availabilityOpen = false; groupsOpen = false
    }
    fun closeSearch() { editing = false; searchOpen = false; setQuery(""); keyboard?.hide(); if (compact) restore(optionsAction) }
    BackHandler(searchOpen && selected == null) { closeSearch() }
    LaunchedEffect(searchOpen) { if (compact && searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() }; if (editing) keyboard?.show() } }
    LaunchedEffect(state.collection?.id, state.importing, state.libraryError) {
        if (addOpen && !state.importing && state.libraryError == null && state.collection?.custom == true) addOpen = false
    }
    val availableKeys = if (state.favouritesOnly) state.favourites.map { it.key }
        else when { state.collection != null -> state.collectionChannels.map { it.key }
            state.tab == SportsTab.EVENTS -> state.events.filter { sportsEventMatches(it, sport, status, query, now) }.map { it.slug }
            state.tab == SportsTab.CHANNELS || state.tab == SportsTab.HIGHLIGHTS -> state.channels.map { it.key }
            else -> (state.categories + state.customPlaylists).map { it.id } }
    val showsAvailability = selected == null && (state.tab != SportsTab.CATEGORIES || state.collection != null || state.favouritesOnly)
    val originKey = if (state.tab == SportsTab.EVENTS) lastTile?.substringAfterLast(':') else lastTile
    val restoreOrigin = state.restoreBrowseFocus && originKey != null && originKey in availableKeys &&
        (!showsAvailability || state.availabilityFilter.includes(state.channelFlags[originKey]))
    val initial = portalEntryFocus ?: if (selected != null) sourceFocus.getOrNull(lastSource ?: 0) ?: backFocus else if (restoreOrigin) originFocus else headerFocus
    fun tileRequester(key: String): FocusRequester = if (restoreOrigin && key == lastTile) originFocus else tileFocus.getOrPut(key) { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).windowInsetsPadding(WindowInsets.safeDrawing)
        .initialFocusEntry(initial, enabled = !searchOpen, entryHandled = searchOpen)) {
        val headerActions = buildList {
            add(SportsHeaderAction("Categories", Icons.Default.Category, state.tab == SportsTab.CATEGORIES) { selectTab(SportsTab.CATEGORIES) })
            if (selected == null) add(SportsHeaderAction("Search sports", Icons.Default.Search, searchOpen) {
                if (searchOpen) closeSearch() else { editing = true; searchOpen = true }
            })
            val favourite = selected != null && state.favourites.any { it.key == selected.key }
            add(SportsHeaderAction(if (selected == null) "Favourites" else if (favourite) "Remove favourite" else "Add favourite",
                if (selected == null || favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                selected == null && state.favouritesOnly) {
                if (selected == null) toggleFavourites() else toggleFavourite(selected)
            })
            if (state.tab == SportsTab.CATEGORIES && selected == null) add(SportsHeaderAction("Add playlist", Icons.Default.PlaylistAdd) {
                dismissLibraryError(); addOpen = true
            })
            if (selected == null && state.tab == SportsTab.EVENTS && !state.favouritesOnly) {
                add(SportsHeaderAction("Live events: $status", Icons.Default.Schedule, status != "Now & next") { statusOpen = true })
                add(SportsHeaderAction("Sport: $sport", Icons.Default.SportsCricket, sport !in setOf("All sports", "All")) { sportOpen = true })
            }
            if (showsAvailability) add(SportsHeaderAction("Availability: ${state.availabilityFilter.title}", Icons.Default.Flag,
                state.availabilityFilter != SportsAvailabilityFilter.ALL) { availabilityOpen = true })
            if (selected == null && (state.favouritesOnly || state.collection != null || state.tab == SportsTab.CHANNELS) && groups.size > 1)
                add(SportsHeaderAction("Group: $group", Icons.Default.FilterList, group != "All groups") { groupsOpen = true })
            add(SportsHeaderAction(if (state.source == SportsCatalogueSource.PLAYFY) "Refresh Playfy" else "Refresh sports", Icons.Default.Refresh, onClick = refresh))
            add(SportsHeaderAction(if (state.source == SportsCatalogueSource.PLAYFY) "Playfy servers" else "Sports servers", Icons.Default.Dns) { serversOpen = true; checkServers() })
            add(SportsHeaderAction("Stream compatibility: ${resolverMode.title}", Icons.Default.Settings) { resolverOpen = true })
        }
        SportsHeader(state.source.title, back, headerActions, backFocus, optionsAction,
            downFocus = if (selected != null) sourceFocus.firstOrNull() else gridEntry,
            menuItems = sportsMainTabs.map { tab ->
                com.nikhil.niktv.discovery.PortalMenuItem(tab.title, tab.navigationIcon, state.tab == tab,
                    Modifier.focusRequester(tabFocus.getValue(tab)).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            val target = if (selected != null) sourceFocus.firstOrNull() ?: backFocus else gridEntry
                            runCatching { target.requestFocus() }; true
                        } else false
                    }) { keyboard?.hide(); selectTab(tab) }
            }, query = query, setQuery = setQuery, searchFocus = searchFocus, closeSearch = ::closeSearch,
            searchPlaceholder = if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) "Find a team or event" else "Find a channel or category")
      Column(Modifier.weight(1f).padding(horizontal = if (compact) 14.dp else 24.dp).padding(top = 10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected != null) {
            SportsDetailHeader(selected, state.event, now)
            state.channelFlags[selected.key]?.let { flag -> SportsAvailabilityNotice(flag) {
                toggleChannelFlag(selected); restore(sourceFocus.getOrNull(lastSource ?: 0) ?: backFocus)
            } }
            Text("Choose a stream", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (state.loadingStreams) SportsLoading("Loading stream choices")
            state.streamError?.let { SportsFailure(it, refresh) }
            if (!state.loadingStreams && state.streams.isEmpty() && state.streamError == null) SportsEmpty("Streams are not available yet", "Refresh to check this channel again.")
            SportsGrid(state.streams, { "source:${it.title}:${state.streams.indexOf(it)}" }, streamsScroll, if (compact) 150 else 190,
                Modifier.weight(1f), headerFocus, requester = { index -> sourceFocus[index] }) { stream, index, modifier ->
                SportsSourceTile(stream, index, state.resolving == index, modifier, state.resolving == null || state.resolving == index) { play(index) }
            }
        } else {
            if (state.collection != null || state.favouritesOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.favouritesOnly) "Favourites" else state.collection?.title.orEmpty(), Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.collection?.custom == true) DiscoveryAction("Playlist options", Icons.Default.MoreHoriz, iconOnly = true) { contextCategory = state.collection }
            }
            if (compact && searchOpen) Box(Modifier.fillMaxWidth()) {
                SeriesEpisodeSearchField(query, setQuery, ::closeSearch, readOnly = !editing,
                modifier = Modifier.focusRequester(searchFocus).onPreviewKeyEvent {
                    if (!editing && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter)) { editing = true; keyboard?.show(); true } else false
                }, options = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                actions = KeyboardActions(onSearch = { editing = false; keyboard?.hide() }),
                placeholder = if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) "Find a team or event" else "Find a channel or category", closeLabel = "Close sports search")
                if (!editing) Box(Modifier.matchParentSize().padding(end = 40.dp).focusProperties { canFocus = false }
                    .clickable { editing = true; keyboard?.show() })
            }
            val activeQuery = query.takeIf { searchOpen }.orEmpty()
            if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) {
                val visible = remember(state.events, sport, status, activeQuery, now.epochSecond, state.channelFlags, state.availabilityFilter) {
                    state.events.filter { sportsEventMatches(it, sport, status, activeQuery, now) && state.availabilityFilter.includes(state.channelFlags[it.slug]) }
                        .sortedWith(compareByDescending<SportsEvent> { it.isHot }.thenBy { if (it.status(now) == SportsStatus.LIVE) 0 else 1 }.thenBy { it.start })
                }
                val sections = remember(visible, state.filters, sport) { sportsEventSections(visible, state.filters, sport) }
                SportsBrowseStatus(state, visible.size, "event", refresh)
                SportsEventRows(sections, Modifier.weight(1f), compact, headerFocus,
                    entry = gridEntry, requester = { tileRequester(it) }) { event, rowKey, modifier ->
                    SportsEventTile(event, now, state.favourites.any { it.key == event.slug }, modifier,
                        click = { lastTile = rowKey; open(event) }, menu = { contextItem = event.asChannel(); restoreTile = tileRequester(rowKey) }, flag = state.channelFlags[event.slug])
                }
            } else if (state.tab == SportsTab.CATEGORIES && state.collection == null && !state.favouritesOnly) {
                val categories = remember(state.categories, state.customPlaylists, activeQuery) {
                    (state.customPlaylists + state.categories).filter { activeQuery.isBlank() || it.title.contains(activeQuery.trim(), true) }
                }
                SportsBrowseStatus(state, categories.size, "category", refresh)
                SportsGrid(categories, { it.id }, eventsScroll, 190, Modifier.weight(1f), headerFocus,
                    entry = gridEntry, requester = { index -> tileRequester(categories[index].id) }) { category, _, modifier ->
                    SportsCategoryTile(category, modifier, click = { lastTile = category.id; openCategory(category) },
                        menu = { if (category.custom) { contextCategory = category; restoreTile = tileRequester(category.id) } })
                }
            } else {
                val channels = browseChannels
                val visible = remember(channels, activeQuery, group, state.channelFlags, state.availabilityFilter) { channels.filter {
                    state.availabilityFilter.includes(state.channelFlags[it.key]) && (group == "All groups" || it.group == group) &&
                        (activeQuery.isBlank() || it.title.contains(activeQuery.trim(), true) || it.group.contains(activeQuery.trim(), true))
                } }
                SportsBrowseStatus(state, visible.size, "channel", refresh)
                SportsGrid(visible, { it.key }, eventsScroll, 190, Modifier.weight(1f), headerFocus,
                    entry = gridEntry, requester = { index -> tileRequester(visible[index].key) }) { item, _, modifier ->
                    SportsChannelTile(item, state.favourites.any { it.key == item.key }, modifier,
                        click = { lastTile = item.key; openChannel(item) }, menu = { contextItem = item; restoreTile = tileRequester(item.key) }, flag = state.channelFlags[item.key])
                }
            }
        }
      }
      if (compact) SportsBottomNavigation(state.tab, { keyboard?.hide(); selectTab(it) }, tabFocus,
          content = if (selected != null) sourceFocus.firstOrNull() ?: backFocus
              else if (showsAvailability && state.availabilityFilter != SportsAvailabilityFilter.ALL &&
                  availableKeys.none { state.availabilityFilter.includes(state.channelFlags[it]) }) optionsAction
              else if (availableKeys.isNotEmpty()) gridEntry else null, portalEntryFocus = portalEntryFocus)
    }
    contextItem?.let { item -> SportsItemDialog(item, state.favourites.any { it.key == item.key },
        toggle = { toggleFavourite(item); contextItem = null; restore(restoreTile) }, close = { contextItem = null; restore(restoreTile) },
        flag = state.channelFlags[item.key], toggleFlag = {
            val remainsVisible = state.availabilityFilter.includes(if (item.key in state.channelFlags) null else SportsChannelFlag(SportsFlagReason.MANUAL, 0))
            toggleChannelFlag(item); contextItem = null
            restore(if (selected == null && !remainsVisible) optionsAction else restoreTile)
        }) }
    if (resolverOpen) SportsResolverModeDialog(resolverMode, {
        resolverPreferences.set(it); resolverMode = it; resolverOpen = false; restore(optionsAction)
    }, { resolverOpen = false; restore(optionsAction) })
    if (serversOpen) SportsServersDialog(servers, checkServers, { base ->
        runCatching { optionsAction.requestFocus() }
        closeServerChecks(); serversOpen = false; selectServer(base)
    }, {
        runCatching { optionsAction.requestFocus() }
        closeServerChecks(); serversOpen = false
    }, title = if (state.source == SportsCatalogueSource.PLAYFY) "Playfy servers" else "Sports servers")
    if (statusOpen) SportsBrowseChoiceDialog("Live events", status, SPORTS_STATUS_FILTERS, {
        closeFilters { setStatus(it); saveDefaultStatus(it) }
    }, { closeFilters() })
    if (sportOpen) SportsBrowseChoiceDialog("Sport", sport, sports, {
        closeFilters { setSport(it); saveDefaultSport(it) }
    }, { closeFilters() })
    if (groupsOpen) SportsGroupDialog(groups, group, { value -> closeFilters { group = value } }, { closeFilters() })
    if (availabilityOpen) SportsAvailabilityFilterDialog(state.availabilityFilter, {
        closeFilters { setAvailabilityFilter(it) }
    }, { closeFilters() })
    contextCategory?.let { category -> SportsRemovePlaylistDialog(category, {
        removePlaylist(category); contextCategory = null; restore(restoreTile ?: headerFocus)
    }, { contextCategory = null; restore(restoreTile ?: headerFocus) }) }
    var fileTitle by rememberSaveable { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { addOpen = false; importPlaylist(uri, fileTitle) }
    }
    if (addOpen) SportsAddPlaylistDialog(state.libraryError, state.importing,
        add = { name, url -> if (sportsNetworkUrl(url.trim())) addOpen = false; addPlaylist(name, url) },
        file = { name -> fileTitle = name; filePicker.launch(arrayOf("*/*")) }, close = { addOpen = false; dismissLibraryError(); restore(headerFocus) })
    if (state.libraryError != null && !addOpen) AlertDialog(onDismissRequest = dismissLibraryError, containerColor = SportsSurface,
        title = { Text("Unable to update playlists") }, text = { Text(state.libraryError) },
        confirmButton = { NikTvSecondaryActionButton(dismissLibraryError) { Text("Close") } })
}

@Composable private fun SportsBrowseStatus(state: SportsState, count: Int, noun: String, refresh: () -> Unit) {
    Text("$count ${if (count == 1) noun else if (noun == "category") "categories" else "${noun}s"}", color = SportsMuted, style = MaterialTheme.typography.labelLarge)
    if (state.loading) SportsLoading("Loading ${if (noun == "category") "categories" else "${noun}s"}")
    state.error?.let { SportsFailure(it, refresh) }
    if (!state.loading && count == 0 && state.error == null) SportsEmpty("Nothing to show",
        if (state.availabilityFilter != SportsAvailabilityFilter.ALL && (noun != "category")) "Try another availability filter, or hold a channel to change its flag."
        else if (state.favouritesOnly) "Hold a channel or event to add it to Favourites." else "Try another filter or refresh this collection.")
}

/** Exact row/column movement, including targets virtualized outside the viewport. */
@Composable internal fun <T> SportsGrid(rows: List<T>, key: (T) -> String, scroll: LazyGridState, minWidth: Int,
    modifier: Modifier, above: FocusRequester, below: FocusRequester? = null, entry: FocusRequester? = null,
    requester: (Int) -> FocusRequester, tile: @Composable (T, Int, Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) { com.nikhil.niktv.discovery.DiscoveryScrollCoordinator(scope) }
    var movement by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(coordinator) { onDispose { movement?.cancel(); coordinator.cancel() } }
    BoxWithConstraints(modifier) {
        val columns = ((maxWidth.value + 12) / (minWidth + 12)).toInt().coerceIn(1, 8)
        com.nikhil.niktv.discovery.DiscoveryVerticalFocusViewport(coordinator) {
            // Keep entry attached to the viewport, including while item zero is offscreen.
            LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize()
                .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
                .onFocusChanged { if (!it.hasFocus) { movement?.cancel(); coordinator.cancel() } }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(5.dp, 4.dp, 5.dp, 18.dp)) {
                // Only real rows belong here. A loading-time footer at index zero
                // becomes the retained scroll anchor when sources arrive above it.
                itemsIndexed(rows, key = { index, item -> "${key(item)}:$index" }) { index, item ->
                    tile(item, index, Modifier.fillMaxWidth()
                        .focusRequester(requester(index)).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) return@onPreviewKeyEvent false
                            val target = sportsGridTarget(index, columns, rows.size, event.key)
                            when {
                                target == -1 -> { movement?.cancel(); coordinator.cancel(); runCatching { above.requestFocus() } }
                                target == rows.size -> {
                                    movement?.cancel()
                                    coordinator.cancel()
                                    below?.let { runCatching { it.requestFocus() } }
                                }
                                target != index -> {
                                    movement?.cancel()
                                    movement = coordinator.moveGrid(scroll, target, columns, alignRow = false, reduceMotion = false) {
                                        runCatching { requester(target).requestFocus() }.getOrDefault(false)
                                    }
                                }
                            }
                            true
                        })
                }
            }
        }
    }
}

internal fun sportsGridTarget(index: Int, columns: Int, count: Int, key: Key): Int = when (key) {
    Key.DirectionUp -> if (index < columns) -1 else index - columns
    Key.DirectionDown -> if (index / columns < (count - 1) / columns) (index + columns).coerceAtMost(count - 1) else count
    Key.DirectionLeft -> if (index % columns > 0) index - 1 else index
    Key.DirectionRight -> if (index % columns < columns - 1 && index + 1 < count) index + 1 else index
    else -> index
}

@Composable internal fun SportsLoading(label: String) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(label, color = SportsMuted)
    }
}
@Composable internal fun SportsEmpty(title: String, description: String) {
    Surface(Modifier.fillMaxWidth(), shape = SportsShape, color = SportsSurface, contentColor = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = SportsMuted) }
    }
}
@Composable internal fun SportsFailure(message: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = SportsShape, color = SportsSurface, contentColor = Color.White, border = BorderStroke(1.dp, Color(0xFF63313A))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message, color = SportsMuted); NikTvSecondaryActionButton(retry) { Text("Retry") } }
    }
}
