package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun SportsScreen(onClose: () -> Unit, vm: SportsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back = { if (!vm.back()) onClose() }
    BackHandler(onBack = back)
    val context = androidx.compose.ui.platform.LocalContext.current
    val browsePreferences = remember(context) { SportsBrowsePreferences(context) }
    var defaults by remember { mutableStateOf(browsePreferences.read()) }
    var sport by rememberSaveable { mutableStateOf(defaults.sport) }
    var status by rememberSaveable { mutableStateOf(defaults.status) }
    var lastSource by rememberSaveable(state.selected?.key) { mutableStateOf<Int?>(null) }
    val pages = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.cancelPendingFallback()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycle, state.selected?.key, state.playing != null) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastCheck = 0L
            while (true) {
                now = Instant.now()
                if (now.toEpochMilli() - lastCheck >= 30_000) { vm.refreshIfStale(); lastCheck = now.toEpochMilli() }
                delay(if (state.selected == null && state.tab == SportsTab.EVENTS) 1_000 else 30_000)
            }
        }
    }
    val playing = state.playing
    if (playing != null) {
        val committedAttemptId = state.playback.attemptId
        SportsPlayer(playing, state.selected?.title.orEmpty(), onBack = { vm.back() },
            onVideoFrameRendered = { vm.playbackRendered(committedAttemptId) },
            streams = state.streams, selectedIndex = state.playingIndex ?: -1,
            navigationIndex = state.resolving ?: state.playingIndex ?: -1,
            onSelectStream = { lastSource = it; vm.play(it) },
            sourceAttemptId = committedAttemptId, onRetryFresh = vm::retryPlayback,
            onTerminalFailure = { vm.playbackFailed(committedAttemptId) },
            onCancelPendingFallback = vm::cancelPendingFallback)
        if (state.resolving != null) androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) { androidx.compose.material3.LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth()) }
        state.streamError?.let { error -> SportsPlaybackErrorDialog(error,
            exhausted = error.startsWith("The available sources could not play."),
            retry = vm::retryPlayback, choose = { vm.back() }, close = vm::dismissStreamError) }
        return
    }
    pages.SaveableStateProvider(state.route) {
        var search by rememberSaveable { mutableStateOf("") }
        SportsBrowseContent(state, now, sport, { sport = it }, status, { status = it }, search, { search = it },
        back, { vm.refresh() }, vm::open,
        { lastSource = it; vm.play(it) }, lastSource = lastSource,
        defaults = defaults,
        saveDefaultSport = { browsePreferences.setSport(it); defaults = browsePreferences.read(); sport = it },
        saveDefaultStatus = { browsePreferences.setStatus(it); defaults = browsePreferences.read(); status = it },
        selectTab = vm::selectTab, openCategory = vm::openCategory, openChannel = vm::openChannel,
        toggleFavourite = vm::toggleFavourite, toggleFavourites = vm::toggleFavourites, adjacentChannel = vm::adjacentChannel,
        addPlaylist = vm::addPlaylist, importPlaylist = vm::importPlaylist, removePlaylist = vm::removePlaylist,
        dismissLibraryError = vm::dismissLibraryError,
        toggleChannelFlag = vm::toggleChannelFlag, setAvailabilityFilter = vm::setAvailabilityFilter) }
}
