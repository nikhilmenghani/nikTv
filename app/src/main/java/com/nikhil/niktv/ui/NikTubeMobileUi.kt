package com.nikhil.niktv.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.*
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NikTubeMobileScreen(back: () -> Unit, vm: NikTubeMobileViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? Activity
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val pip = (activity as? com.nikhil.niktv.NikTubeActivity)?.tubeInPip == true
    var destination by rememberSaveable { mutableStateOf("Home") }
    var search by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<NikTubeVideo?>(null) }
    var optionsPlaylist by remember { mutableStateOf<String?>(null) }
    var playlistVideo by remember { mutableStateOf<NikTubeVideo?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var commentsSheet by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf(false) }
    var playbackOptions by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    val stateHolder = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val player = NikTubeSession.player
    val video = NikTubeSession.video
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var downloadHeight by remember(vm.downloadVideo?.id) { mutableIntStateOf(0) }
    var downloadAudio by remember(vm.downloadVideo?.id) { mutableStateOf("") }
    LaunchedEffect(player) {
        while (player != null) {
            position = player.currentPosition; duration = player.duration.coerceAtLeast(0)
            buffering = player.playbackState == Player.STATE_BUFFERING
            vm.saveProgress(); delay(500)
        }
    }
    LaunchedEffect(vm.fullscreen) {
        activity?.requestedOrientation = if (vm.fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (vm.fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler {
        when {
            vm.fullscreen -> vm.fullscreen = false
            vm.watchVisible -> vm.watchVisible = false
            vm.remotePlaylist != null -> vm.closeRemotePlaylist()
            vm.channel != null -> vm.closeChannel()
            destination != "Home" -> { destination = "Home"; vm.browse() }
            else -> back()
        }
    }
    fun external(item: NikTubeVideo) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${item.id}"))) } }
    fun submit(value: String) { search = value; destination = "Search"; vm.browse(value.trim()); keyboard?.hide() }
    fun select(value: String) { vm.closeChannel(); destination = value; if (value == "Home") vm.browse() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val watch = vm.watchVisible && video != null
        val full = vm.fullscreen || pip
        val split = watch && !full && maxWidth >= 840.dp && maxWidth > maxHeight
        val shortsVisible = destination == "Shorts" && vm.channel == null && !watch && !full
        val videoWidth = when { full -> maxWidth; split -> maxWidth * .6f; watch -> maxWidth; else -> 120.dp }
        val videoHeight = when { full -> maxHeight; split -> maxHeight; watch -> videoWidth * 9 / 16; else -> 68.dp }
        if (!full) {
            if (watch) {
                val related = vm.details?.related?.videos.orEmpty().filterNot { it.id == video?.id }
                Column(Modifier.fillMaxSize().then(if (split) Modifier.padding(start = videoWidth) else Modifier.padding(top = videoHeight))) {
                    LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.watchVisible = false }) { Icon(Icons.Default.KeyboardArrowDown, "Minimize video") }
                                Text(video!!.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { options = video }) { Icon(Icons.Default.MoreVert, "Video actions") }
                            }
                            Text(video!!.channel, Modifier.clickable(enabled = video.channelId.isNotBlank()) { vm.openChannel(video); vm.watchVisible = false }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { description = true }, enabled = vm.details?.description?.isNotBlank() == true) { Text("Description") }
                                OutlinedButton(onClick = { external(video!!) }) { Text("Open with") }
                                IconButton(onClick = { vm.loadComments(); commentsSheet = true }) { Icon(Icons.Default.Comment, "Comments") }
                            }
                            }
                        }
                        if (related.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        items(related, key = { it.id }) { item -> MobileTubeCard(item, vm.positions[item.id], { vm.play(item) }, { options = item; optionsPlaylist = null }, { vm.openChannel(item); vm.watchVisible = false }) }
                    }
                }
            } else Row(Modifier.fillMaxSize()) {
                if (tablet) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Home" to Icons.Default.Home, "Shorts" to Icons.Default.PlayCircle, "Search" to Icons.Default.Search, "Subscriptions" to Icons.Default.Subscriptions, "Library" to Icons.Default.VideoLibrary).forEach { (label, icon) ->
                        NavigationRailItem(destination == label, { select(label) }, { Icon(icon, label) }, label = { Text(label) })
                    }
                }
                Scaffold(Modifier.weight(1f), contentWindowInsets = WindowInsets(0.dp), topBar = {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (vm.channel != null) vm.closeChannel() else back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (vm.channel != null) "Back to browsing" else "Back to NikTV") }
                        Text("NikTube", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        IconButton(onClick = { destination = "Search" }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = { when { vm.channel != null -> vm.channel?.let { vm.openChannel(it.id, it.title) }; destination == "Subscriptions" -> vm.refreshSubscriptions(true); else -> vm.browse(if (destination == "Search") search else null, true) } }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "NikTube settings") }
                    }
                }, bottomBar = {
                    Column {
                        if (video != null && !shortsVisible) Surface(onClick = { vm.watchVisible = true }, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.fillMaxWidth().height(84.dp).padding(start = 132.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(video.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = NikTubeSession::toggle) { Icon(if (NikTubeSession.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                                IconButton(onClick = vm::close) { Icon(Icons.Default.Close, "Close video") }
                            }
                        }
                        if (!tablet) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0.dp)) {
                            listOf("Home" to Icons.Default.Home, "Shorts" to Icons.Default.PlayCircle, "Subscriptions" to Icons.Default.Subscriptions, "Library" to Icons.Default.VideoLibrary).forEach { (label, icon) ->
                                NavigationBarItem(destination == label, { select(label) }, { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (destination == "Search") {
                            OutlinedTextField(search, { search = it; vm.suggest(it) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                placeholder = { Text("Search YouTube") }, singleLine = true, shape = MaterialTheme.shapes.large,
                                trailingIcon = { IconButton(onClick = { submit(search) }, enabled = search.isNotBlank()) { Icon(Icons.Default.Search, "Submit search") } },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit(search) }))
                            if (search != vm.query) vm.suggestions.take(5).forEach { text -> TextButton(onClick = { submit(text) }, modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.fillMaxWidth()) } }
                        }
                        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        vm.error?.let { message ->
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { if (vm.continuation != null) vm.loadMore() else vm.browse(vm.query, true) }) { Text("Retry") }
                            }
                        }
                        when {
                            vm.channel != null -> stateHolder.SaveableStateProvider("channel:${vm.channel?.id}:${vm.channelParams}") { NikTubeChannelContent(vm) { options = it; optionsPlaylist = null } }
                            destination == "Shorts" -> stateHolder.SaveableStateProvider("shorts") { NikTubeShortsContent(vm) { options = it; optionsPlaylist = null } }
                            destination == "Subscriptions" -> stateHolder.SaveableStateProvider("subscriptions") { NikTubeSubscriptionsContent(vm) { options = it; optionsPlaylist = null } }
                            destination == "Library" -> stateHolder.SaveableStateProvider("library") { NikTubeLibraryContent(vm) { item, playlist -> options = item; optionsPlaylist = playlist } }
                            else -> stateHolder.SaveableStateProvider(destination + ":" + (vm.query ?: "")) {
                            val entries = when { destination == "Library" -> vm.history; destination == "Search" && vm.query == null -> emptyList(); else -> vm.videos }
                            val grid = rememberLazyGridState()
                            val nearEnd by remember { derivedStateOf { entries.isNotEmpty() && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= entries.size - 5 } }
                            LaunchedEffect(nearEnd, entries.size, destination) { if (nearEnd && destination != "Library") vm.loadMore() }
                            LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), state = grid,
                                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (destination == "Library") item(span = { GridItemSpan(maxLineSpan) }) { Text("Continue watching", style = MaterialTheme.typography.headlineSmall) }
                                if (entries.isEmpty() && !vm.loading) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (destination == "Library") "Videos you watch will appear here." else "Search for a video or refresh to explore.", Modifier.padding(vertical = 24.dp)) }
                                items(entries, key = { it.id }) { item -> MobileTubeCard(item, vm.positions[item.id], { vm.play(item) }, { options = item; optionsPlaylist = null }, { vm.openChannel(item) }) }
                                if (vm.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                            }
                        }
                        }
                    }
                }
            }
        }
        // One PlayerView call site: changes in presentation never create another player.
        if (player != null && !shortsVisible) Box(Modifier.then(if (!watch && !full) Modifier.align(Alignment.BottomStart).padding(start = if (tablet) 88.dp else 8.dp, bottom = if (tablet) 8.dp else 88.dp) else Modifier.align(Alignment.TopStart))
            .width(videoWidth).height(videoHeight).background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; setOnTouchListener { _, event -> if (event.action == android.view.MotionEvent.ACTION_UP) { if (watch || full) controls = !controls else vm.watchVisible = true }; true } } },
                update = { if (it.player !== player) it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
            if ((watch || full) && controls && !pip) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .65f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (duration > 0) {
                    Slider((seekPosition ?: position.toFloat()).coerceIn(0f, duration.toFloat()), { seekPosition = it },
                        valueRange = 0f..duration.toFloat(), onValueChangeFinished = { seekPosition?.let { player.seekTo(it.toLong()) }; seekPosition = null })
                    Text("${tubeTime(position)} / ${tubeTime(duration)}", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Back 10 seconds") }
                    IconButton(onClick = NikTubeSession::toggle) { Icon(if (NikTubeSession.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                    IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Icon(Icons.Default.Forward10, "Forward 10 seconds") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { playbackOptions = true }) { Icon(Icons.Default.Tune, "Playback options") }
                    IconButton(onClick = { if (Build.VERSION.SDK_INT >= 26) activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }) { Icon(Icons.Default.PictureInPicture, "Picture in picture") }
                    IconButton(onClick = { vm.fullscreen = !vm.fullscreen }) { Icon(if (vm.fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen") }
                }
            }
            NikTubeSession.failure?.let { message -> Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                Text(message); Row { TextButton(onClick = { video?.let { vm.play(it, position) } }) { Text("Retry") }; TextButton(onClick = { video?.let(::external) }) { Text("Open with") } }
            } }
        }
        if (!shortsVisible) vm.opening?.let { item -> AlertDialog(onDismissRequest = vm::cancelOpening, title = { Text("Opening video") }, text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(item.title) } }, confirmButton = { TextButton(onClick = vm::cancelOpening) { Text("Cancel") } }) }
        vm.failedVideo?.let { item -> AlertDialog(onDismissRequest = vm::dismissFailure, title = { Text("Could not open video") }, text = { Text(vm.error.orEmpty()) },
            confirmButton = { FilledTonalButton(onClick = { vm.play(item) }) { Text("Retry") } }, dismissButton = { OutlinedButton(onClick = { external(item); vm.dismissFailure() }) { Text("Open with") } }) }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("NikTube playback") }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Background audio", Modifier.weight(1f)); Switch(NikTubeSession.backgroundAudio, NikTubeSession::setBackground) }
        }, confirmButton = { FilledTonalButton(onClick = { settings = false }) { Text("Close") } })
        if (description) ModalBottomSheet(onDismissRequest = { description = false }) {
            Text("Description", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(vm.details?.description.orEmpty())
                vm.details?.chapters.orEmpty().forEach { chapter -> TextButton(onClick = { player?.seekTo(chapter.startMillis); description = false }) { Text(chapter.title) } }
            }
        }
        if (playbackOptions && player != null) ModalBottomSheet(onDismissRequest = { playbackOptions = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Playback options", style = MaterialTheme.typography.titleLarge)
                Text("Speed", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f)) { speed ->
                        FilterChip(player.playbackParameters.speed == speed, { player.setPlaybackSpeed(speed); playbackOptions = false }, { Text("${speed}×") })
                    }
                }
                val heights = player.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }.filter { it > 0 }.distinct().sortedDescending()
                if (heights.isNotEmpty()) {
                    Text("Quality", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(player.trackSelectionParameters.maxVideoHeight == Int.MAX_VALUE, { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearVideoSizeConstraints().build(); playbackOptions = false }, { Text("Auto") }) }
                        items(heights) { height -> FilterChip(player.trackSelectionParameters.maxVideoHeight == height, { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, height).build(); playbackOptions = false }, { Text("${height}p") }) }
                    }
                }
                listOf(androidx.media3.common.C.TRACK_TYPE_AUDIO to "Audio", androidx.media3.common.C.TRACK_TYPE_TEXT to "Captions").forEach { (type, label) ->
                    val groups = player.currentTracks.groups.filter { it.type == type }
                    if (groups.isNotEmpty()) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        groups.forEach { group -> repeat(group.length) { index ->
                            val format = group.getTrackFormat(index)
                            TextButton(onClick = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build(); playbackOptions = false }) {
                                Text(format.label ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "$label ${index + 1}")
                            }
                        } }
                        if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) TextButton(onClick = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build(); playbackOptions = false }) { Text("Off") }
                    }
                }
            }
        }
        if (commentsSheet) ModalBottomSheet(onDismissRequest = { commentsSheet = false }) {
            Text("Comments", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            if (vm.commentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.commentsError?.let { Text(it, Modifier.padding(24.dp)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.comments.isEmpty() && !vm.commentsLoading) item { Text("No comments were returned for this video.") }
                items(vm.comments) { comment -> Column { Text(comment.author, fontWeight = FontWeight.Bold); Text(comment.text, style = MaterialTheme.typography.bodyMedium) } }
                item { TextButton(onClick = { vm.loadComments(true) }, enabled = !vm.commentsLoading) { Text("More comments") } }
            }
        }
        playlistVideo?.let { item -> ModalBottomSheet(onDismissRequest = { playlistVideo = null }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save to playlist", style = MaterialTheme.typography.titleLarge)
                vm.playlists.keys.sorted().forEach { name -> TextButton(onClick = { vm.addToPlaylist(name, item); playlistVideo = null }) { Text(name) } }
                OutlinedTextField(newPlaylistName, { newPlaylistName = it.take(80) }, label = { Text("New playlist name") }, singleLine = true)
                FilledTonalButton(onClick = { vm.addToPlaylist(newPlaylistName, item); newPlaylistName = ""; playlistVideo = null }, enabled = newPlaylistName.isNotBlank()) { Text("Create and save") }
            }
        } }
        vm.downloadVideo?.let { item -> ModalBottomSheet(onDismissRequest = vm::cancelDownloadOptions) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Download video", style = MaterialTheme.typography.titleLarge)
                Text(item.title)
                val stream = vm.downloadStream
                if (stream == null && vm.downloadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.downloadError?.let { Text(it); TextButton(onClick = { vm.prepareDownload(item) }) { Text("Retry") } }
                if (stream != null) {
                    val heights = stream.sabr?.tracks.orEmpty().filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }.map { it.format.height }.distinct().sortedDescending()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(downloadHeight == 0, { downloadHeight = 0 }, { Text("Best available") }) }
                        items(heights) { height -> FilterChip(downloadHeight == height, { downloadHeight = height }, { Text("${height}p") }) }
                    }
                    val audio = stream.sabr?.tracks.orEmpty().filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.distinctBy { it.audioTrackId ?: it.format.id }
                    if (audio.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(audio) { track -> val id = track.audioTrackId ?: track.format.id.orEmpty(); FilterChip(downloadAudio == id, { downloadAudio = id }, { Text(track.format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Audio ${audio.indexOf(track) + 1}") }) }
                    }
                    FilledTonalButton(onClick = { vm.enqueueDownload(downloadHeight, downloadAudio) }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                }
                OutlinedButton(onClick = vm::cancelDownloadOptions, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                Spacer(Modifier.height(16.dp))
            }
        } }
        options?.let { item -> ModalBottomSheet(onDismissRequest = { options = null }) {
            Text(item.title, Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.play(item); options = null }, Modifier.fillMaxWidth()) { Text("Play") }
            TextButton(onClick = { vm.addToPlaylist("Watch Later", item); options = null }, Modifier.fillMaxWidth()) { Text("Watch Later") }
            TextButton(onClick = { playlistVideo = item; options = null }, Modifier.fillMaxWidth()) { Text("Save to playlist") }
            TextButton(onClick = { vm.prepareDownload(item); options = null }, Modifier.fillMaxWidth()) { Text("Download") }
            if (item.channelId.isNotBlank()) TextButton(onClick = { vm.openChannel(item); vm.watchVisible = false; options = null }, Modifier.fillMaxWidth()) { Text("View channel") }
            optionsPlaylist?.let { name ->
                TextButton(onClick = { vm.moveInPlaylist(name, item, -1); options = null }, Modifier.fillMaxWidth()) { Text("Move earlier") }
                TextButton(onClick = { vm.moveInPlaylist(name, item, 1); options = null }, Modifier.fillMaxWidth()) { Text("Move later") }
                TextButton(onClick = { vm.removeFromPlaylist(name, item); options = null }, Modifier.fillMaxWidth()) { Text("Remove from playlist") }
            }
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${item.id}"), "Share video")); options = null }, Modifier.fillMaxWidth()) { Text("Share") }
            TextButton(onClick = { external(item); options = null }, Modifier.fillMaxWidth()) { Text("Open with") }
            Spacer(Modifier.height(24.dp))
        } }
    }
    }
}

private fun tubeTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun MobileTubeCard(video: NikTubeVideo, position: Long?, play: () -> Unit, actions: () -> Unit, channel: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = play, onLongClick = actions)) {
            AsyncImage(video.thumbnail, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video.duration.isNotBlank()) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = MaterialTheme.shapes.extraSmall, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .8f)) {
                Text(video.duration, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
            }
            val seconds = video.duration.split(':').fold(0L) { total, value -> total * 60 + (value.toLongOrNull() ?: 0) }
            if (position != null && position > 0 && seconds > 0) LinearProgressIndicator({ (position / (seconds * 1000f)).coerceIn(0f, 1f) }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(40.dp).clickable(enabled = video.channelId.isNotBlank(), onClick = channel), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                if (video.avatar.isNotBlank()) AsyncImage(video.avatar, video.channel, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(contentAlignment = Alignment.Center) { Text(video.channel.take(1)) }
            }
            Column(Modifier.weight(1f).clickable(onClick = play), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(video.channel, video.views, video.published).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = actions, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Video actions") }
        }
    }
}
