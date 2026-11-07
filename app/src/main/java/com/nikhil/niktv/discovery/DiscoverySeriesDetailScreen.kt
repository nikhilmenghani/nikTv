package com.nikhil.niktv.discovery

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.nikhil.niktv.model.*
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.ui.*

/** Same presentation as provider series; TMDB identity and on-demand playback stay intact. */
@Composable
internal fun DiscoverySeriesDetailScreen(
    state: DiscoveryState, app: NikTvState, loadSeason: (Int) -> Unit,
    play: (DiscoveryCard, Int, Int) -> Unit, download: (DiscoveryCard, Int, Int) -> Unit,
    close: () -> Unit, favorite: () -> Unit,
    openMovie: (TrendingMovie) -> Unit, openSeries: (TrendingSeries) -> Unit,
    metadataSource: DiscoveryDataSource? = null,
) {
    val card = state.detail ?: return
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(configuration))
    val playFocus = remember(card.key) { FocusRequester() }
    val episodesFocus = remember(card.key) { FocusRequester() }
    val castFocus = remember(card.key) { FocusRequester() }
    val relatedFocus = remember(card.key) { FocusRequester() }
    val infoFocus = remember(card.key) { FocusRequester() }
    val backFocus = remember(card.key) { FocusRequester() }
    val page = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hasSynopsis by remember(card.key) { mutableStateOf(false) }
    var hasCast by remember(card.key) { mutableStateOf(false) }
    fun focusHeader(requester: FocusRequester) {
        scope.launch {
            page.scrollToItem(0)
            repeat(20) { withFrameNanos { }; if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch }
        }
    }
    fun focusEpisodes() {
        scope.launch {
            page.scrollToItem(1)
            repeat(20) { withFrameNanos { }; if (runCatching { episodesFocus.requestFocus() }.getOrDefault(false)) return@launch }
        }
    }
    fun headerTools() = focusHeader(if (hasCast) castFocus else relatedFocus)
    var panel by rememberSaveable(state.profile, card.key) { mutableStateOf<String?>(null) }
    val details = state.details ?: DiscoveryDetails(card)
    val lastWatched = discoveryLastEpisode(card, state.profile, app.recentlyPlayed)
    val primary = lastWatched ?: state.episodes.maxByOrNull { it.number }?.let { it.season to it.number }
    var initialFocusDone by remember(card.key) { mutableStateOf(false) }
    // Metadata/initial-season loading belongs to the route, not a lazy child
    // that can leave composition while its hero or cast panel is visible.
    LaunchedEffect(card.key, state.details?.card?.key) {
        val initial = lastWatched?.first ?: if (app.seriesStartSeason == SeriesStartSeason.LAST)
            state.details?.seasons?.lastOrNull() else state.details?.seasons?.firstOrNull()
        if (state.season == null) initial?.let(loadSeason)
    }
    LaunchedEffect(card.key, primary != null) {
        if (initialFocusDone || primary == null) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) {
                initialFocusDone = true
                return@LaunchedEffect
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090B10)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val episodeHeight = maxHeight
        LazyColumn(Modifier.fillMaxSize(), state = page) {
        item("series-header:${card.key}") {
        ProviderDetailHeader(card.media, CatalogType.SERIES, app,
            play = { primary?.let { play(card, it.first, it.second) } },
            close = close, favorite = favorite,
            isFavorite = app.favorites.any { it.profileKey == state.profile && it.media.id == card.media.id },
            openTmdbMovie = openMovie, openTmdbSeries = openSeries,
            metadataSource = metadataSource,
            openDiscoveryDetail = { selected ->
                if (selected.series) openSeries(TrendingSeries(selected.show()))
                else openMovie(TrendingMovie(selected.movie()))
            },
            playRequester = playFocus, backRequester = backFocus, compactSeries = phone, discoveryDetails = details,
            playLabel = if (lastWatched != null) "Resume" else "Play latest", playEnabled = primary != null,
            down = { if (phone) focusEpisodes() else if (hasSynopsis) focusHeader(infoFocus) else headerTools() },
            topDown = {
                if (primary != null) focusHeader(playFocus)
                else if (hasSynopsis) focusHeader(infoFocus) else headerTools()
            }, heroUp = { focusHeader(backFocus) },
            synopsisRequester = infoFocus, synopsisAvailable = { hasSynopsis = it },
            synopsisUp = { focusHeader(if (primary != null) playFocus else backFocus) }, synopsisDown = ::headerTools,
            castUp = { focusHeader(castFocus) }, relatedUp = { focusHeader(relatedFocus) },
            episodeDown = ::focusEpisodes,
            layout = { hero, cast, related, castEntry, relatedEntry ->
                SideEffect { hasCast = castEntry != null }
                fun Modifier.panelRoutes(kind: String, entry: (() -> Boolean)?) = onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { focusHeader(if (!phone && hasSynopsis) infoFocus else if (primary != null) playFocus else backFocus); true }
                        Key.DirectionDown -> {
                            if (panel == kind && entry != null) scope.launch {
                                repeat(20) { withFrameNanos { }; if (entry()) return@launch }
                            } else focusEpisodes()
                            true
                        }
                        else -> false
                    }
                }
                val actions: @Composable () -> Unit = {
                    DiscoveryAction(if (panel == "cast") "Hide cast" else "Cast",
                        if (panel == "cast") Icons.Default.ExpandLess else Icons.Default.People,
                        selected = panel == "cast", iconOnly = true, enabled = castEntry != null,
                        modifier = Modifier.focusRequester(castFocus).panelRoutes("cast", castEntry)) { panel = if (panel == "cast") null else "cast" }
                    DiscoveryAction(if (panel == "related") "Hide related" else "Related",
                        if (panel == "related") Icons.Default.ExpandLess else Icons.Default.Movie,
                        selected = panel == "related", iconOnly = true,
                        modifier = Modifier.focusRequester(relatedFocus).panelRoutes("related", relatedEntry)) { panel = if (panel == "related") null else "related" }
                }
                val expanded: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().animateContentSize().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())) {
                        if (panel == "cast") cast()
                        if (panel == "related") related()
                    }
                }
                CompositionLocalProvider(LocalProviderSynopsisActions provides actions,
                    LocalProviderSynopsisPanel provides expanded) { hero() }
            })
        }
        item("series-episodes:${card.key}") {
            DiscoverySeriesEpisodes(state, app.seriesStartSeason, episodesFocus, loadSeason, play, download,
                onFocus = {
                    if (page.firstVisibleItemIndex != 1 || page.firstVisibleItemScrollOffset != 0)
                        scope.launch { page.scrollToItem(1) }
                }, up = { if (phone) focusHeader(if (primary != null) playFocus else backFocus) else headerTools(); true }, down = { false },
                modifier = Modifier.height(episodeHeight), lastWatched = lastWatched, loadInitialSeason = false)
        }
        }
    }
}


internal fun discoveryLastEpisode(card: DiscoveryCard, profile: String, recent: List<RecentItem>): Pair<Int, Int>? {
    val item = recent.filter { it.profileKey == profile && it.kind == FavoriteKind.SERIES &&
        (if (it.alternateTarget != null) it.alternateTarget.tmdbId == card.id
         else it.media.id == card.media.id || it.series?.id == card.media.id || it.series?.externalTmdbId == card.id) }
        .maxByOrNull { it.playedAtMillis } ?: return null
    val season = item.alternateTarget?.season ?: item.lastPlayed?.seasonNumber ?: return null
    val episode = item.alternateTarget?.episode ?: item.lastPlayed?.episodeNumber ?: return null
    return season to episode
}
