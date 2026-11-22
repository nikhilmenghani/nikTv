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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun SportsScreen(onClose: () -> Unit, vm: SportsViewModel? = null, source: SportsCatalogueSource = SportsCatalogueSource.CRICFY) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    val factory = remember(application, source) { viewModelFactory { initializer { SportsViewModel(application, source) } } }
    val model = vm ?: viewModel<SportsViewModel>(key = "catalogue-${source.name}", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val back = { if (!model.back()) onClose() }
    BackHandler(onBack = back)
    val browsePreferences = remember(context, source) { SportsBrowsePreferences(context, source.storagePrefix) }
    var defaults by remember { mutableStateOf(browsePreferences.read()) }
    var sport by rememberSaveable { mutableStateOf(defaults.sport) }
    var status by rememberSaveable { mutableStateOf(defaults.status) }
    var lastSource by rememberSaveable(state.selected?.key) { mutableStateOf<Int?>(null) }
    val pages = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.cancelPendingFallback()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycle, state.selected?.key, state.playing != null, state.tab) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (state.tab == SportsTab.HIGHLIGHTS && source == SportsCatalogueSource.CRICFY) return@repeatOnLifecycle
            var lastCheck = 0L
            while (true) {
                now = Instant.now()
                if (now.toEpochMilli() - lastCheck >= 30_000) { model.refreshIfStale(); lastCheck = now.toEpochMilli() }
                delay(if (state.selected == null && state.tab == SportsTab.EVENTS) 1_000 else 30_000)
            }
        }
    }
    if (state.tab == SportsTab.HIGHLIGHTS && source == SportsCatalogueSource.CRICFY) {
        CricketHighlightsScreen(onClose = onClose, selectTab = model::selectTab)
        return
    }
    val playing = state.playing
    if (playing != null) {
        val committedAttemptId = state.playback.attemptId
        SportsPlayer(playing, state.selected?.title.orEmpty(), onBack = { model.back() },
            onVideoFrameRendered = { model.playbackRendered(committedAttemptId) },
            streams = state.streams, selectedIndex = state.playingIndex ?: -1,
            navigationIndex = state.resolving ?: state.playingIndex ?: -1,
            onSelectStream = { lastSource = it; model.play(it) },
            sourceAttemptId = committedAttemptId, onRetryFresh = model::retryPlayback,
            onAuthorizationFailure = { model.playbackAuthorizationFailed(committedAttemptId) },
            onTerminalFailure = { model.playbackFailed(committedAttemptId) },
            onCancelPendingFallback = model::cancelPendingFallback, source = source)
        if (state.resolving != null) androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) { androidx.compose.material3.LinearProgressIndicator(androidx.compose.ui.Modifier.fillMaxWidth()) }
        state.streamError?.let { error -> SportsPlaybackErrorDialog(error,
            exhausted = error.startsWith("The available sources could not play.") ||
                error == SPORTS_EXPIRED_LINK_MESSAGE || error == SPORTS_AUTHORIZATION_MESSAGE,
            retry = model::retryPlayback, choose = { model.back() }, close = model::dismissStreamError) }
        return
    }
    pages.SaveableStateProvider(state.route) {
        var search by rememberSaveable { mutableStateOf("") }
        val servers by model.servers.collectAsState()
        SportsBrowseContent(state, now, sport, { sport = it }, status, { status = it }, search, { search = it },
        back, { model.refresh() }, model::open,
        { lastSource = it; model.play(it) }, lastSource = lastSource,
        defaults = defaults,
        saveDefaultSport = { browsePreferences.setSport(it); defaults = browsePreferences.read(); sport = it },
        saveDefaultStatus = { browsePreferences.setStatus(it); defaults = browsePreferences.read(); status = it },
        selectTab = model::selectTab, openCategory = model::openCategory, openChannel = model::openChannel,
        toggleFavourite = model::toggleFavourite, toggleFavourites = model::toggleFavourites, adjacentChannel = model::adjacentChannel,
        addPlaylist = model::addPlaylist, importPlaylist = model::importPlaylist, removePlaylist = model::removePlaylist,
        dismissLibraryError = model::dismissLibraryError,
        toggleChannelFlag = model::toggleChannelFlag, setAvailabilityFilter = model::setAvailabilityFilter,
        servers = servers, checkServers = model::checkServers, selectServer = model::selectServer, closeServerChecks = model::closeServerChecks) }
}
