package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.ui.remoteFocusFrame
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.data.RemoteCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CricketHighlightsScreen(onClose: () -> Unit, selectTab: (SportsTab) -> Unit,
    vm: CricketHighlightsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { CricketBrowsePreferences(context) }
    LaunchedEffect(RemoteCredentials.values) {
        val defaults = preferences.read()
        vm.selectAppVersion(defaults.version)
        vm.selectRegion(defaults.region)
        if (state.playing == null && state.preparingId == null) vm.refresh()
    }
    val scope = rememberCoroutineScope()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(65_537)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                        ?: throw IllegalArgumentException("Empty configuration")
                    require(bytes.size <= 65_536)
                    RemoteCredentials.saveLocalCricketConfiguration(context, bytes.toString(Charsets.UTF_8))
                }
                vm.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.configurationFailed() }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.refreshIfStale()
            if (event == Lifecycle.Event.ON_STOP) vm.cancelPreparation()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.cancelPreparation() }
    }
    BackHandler(state.playing != null || state.preparingId != null || state.selected != null) { vm.stopPlayback() }
    val browseState = rememberSaveableStateHolder()
    BackHandler(state.collectionId != null && state.playing == null && state.selected == null) { vm.selectCollection(null) }
    val playing = state.playing
    if (playing != null) {
        SportsPlayer(playing, state.selected?.title.orEmpty(), vm::stopPlayback,
            sourceAttemptId = state.attempt, onRetryFresh = vm::retryPlayback,
            onTerminalFailure = { vm.playbackFailed(state.attempt) })
    } else browseState.SaveableStateProvider("cricket-browse:${state.region}:${state.appVersion}") { CricketHighlightsContent(state, onClose, selectTab, vm::refresh, vm::play, vm::retryPlayback, vm::stopPlayback,
        importConfiguration = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        selectCollection = vm::selectCollection, loadMore = vm::loadMore,
        selectRegion = { region -> preferences.setRegion(region); vm.selectRegion(region) },
        selectAppVersion = { version -> preferences.setVersion(version); vm.selectAppVersion(version) }) }
}

@Composable
internal fun CricketHighlightsContent(state: CricketHighlightsState, onClose: () -> Unit,
    selectTab: (SportsTab) -> Unit, refresh: () -> Unit, play: (String) -> Unit,
    retryPlayback: () -> Unit = {}, dismissPlayback: () -> Unit = {}, importConfiguration: () -> Unit = {},
    selectCollection: (Int?) -> Unit = {}, loadMore: () -> Unit = {}, selectRegion: (String) -> Unit = {}, selectAppVersion: (String) -> Unit = {}) {
    val compact = sportsPhoneLayout()
    val tabFocus = remember { SportsTab.entries.associateWith { FocusRequester() } }
    val header = tabFocus.getValue(SportsTab.HIGHLIGHTS)
    val entry = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    val more = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val scroll = rememberLazyGridState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var lastFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var previousCollection by rememberSaveable { mutableStateOf(state.collectionId) }
    LaunchedEffect(state.collectionId) {
        if (previousCollection != state.collectionId) {
            query = ""; lastFocusKey = null; scroll.scrollToItem(0); previousCollection = state.collectionId
        }
    }
    LaunchedEffect(searchVisible) { if (searchVisible) { withFrameNanos { }; runCatching { search.requestFocus() } } }
    val visible = remember(state.videos, query) { state.videos.filter { it.title.contains(query.trim(), true) } }
    val sections = remember(state.sections, state.videos, query) {
        state.sections.ifEmpty { listOf(CricketVideoSection(null, "Featured highlights", state.videos)) }
            .map { section -> section.copy(videos = section.videos.filter { it.title.contains(query.trim(), true) }) }
            .filter { it.videos.isNotEmpty() }
    }
    val validFocus = if (state.collectionId == null) sections.any { row -> row.videos.any { "${row.id}:${it.id}" == lastFocusKey } }
        else visible.any { "grid:${it.id}" == lastFocusKey }
    val initial = lastFocusKey?.takeIf { validFocus }?.let { requesters.getOrPut(it) { FocusRequester() } } ?: header
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).windowInsetsPadding(WindowInsets.safeDrawing)
        .initialFocusEntry(initial).padding(horizontal = if (compact) 14.dp else 24.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val title = state.collections.firstOrNull { it.id == state.collectionId }?.title ?: "Highlights"
        val headerActions = buildList {
            if (state.configured) add(SportsHeaderAction("Search highlights", Icons.Default.Search, searchVisible) {
                searchVisible = !searchVisible; if (!searchVisible) query = ""
            })
            add(SportsHeaderAction("Refresh highlights", Icons.Default.Refresh, onClick = refresh))
            add(SportsHeaderAction("Cricbuzz configuration", Icons.Default.Key, onClick = importConfiguration))
        }
        SportsHeader(title, { if (state.collectionId != null) selectCollection(null) else onClose() },
            headerActions, backFocus, menuFocus) { modifier ->
            Row(modifier.horizontalScroll(rememberScrollState()).focusGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SportsTab.entries.forEach { tab ->
                    DiscoveryAction(tab.title, selected = tab == SportsTab.HIGHLIGHTS,
                        modifier = Modifier.focusRequester(tabFocus.getValue(tab))
                            .onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown &&
                                    (visible.isNotEmpty() || sections.isNotEmpty())) {
                                    runCatching { entry.requestFocus() }; true
                                } else false
                            }, onClick = { selectTab(tab) })
                }
            }
        }
        if (!compact && state.collectionId != null) Text(title, color = Color.White,
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SportsDropdown("Version", state.appVersion, listOf("16.11.01", "6.16.04").map { it to it },
                selectAppVersion, Modifier.weight(1f))
            SportsDropdown("Region", if (state.region == "IN") "India" else "Canada",
                listOf("CA" to "Canada", "IN" to "India"), selectRegion, Modifier.weight(1f))
        }
        if (!state.configured) {
            SportsEmpty("Connect Cricbuzz", "Import your Cricbuzz API configuration to load videos directly.")
            NikTvSecondaryActionButton(importConfiguration) { Text("Import Cricbuzz configuration") }
        }
        if (state.loading) SportsLoading("Loading cricket videos")
        if (state.selected == null) state.error?.let { SportsFailure(it, refresh) }
        if (state.configured) {
            if (searchVisible) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(search).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                    runCatching { entry.requestFocus() }; true
                } else false
            }, singleLine = true, label = { Text("Find a team or video") })
            if (state.collectionId == null) {
                if (!state.loading && state.error == null && sections.isEmpty()) SportsEmpty("No matching videos", "Try another team or refresh the catalogue.")
                CricketVideoRows(sections, state.preparingId, Modifier.weight(1f), compact, header, entry, requesters,
                    onFocused = { lastFocusKey = it }, selectCollection = selectCollection, play = play)
            } else {
                if (!state.loading && state.error == null && visible.isEmpty()) SportsEmpty("No matching videos", "Try another team or refresh this category.")
                SportsGrid(visible, { it.id }, scroll, if (compact) 160 else 280, Modifier.weight(1f), header,
                    below = more.takeIf { state.hasMore }, entry = entry,
                    requester = { index -> requesters.getOrPut("grid:${visible[index].id}") { FocusRequester() } }) { video, _, modifier ->
                    CricketHighlightTile(video, state.preparingId == video.id, modifier.onFocusChanged {
                        if (it.isFocused) lastFocusKey = "grid:${video.id}"
                    }, state.preparingId == null, compact) { lastFocusKey = "grid:${video.id}"; play(video.id) }
                }
                if (state.hasMore) NikTvSecondaryActionButton(loadMore, modifier = Modifier.focusRequester(more), enabled = !state.loadingMore) {
                    Text(if (state.loadingMore) "Loading more videos…" else "Load more videos")
                }
            }
        }
      }
      if (compact) SportsBottomNavigation(SportsTab.HIGHLIGHTS, selectTab, tabFocus,
          content = entry.takeIf { state.configured && (visible.isNotEmpty() || sections.isNotEmpty()) })
    }
    if (state.selected != null && state.error != null) AlertDialog(onDismissRequest = dismissPlayback,
        containerColor = SportsSurface, title = { Text("Highlight unavailable") }, text = { Text(state.error) },
        confirmButton = { TextButton(onClick = retryPlayback) { Text("Refresh and retry") } },
        dismissButton = { TextButton(onClick = dismissPlayback) { Text("Back to highlights") } })
}

@Composable
private fun CricketVideoRows(sections: List<CricketVideoSection>, preparingId: String?, modifier: Modifier,
    compact: Boolean, header: FocusRequester, entry: FocusRequester, requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit, selectCollection: (Int?) -> Unit, play: (String) -> Unit) {
    val vertical = rememberLazyListState()
    val rowStates = remember { mutableMapOf<Int?, LazyListState>() }
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun focus(row: Int, index: Int) {
        movement?.cancel()
        movement = scope.launch {
            if (row < 0) { runCatching { header.requestFocus() }; return@launch }
            val section = sections.getOrNull(row) ?: return@launch
            val target = index.coerceIn(0, section.videos.lastIndex)
            vertical.scrollToItem(row)
            val horizontal = rowStates.getOrPut(section.id) { LazyListState() }
            horizontal.scrollToItem(target)
            withFrameNanos { }
            runCatching { requesters.getOrPut("${section.id}:${section.videos[target].id}") { FocusRequester() }.requestFocus() }
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = vertical, verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 18.dp)) {
        itemsIndexed(sections, key = { _, row -> row.id?.toString() ?: "featured" }) { rowIndex, section ->
            val restored = rememberLazyListState()
            val horizontal = rowStates.getOrPut(section.id) { restored }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (section.id != null) DiscoveryAction("View all ${section.title}", Icons.Default.ChevronRight,
                        visualLabel = "View all", modifier = Modifier.semantics { contentDescription = "View all ${section.title}" },
                        onClick = { selectCollection(section.id) })
                }
                LazyRow(Modifier.fillMaxWidth().focusGroup(), state = horizontal,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                    itemsIndexed(section.videos, key = { _, video -> video.id }) { index, video ->
                        val key = "${section.id}:${video.id}"
                        val tileModifier = Modifier.width(if (compact) 268.dp else 300.dp)
                            .then(if (rowIndex == 0 && index == 0) Modifier.focusRequester(entry) else Modifier)
                            .focusRequester(requesters.getOrPut(key) { FocusRequester() })
                            .onFocusChanged { if (it.isFocused) onFocused(key) }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionRight -> { if (index < section.videos.lastIndex) focus(rowIndex, index + 1); true }
                                    Key.DirectionLeft -> { if (index > 0) focus(rowIndex, index - 1); true }
                                    Key.DirectionDown -> { focus(rowIndex + 1, index); true }
                                    Key.DirectionUp -> { focus(rowIndex - 1, index); true }
                                    else -> false
                                }
                            }
                        CricketHighlightTile(video, preparingId == video.id, tileModifier, preparingId == null, compact) {
                            onFocused(key); play(video.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CricketHighlightTile(video: CricketHighlight, preparing: Boolean, modifier: Modifier,
    enabled: Boolean, compact: Boolean, play: () -> Unit) {
    Surface(onClick = play, enabled = enabled, modifier = modifier.remoteFocusFrame(SportsShape)
        .semantics { contentDescription = video.title }, shape = SportsShape,
        color = SportsSurface, contentColor = Color.White) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color(0xFF303030))) {
            if (video.image.isNotBlank()) AsyncImage(video.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB000000)))))
            if (video.duration.isNotBlank()) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), color = Color(0xBB000000),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                Text(video.duration, Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    .semantics { contentDescription = "${video.duration} · ${video.type}" },
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(22.dp), tint = Color.White)
                Text(video.title, color = Color.White,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
