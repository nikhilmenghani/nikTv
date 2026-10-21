package com.nikhil.niktv.discovery

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
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverySeriesEpisodes(state: DiscoveryState, startSeason: SeriesStartSeason,
    seasonEntry: FocusRequester, loadSeason: (Int) -> Unit,
    play: (DiscoveryCard, Int, Int) -> Unit, download: (DiscoveryCard, Int, Int) -> Unit,
    onFocus: () -> Unit, up: () -> Boolean, down: () -> Boolean) {
    val card = state.detail ?: return
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val wide = configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(configuration)
    val seasons = state.details?.seasons.orEmpty()
    val chosen = state.season ?: if (startSeason == SeriesStartSeason.LAST) seasons.lastOrNull() else seasons.firstOrNull()
    var listView by rememberSaveable(state.profile, card.key) { mutableStateOf(true) }
    var newest by rememberSaveable(state.profile, card.key) { mutableStateOf(true) }
    var searchOpen by rememberSaveable(state.profile, card.key) { mutableStateOf(false) }
    var query by rememberSaveable(state.profile, card.key) { mutableStateOf("") }
    var seasonOpen by remember { mutableStateOf(false) }
    var focused by rememberSaveable(state.profile, card.key) { mutableStateOf<String?>(null) }
    val search = remember { FocusRequester() }
    val toolbarSearch = if (wide) seasonEntry else search
    val sort = remember { FocusRequester() }
    val layout = remember { FocusRequester() }
    val sidebar = remember { FocusRequester() }
    val selectedSeason = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val episodeState = key(card.key, chosen) { rememberLazyListState() }
    LaunchedEffect(listView, chosen) {
        withFrameNanos { }
        episodeState.scrollToItem(episodeState.firstVisibleItemIndex, 0)
    }
    var scrollReset by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollReset) {
        if (scrollReset > 0) { withFrameNanos { }; episodeState.scrollToItem(0) }
    }
    val scope = rememberCoroutineScope()
    var entryJob by remember { mutableStateOf<Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var searchImeWasVisible by remember { mutableStateOf(false) }
    val episodes = remember(state.episodes, query, searchOpen, newest) {
        val filtered = state.episodes.filter { episodeMatchesSearch(it.title, it.overview, it.number, if (searchOpen) query else "") }
        if (newest) filtered.sortedByDescending { it.number } else filtered.sortedBy { it.number }
    }
    DisposableEffect(card.key, chosen, episodes, listView) {
        onDispose { entryJob?.cancel() }
    }
    fun closeSearch() {
        searchImeWasVisible = false; searchOpen = false; keyboard?.hide()
        scope.launch { withFrameNanos { }; runCatching { toolbarSearch.requestFocus() } }
    }
    BackHandler(searchOpen, ::closeSearch)
    LaunchedEffect(searchOpen) {
        if (searchOpen) repeat(12) {
            withFrameNanos { }
            if (runCatching { toolbarSearch.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(imeVisible, searchOpen) {
        if (searchOpen && imeVisible) searchImeWasVisible = true
        else if (searchOpen && searchImeWasVisible) closeSearch()
    }
    fun enterEpisodes() {
        searchImeWasVisible = false; keyboard?.hide()
        if (episodes.isEmpty()) return
        val index = episodes.indexOfFirst { "${it.season}:${it.number}" == focused }.coerceAtLeast(0)
        entryJob?.cancel()
        entryJob = scope.launch {
            episodeState.scrollToItem(index)
            repeat(20) {
                withFrameNanos { }
                val target = episodes.getOrNull(index) ?: return@launch
                if (runCatching { requesters["${target.season}:${target.number}"]?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    LaunchedEffect(state.details?.card?.key) { chosen?.let(loadSeason) }
    LaunchedEffect(seasonOpen) {
        if (seasonOpen) repeat(12) {
            withFrameNanos { }
            if (runCatching { selectedSeason.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Column {
        SeriesEpisodeToolbar(
            season = { if (wide) Text("Episodes", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium) else Box {
                DiscoveryAction(chosen?.let { "Season $it" } ?: "Episodes", Icons.Default.ExpandMore,
                    modifier = Modifier.focusRequester(seasonEntry)) { seasonOpen = true }
                DropdownMenu(seasonOpen, { seasonOpen = false }) {
                    seasons.sorted().forEach { season ->
                        NikDropdownMenuItem(text = { Text("Season $season") },
                            modifier = if (season == chosen) Modifier.focusRequester(selectedSeason) else Modifier,
                            leadingIcon = if (season == chosen) {{ Icon(Icons.Default.Check, null) }} else null,
                            onClick = { seasonOpen = false; loadSeason(season) })
                    }
                }
            } },
            count = seriesEpisodeCountLabel(episodes.size, false, searchOpen && query.isNotBlank()),
            compact = !wide, newestFirst = newest, onNewestFirst = {
                newest = it; scrollReset++
            },
            listView = listView, onListView = { listView = it }, searchOpen = searchOpen,
            onSearch = { if (searchOpen) closeSearch() else searchOpen = true },
            searchRequester = toolbarSearch, sortRequester = sort, layoutRequester = layout,
            beforeAction = { searchImeWasVisible = false; keyboard?.hide() },
            searchField = {
                SeriesEpisodeSearchField(query, { query = it; scrollReset++ }, ::closeSearch,
                    options = KeyboardOptions(imeAction = ImeAction.Search),
                    actions = KeyboardActions(onSearch = { searchImeWasVisible = false; keyboard?.hide() }))
            }, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { searchImeWasVisible = false; keyboard?.hide(); up() }
                    Key.DirectionDown -> {
                        if (wide && !listView && seasons.isNotEmpty()) runCatching { sidebar.requestFocus() } else enterEpisodes(); true
                    }
                    else -> false
                }
            })
        state.errors["season"]?.let { DiscoveryAction(it) { chosen?.let(loadSeason) } }
        if ("season" in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if (!listView && episodes.isEmpty() && "season" !in state.loading) Text(
            if (searchOpen && query.isNotBlank()) "No episodes match your search." else "No episodes are available for this season.",
            Modifier.padding(24.dp))
        val tile: @Composable (Int, DiscoveryEpisode) -> Unit = { index, episode ->
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
            DiscoveryEpisodeTile(episode.title, episode.still, "S${episode.season} · E${episode.number}", episode.date.orEmpty(),
                listView = listView, overview = episode.overview,
                modifier = (if (listView) Modifier.fillMaxWidth() else Modifier.width(200.dp)).focusRequester(requester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> if (listView && wide && seasons.size > 1) runCatching { sidebar.requestFocus() }.getOrDefault(false) else false
                            Key.DirectionUp -> if (!listView && wide && seasons.isNotEmpty()) runCatching { sidebar.requestFocus() }.getOrDefault(false)
                                else if (!listView || index == 0) runCatching { seasonEntry.requestFocus() }.getOrDefault(false) else false
                            Key.DirectionDown -> if (!listView || index == episodes.lastIndex) down() else false
                            else -> false
                        }
                    },
                onFocus = { focused = identity; onFocus() }, onClick = { play(card, episode.season, episode.number) }, onLongClick = { options = true })
            if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(episode.title) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NikTvSecondaryActionButton({ options = false; download(card, episode.season, episode.number) }, Modifier.fillMaxWidth()) { Text("Download") }
                    if (!episode.overview.isNullOrBlank()) NikTvSecondaryActionButton({ options = false; description = true }, Modifier.fillMaxWidth()) { Text("Description") }
                } }, confirmButton = { NikTvSecondaryActionButton({ options = false }) { Text("Close") } })
            if (description) FullDescriptionDialog(episode.title, episode.overview.orEmpty()) { description = false }
        }
        val counts = state.details?.seasonEpisodeCounts.orEmpty().mapValues { "${it.value} episodes" }.toMutableMap()
        if (chosen != null && "season" !in state.loading) counts[chosen] = "${state.episodes.size} episodes"
        if (wide && !listView && seasons.isNotEmpty()) SeriesSeasonSidebar(seasons, chosen, counts, sidebar,
            loadSeason, ::enterEpisodes, { runCatching { toolbarSearch.requestFocus() } },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontal = true)
        if (listView) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .height((configuration.screenHeightDp * .68f).coerceIn(260f, 620f).dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide && seasons.size > 1) SeriesSeasonSidebar(seasons, chosen, counts, sidebar,
                loadSeason, ::enterEpisodes, { runCatching { seasonEntry.requestFocus() } }, Modifier.width(156.dp).fillMaxHeight())
            LazyColumn(Modifier.weight(1f), state = episodeState, contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (episodes.isEmpty()) item("episode-status") {
                    Text(if ("season" in state.loading) "Loading episodes…"
                        else if (searchOpen && query.isNotBlank()) "No episodes match your search."
                        else "No episodes are available for this season.", Modifier.padding(16.dp))
                }
                itemsIndexed(episodes, key = { _, episode -> "${episode.season}:${episode.number}" }, ) { index, episode -> tile(index, episode) }
            }
        } else if (!listView) LazyRow(state = episodeState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(episodes, key = { _, episode -> "${episode.season}:${episode.number}" }, ) { index, episode -> tile(index, episode) }
        }
    }
}
