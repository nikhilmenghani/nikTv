package com.nikhil.niktv.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.R
import com.nikhil.niktv.data.NikTubeVideo
import com.nikhil.niktv.model.PlaybackEngine

private enum class TubeDestination(val label: String, val icon: ImageVector) {
    DISCOVER("Discover", Icons.Default.Home), SEARCH("Search", Icons.Default.Search), HISTORY("History", Icons.Default.History)
}

private val tubeTopics = listOf(
    "" to "All", "Discover" to "Nature & travel", "Science & space" to "Science & space",
    "Technology" to "Technology", "Music" to "Music"
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun NikTubeScreen(onBack: () -> Unit, vm: NikTubeViewModel = viewModel()) {
    val device = LocalContext.current
    val tv = device.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        !device.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    if (!tv) { NikTubeMobileScreen(onBack); return }
    var destination by rememberSaveable { mutableStateOf(TubeDestination.DISCOVER) }
    var topic by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var returnToContent by rememberSaveable { mutableStateOf(false) }
    val browseState = rememberSaveableStateHolder()
    fun play(video: NikTubeVideo) { returnToContent = true; vm.play(video) }
    val playing = vm.playing
    if (playing != null) {
        PlayerScreen(media = playing, onBack = vm::closePlayer,
            onRetry = { position -> vm.activeVideo?.let { vm.play(it, position) } },
            onRetryAlternateDecoder = { position -> vm.activeVideo?.let { vm.play(it, position) } },
            onPlaybackAuthorizationFailure = vm::streamRejected,
            onPlayPrevious = {}, onPlayNext = {},
            onProgress = { _, position, duration -> vm.updateProgress(position, duration) },
            playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true,
            onVideoFrameRendered = vm::onVideoFrameRendered,
            onVideoFramesRendered = vm::onVideoFramesRendered)
        return
    }
    BackHandler {
        when {
            vm.resolving != null -> vm.closePlayer()
            destination != TubeDestination.DISCOVER || topic.isNotEmpty() -> {
                destination = TubeDestination.DISCOVER; topic = ""; vm.dismissError()
            }
            else -> onBack()
        }
    }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    val wide = isTv || configuration.smallestScreenWidthDp >= 600
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val select: (TubeDestination) -> Unit = { destination = it; vm.dismissError() }
    val submit = {
        if (query.isNotBlank()) { vm.browse(query.trim()); keyboard?.hide(); searchFocus.freeFocus() }
    }
    val refresh = {
        if (destination == TubeDestination.SEARCH) vm.searchTitle?.let(vm::browse) else vm.browse()
        Unit
    }
    val showRefresh = destination == TubeDestination.DISCOVER ||
        destination == TubeDestination.SEARCH && vm.searchTitle != null
    val header = when (destination) {
        TubeDestination.DISCOVER -> tubeTopics.firstOrNull { it.first == topic }?.second?.takeUnless { topic.isEmpty() } ?: "Discover"
        TubeDestination.SEARCH -> "Search"
        TubeDestination.HISTORY -> "Continue watching"
    }
    val contentReady = when (destination) {
        TubeDestination.DISCOVER -> vm.history.isNotEmpty() && topic.isEmpty() || vm.rows.any { (key, videos) ->
            videos.isNotEmpty() && (topic.isEmpty() || topic == key)
        }
        TubeDestination.HISTORY -> vm.history.isNotEmpty()
        TubeDestination.SEARCH -> vm.searchRows.any { it.second.isNotEmpty() }
    }
    LaunchedEffect(isTv, contentReady) {
        if (isTv) {
            withFrameNanos { }
            if (returnToContent && contentReady) { contentFocus.requestFocus(); returnToContent = false }
            else railFocus.requestFocus()
        }
    }
    LaunchedEffect(destination) {
        if (destination == TubeDestination.SEARCH) { withFrameNanos { }; searchFocus.requestFocus() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                val compactRail = configuration.screenWidthDp < 900
                TubeSideMenu(destination, topic, compactRail, railFocus,
                    if (destination == TubeDestination.SEARCH) searchFocus else if (contentReady) contentFocus else null, select,
                    { topic = it; destination = TubeDestination.DISCOVER; vm.dismissError() }, onBack)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TubeWideHeader(header, { select(TubeDestination.SEARCH) }, refresh, vm.loading, showRefresh)
                    if (destination == TubeDestination.SEARCH) TubeSearchField(query, { query = it }, submit, searchFocus)
                    if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    browseState.SaveableStateProvider("wide:$destination:$topic") {
                        TubeWideContent(vm, destination, topic, railFocus, ::play,
                            Modifier.weight(1f).fillMaxWidth().focusRequester(contentFocus).focusGroup())
                    }
                }
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    Column {
                        TubeMobileHeader(onBack, { select(TubeDestination.SEARCH) }, refresh, vm.loading, showRefresh)
                        if (destination == TubeDestination.DISCOVER) TubeTopicChips(topic) { topic = it }
                        if (destination == TubeDestination.SEARCH) TubeSearchField(query, { query = it }, submit, searchFocus)
                        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0.dp)) {
                        TubeDestination.entries.forEach { entry ->
                            NavigationBarItem(selected = destination == entry, onClick = { select(entry) },
                                icon = { Icon(entry.icon, null) }, label = { Text(entry.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            ) { padding ->
                browseState.SaveableStateProvider("mobile:$destination:$topic") {
                    TubeMobileContent(vm, destination, topic, configuration.screenWidthDp, ::play,
                        Modifier.fillMaxSize().padding(padding))
                }
            }
        }
        vm.resolving?.let { video ->
            AlertDialog(onDismissRequest = vm::closePlayer, title = { Text("Opening video") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(video.title)
                } }, confirmButton = { TextButton(onClick = vm::closePlayer) { Text("Cancel") } })
        }
    }
}

@Composable
private fun TubeMobileHeader(back: () -> Unit, search: () -> Unit, refresh: () -> Unit, loading: Boolean, showRefresh: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to NikTV") }
        Text("NikTube", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        IconButton(onClick = search) { Icon(Icons.Default.Search, "Search videos") }
        if (showRefresh) IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh videos") }
    }
}

@Composable
private fun TubeWideHeader(title: String, search: () -> Unit, refresh: () -> Unit, loading: Boolean, showRefresh: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        FilledTonalButton(onClick = search) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Search videos")
        }
        if (showRefresh) IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh videos") }
    }
}

@Composable
private fun TubeSideMenu(destination: TubeDestination, topic: String, compact: Boolean,
    railFocus: FocusRequester, contentFocus: FocusRequester?, select: (TubeDestination) -> Unit,
    selectTopic: (String) -> Unit, back: () -> Unit) {
    Surface(Modifier.width(if (compact) 76.dp else 212.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start) {
                Image(painterResource(R.drawable.niktv_logo_foreground), null, Modifier.size(32.dp))
                if (!compact) { Spacer(Modifier.width(10.dp)); Text("NikTube", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TubeDestination.entries.forEach { entry ->
                    val selected = destination == entry && (entry != TubeDestination.DISCOVER || topic.isEmpty())
                    TubeMenuButton(entry.label, entry.icon, selected, compact,
                        Modifier.then(if (selected) Modifier.focusRequester(railFocus) else Modifier)
                            .focusProperties { if (contentFocus != null) right = contentFocus }) { select(entry); if (entry == TubeDestination.DISCOVER) selectTopic("") }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (!compact) Text("EXPLORE", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tubeTopics.drop(1).forEach { (key, label) ->
                    val selected = destination == TubeDestination.DISCOVER && topic == key
                    val icon = when (key) { "Science & space" -> Icons.Default.Science; "Technology" -> Icons.Default.Devices
                        "Music" -> Icons.Default.MusicNote; else -> Icons.Default.Public }
                    TubeMenuButton(label, icon, selected, compact,
                        Modifier.then(if (selected) Modifier.focusRequester(railFocus) else Modifier)
                            .focusProperties { if (contentFocus != null) right = contentFocus }) { selectTopic(key) }
                }
            }
            TubeMenuButton("Back to NikTV", Icons.AutoMirrored.Filled.ArrowBack, false, compact, Modifier, back)
        }
    }
}

@Composable
private fun TubeMenuButton(label: String, icon: ImageVector, active: Boolean, compact: Boolean, modifier: Modifier, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = click, modifier = modifier.fillMaxWidth().heightIn(min = 50.dp).onFocusChanged { focused = it.hasFocus }
        .semantics { role = Role.Tab; selected = active; if (compact) contentDescription = label },
        color = when { active -> MaterialTheme.colorScheme.primaryContainer; focused -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface }, shape = MaterialTheme.shapes.medium,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
        Row(Modifier.padding(horizontal = if (compact) 12.dp else 14.dp, vertical = 14.dp),
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (active || focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) { Spacer(Modifier.width(12.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, color = if (active || focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TubeTopicChips(topic: String, select: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusGroup()) {
        items(tubeTopics, key = { it.first }) { (key, label) -> FilterChip(selected = topic == key, onClick = { select(key) },
            label = { Text(label) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer)) }
    }
}

@Composable
private fun TubeSearchField(query: String, change: (String) -> Unit, submit: () -> Unit, focus: FocusRequester) {
    OutlinedTextField(value = query, onValueChange = change, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).focusRequester(focus),
        shape = MaterialTheme.shapes.large, placeholder = { Text("Search YouTube") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { IconButton(onClick = submit, enabled = query.isNotBlank()) { Icon(Icons.Default.ArrowForward, "Submit search") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
}

private fun tubeFeed(rows: List<Pair<String, List<NikTubeVideo>>>, topic: String): List<NikTubeVideo> {
    if (topic.isNotEmpty()) return rows.firstOrNull { it.first == topic }?.second.orEmpty()
    return buildList {
        val count = rows.maxOfOrNull { it.second.size } ?: 0
        repeat(count) { index -> rows.forEach { row -> row.second.getOrNull(index)?.let(::add) } }
    }.distinctBy { it.id }
}

@Composable
private fun TubeMobileContent(vm: NikTubeViewModel, destination: TubeDestination, topic: String, screenWidth: Int,
    play: (NikTubeVideo) -> Unit, modifier: Modifier) {
    val videos = when (destination) {
        TubeDestination.DISCOVER -> tubeFeed(vm.rows, topic)
        TubeDestination.SEARCH -> vm.searchRows.flatMap { it.second }.distinctBy { it.id }
        TubeDestination.HISTORY -> vm.history
    }
    LazyVerticalGrid(columns = GridCells.Fixed(if (screenWidth >= 640) 2 else 1), modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (vm.error != null) item("error", span = { GridItemSpan(maxLineSpan) }) { TubeError(vm, play) }
        if (destination == TubeDestination.DISCOVER && topic.isEmpty() && vm.history.isNotEmpty()) {
            item("continue", span = { GridItemSpan(maxLineSpan) }) {
                TubeShelf("Continue watching", vm.history, 224.dp, vm, play, inset = 0.dp)
            }
        }
        if (destination == TubeDestination.HISTORY) item("history-title", span = { GridItemSpan(maxLineSpan) }) {
            Text("Your history", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (destination == TubeDestination.SEARCH && vm.searchTitle != null) item("search-title", span = { GridItemSpan(maxLineSpan) }) {
            Text("Results for “${vm.searchTitle}”", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (videos.isEmpty() && !vm.loading) item("empty", span = { GridItemSpan(maxLineSpan) }) { TubeEmpty(destination) }
        items(videos, key = { it.id }) { video -> TubeVideoCard(video, true, vm.resumeFraction(video), Modifier.fillMaxWidth()) { play(video) } }
    }
}

@Composable
private fun TubeWideContent(vm: NikTubeViewModel, destination: TubeDestination, topic: String,
    railFocus: FocusRequester, play: (NikTubeVideo) -> Unit, modifier: Modifier) {
    val rows = if (destination == TubeDestination.DISCOVER) buildList {
        if (topic.isEmpty() && vm.history.isNotEmpty()) add("Continue watching" to vm.history)
        addAll(vm.rows.filter { topic.isEmpty() || it.first == topic }.map { (name, videos) ->
            (if (name == "Discover") "Nature & travel" else name) to videos
        })
    } else emptyList()
    if (destination == TubeDestination.DISCOVER) {
        BoxWithConstraints(modifier) {
            val cardWidth = ((maxWidth - 72.dp) / (if (maxWidth >= 850.dp) 3 else 2)).coerceIn(220.dp, 310.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (vm.error != null) item("error") { TubeError(vm, play) }
                if (rows.isEmpty() && !vm.loading) item("empty") { TubeEmpty(destination) }
                items(rows, key = { it.first }) { (title, videos) ->
                    TubeShelf(title, videos, cardWidth, vm, play, railFocus)
                }
            }
        }
    } else {
        val videos = if (destination == TubeDestination.HISTORY) vm.history else vm.searchRows.flatMap { it.second }.distinctBy { it.id }
        BoxWithConstraints(modifier) {
            val columns = ((maxWidth - 32.dp) / 246.dp).toInt().coerceAtLeast(1)
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (vm.error != null) item("error", span = { GridItemSpan(maxLineSpan) }) { TubeError(vm, play) }
                if (destination == TubeDestination.SEARCH && vm.searchTitle != null) item("query", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Results for “${vm.searchTitle}”", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (videos.isEmpty() && !vm.loading) item("empty", span = { GridItemSpan(maxLineSpan) }) { TubeEmpty(destination) }
                itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                    TubeVideoCard(video, false, vm.resumeFraction(video), Modifier.fillMaxWidth()
                        .then(if (index % columns == 0) Modifier.focusProperties { left = railFocus } else Modifier)) { play(video) }
                }
            }
        }
    }
}

@Composable
private fun TubeShelf(title: String, videos: List<NikTubeVideo>, width: Dp,
    vm: NikTubeViewModel, play: (NikTubeVideo) -> Unit, railFocus: FocusRequester? = null, inset: Dp = 24.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.padding(horizontal = inset),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(contentPadding = PaddingValues(horizontal = inset, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusGroup()) {
            itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                TubeVideoCard(video, false, vm.resumeFraction(video), Modifier.width(width)
                    .then(if (index == 0 && railFocus != null) Modifier.focusProperties { left = railFocus } else Modifier)) { play(video) }
            }
        }
    }
}

@Composable
private fun TubeVideoCard(video: NikTubeVideo, mobile: Boolean, progress: Float?, modifier: Modifier, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = click, modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = if (mobile) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(if (mobile) MaterialTheme.shapes.medium else MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                AsyncImage(video.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (video.duration.isNotBlank()) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface) {
                    Text(video.duration, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mobile) Surface(Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Text(video.channel.take(1).uppercase(), style = MaterialTheme.typography.titleSmall) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(video.title, maxLines = 2, minLines = if (mobile) 1 else 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(video.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TubeError(vm: NikTubeViewModel, play: (NikTubeVideo) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp)) {
            Text(vm.error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Row {
                vm.failedVideo?.let { video -> TextButton(onClick = { play(video) }) { Text("Retry playback") } }
                TextButton(onClick = vm::dismissError) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun TubeEmpty(destination: TubeDestination) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(destination.icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (destination) { TubeDestination.HISTORY -> "Your next watch starts here"; TubeDestination.SEARCH -> "What would you like to watch?"
            else -> "Nothing here yet" }, style = MaterialTheme.typography.titleLarge)
        Text(when (destination) { TubeDestination.HISTORY -> "Videos you play will appear in your history."; TubeDestination.SEARCH -> "Find a video, creator or topic."
            else -> "Refresh to load videos." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
