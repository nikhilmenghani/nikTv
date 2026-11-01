package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.model.*
import kotlinx.coroutines.delay

private val EpisodeSurface = Color(0xFF151720)
private val EpisodeOutline = Color(0xFF2A2D36)
private val EpisodeMuted = Color(0xFFAFB5C0)

@Composable
internal fun NewEpisodesSection(
    watchedSeries: List<WatchedSeries>,
    profileKey: String,
    favorites: List<FavoriteItem>,
    horizontal: Boolean,
    open: (WatchedSeries, MediaItem) -> Unit,
    clear: (WatchedSeries, MediaItem) -> Unit,
    markWatched: (WatchedSeries, MediaItem) -> Unit,
    openSeries: (MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit
) {
    val groups = remember(watchedSeries, profileKey) { newEpisodeGroups(watchedSeries, profileKey) }
    var showAll by rememberSaveable(profileKey) { mutableStateOf(false) }
    var selectedKey by rememberSaveable(profileKey) { mutableStateOf<String?>(null) }
    val selected = groups.firstOrNull { it.key == selectedKey }
    fun close() { selectedKey = null; showAll = false }
    fun play(group: NewEpisodeGroup, episode: MediaItem) { close(); open(group.watched, episode) }

    if (groups.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("New Episodes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = Color(0xFFD5D7DC))
            if (groups.size > 3) NikTvTextActionButton(onClick = { showAll = true }) { Text("See all (${groups.size})") }
        }
        if (horizontal) LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(4.dp)) {
            items(groups.take(3), key = { it.key }) { group ->
                NewEpisodeSeriesCard(group, { play(group, group.latest) }, { selectedKey = group.key }, Modifier.width(320.dp))
            }
        } else groups.take(3).forEach { group ->
            key(group.key) { NewEpisodeSeriesCard(group, { play(group, group.latest) }, { selectedKey = group.key }) }
        }
    }

    if (showAll || selectedKey != null) {
        val configuration = LocalConfiguration.current
        val firstFocus = remember { FocusRequester() }
        var latestFirst by rememberSaveable(selectedKey) { mutableStateOf(true) }
        val orderedEpisodes = remember(selected?.episodes, latestFirst) {
            selected?.episodes.orEmpty().let { if (latestFirst) it.asReversed() else it }
        }
        fun back() { if (selectedKey != null && showAll) selectedKey = null else close() }
        LaunchedEffect(selectedKey, selected?.episodes?.firstOrNull()?.id, groups.isEmpty()) {
            delay(100)
            runCatching { firstFocus.requestFocus() }
        }
        Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp * .88f).dp),
                shape = RoundedCornerShape(24.dp), color = Color(0xFF101116),
                border = BorderStroke(1.dp, EpisodeOutline)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected?.watched?.series?.title ?: "New Episodes", color = Color.White,
                        style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (selected != null) "${selected.episodes.size} unwatched" else "${groups.size} series with new episodes",
                            modifier = Modifier.weight(1f), color = EpisodeMuted, style = MaterialTheme.typography.bodySmall)
                        if (selected != null) NikTvSecondaryActionButton(
                            onClick = { latestFirst = !latestFirst },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (latestFirst) "Latest first" else "Earliest first", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (selectedKey != null && selected == null || groups.isEmpty()) {
                        Text("You're all caught up.", color = Color.White, modifier = Modifier.padding(vertical = 20.dp))
                    } else if (selected != null) {
                        // Start at the new first episode after sorting; keep D-pad focus on the toggle.
                        key(selected.key, latestFirst) {
                            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(3.dp)) {
                                items(orderedEpisodes, key = { it.id }) { episode ->
                                    val favorite = FavoriteItem(FavoriteKind.EPISODE, episode, selected.watched.series,
                                        profileKey = selected.watched.profileKey, categoryTitle = selected.watched.categoryTitle)
                                    CompactNewEpisodeRow(
                                        episode = episode,
                                        series = selected.watched.series,
                                        isFavorite = favorites.any { it.key == favorite.key },
                                        modifier = if (episode.id == orderedEpisodes.first().id) Modifier.focusRequester(firstFocus) else Modifier,
                                        play = { play(selected, episode) },
                                        favorite = { toggleFavorite(favorite) },
                                        markWatched = { markWatched(selected.watched, episode) },
                                        clear = { clear(selected.watched, episode) },
                                        openSeries = { close(); openSeries(selected.watched.series) }
                                    )
                                }
                            }
                        }
                    } else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(3.dp)) {
                        items(groups, key = { it.key }) { group ->
                            NewEpisodeSeriesCard(group, { play(group, group.latest) }, { selectedKey = group.key },
                                firstActionModifier = if (group.key == groups.first().key) Modifier.focusRequester(firstFocus) else Modifier)
                        }
                    }
                    NikTvSecondaryActionButton(
                        onClick = ::back,
                        modifier = Modifier.fillMaxWidth().then(if (groups.isEmpty() || selectedKey != null && selected == null) Modifier.focusRequester(firstFocus) else Modifier)
                    ) { Text(if (selectedKey != null && showAll) "Back to series" else "Close") }
                }
            }
        }
    }
}

@Composable
private fun NewEpisodeSeriesCard(
    group: NewEpisodeGroup,
    play: () -> Unit,
    viewEpisodes: () -> Unit,
    modifier: Modifier = Modifier,
    firstActionModifier: Modifier = Modifier
) {
    val next = group.latest
    val returningPlay = rememberReturningTile(play)
    val artwork = group.watched.series.takeUnless { it.logo.isNullOrBlank() } ?: next
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = EpisodeSurface,
        border = BorderStroke(1.dp, EpisodeOutline)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ModernPosterImage(artwork, LocalContext.current, Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(9.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.watched.series.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Surface(color = Color(0xFF4A1C24), shape = RoundedCornerShape(6.dp)) {
                        Text("${group.episodes.size} new", Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            color = Color(0xFFFFBAC3), style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Latest · ${next.newEpisodeLabel()}", color = EpisodeMuted, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(next.compactEpisodeTitle(), color = Color(0xFFE3E5EA), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvPrimaryActionButton(returningPlay.open, Modifier.weight(1f).then(firstActionModifier).then(returningPlay.modifier), contentPadding = PaddingValues(8.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play latest", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                NikTvSecondaryActionButton(viewEpisodes, Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    Text("View episodes", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun MediaItem.newEpisodeLabel(): String = listOfNotNull(
    seasonNumber?.let { "S$it" }, episodeNumber?.let { "E$it" }
).joinToString(" · ").ifBlank { "Episode" }

@Composable
private fun CompactNewEpisodeRow(
    episode: MediaItem, series: MediaItem, isFavorite: Boolean, modifier: Modifier,
    play: () -> Unit, favorite: () -> Unit, markWatched: () -> Unit, clear: () -> Unit, openSeries: () -> Unit
) {
    var menuOpen by remember(episode.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box {
        Surface(color = EpisodeSurface, shape = shape, border = BorderStroke(1.dp, EpisodeOutline)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier.weight(1f).remoteFocusFrame(shape)
                    .remoteCombinedClickable(onClick = play, onLongClick = { menuOpen = true }).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFFFBAC3), modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(episode.newEpisodeLabel(), episode.episodeAirDate).joinToString(" · "),
                            color = EpisodeMuted, style = MaterialTheme.typography.labelSmall)
                        Text(episode.compactEpisodeTitle(), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.remoteFocusFrame()) {
                    Icon(Icons.Default.MoreVert, "Options for ${episode.newEpisodeLabel()}", tint = EpisodeMuted)
                }
            }
        }
        ModernTileActionsMenu(menuOpen, isFavorite, { menuOpen = false }, favorite,
            recommendationItem = series, trailerSeries = true,
            alternateSource = AlternateSourceRequest(episode, CatalogType.SERIES, series,
                episode.seasonNumber, episode.episodeNumber),
            openSeries = openSeries, markWatched = markWatched, clear = clear)
    }
}
