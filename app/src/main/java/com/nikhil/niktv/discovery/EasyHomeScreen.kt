package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.RecentItem
import com.nikhil.niktv.ui.LocalRemoteFocusMargin
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.XtreamContentGate
import com.nikhil.niktv.ui.initialFocusEntry
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

internal fun NikTvState.easyHomeResume(): RecentItem? {
    val portal = session?.profile?.cacheKey() ?: return null
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
                !recent.media.isAdult && !media.isAdult &&
                !recent.media.id.startsWith("tmdb-", ignoreCase = true) &&
                !media.id.startsWith("tmdb-", ignoreCase = true) &&
                gate.allows(recent.series ?: recent.media, type, recent.categoryTitle)
        }
        .maxByOrNull(RecentItem::playedAtMillis)
}

/** One landing page for both portrait touch and wide remote navigation. */
@Composable
internal fun EasyHomeScreen(recent: RecentItem?, returnTo: EasyHomeAction, onAction: (EasyHomeAction) -> Unit) {
    val requesters = remember { EasyHomeAction.entries.associateWith { FocusRequester() } }
    val initialAction = remember {
        if (returnTo == EasyHomeAction.RESUME && recent == null) EasyHomeAction.LIVE else returnTo
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val remote = context.usesRemoteNavigation(configuration) || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val spacing = if (configuration.screenHeightDp < 440) 10.dp else 16.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalRemoteFocusMargin provides 6.dp) {
                Column(
                    Modifier.widthIn(max = 960.dp).fillMaxWidth()
                        .initialFocusEntry(requesters.getValue(initialAction), remote)
                        .verticalScroll(rememberScrollState()).padding(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
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
                    if (recent != null) {
                        val action = EasyHomeAction.RESUME
                        EasyHomeTile(action, { onAction(action) },
                            Modifier.fillMaxWidth().focusRequester(requesters.getValue(action)),
                            subtitle = recent.series?.title ?: recent.media.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun EasyHomeTile(action: EasyHomeAction, open: () -> Unit, modifier: Modifier, subtitle: String? = null) {
    val shape = RoundedCornerShape(14.dp)
    Surface(onClick = open, modifier = modifier.heightIn(min = 84.dp).remoteFocusFrame(shape),
        shape = shape, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(action.icon, null, Modifier.size(26.dp))
            Column {
                Text(action.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
