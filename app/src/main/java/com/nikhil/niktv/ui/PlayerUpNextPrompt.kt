package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal val LocalUpNextEpisodeTitle = staticCompositionLocalOf<(PlayingMedia) -> Flow<String?>> { { flowOf(null) } }

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
    onCancel: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val next = playing.nextEpisode ?: return
    val loadTitle = LocalUpNextEpisodeTitle.current
    val titleFlow = remember(loadTitle, playing.userScope, playing.profileKey, playing.series, next) { loadTitle(playing) }
    val storedTitle by titleFlow.collectAsStateWithLifecycle(initialValue = null)
    val fallbackTitle = remember(next, playing.series?.title) { upNextEpisodeTitle(next, playing.series?.title) }
    val titleScroll = rememberScrollState()
    Surface(modifier.widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Up next in ${countdown}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(storedTitle ?: fallbackTitle, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(titleScroll))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
                NikTvTextActionButton(onClick = onCancel) { Text("Cancel") }
                NikTvPrimaryActionButton(onClick = onPlay, modifier = Modifier.focusRequester(playFocus)) { Text("Play now") }
            }
        }
    }
}
