package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal val LocalUpNextEpisodeTitle = staticCompositionLocalOf<(PlayingMedia) -> Flow<String?>> { { flowOf(null) } }

/** Own the key release as well, since opening controls moves focus out of the prompt. */
@Composable
internal fun Modifier.playerUpNextControls(visible: Boolean, showControls: () -> Unit): Modifier {
    var pressedKey by remember { mutableStateOf<Int?>(null) }
    return onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        when {
            pressedKey == code -> {
                if (event.type == KeyEventType.KeyUp) pressedKey = null
                true
            }
            visible && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                (event.key == Key.DirectionUp || code == android.view.KeyEvent.KEYCODE_MENU) -> {
                pressedKey = code
                showControls()
                true
            }
            else -> false
        }
    }
}

private val episodePrefix = Regex(
    """^(?:S\d+[\s:._-]*E(?:P(?:ISODE)?)?\s*#?\s*(\d+)|(?:EPISODE|EP|E)\s*#?\s*(\d+))(?=$|[\s.:|_\-–—·])""",
    RegexOption.IGNORE_CASE
)
private val episodeDatePrefix = Regex("""^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?=$|[\s.:|_\-–—·])""")
private val episodeTitleSeparators = charArrayOf(' ', '\t', '.', ':', '|', '_', '-', '–', '—', '·')

private fun cleanUpNextTitle(title: String, seriesTitle: String?): String {
    val text = title.trim()
    val withoutSeries = if (!seriesTitle.isNullOrBlank() && text.startsWith(seriesTitle, ignoreCase = true) &&
        text.getOrNull(seriesTitle.length)?.let { it in episodeTitleSeparators } != false) {
        text.drop(seriesTitle.length).trimStart(*episodeTitleSeparators)
    } else text
    return withoutSeries.replaceFirst(episodePrefix, "").trimStart(*episodeTitleSeparators)
        .replaceFirst(episodeDatePrefix, "").trimStart(*episodeTitleSeparators).trim()
}

internal fun upNextEpisodeTitle(episode: MediaItem, seriesTitle: String?): String =
    cleanUpNextTitle(episode.title, seriesTitle).ifBlank {
        val number = episode.episodeNumber ?: episodePrefix.find(episode.title.trim())?.groupValues
            ?.drop(1)?.firstOrNull(String::isNotBlank)?.toIntOrNull()
        number?.let { "Episode $it" } ?: "Next episode"
    }

internal fun cachedUpNextEpisodeTitle(playing: PlayingMedia, caches: List<EpisodeSeasonCache>): String? {
    val next = playing.nextEpisode ?: return null
    val series = playing.series ?: return null
    if (playing.profileKey.isBlank()) return null
    fun matches(candidate: MediaItem): Boolean =
        (candidate.id == next.id || next.portalEpisodeId?.takeIf(String::isNotBlank) == candidate.portalEpisodeId &&
            !candidate.portalEpisodeId.isNullOrBlank()) &&
            (candidate.seasonNumber == null || next.seasonNumber == null || candidate.seasonNumber == next.seasonNumber)
    return caches.asSequence().filter {
        it.profileKey == playing.profileKey && it.seriesId == series.id &&
            (it.season == null || next.seasonNumber == null || it.season == next.seasonNumber)
    }.mapNotNull { cache ->
        val stored = cache.episodes.singleOrNull(::matches) ?: return@mapNotNull null
        val raw = cache.iptvEpisodes.singleOrNull(::matches)
        val cleaned = cleanUpNextTitle(stored.title, series.title).takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (raw != null && raw.title != stored.title && stored.externalTmdbId != null) stored.title.trim() else cleaned
    }.firstOrNull()
}

@Composable
internal fun PlayerUpNextPrompt(playing: PlayingMedia, countdown: Int, playFocus: FocusRequester,
    onCancel: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier, onShowControls: (() -> Unit)? = null) {
    val next = playing.nextEpisode ?: return
    val loadTitle = LocalUpNextEpisodeTitle.current
    val titleFlow = remember(loadTitle, playing.userScope, playing.profileKey, playing.series, next) { loadTitle(playing) }
    val storedTitle by titleFlow.collectAsStateWithLifecycle(initialValue = null)
    val fallbackTitle = remember(next, playing.series?.title) { upNextEpisodeTitle(next, playing.series?.title) }
    val titleScroll = rememberScrollState()
    val directionalInput = playerUsesDirectionalInput()
    LaunchedEffect(playFocus, directionalInput) {
        if (directionalInput) {
            withFrameNanos { }
            playFocus.requestFocus()
        }
    }
    if (onShowControls != null) BackHandler(onBack = onShowControls)
    Surface(modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        BoxWithConstraints(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            val inline = maxWidth >= (392 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
            // Give the title room to wrap without letting a long label dominate short video windows.
            val titleHeight = (maxHeight * .32f).coerceIn(40.dp, 144.dp)
            val infoModifier = if (onShowControls == null) Modifier else Modifier
                .focusProperties { canFocus = false }
                .clickable(onClickLabel = "Show playback controls", onClick = onShowControls)
            Layout(content = {
                Text("Up next in ${countdown}s", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(storedTitle ?: fallbackTitle, style = MaterialTheme.typography.titleMedium,
                    modifier = infoModifier.heightIn(max = titleHeight).verticalScroll(titleScroll))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NikTvTextActionButton(onClick = onCancel) { Text("Cancel") }
                    NikTvPrimaryActionButton(onClick = onPlay, modifier = Modifier.focusRequester(playFocus)) { Text("Play now") }
                }
            }) { measurables, constraints ->
                // Reposition the same buttons when the video window resizes so their focus survives.
                val gap = if (inline) 12.dp.roundToPx() else 8.dp.roundToPx()
                val width = constraints.maxWidth
                val content = constraints.copy(minWidth = 0, minHeight = 0)
                val actions = measurables[2].measure(content)
                val titleWidth = if (inline) (width - actions.width - gap).coerceAtLeast(0) else width
                val timer = measurables[0].measure(content.copy(maxWidth = titleWidth))
                val timerSpace = timer.height + 4.dp.roundToPx()
                val titleHeightLimit = if (!constraints.hasBoundedHeight) Constraints.Infinity else
                    (constraints.maxHeight - timerSpace - if (inline) 0 else actions.height + gap).coerceAtLeast(0)
                val title = measurables[1].measure(Constraints(maxWidth = titleWidth, maxHeight = titleHeightLimit))
                val titleCenter = timerSpace + title.height / 2
                val buttonCenter = actions.height / 2
                val center = if (inline) maxOf(titleCenter, buttonCenter) else titleCenter
                val titleTop = center - title.height / 2
                val actionsTop = if (inline) center - buttonCenter else titleTop + title.height + gap
                val height = constraints.constrainHeight(maxOf(titleTop + title.height, actionsTop + actions.height))
                layout(width, height) {
                    timer.placeRelative(0, titleTop - timerSpace)
                    title.placeRelative(0, titleTop)
                    actions.placeRelative(width - actions.width, actionsTop)
                }
            }
        }
    }
}
