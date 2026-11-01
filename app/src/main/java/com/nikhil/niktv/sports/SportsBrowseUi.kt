package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.stateDescription
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
    toggleFavourites: () -> Unit = {}, adjacentChannel: (Int) -> Unit = {},
    addPlaylist: (String, String) -> Unit = { _, _ -> }, importPlaylist: (android.net.Uri, String) -> Unit = { _, _ -> },
    removePlaylist: (SportsCategory) -> Unit = {}, dismissLibraryError: () -> Unit = {},
    toggleChannelFlag: (SportsChannel) -> Unit = {}, setAvailabilityFilter: (SportsAvailabilityFilter) -> Unit = {}) {
    val compact = sportsPhoneLayout()
    val scope = rememberCoroutineScope()
    val tabFocus = remember { SportsTab.entries.associateWith { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    val optionsAction = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }
    val gridEntry = remember { FocusRequester() }
    val relatedEntry = remember { FocusRequester() }
    val groupAction = remember { FocusRequester() }
    val availabilityAction = remember { FocusRequester() }
    val sourceFocus = remember(state.streams) { state.streams.map { FocusRequester() } }
    val selected = state.selected
    var relatedExpanded by rememberSaveable(selected?.key) { mutableStateOf(false) }
    val hasGroups = (if (state.favouritesOnly) state.favourites else state.collectionChannels).any { it.group.isNotBlank() }
    val headerFocus = tabFocus.getValue(state.tab)
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var editing by remember { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var groupsOpen by remember { mutableStateOf(false) }
    var availabilityOpen by remember { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf("All groups") }
    var contextItem by remember { mutableStateOf<SportsChannel?>(null) }
    var contextCategory by remember { mutableStateOf<SportsCategory?>(null) }
    var lastTile by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreTile by remember { mutableStateOf<FocusRequester?>(null) }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    val originFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun restore(requester: FocusRequester?) { scope.launch { withFrameNanos { }; requester?.let { runCatching { it.requestFocus() } } } }
    fun closeSearch() { editing = false; searchOpen = false; setQuery(""); keyboard?.hide(); restore(optionsAction) }
    BackHandler(searchOpen && selected == null) { closeSearch() }
    LaunchedEffect(searchOpen) { if (searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() }; if (editing) keyboard?.show() } }
    LaunchedEffect(state.collection?.id, state.importing, state.libraryError) {
        if (addOpen && !state.importing && state.libraryError == null && state.collection?.custom == true) addOpen = false
    }
    val availableKeys = if (state.favouritesOnly) state.favourites.map { it.key }
        else when { state.collection != null -> state.collectionChannels.map { it.key }
            state.tab == SportsTab.EVENTS -> state.events.map { it.slug }
            state.tab == SportsTab.CHANNELS -> state.channels.map { it.key }
            else -> (state.categories + state.customPlaylists).map { it.id } }
    val showsAvailability = selected == null && (state.tab != SportsTab.CATEGORIES || state.collection != null || state.favouritesOnly)
    val originKey = if (state.tab == SportsTab.EVENTS) lastTile?.substringAfterLast(':') else lastTile
    val restoreOrigin = state.restoreBrowseFocus && originKey != null && originKey in availableKeys &&
        (!showsAvailability || state.availabilityFilter.includes(state.channelFlags[originKey]))
    val initial = if (selected != null) sourceFocus.getOrNull(lastSource ?: 0) ?: backFocus else if (restoreOrigin) originFocus else headerFocus
    fun tileRequester(key: String): FocusRequester = if (restoreOrigin && key == lastTile) originFocus else tileFocus.getOrPut(key) { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).windowInsetsPadding(WindowInsets.safeDrawing)
        .initialFocusEntry(initial).padding(horizontal = if (compact) 14.dp else 24.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val headerActions = buildList {
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
            add(SportsHeaderAction("Refresh sports", Icons.Default.Refresh, onClick = refresh))
        }
        SportsHeader("Sports", back, headerActions, backFocus, optionsAction) { modifier ->
            Row(modifier.horizontalScroll(rememberScrollState()).focusGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SportsTab.entries.forEach { tab ->
                    DiscoveryAction(tab.title, selected = state.tab == tab,
                        modifier = Modifier.focusRequester(tabFocus.getValue(tab)).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            val target = when { selected != null -> sourceFocus.firstOrNull() ?: backFocus
                                state.tab == SportsTab.EVENTS && !state.favouritesOnly -> firstFilter
                                showsAvailability && state.availabilityFilter != SportsAvailabilityFilter.ALL &&
                                    availableKeys.none { state.availabilityFilter.includes(state.channelFlags[it]) } -> availabilityAction
                                hasGroups -> groupAction
                                else -> gridEntry }
                            runCatching { target.requestFocus() }; true
                        }, onClick = { keyboard?.hide(); selectTab(tab) })
                }
            }
        }
        if (selected != null) {
            SportsDetailHeader(selected, state.event, state.detail, now, adjacentChannel)
            state.channelFlags[selected.key]?.let { flag -> SportsAvailabilityNotice(flag) {
                toggleChannelFlag(selected); restore(sourceFocus.getOrNull(lastSource ?: 0) ?: backFocus)
            } }
            Text("Choose a stream", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (state.loadingStreams) SportsLoading("Loading stream choices")
            state.streamError?.let { SportsFailure(it, refresh) }
            if (!state.loadingStreams && state.streams.isEmpty() && state.streamError == null) SportsEmpty("Streams are not available yet", "Refresh to check this channel again.")
            SportsGrid(state.streams, { "source:${it.title}:${state.streams.indexOf(it)}" }, streamsScroll, if (compact) 150 else 190,
                Modifier.weight(1f), headerFocus, relatedEntry.takeIf { state.detail.related.isNotEmpty() },
                requester = { index -> sourceFocus[index] }, footer = {
                    if (state.detail.related.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { relatedExpanded = !relatedExpanded }, color = SportsSurface, contentColor = SportsMuted,
                            shape = SportsShape, modifier = Modifier.fillMaxWidth().focusRequester(relatedEntry)
                                .remoteFocusFrame(SportsShape)
                                .semantics { stateDescription = if (relatedExpanded) "Expanded" else "Collapsed" }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) false
                                    else { scope.launch {
                                        if (state.streams.isNotEmpty()) streamsScroll.scrollToItem(state.streams.lastIndex)
                                        withFrameNanos { }; runCatching { (sourceFocus.lastOrNull() ?: backFocus).requestFocus() }
                                    }; true }
                                }) {
                            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Related channels (${state.detail.related.size})", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Icon(if (relatedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    if (relatedExpanded) "Hide related channels" else "Show related channels", Modifier.size(20.dp))
                            }
                        }
                        AnimatedVisibility(relatedExpanded) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp, 2.dp, 4.dp, 8.dp)) {
                                items(state.detail.related, key = { it.key }) { item ->
                                    SportsChannelTile(item, state.favourites.any { it.key == item.key }, Modifier.width(152.dp)
                                        .focusProperties { up = relatedEntry },
                                        click = { openChannel(item) }, menu = { contextItem = item; restoreTile = relatedEntry }, flag = state.channelFlags[item.key])
                                }
                            }
                        }
                    }
                }) { stream, index, modifier ->
                SportsSourceTile(stream, index, state.resolving == index, modifier, state.resolving == null || state.resolving == index) { play(index) }
            }
        } else {
            if (state.collection != null || state.favouritesOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.favouritesOnly) "Favourites" else state.collection?.title.orEmpty(), Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.collection?.custom == true) DiscoveryAction("Playlist options", Icons.Default.MoreHoriz, iconOnly = true) { contextCategory = state.collection }
            }
            if (searchOpen) Box(Modifier.fillMaxWidth()) {
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
            if (showsAvailability && (state.tab != SportsTab.EVENTS || state.favouritesOnly)) DiscoveryAction("Availability: ${state.availabilityFilter.title}", Icons.Default.Flag,
                selected = state.availabilityFilter != SportsAvailabilityFilter.ALL,
                modifier = Modifier.focusRequester(availabilityAction).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                        val target = if (event.key == Key.DirectionUp) headerFocus else if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) firstFilter
                            else if (hasGroups) groupAction else gridEntry
                        runCatching { target.requestFocus() }; true
                    } else false
                }) { availabilityOpen = true }
            if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportsDropdown("Live events", status, SPORTS_STATUS_FILTERS.map { it to it },
                        select = { setStatus(it); saveDefaultStatus(it) }, modifier = Modifier.weight(1f).focusRequester(firstFilter))
                    DiscoveryAction("Availability: ${state.availabilityFilter.title}", Icons.Default.Flag, iconOnly = compact,
                        selected = state.availabilityFilter != SportsAvailabilityFilter.ALL,
                        modifier = Modifier.focusRequester(availabilityAction)) { availabilityOpen = true }
                }
                val filters = remember(state.events, state.filters, defaults.sport) {
                    if (state.filters.isNotEmpty()) listOf(SportsEventFilter("all", "All sports")) + state.filters.filterNot { it.title.equals("All", true) }
                    else (listOf("All sports", defaults.sport) + state.events.map { it.sport }.filter(String::isNotBlank).sorted()).distinct().map { SportsEventFilter(it, it) }
                }
                val visible = remember(state.events, status, activeQuery, now.epochSecond, state.channelFlags, state.availabilityFilter) {
                    state.events.filter { sportsEventMatches(it, "All sports", status, activeQuery, now) && state.availabilityFilter.includes(state.channelFlags[it.slug]) }
                        .sortedWith(compareByDescending<SportsEvent> { it.isHot }.thenBy { if (it.status(now) == SportsStatus.LIVE) 0 else 1 }.thenBy { it.start })
                }
                val sections = remember(visible, filters) { sportsEventSections(visible, filters) }
                SportsBrowseStatus(state, visible.size, "event", refresh)
                SportsEventRows(sections, Modifier.weight(1f), compact, firstFilter,
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
                val channels = if (state.favouritesOnly) state.favourites else if (state.collection != null) state.collectionChannels else state.channels
                val groups = remember(channels) { listOf("All groups") + channels.map { it.group }.filter(String::isNotBlank).distinct() }
                if (groups.size > 1) DiscoveryAction("Group: $group", Icons.Default.FilterList, selected = group != "All groups",
                    modifier = Modifier.focusRequester(groupAction).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                            runCatching { (if (event.key == Key.DirectionUp) availabilityAction else gridEntry).requestFocus() }; true
                        } else false
                    }) { groupsOpen = true }
                val visible = remember(channels, activeQuery, group, state.channelFlags, state.availabilityFilter) { channels.filter {
                    state.availabilityFilter.includes(state.channelFlags[it.key]) && (group == "All groups" || it.group == group) &&
                        (activeQuery.isBlank() || it.title.contains(activeQuery.trim(), true) || it.group.contains(activeQuery.trim(), true))
                } }
                SportsBrowseStatus(state, visible.size, "channel", refresh)
                SportsGrid(visible, { it.key }, eventsScroll, 170, Modifier.weight(1f), if (groups.size > 1) groupAction else availabilityAction,
                    entry = gridEntry, requester = { index -> tileRequester(visible[index].key) }) { item, _, modifier ->
                    SportsChannelTile(item, state.favourites.any { it.key == item.key }, modifier,
                        click = { lastTile = item.key; openChannel(item) }, menu = { contextItem = item; restoreTile = tileRequester(item.key) }, flag = state.channelFlags[item.key])
                }
                if (groupsOpen) SportsGroupDialog(groups, group, { group = it; groupsOpen = false; restore(groupAction) }, { groupsOpen = false; restore(groupAction) })
            }
        }
      }
      if (compact) SportsBottomNavigation(state.tab, { keyboard?.hide(); selectTab(it) }, tabFocus,
          content = if (selected != null) sourceFocus.firstOrNull() ?: backFocus
              else if (state.tab == SportsTab.EVENTS && !state.favouritesOnly) firstFilter
              else if (availableKeys.isNotEmpty()) gridEntry else optionsAction)
    }
    contextItem?.let { item -> SportsItemDialog(item, state.favourites.any { it.key == item.key },
        toggle = { toggleFavourite(item); contextItem = null; restore(restoreTile) }, close = { contextItem = null; restore(restoreTile) },
        flag = state.channelFlags[item.key], toggleFlag = {
            val remainsVisible = state.availabilityFilter.includes(if (item.key in state.channelFlags) null else SportsChannelFlag(SportsFlagReason.MANUAL, 0))
            toggleChannelFlag(item); contextItem = null
            restore(if (selected == null && !remainsVisible) availabilityAction else restoreTile)
        }) }
    if (availabilityOpen) SportsAvailabilityFilterDialog(state.availabilityFilter, {
        setAvailabilityFilter(it); availabilityOpen = false; restore(availabilityAction)
    }, { availabilityOpen = false; restore(availabilityAction) })
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
    requester: (Int) -> FocusRequester, footer: (@Composable () -> Unit)? = null, tile: @Composable (T, Int, Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    BoxWithConstraints(modifier) {
        val columns = ((maxWidth.value + 10) / (minWidth + 10)).toInt().coerceIn(1, 8)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(5.dp, 4.dp, 5.dp, 18.dp)) {
            itemsIndexed(rows, key = { index, item -> "${key(item)}:$index" }) { index, item ->
                tile(item, index, Modifier.fillMaxWidth().then(if (index == 0 && entry != null) Modifier.focusRequester(entry) else Modifier)
                    .focusRequester(requester(index)).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) return@onPreviewKeyEvent false
                        val target = sportsGridTarget(index, columns, rows.size, event.key)
                        when {
                            target == -1 -> { movement?.cancel(); runCatching { above.requestFocus() } }
                            target == rows.size -> {
                                movement?.cancel()
                                if (below != null) movement = scope.launch {
                                    if (footer != null) scroll.scrollToItem(rows.size)
                                    withFrameNanos { }; runCatching { below.requestFocus() }
                                }
                            }
                            target != index -> {
                                movement?.cancel()
                                movement = scope.launch {
                                    val visible = scroll.layoutInfo.visibleItemsInfo
                                    if (visible.none { it.index == target }) {
                                        val rowCount = visible.map { it.row }.distinct().size.coerceAtLeast(1)
                                        val first = if (target > (visible.lastOrNull()?.index ?: -1))
                                            ((target / columns - rowCount + 1).coerceAtLeast(0) * columns) else target
                                        scroll.scrollToItem(first)
                                    }
                                    withFrameNanos { }; runCatching { requester(target).requestFocus() }
                                }
                            }
                        }
                        true
                    })
            }
            if (footer != null) item(key = "sports-grid-footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
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
