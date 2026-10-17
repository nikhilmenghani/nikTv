package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.NikTubeVideo

@Composable
internal fun NikTubeLibraryContent(vm: NikTubeMobileViewModel, options: (NikTubeVideo, String?) -> Unit) {
    var playlist by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var offline by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(offline) { while (offline) { vm.refreshDownloads(); kotlinx.coroutines.delay(2000) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your library", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { create = true }) { Icon(Icons.Default.Add, "Create playlist") }
            IconButton(onClick = { clear = true }) { Icon(Icons.Default.DeleteOutline, if (playlist == null) "Clear history" else "Delete playlist") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(playlist == null && !offline, { playlist = null; offline = false }, { Text("History") }) }
            item { FilterChip(playlist == "Watch Later" && !offline, { playlist = "Watch Later"; offline = false }, { Text("Watch Later") }) }
            item { FilterChip(offline, { offline = true }, { Text("Downloads") }) }
            items(vm.playlists.keys.filterNot { it == "Watch Later" }.sorted()) { key -> FilterChip(playlist == key && !offline, { playlist = key; offline = false }, { Text(key) }) }
        }
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, placeholder = { Text("Search your library") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = MaterialTheme.shapes.large)
        val list = (if (playlist == null) vm.history else vm.playlists[playlist].orEmpty()).filter { filter.isBlank() || it.title.contains(filter, true) || it.channel.contains(filter, true) }
        if (offline) NikTubeDownloadsContent(vm, filter) else key(playlist) {
            LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No videos here yet. Add videos using their action menu.") }
                items(list, key = { it.id }) { video -> MobileTubeCard(video, vm.positions[video.id], { vm.play(video) }, { options(video, playlist) }, { vm.openChannel(video) }) }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Create playlist") }, text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { FilledTonalButton(onClick = { vm.createPlaylist(name); playlist = name.trim(); name = ""; create = false }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { OutlinedButton(onClick = { create = false }) { Text("Cancel") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(if (playlist == null) "Clear history?" else "Delete playlist?") },
        text = { Text("This changes only your local NikTube library.") }, confirmButton = { FilledTonalButton(onClick = { if (playlist == null) vm.clearHistory() else vm.deletePlaylist(playlist!!); playlist = null; clear = false }) { Text("Delete") } },
        dismissButton = { OutlinedButton(onClick = { clear = false }) { Text("Keep") } })
}

@Composable
private fun NikTubeDownloadsContent(vm: NikTubeMobileViewModel, filter: String) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.downloads.isEmpty()) item { Text("No offline videos. Download a video using its action menu.") }
        items(vm.downloads.filter { it.record.video.title.contains(filter, true) }, key = { it.record.key }) { state ->
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.record.video.title, style = MaterialTheme.typography.titleMedium)
                    Text(when { state.file != null && state.state == androidx.work.WorkInfo.State.SUCCEEDED -> "Downloaded · ${state.bytes / (1024 * 1024)} MB"
                        state.state == androidx.work.WorkInfo.State.RUNNING -> "Downloading · ${state.percent}% · ${state.bytes / (1024 * 1024)} MB"
                        state.state == androidx.work.WorkInfo.State.FAILED -> state.error ?: "Download failed"
                        state.state == androidx.work.WorkInfo.State.CANCELLED -> "Canceled"
                        state.state == androidx.work.WorkInfo.State.SUCCEEDED -> "File missing"
                        else -> "Queued" }, style = MaterialTheme.typography.bodySmall)
                    if (state.state == androidx.work.WorkInfo.State.RUNNING) LinearProgressIndicator({ state.percent / 100f }, Modifier.fillMaxWidth())
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.file != null) FilledTonalButton(onClick = { vm.playDownload(state) }) { Text("Play") }
                        if (state.state == androidx.work.WorkInfo.State.RUNNING || state.state == androidx.work.WorkInfo.State.ENQUEUED) OutlinedButton(onClick = { vm.cancelDownload(state.record) }) { Text("Cancel") }
                        else if (state.file == null) FilledTonalButton(onClick = { vm.retryDownload(state.record) }) { Text("Retry") }
                        OutlinedButton(onClick = { vm.removeDownload(state.record) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NikTubeChannelContent(vm: NikTubeMobileViewModel, options: (NikTubeVideo) -> Unit) {
    val channel = vm.channel ?: return
    val playlist = vm.remotePlaylist
    val page = if (playlist != null) vm.remotePlaylistPage else channel.page
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= page.videos.size - 4 } }
    LaunchedEffect(nearEnd, page.videos.size, page.continuation) { if (nearEnd && page.continuation != null) { if (playlist != null) vm.openRemotePlaylist(playlist, true) else vm.openChannel(channel.id, more = true) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(channel.avatar, null, Modifier.size(56.dp), contentScale = ContentScale.Crop)
            Text(channel.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            FilledTonalButton(onClick = { vm.subscribe(channel) }) { Text(if (vm.subscriptions.any { it.id == channel.id }) "Subscribed" else "Subscribe") }
        }
        if (channel.description.isNotBlank()) Text(channel.description, Modifier.padding(horizontal = 16.dp), maxLines = 4, style = MaterialTheme.typography.bodySmall)
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(channel.tabs) { tab -> AssistChip(onClick = { vm.openChannel(channel.id, channel.title, tab.params) }, label = { Text(tab.title) }) }
        }
        if (vm.channelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (playlist != null) TextButton(onClick = vm::closeRemotePlaylist) { Text("Back to channel · ${playlist.title}") }
        vm.channelError?.let { Text(it, Modifier.padding(16.dp)) }
        LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (playlist == null) items(channel.playlists, key = { "playlist:${it.id}" }) { item -> FilledTonalButton(onClick = { vm.openRemotePlaylist(item) }) { Text(item.title) } }
            items(page.videos, key = { it.id }) { video -> MobileTubeCard(video.copy(channel = video.channel.ifBlank { channel.title }, channelId = video.channelId.ifBlank { channel.id }), vm.positions[video.id], { vm.play(video) }, { options(video) }, { vm.openChannel(video) }) }
            if (page.videos.isEmpty() && channel.playlists.isEmpty() && !vm.channelLoading) item(span = { GridItemSpan(maxLineSpan) }) { Text("No videos were returned for this section.") }
        }
    }
}

@Composable
internal fun NikTubeSubscriptionsContent(vm: NikTubeMobileViewModel, options: (NikTubeVideo) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSubscriptions() }
    Column(Modifier.fillMaxSize()) {
        Text("Subscriptions", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.subscriptions, key = { it.id }) { channel -> FilledTonalButton(onClick = { vm.openChannel(channel.id, channel.title) }) { Text(channel.title) } }
        }
        if (vm.channelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.channelError?.let { Text(it, Modifier.padding(16.dp)) }
        LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (vm.subscriptions.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Subscribe from a channel page to see its uploads here.") }
            items(vm.subscriptionVideos, key = { it.id }) { video -> MobileTubeCard(video, vm.positions[video.id], { vm.play(video) }, { options(video) }, { vm.openChannel(video) }) }
        }
    }
}
