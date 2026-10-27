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
    var sport by rememberSaveable { mutableStateOf("All sports") }
    var status by rememberSaveable { mutableStateOf("Now & next") }
    var search by rememberSaveable { mutableStateOf("") }
    var lastSource by rememberSaveable(state.event?.slug) { mutableStateOf<Int?>(null) }
    val eventsScroll = rememberLazyGridState()
    val streamsScroll = key(state.event?.slug) { rememberLazyGridState() }
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
    LaunchedEffect(lifecycle, state.event?.slug, state.playing != null) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); vm.refreshIfStale(); delay(30_000) }
        }
    }
    val playing = state.playing
    if (playing != null) {
        val committedAttemptId = state.playback.attemptId
        SportsPlayer(playing, state.event?.title.orEmpty(), onBack = { vm.back() },
            streams = state.streams, selectedIndex = state.playingIndex ?: -1,
            navigationIndex = state.resolving ?: state.playingIndex ?: -1,
            onSelectStream = { lastSource = it; vm.play(it) },
            sourceAttemptId = committedAttemptId, onRetryFresh = vm::retryPlayback,
            onTerminalFailure = { vm.playbackFailed(committedAttemptId) },
            onCancelPendingFallback = vm::cancelPendingFallback)
        if (state.resolving != null) androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) { androidx.compose.material3.LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth()) }
        state.streamError?.let { error -> androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissStreamError, title = { androidx.compose.material3.Text("Unable to switch stream") },
            text = { androidx.compose.material3.Text(error) },
            confirmButton = { com.nikhil.niktv.ui.NikTvActionButton("Close", onClick = vm::dismissStreamError) }) }
        return
    }
    pages.SaveableStateProvider(state.event?.slug?.let { "event:$it" } ?: "catalogue") { SportsBrowseContent(state, now, sport, { sport = it }, status, { status = it }, search, { search = it },
        back, { state.event?.let(vm::open) ?: vm.refresh() }, vm::open,
        { lastSource = it; vm.play(it) }, eventsScroll, streamsScroll, lastSource) }
}
