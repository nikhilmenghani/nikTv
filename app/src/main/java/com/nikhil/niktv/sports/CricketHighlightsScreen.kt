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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
    val playing = state.playing
    if (playing != null) {
        SportsPlayer(playing, state.selected?.title.orEmpty(), vm::stopPlayback,
            sourceAttemptId = state.attempt, onRetryFresh = vm::retryPlayback,
            onTerminalFailure = { vm.playbackFailed(state.attempt) })
    } else CricketHighlightsContent(state, onClose, selectTab, vm::refresh, vm::play, vm::retryPlayback, vm::stopPlayback,
        importConfiguration = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
}

@Composable
internal fun CricketHighlightsContent(state: CricketHighlightsState, onClose: () -> Unit,
    selectTab: (SportsTab) -> Unit, refresh: () -> Unit, play: (String) -> Unit,
    retryPlayback: () -> Unit = {}, dismissPlayback: () -> Unit = {}, importConfiguration: () -> Unit = {}) {
    val compact = LocalConfiguration.current.screenWidthDp < 700
    val header = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val scroll = rememberLazyGridState()
    var query by rememberSaveable { mutableStateOf("") }
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(state.videos, query) { state.videos.filter { it.title.contains(query.trim(), true) } }
    val initial = lastId?.takeIf { id -> visible.any { it.id == id } }?.let { requesters.getOrPut(it) { FocusRequester() } } ?: header
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).windowInsetsPadding(WindowInsets.safeDrawing)
        .initialFocusEntry(initial).padding(horizontal = if (compact) 14.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = SportsShape, color = SportsSurface, contentColor = Color.White) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onClose)
                    Text("Sports", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    DiscoveryAction("Cricbuzz configuration", Icons.Default.Key, iconOnly = true, onClick = importConfiguration)
                    if (state.configured) DiscoveryAction("Search highlights", Icons.Default.Search, iconOnly = true,
                        onClick = { runCatching { search.requestFocus() } })
                    DiscoveryAction("Refresh highlights", Icons.Default.Refresh, iconOnly = true, onClick = refresh)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SportsTab.entries.forEach { tab ->
                        DiscoveryAction(tab.title, selected = tab == SportsTab.HIGHLIGHTS,
                            modifier = (if (tab == SportsTab.HIGHLIGHTS) Modifier.focusRequester(header) else Modifier)
                                .onPreviewKeyEvent {
                                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && visible.isNotEmpty()) {
                                        runCatching { grid.requestFocus() }; true
                                    } else false
                                }, onClick = { selectTab(tab) })
                    }
                }
            }
        }
        Text("Cricket highlights", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Featured in Canada", color = SportsMuted, style = MaterialTheme.typography.labelLarge)
        if (!state.configured) {
            SportsEmpty("Connect Cricbuzz", "Import your Cricbuzz API configuration to load the Canadian featured catalogue directly.")
            NikTvSecondaryActionButton(importConfiguration) { Text("Import Cricbuzz configuration") }
        }
        if (state.loading) SportsLoading("Loading featured highlights")
        if (state.selected == null) state.error?.let { SportsFailure(it, refresh) }
        if (state.configured) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(search).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && visible.isNotEmpty()) {
                    runCatching { grid.requestFocus() }; true
                } else false
            }, singleLine = true,
                label = { Text("Find a team or highlight") })
            if (!state.loading && state.error == null && visible.isEmpty()) SportsEmpty(
                if (query.isBlank()) "No featured highlights" else "No matching highlights",
                if (query.isBlank()) "Refresh to check the Canadian catalogue again." else "Try another team or clear your search.")
            SportsGrid(visible, { it.id }, scroll, if (compact) 240 else 280, Modifier.weight(1f), header,
                entry = grid, requester = { index -> requesters.getOrPut(visible[index].id) { FocusRequester() } }) { video, _, modifier ->
                CricketHighlightTile(video, state.preparingId == video.id, modifier, state.preparingId == null) {
                    lastId = video.id; play(video.id)
                }
            }
        }
    }
    if (state.selected != null && state.error != null) AlertDialog(onDismissRequest = dismissPlayback,
        containerColor = SportsSurface, title = { Text("Highlight unavailable") }, text = { Text(state.error) },
        confirmButton = { TextButton(onClick = retryPlayback) { Text("Refresh and retry") } },
        dismissButton = { TextButton(onClick = dismissPlayback) { Text("Back to highlights") } })
}

@Composable
private fun CricketHighlightTile(video: CricketHighlight, preparing: Boolean, modifier: Modifier,
    enabled: Boolean, play: () -> Unit) {
    Surface(onClick = play, enabled = enabled, modifier = modifier.remoteFocusFrame(SportsShape)
        .semantics { contentDescription = video.title }, shape = SportsShape,
        color = SportsSurface, contentColor = Color.White, border = BorderStroke(1.dp, Color(0xFF353C49))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color(0xFF292F3B)), contentAlignment = Alignment.Center) {
                if (video.image.isNotBlank()) AsyncImage(video.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (preparing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (preparing) "Preparing highlight…" else listOf(video.duration, "Highlights").filter(String::isNotBlank).joinToString(" · "),
                    color = SportsMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
