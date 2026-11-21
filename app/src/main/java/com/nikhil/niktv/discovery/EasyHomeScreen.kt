package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.WatchedSeries
import com.nikhil.niktv.model.distinctRecentContent
import com.nikhil.niktv.ui.LocalMarkUnwatched
import com.nikhil.niktv.ui.LocalRemoteFocusMargin
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.XtreamContentGate
import com.nikhil.niktv.ui.continueWatchingSubtitle
import com.nikhil.niktv.ui.episodeRemainingLabel
import com.nikhil.niktv.ui.episodeTitleWithoutIdentity
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.ui.newEpisodeGroups
import com.nikhil.niktv.ui.seriesEpisodeProgress
import com.nikhil.niktv.ui.remoteFocusFrame
import com.nikhil.niktv.ui.usesRemoteNavigation

internal enum class EasyHomeAction(val label: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Default.LiveTv),
    MOVIES("Movies", Icons.Default.Movie),
    SERIES("Series", Icons.Default.Tv),
    SEARCH("Search", Icons.Default.Search),
    SAVED("My List", Icons.Default.VideoLibrary),
    RESUME("Continue watching", Icons.Default.PlayArrow),
    PROFILES("Profiles", Icons.Default.Person),
    SETTINGS("Settings", Icons.Default.Settings)
}

private val easyHomeDestinations = listOf(
    EasyHomeAction.LIVE, EasyHomeAction.MOVIES, EasyHomeAction.SERIES,
    EasyHomeAction.SEARCH, EasyHomeAction.SAVED
)

internal fun NikTvState.easyHomeRecents(): List<RecentItem> {
    val portal = session?.profile?.cacheKey() ?: return emptyList()
    val gate = XtreamContentGate(this)
    return recentlyPlayed.asSequence()
        .filter { it.profileKey == portal && it.alternateTarget == null }
        .filter { recent ->
            val type = when (recent.kind) {
                FavoriteKind.MOVIE -> CatalogType.MOVIES
                FavoriteKind.SERIES, FavoriteKind.EPISODE -> CatalogType.SERIES
                else -> return@filter false
            }
            val media = recent.lastPlayed ?: recent.media
            (recent.kind != FavoriteKind.SERIES || recent.lastPlayed != null) &&
                recent.media.isEasyIptvTitle() && media.isEasyIptvTitle() && gate.allows(media, type, recent.categoryTitle) &&
                gate.allows(recent.series ?: recent.media, type, recent.categoryTitle)
        }
        .toList().distinctRecentContent()
}

internal enum class EasyHomeSection(val title: String, val emptyMessage: String) {
    NEW_EPISODES("New Episodes", "No new episodes from the series you follow."),
    CONTINUE_WATCHING("Continue Watching", "Nothing to continue watching.")
}

internal const val EASY_HOME_PREVIEW_SIZE = 10

internal data class EasyHomeMedia(val entry: EasyBrowseEntry, val open: () -> Unit, val actions: List<EasyMediaAction>)

internal fun EasyHomeSection.viewAllKey() = "easy-view-all:$name"

@Composable
internal fun easyHomeMedia(
    state: NikTvState,
    returnTo: (String) -> Unit,
    openRecent: (RecentItem) -> Unit,
    openEpisode: (WatchedSeries, MediaItem) -> Unit,
    openSeries: (MediaItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    markWatched: (WatchedSeries, MediaItem) -> Unit,
    dismissEpisode: (WatchedSeries, MediaItem) -> Unit
): Map<EasyHomeSection, List<EasyHomeMedia>> {
    val profile = state.session?.profile?.cacheKey() ?: return emptyMap()
    val markUnwatched = LocalMarkUnwatched.current
    val gate = XtreamContentGate(state)
    val saved = remember(state.favorites) { state.favorites.mapTo(mutableSetOf(), FavoriteItem::key) }
    fun save(favorite: FavoriteItem) = easySaveAction(favorite.key in saved) { toggleFavorite(favorite) }

    val newEpisodes = newEpisodeGroups(state.watchedSeries, profile).mapNotNull { group ->
        val watched = group.watched
        if (!watched.series.isEasyIptvTitle() || !gate.allows(watched.series, CatalogType.SERIES, watched.categoryTitle))
            return@mapNotNull null
        val episode = group.episodes.asReversed().firstOrNull { it.isEasyIptvTitle() &&
            episodeRemainingLabel(seriesEpisodeProgress(state.playbackProgress, profile, watched.series, it)) != "Watched" }
            ?: return@mapNotNull null
        val key = "new:${group.key}"
        val detail = listOfNotNull(episode.easyEpisodeLabel(), episode.episodeTitleWithoutIdentity().takeIf { it.isNotBlank() })
            .joinToString(" · ").ifBlank { null }
        val favorite = FavoriteItem(FavoriteKind.EPISODE, episode, watched.series, profileKey = profile,
            categoryTitle = watched.categoryTitle)
        EasyHomeMedia(
            EasyBrowseEntry(key, watched.series.title, Icons.Default.PlayArrow, detail, kindLabel = "Series"),
            { returnTo(key); openEpisode(watched, episode) },
            listOf(
                EasyMediaAction("Open series", Icons.Default.Tv) { returnTo(key); openSeries(watched.series) },
                save(favorite),
                EasyMediaAction("Mark as watched", Icons.Default.Check, removesTile = true) { markWatched(watched, episode) },
                EasyMediaAction("Dismiss new episodes", Icons.Default.Close, removesTile = true) { dismissEpisode(watched, episode) }
            )
        )
    }
    val recents = state.easyHomeRecents().map { recent ->
        val series = when (recent.kind) {
            FavoriteKind.SERIES -> recent.series ?: recent.media
            FavoriteKind.EPISODE -> recent.series
            else -> null
        }
        val progress = if (recent.kind == FavoriteKind.EPISODE && series != null)
            seriesEpisodeProgress(state.playbackProgress, profile, series, recent.media)
        else discoveryRecentProgress(recent, state.playbackProgress)
        val key = "recent:${recent.key}"
        val favorite = FavoriteItem(recent.kind, recent.media, recent.series, profileKey = profile, categoryTitle = recent.categoryTitle)
        EasyHomeMedia(
            EasyBrowseEntry(key, series?.title ?: recent.media.title, Icons.Default.PlayArrow,
                recent.continueWatchingSubtitle().takeIf { it.isNotBlank() }, progress = progress.easyProgressFraction(),
                status = episodeRemainingLabel(progress), kindLabel = recent.kind.easyDestination().label),
            { returnTo(key); openRecent(recent) },
            buildList {
                if (series != null) add(EasyMediaAction("Open series", Icons.Default.Tv) { returnTo(key); openSeries(series) })
                add(save(favorite))
                val item = recent.lastPlayed ?: recent.media
                val type = if (recent.kind == FavoriteKind.MOVIE) CatalogType.MOVIES else CatalogType.SERIES
                if (markUnwatched != null && episodeRemainingLabel(progress) != null &&
                    (type == CatalogType.MOVIES || series != null)) {
                    add(EasyMediaAction("Mark as unwatched", Icons.Default.Replay) { markUnwatched(item, type, series) })
                }
                add(EasyMediaAction("Remove from Continue Watching", Icons.Default.Close, removesTile = true) { removeRecent(recent) })
            }
        )
    }
    return mapOf(EasyHomeSection.NEW_EPISODES to newEpisodes, EasyHomeSection.CONTINUE_WATCHING to recents)
}

@Composable
internal fun EasyHomeScreen(sections: Map<EasyHomeSection, List<EasyHomeMedia>>, returnTo: EasyHomeAction,
    returningKey: String?, onAction: (EasyHomeAction) -> Unit, viewAll: (EasyHomeSection) -> Unit) {
    val keys = buildList {
        add("home-shortcuts")
        EasyHomeSection.entries.forEach { section ->
            val rows = sections[section].orEmpty()
            if (rows.isNotEmpty()) {
                add("heading:${section.name}")
                addAll(rows.take(EASY_HOME_PREVIEW_SIZE).map { it.entry.key })
                if (rows.size > EASY_HOME_PREVIEW_SIZE) add(section.viewAllKey())
            }
        }
    }
    val requesters = remember { EasyHomeAction.entries.associateWith { FocusRequester() } }
    val restoredRequester = remember { FocusRequester() }
    val restored = remember {
        returningKey?.takeIf { it in keys } ?: EasyHomeSection.entries
            .firstOrNull { it.viewAllKey() == returningKey }
            ?.let { sections[it]?.take(EASY_HOME_PREVIEW_SIZE)?.lastOrNull()?.entry?.key }
    }
    val initialAction = remember {
        if (returnTo == EasyHomeAction.RESUME) EasyHomeAction.LIVE else returnTo
    }
    val initialTarget = if (restored != null) restoredRequester else requesters.getValue(initialAction)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = keys.indexOf(restored).coerceAtLeast(0))
    val tileRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val remote = context.usesRemoteNavigation(configuration) || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val spacing = if (configuration.screenHeightDp < 440) 10.dp else 16.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalRemoteFocusMargin provides 6.dp) {
                LazyColumn(
                    Modifier.widthIn(max = 960.dp).fillMaxWidth()
                        .initialFocusEntry(initialTarget, remote),
                    state = list,
                    contentPadding = PaddingValues(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    item(key = "home-shortcuts") {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Home", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                listOf(EasyHomeAction.PROFILES, EasyHomeAction.SETTINGS).forEach { action ->
                                    NikTvSecondaryActionButton({ onAction(action) },
                                        Modifier.heightIn(min = 48.dp).focusRequester(requesters.getValue(action))) {
                                        Text(action.label)
                                    }
                                }
                            }
                            BoxWithConstraints {
                                val columns = if (maxWidth >= 560.dp) 3 else 2
                                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                                    easyHomeDestinations.chunked(columns).forEach { actions ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                            actions.forEach { action ->
                                                EasyHomeTile(action, { onAction(action) },
                                                    Modifier.weight(1f).focusRequester(requesters.getValue(action)))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EasyHomeSection.entries.forEach { section ->
                        val rows = sections[section].orEmpty()
                        if (rows.isEmpty()) return@forEach
                        item(key = "heading:${section.name}") {
                            Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        items(rows.take(EASY_HOME_PREVIEW_SIZE), key = { it.entry.key }) { media ->
                            val key = media.entry.key
                            val requester = remember { FocusRequester() }
                            DisposableEffect(key, requester) {
                                tileRequesters[key] = requester
                                onDispose { tileRequesters.remove(key) }
                            }
                            val modifier = Modifier.focusRequester(requester)
                                .then(if (key == restored) Modifier.focusRequester(restoredRequester) else Modifier)
                            EasyMediaTile(media.entry, media.open, media.actions, modifier) {
                                if (remote) focusEasyNeighbour(keys.filter { it in tileRequesters }, key,
                                    { tileRequesters[it]?.requestFocus() == true }) {
                                    requesters[EasyHomeAction.LIVE]?.requestFocus()
                                }
                            }
                        }
                        if (rows.size > EASY_HOME_PREVIEW_SIZE) item(key = section.viewAllKey()) {
                            EasyBrowseTile(EasyBrowseEntry(section.viewAllKey(), "View All", Icons.Default.ViewList,
                                "${rows.size} titles", opensList = true), { viewAll(section) },
                                (if (section.viewAllKey() == restored) Modifier.focusRequester(restoredRequester)
                                else Modifier).semantics { contentDescription = "View all ${section.title.lowercase()}" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EasyHomeTile(action: EasyHomeAction, open: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Surface(onClick = open, modifier = modifier.heightIn(min = 84.dp).remoteFocusFrame(shape),
        shape = shape, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(action.icon, null, Modifier.size(26.dp))
            Text(action.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
