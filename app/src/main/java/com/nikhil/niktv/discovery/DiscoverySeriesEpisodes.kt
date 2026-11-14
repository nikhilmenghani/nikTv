package com.nikhil.niktv.discovery

import com.nikhil.niktv.discovery.components.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.SeriesStartSeason
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverySeriesEpisodes(state: DiscoveryState, startSeason: SeriesStartSeason,
    seasonEntry: FocusRequester, loadSeason: (Int) -> Unit,
    play: (DiscoveryCard, Int, Int) -> Unit, download: (DiscoveryCard, Int, Int) -> Unit,
    onFocus: () -> Unit, up: () -> Boolean, down: () -> Boolean,
    modifier: Modifier = Modifier, lastWatched: Pair<Int, Int>? = null, loadInitialSeason: Boolean = true,
    onNewestFirstChanged: (Boolean) -> Unit = {},
    header: (@Composable (@Composable () -> Unit) -> Unit)? = null) {
    val card = state.detail ?: return
    val alternateSource = LocalPlayAlternateSource.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val wide = configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(configuration) ||
        configuration.screenWidthDp > configuration.screenHeightDp
    val seasons = state.details?.seasons.orEmpty()
    val chosen = state.season ?: discoveryInitialSeriesSeason(state.details, startSeason, lastWatched?.first)
    var listView by rememberSaveable(state.profile, card.key) { mutableStateOf(true) }
    val shortWindow = configuration.usesShortWideLayout(context.isTvLikeDevice(configuration))
    val gridColumns = seriesEpisodeGridColumns(shortWindow)
    val listColumns = seriesEpisodeListColumns(configuration.smallestScreenWidthDp, configuration.screenWidthDp, context.isTvLikeDevice(configuration))
    val twoColumnList = listView && listColumns == 2
    val viewportColumns = if (twoColumnList) listColumns else if (wide && !listView) gridColumns else 1
    var pendingLayoutEpisode by remember(card.key) { mutableStateOf<Int?>(null) }
    var newest by rememberSaveable(state.profile, card.key) { mutableStateOf(true) }
    LaunchedEffect(newest) { onNewestFirstChanged(newest) }
    var searchOpen by rememberSaveable(state.profile, card.key) { mutableStateOf(false) }
    var query by rememberSaveable(state.profile, card.key) { mutableStateOf("") }
    // A season change must not reuse another season's episode key or viewport.
    var focusedBySeason by rememberSaveable(state.profile, card.key) { mutableStateOf(mapOf<Int, String>()) }
    var positionedResumes by rememberSaveable(state.profile, card.key) { mutableStateOf(mapOf<Int, String>()) }
    var pendingEpisodeEntry by rememberSaveable(state.profile, card.key) { mutableStateOf<String?>(null) }
    var interacted by remember(card.key) { mutableStateOf(false) }
    fun focusedEpisode() = chosen?.let(focusedBySeason::get)
    fun rememberEpisode(identity: String?, season: Int? = chosen) {
        if (season != null) focusedBySeason = if (identity == null) focusedBySeason - season else focusedBySeason + (season to identity)
    }
    val search = remember { FocusRequester() }
    val searchInput = remember { FocusRequester() }
    var searchInputFocused by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    val toolbarSearch = search
    val sort = remember { FocusRequester() }
    val layout = remember { FocusRequester() }
    val retrySeason = remember { FocusRequester() }
    var requestedSeasonFocus by remember(card.key) { mutableStateOf<Int?>(null) }
    val seasonHost = LocalView.current
    LaunchedEffect(requestedSeasonFocus, chosen, state.loading, state.episodes) {
        val requested = requestedSeasonFocus ?: return@LaunchedEffect
        repeat(30) { withFrameNanos { }
            if (seasonHost.hasWindowFocus() && runCatching { seasonEntry.requestFocus() }.getOrDefault(false)) {
                if (chosen == requested && "season" !in state.loading) requestedSeasonFocus = null
                return@LaunchedEffect
            }
        }
    }
    val requesters = remember(card.key) { mutableMapOf<String, FocusRequester>() }
    val episodeState = rememberSeriesEpisodeViewport("${state.profile}:${card.key}:$chosen", viewportColumns)
    LaunchedEffect(pendingLayoutEpisode, viewportColumns) {
        val firstEpisode = pendingLayoutEpisode ?: return@LaunchedEffect
        withFrameNanos { }
        episodeState.scrollToItem(seriesEpisodeViewportIndex(firstEpisode, viewportColumns), 0)
        pendingLayoutEpisode = null
    }
    var scrollReset by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollReset) {
        if (scrollReset > 0) { withFrameNanos { }; episodeState.scrollToItem(0) }
    }
    val scope = rememberCoroutineScope()
    var entryJob by remember { mutableStateOf<Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val episodes = remember(state.episodes, chosen, query, searchOpen, newest) {
        val filtered = state.episodes.filter { (chosen == null || it.season == chosen) &&
            episodeMatchesSearch(it.title, it.overview, it.number, if (searchOpen) query else "") }
        if (newest) filtered.sortedByDescending { it.number } else filtered.sortedBy { it.number }
    }
    DisposableEffect(card.key, chosen, episodes, listView) {
        onDispose { entryJob?.cancel() }
    }
    val searchInputMode = LocalInputModeManager.current
    fun closeSearch() {
        searchInputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        runCatching { toolbarSearch.requestFocus() }
        searchOpen = false; searchEditing = false; keyboard?.hide()
    }
    BackHandler(searchOpen, ::closeSearch)
    SeriesSearchKeyboardDismiss(searchOpen && searchEditing, ::closeSearch)
    LaunchedEffect(searchOpen) {
        if (searchOpen) repeat(12) {
            withFrameNanos { }
            if (runCatching { searchInput.requestFocus() }.getOrDefault(false)) {
                if (searchEditing) { withFrameNanos { }; keyboard?.show() }
                return@LaunchedEffect
            }
        }
    }
    fun focusEpisode(index: Int): Boolean {
        if (index !in episodes.indices) return false
        keyboard?.hide()
        val target = episodes[index]
        val targetKey = "${target.season}:${target.number}"
        rememberEpisode(targetKey)
        entryJob?.cancel()
        val viewport = episodeState.layoutInfo
        val rowIndex = seriesEpisodeViewportIndex(index, viewportColumns)
        val visible = viewport.visibleItemsInfo.firstOrNull { it.index == rowIndex }
        val fullyVisible = visible != null && visible.offset >= viewport.viewportStartOffset &&
            visible.offset + visible.size <= viewport.viewportEndOffset
        if (fullyVisible && runCatching { requesters[targetKey]?.requestFocus() == true }.getOrDefault(false)) return true
        entryJob = scope.launch {
            episodeState.revealSeriesEpisodeRow(rowIndex)
            repeat(20) {
                withFrameNanos { }
                if (runCatching { requesters[targetKey]?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
        return true
    }
    fun focusSeasonChoice(): Boolean = if (seasons.isNotEmpty())
        runCatching { seasonEntry.requestFocus() }.getOrDefault(false) else false
    fun enterEpisodes(fromStart: Boolean = false) {
        val index = if (fromStart) 0 else episodes.indexOfFirst { "${it.season}:${it.number}" == focusedEpisode() }.coerceAtLeast(0)
        focusEpisode(index)
    }
    LaunchedEffect(episodes, lastWatched, chosen, pendingEpisodeEntry) {
        if (episodes.isNotEmpty() && lastWatched != null && lastWatched.first == chosen) {
            val identity = "${lastWatched.first}:${lastWatched.second}"
            val index = episodes.indexOfFirst { it.number == lastWatched.second }
            if (index >= 0 && positionedResumes[lastWatched.first] != identity) {
                if (!interacted) episodeState.scrollToItem(seriesEpisodeViewportIndex((index - 1).coerceAtLeast(0), viewportColumns))
                if (!interacted || focusedEpisode() == null) rememberEpisode(identity)
                positionedResumes = positionedResumes + (lastWatched.first to identity)
            }
        }
        val pending = pendingEpisodeEntry
        val index = episodes.indexOfFirst { "${it.season}:${it.number}" == pending }
        if (pending != null && index >= 0) {
            pendingEpisodeEntry = null
            focusEpisode(index)
        }
    }
    var initialSeasonRequested by rememberSaveable(state.profile, card.key) { mutableStateOf(false) }
    LaunchedEffect(state.details?.card?.key, loadInitialSeason) {
        if (loadInitialSeason && !initialSeasonRequested) chosen?.let {
            initialSeasonRequested = true
            loadSeason(it)
        }
    }
    Column(modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in listOf(
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            interacted = true
            entryJob?.cancel()
        }
        false
    }) {
        val toolbar: @Composable () -> Unit = {
        SeriesEpisodeToolbar(
            season = { SeriesSeasonDropdown(seasons, chosen, seasonEntry, choose = { season ->
                interacted = true; entryJob?.cancel(); requestedSeasonFocus = season
                loadSeason(season)
            }) },
            count = seriesEpisodeCountLabel(episodes.size, false, searchOpen && query.isNotBlank()),
            compact = !wide, inline = header != null && !wide, withinPanel = wide, newestFirst = newest, onNewestFirst = {
                newest = it; rememberEpisode(null)
            },
            listView = listView, onListView = {
                pendingLayoutEpisode = episodeState.firstVisibleItemIndex * viewportColumns
                listView = it
            }, searchOpen = searchOpen,
            onSearch = { if (searchOpen) closeSearch() else { searchEditing = true; searchOpen = true } },
            searchRequester = toolbarSearch, sortRequester = sort, layoutRequester = layout,
            onSortApplied = { enterEpisodes(fromStart = true) },
            onGoToEpisode = { number ->
                episodes.firstOrNull { it.number == number }?.let { rememberEpisode("${it.season}:${it.number}"); enterEpisodes() }
            },
            onLastWatched = lastWatched?.let { watched -> {
                rememberEpisode("${watched.first}:${watched.second}", watched.first)
                if (chosen != watched.first) {
                    pendingEpisodeEntry = "${watched.first}:${watched.second}"
                    loadSeason(watched.first)
                } else enterEpisodes()
            } },
            beforeAction = { searchEditing = false; keyboard?.hide() },
            searchField = {
                SeriesEpisodeSearchField(query, { query = it; scrollReset++ }, ::closeSearch,
                    clear = { query = ""; scrollReset++ },
                    readOnly = !searchEditing,
                    activate = { searchEditing = true; scope.launch { withFrameNanos { }; searchInput.requestFocus(); keyboard?.show() } },
                    modifier = Modifier.focusRequester(searchInput).onFocusChanged { searchInputFocused = it.isFocused },
                    options = KeyboardOptions(imeAction = ImeAction.Search),
                    actions = KeyboardActions(onSearch = { searchEditing = false; keyboard?.hide() }))
            }, modifier = Modifier.then(if (header != null || wide) Modifier else Modifier.padding(horizontal = 24.dp, vertical = 12.dp)).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> {
                        searchEditing = false
                        keyboard?.hide()
                        if (!wide && searchOpen && searchInputFocused) runCatching { toolbarSearch.requestFocus() }.getOrDefault(false) else up()
                    }
                    Key.DirectionDown -> {
                        searchEditing = false; keyboard?.hide()
                        if (!wide && searchOpen && !searchInputFocused) runCatching { searchInput.requestFocus() }
                        else if (state.errors["season"] != null) runCatching { retrySeason.requestFocus() }
                        else if (episodes.isEmpty()) focusSeasonChoice()
                        else enterEpisodes()
                        true
                    }
                    else -> false
                }
            })
        }
        val episodeBody: @Composable ColumnScope.() -> Unit = {
        state.errors["season"]?.let { message ->
            DiscoveryAction(message, modifier = Modifier.focusRequester(retrySeason).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> runCatching { toolbarSearch.requestFocus() }.getOrDefault(false)
                    Key.DirectionDown -> focusSeasonChoice()
                    else -> false
                }
            }) {
                // The error action disappears as soon as retry begins; transfer
                // focus to a surviving control before invoking that mutation.
                runCatching { toolbarSearch.requestFocus() }
                chosen?.let(loadSeason)
            }
        }
        if ("season" in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if ((!listView || twoColumnList) && episodes.isEmpty() && "season" !in state.loading) Text(
            if (searchOpen && query.isNotBlank()) "No episodes match your search." else "No episodes are available for this season.",
            Modifier.padding(24.dp))
        val tile: @Composable (Int, DiscoveryEpisode, Modifier) -> Unit = { index, episode, cell ->
            val identity = "${episode.season}:${episode.number}"
            val requester = remember(identity) { requesters.getOrPut(identity) { FocusRequester() } }
            var options by remember { mutableStateOf(false) }
            var description by remember { mutableStateOf(false) }
            var hadPopup by remember { mutableStateOf(false) }
            val host = LocalView.current
            LaunchedEffect(options, description) {
                if (options || description) hadPopup = true
                else if (hadPopup) {
                    hadPopup = false
                    repeat(30) { withFrameNanos { }; if (host.hasWindowFocus() && runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
                }
            }
            Box(cell) {
            DiscoveryEpisodeTile(episode.title, episode.still, "S${episode.season} · E${episode.number}", episode.date.orEmpty(),
                listView = listView, compactList = twoColumnList, overview = episode.overview, resume = lastWatched == (episode.season to episode.number),
                modifier = (if (listView || wide) Modifier.fillMaxWidth() else Modifier.width(200.dp)).focusRequester(requester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else {
                        val cursor = episodes.indexOfFirst { "${it.season}:${it.number}" == focusedEpisode() }.takeIf { it >= 0 } ?: index
                        if (viewportColumns > 1) {
                            val target = when (event.key) {
                                Key.DirectionLeft -> seriesEpisodeGridMove(cursor, episodes.size, viewportColumns, dx = -1)
                                Key.DirectionRight -> seriesEpisodeGridMove(cursor, episodes.size, viewportColumns, dx = 1)
                                Key.DirectionUp -> seriesEpisodeGridMove(cursor, episodes.size, viewportColumns, dy = -1)
                                Key.DirectionDown -> seriesEpisodeGridMove(cursor, episodes.size, viewportColumns, dy = 1)
                                Key.Menu -> null
                                else -> -1
                            }
                            if (target == -1) false
                            else if (target == null) runCatching { (if (event.key == Key.DirectionLeft && seasons.isNotEmpty()) seasonEntry else sort).requestFocus() }.getOrDefault(false)
                            else { focusEpisode(target); true }
                        } else when (event.key) {
                            Key.DirectionLeft -> if (listView && seasons.isNotEmpty()) runCatching { seasonEntry.requestFocus() }.getOrDefault(false)
                                else if (!listView) { focusEpisode(cursor - 1); true } else false
                            Key.DirectionRight -> if (!listView) { focusEpisode(cursor + 1); true } else false
                            Key.DirectionUp -> if (!listView && seasons.isNotEmpty()) runCatching { sort.requestFocus() }.getOrDefault(false)
                                else if (!listView || cursor == 0) runCatching { (if (!wide && searchOpen) searchInput else toolbarSearch).requestFocus() }.getOrDefault(false)
                                else focusEpisode(cursor - 1)
                            Key.DirectionDown -> if (!listView || cursor == episodes.lastIndex) { down(); true }
                                else focusEpisode(cursor + 1)
                            else -> false
                        }
                        }
                    },
                onFocus = { rememberEpisode(identity); onFocus() }, onClick = { play(card, episode.season, episode.number) },
                onLongClick = { runCatching { requester.requestFocus() }; options = true })
            SeriesActionsMenu(options, { options = false }) {
                    alternateSource?.let { playAlternate ->
                        EpisodeSortMenuItem("Play from another source", Icons.Default.PlayArrow, onClick = {
                            options = false
                            playAlternate(AlternateSourceRequest(card.media.copy(
                                id = "${card.media.id}:s${episode.season}:e${episode.number}",
                                title = episode.title, logo = episode.still ?: card.poster,
                                description = episode.overview, seasonNumber = episode.season,
                                episodeNumber = episode.number), CatalogType.SERIES, card.media,
                                episode.season, episode.number))
                        })
                    }
                    EpisodeSortMenuItem("Download episode", Icons.Default.Download, onClick = { options = false; download(card, episode.season, episode.number) })
                    if (!episode.overview.isNullOrBlank()) EpisodeSortMenuItem("View description", Icons.Default.Info,
                        onClick = { options = false; description = true })
            }
            }
            if (description) FullDescriptionDialog(episode.title, episode.overview.orEmpty()) { description = false }
        }
        val counts = state.details?.seasonEpisodeCounts.orEmpty().mapValues { "${it.value} episodes" }.toMutableMap()
        if (chosen != null && "season" !in state.loading) counts[chosen] = "${state.episodes.size} episodes"
        // Local function references compare equal even when their captured episode
        // list changes. A lambda keeps the sidebar callback current after loading.
        if (twoColumnList) SeriesEpisodeGrid(episodes, listColumns, episodeState,
            key = { "${it.season}:${it.number}" }, modifier = Modifier.weight(1f).fillMaxWidth(), visibleRows = 3, content = tile)
        else if (listView) Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 0.dp else 24.dp)
            .weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f), state = episodeState, contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(if (LocalConfiguration.current.usesShortWideLayout(LocalContext.current.isTvLikeDevice(LocalConfiguration.current))) 4.dp else 8.dp)) {
                if (episodes.isEmpty()) item("episode-status") {
                    Text(if ("season" in state.loading) "Loading episodes…"
                        else if (searchOpen && query.isNotBlank()) "No episodes match your search."
                        else "No episodes are available for this season.", Modifier.padding(16.dp))
                }
                itemsIndexed(episodes, key = { _, episode -> "${episode.season}:${episode.number}" }, ) { index, episode -> tile(index, episode, Modifier) }
            }
        } else if (wide) SeriesEpisodeGrid(episodes, gridColumns, episodeState,
            key = { "${it.season}:${it.number}" }, modifier = Modifier.weight(1f).fillMaxWidth(), content = tile)
        else if (!listView) LazyRow(modifier = Modifier.weight(1f), state = episodeState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(episodes, key = { _, episode -> "${episode.season}:${episode.number}" }, ) { index, episode -> tile(index, episode, Modifier) }
        }
        }
        if (wide) {
            header?.invoke({})
            val short = configuration.usesShortWideLayout(context.isTvLikeDevice(configuration))
            SeriesEpisodePanel(Modifier.weight(1f).fillMaxWidth()
                .padding(start = if (short) 16.dp else 24.dp, end = if (short) 16.dp else 24.dp,
                    bottom = if (short) 8.dp else 16.dp), compact = short,
                loading = "season" in state.loading || lastWatched?.takeIf { it.first == chosen }?.let {
                    positionedResumes[it.first] != "${it.first}:${it.second}" } == true,
                loadingColumns = viewportColumns, toolbar = toolbar) {
                Column(Modifier.fillMaxSize(), content = episodeBody)
            }
        } else {
            if (header != null) header(toolbar) else toolbar()
            episodeBody()
        }
    }
}
