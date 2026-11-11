package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
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
    val context = LocalContext.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
    val reduceMotion = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val playFocus = remember(card.key) { FocusRequester() }
    val episodesFocus = remember(card.key) { FocusRequester() }
    val castFocus = remember(card.key) { FocusRequester() }
    val relatedFocus = remember(card.key) { FocusRequester() }
    val infoFocus = remember(card.key) { FocusRequester() }
    val backFocus = remember(card.key) { FocusRequester() }
    val scope = rememberCoroutineScope()
    var hasSynopsis by remember(card.key) { mutableStateOf(false) }
    var hasCast by remember(card.key) { mutableStateOf(false) }
    var lastHeroControl by remember(card.key) { mutableStateOf<FocusRequester?>(null) }
    var navigationMove by remember(card.key) { mutableStateOf<Job?>(null) }
    DisposableEffect(card.key) { onDispose { navigationMove?.cancel() } }
    fun focusHeader(requester: FocusRequester) {
        navigationMove?.cancel()
        navigationMove = scope.launch {
            repeat(20) { withFrameNanos { }; if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch }
        }
    }
    fun focusEpisodes() {
        navigationMove?.cancel()
        navigationMove = scope.launch {
            repeat(20) { withFrameNanos { }; if (runCatching { episodesFocus.requestFocus() }.getOrDefault(false)) return@launch }
        }
    }
    fun focusPanel(entry: () -> Boolean) {
        navigationMove?.cancel()
        navigationMove = scope.launch {
            repeat(20) { withFrameNanos { }; if (entry()) return@launch }
        }
    }
    fun headerTools() = focusHeader(if (hasCast) castFocus else relatedFocus)
    var panel by rememberSaveable(state.profile, card.key) { mutableStateOf<String?>(null) }
    BackHandler(panel != null) {
        val origin = if (panel == "cast") castFocus else relatedFocus
        panel = null
        focusHeader(origin)
    }
    val details = state.details ?: DiscoveryDetails(card)
    val lastWatched = discoveryLastEpisode(card, state.profile, app.recentlyPlayed)?.takeIf {
        state.details == null || it.first in details.seasons
    }
    var newestFirst by rememberSaveable(state.profile, card.key) { mutableStateOf(true) }
    val primary = lastWatched ?: state.episodes.filter { it.season == state.season }.let { episodes ->
        (if (newestFirst) episodes.maxByOrNull { it.number } else episodes.minByOrNull { it.number })
            ?.let { it.season to it.number }
    }
    var automaticSeason by rememberSaveable(state.profile, card.key) { mutableStateOf<Int?>(null) }
    var emptyInitialSeasons by rememberSaveable(state.profile, card.key) { mutableStateOf(listOf<Int>()) }
    var initialFocusDone by remember(card.key) { mutableStateOf(false) }
    // Metadata/initial-season loading belongs to the route, not a lazy child
    // that can leave composition while its hero or cast panel is visible.
    LaunchedEffect(card.key, state.details?.card?.key) {
        val initial = discoveryInitialSeriesSeason(state.details, app.seriesStartSeason, lastWatched?.first)
        if (state.season == null) initial?.let {
            automaticSeason = it.takeIf { lastWatched == null }
            loadSeason(it)
        }
    }
    LaunchedEffect(state.season, state.episodes, state.loading, state.errors["season"], automaticSeason) {
        val selected = automaticSeason ?: return@LaunchedEffect
        if (state.season != selected || "season" in state.loading) return@LaunchedEffect
        if (state.episodes.isNotEmpty() || state.errors["season"] != null) {
            automaticSeason = null
        } else {
            // Older cached details have no counts. An actual empty response is
            // authoritative; try another season only for automatic selection.
            emptyInitialSeasons = (emptyInitialSeasons + selected).distinct()
            val next = discoverySeriesSeasonAfterEmpty(state.details, app.seriesStartSeason, emptyInitialSeasons.toSet())
            automaticSeason = next
            next?.let(loadSeason)
        }
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
        val episodePane: @Composable () -> Unit = {
            DiscoverySeriesEpisodes(state, app.seriesStartSeason, episodesFocus,
                { automaticSeason = null; initialFocusDone = true; loadSeason(it) }, play, download,
                onFocus = {}, up = {
                    val origin = lastHeroControl?.takeUnless {
                        it == infoFocus && !hasSynopsis || it == castFocus && !hasCast
                    } ?: if (primary != null) playFocus else backFocus
                    focusHeader(origin)
                    true
                }, down = { false }, modifier = Modifier.fillMaxSize(), lastWatched = lastWatched, loadInitialSeason = false,
                onNewestFirstChanged = { newestFirst = it })
        }
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
            playLabel = if (lastWatched != null) "Resume" else if (newestFirst) "Play latest" else "Start series", playEnabled = primary != null,
            down = { lastHeroControl = null; focusEpisodes() },
            topDown = {
                if (primary != null) focusHeader(playFocus)
                else if (hasSynopsis) focusHeader(infoFocus) else headerTools()
            }, heroUp = { focusHeader(backFocus) },
            synopsisRequester = infoFocus, synopsisAvailable = { hasSynopsis = it },
            synopsisUp = { focusHeader(backFocus) }, synopsisDown = { lastHeroControl = infoFocus; focusEpisodes() },
            castUp = { focusHeader(castFocus) }, relatedUp = { focusHeader(relatedFocus) },
            episodeDown = ::focusEpisodes,
            layout = { hero, cast, related, castEntry, relatedEntry ->
                SideEffect { hasCast = castEntry != null }
                fun Modifier.panelRoutes(kind: String, requester: FocusRequester, entry: (() -> Boolean)?) =
                    onFocusChanged { if (it.isFocused) lastHeroControl = requester }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { focusHeader(backFocus); true }
                        Key.DirectionDown -> {
                            lastHeroControl = requester
                            if (panel == kind && entry != null) focusPanel(entry) else focusEpisodes()
                            true
                        }
                        else -> false
                    }
                }
                val actions: @Composable () -> Unit = {
                    DiscoveryAction(if (panel == "cast") "Hide cast" else "Cast",
                        if (panel == "cast") Icons.Default.ExpandLess else Icons.Default.People,
                        selected = panel == "cast", iconOnly = true, enabled = castEntry != null,
                        modifier = Modifier.focusRequester(castFocus).panelRoutes("cast", castFocus, castEntry)) { panel = if (panel == "cast") null else "cast" }
                    DiscoveryAction(if (panel == "related") "Hide related" else "Related",
                        if (panel == "related") Icons.Default.ExpandLess else Icons.Default.Movie,
                        selected = panel == "related", iconOnly = true,
                        modifier = Modifier.focusRequester(relatedFocus).panelRoutes("related", relatedFocus, relatedEntry)) { panel = if (panel == "related") null else "related" }
                }
                val expanded: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().animateContentSize().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())) {
                        if (panel == "cast") cast()
                        if (panel == "related") related()
                    }
                }
                SeriesDetailScaffold(header = {
                    CompositionLocalProvider(LocalProviderSynopsisActions provides actions,
                        LocalProviderSynopsisPanel provides expanded) { hero() }
                }, episodes = episodePane)
            })
    }
}

/** Announced empty seasons must not disable Play when earlier episodes exist. */
internal fun discoveryInitialSeriesSeason(details: DiscoveryDetails?, start: SeriesStartSeason, resume: Int? = null): Int? {
    val all = details?.seasons.orEmpty().filter { it >= 0 }.distinct().sorted()
    if (resume != null && resume in all) return resume
    val regular = all.filter { it > 0 }.ifEmpty { all }
    val available = regular.filter { details?.seasonEpisodeCounts?.get(it) != 0 }.ifEmpty { regular }
    return if (start == SeriesStartSeason.LAST) available.lastOrNull() else available.firstOrNull()
}

internal fun discoverySeriesSeasonAfterEmpty(details: DiscoveryDetails?, start: SeriesStartSeason, empty: Set<Int>): Int? {
    val available = details?.seasons.orEmpty().filter { it >= 0 && it !in empty && details?.seasonEpisodeCounts?.get(it) != 0 }
        .distinct().sorted().let { seasons -> seasons.filter { it > 0 }.ifEmpty { seasons } }
    return if (start == SeriesStartSeason.LAST) available.lastOrNull() else available.firstOrNull()
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
