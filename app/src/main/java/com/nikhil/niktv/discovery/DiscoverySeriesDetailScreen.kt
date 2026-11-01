package com.nikhil.niktv.discovery

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
) {
    val card = state.detail ?: return
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(configuration))
    val playFocus = remember(card.key) { FocusRequester() }
    val episodesFocus = remember(card.key) { FocusRequester() }
    val castFocus = remember(card.key) { FocusRequester() }
    val relatedFocus = remember(card.key) { FocusRequester() }
    var panel by rememberSaveable(state.profile, card.key) { mutableStateOf<String?>(null) }
    val details = state.details ?: DiscoveryDetails(card)
    val lastWatched = discoveryLastEpisode(card, state.profile, app.recentlyPlayed)
    val primary = lastWatched ?: state.episodes.maxByOrNull { it.number }?.let { it.season to it.number }
    LaunchedEffect(card.key) {
        lastWatched?.first?.let(loadSeason)
        repeat(12) { withFrameNanos { }; if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        ProviderDetailHeader(card.media, CatalogType.SERIES, app,
            play = { primary?.let { play(card, it.first, it.second) } },
            close = close, favorite = favorite,
            isFavorite = app.favorites.any { it.profileKey == state.profile && it.media.id == card.media.id },
            openTmdbMovie = openMovie, openTmdbSeries = openSeries,
            playRequester = playFocus, compactSeries = phone, discoveryDetails = details,
            playLabel = if (lastWatched != null) "Resume" else "Play latest", playEnabled = primary != null,
            down = { runCatching { episodesFocus.requestFocus() } },
            castUp = { runCatching { castFocus.requestFocus() }; Unit },
            relatedUp = { runCatching { relatedFocus.requestFocus() }; Unit },
            layout = { hero, cast, related, castEntry, relatedEntry ->
                val actions: @Composable () -> Unit = {
                    DiscoveryAction(if (panel == "cast") "Hide cast" else "Cast",
                        if (panel == "cast") Icons.Default.ExpandLess else Icons.Default.People,
                        selected = panel == "cast", iconOnly = true, enabled = castEntry != null,
                        modifier = Modifier.focusRequester(castFocus)) { panel = if (panel == "cast") null else "cast" }
                    DiscoveryAction(if (panel == "related") "Hide related" else "Related",
                        if (panel == "related") Icons.Default.ExpandLess else Icons.Default.Movie,
                        selected = panel == "related", iconOnly = true,
                        modifier = Modifier.focusRequester(relatedFocus)) { panel = if (panel == "related") null else "related" }
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
        DiscoverySeriesEpisodes(state, app.seriesStartSeason, episodesFocus, loadSeason, play, download,
            onFocus = {}, up = { runCatching { playFocus.requestFocus() }.getOrDefault(false) }, down = { false },
            modifier = Modifier.weight(1f), lastWatched = lastWatched)
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
