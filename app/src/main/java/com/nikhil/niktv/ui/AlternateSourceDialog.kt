package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.data.AlternateSourceProvider
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.data.providerTmdbTitle
import com.nikhil.niktv.model.CatalogType
import java.security.MessageDigest

/** Signed source URLs live only in this session, not in favorites or IPTV mappings. */
data class AlternateSourcePickerState(
    val request: AlternateSourceRequest,
    val target: AlternateSourceTarget,
    val profileKey: String,
    val loading: Boolean = false,
    val streams: List<AlternateStream> = emptyList(),
    val error: String? = null,
    val resumePositionMillis: Long? = null,
    val playWhenReady: Boolean = true,
    val selectedId: String? = null
)

internal fun alternateTargetFor(request: AlternateSourceRequest): AlternateSourceTarget {
    val source = request.series ?: request.item
    val series = request.type == CatalogType.SERIES
    // The alternate source requires the title itself, rather than the provider's
    // language/quality decoration. Only complete metadata tags at the edges are
    // removed: language words within real titles remain searchable as written.
    val language = "english|eng|en|hindi|hin|hi|tamil|tam|ta|telugu|tel|te|kannada|kan|kn|malayalam|mal|ml|marathi|mar|mr|bengali|bangla|ben|bn|gujarati|guj|gu|punjabi|pan|pa|urdu|urd|ur|french|fra|fre|fr|spanish|spa|es|german|ger|deu|de|italian|ita|it|portuguese|por|pt|russian|rus|ru|arabic|ara|ar|japanese|jpn|ja|korean|kor|ko|chinese|mandarin|cantonese|zho|chi|zh|turkish|tur|tr|dutch|nld|dut|nl|polish|pol|pl|thai|tha|th|indonesian|ind|id|ukrainian|ukr|uk"
    val quality = "4k|8k|uhd|fhd|hd|sd|2160p|1080p|720p|480p|360p|hdr10\\+?|hdr|dolby\\s+vision|hevc|h[ .-]?26[45]|x26[45]|blu[ -]?ray|web[ -]?dl|web[ -]?rip|hdtv|dual[ -]?audio|multi[ -]?(?:audio|language)|multi|dubbed|subbed|e[ -]?subs?|aac|ddp?[ .]?[257]\\.1"
    val atom = "(?:$language|$quality|(?:19|20)\\d{2})"
    val metadata = Regex("^$atom(?:[\\s,|/+&-]+$atom)*$", RegexOption.IGNORE_CASE)
    val leadingGroup = Regex("^\\s*(?:\\[([^\\]]+)\\]|\\(([^)]+)\\))\\s*")
    val trailingGroup = Regex("\\s*(?:\\[([^\\]]+)\\]|\\(([^)]+)\\))\\s*$")
    val leadingLabel = Regex("^$atom(?:[\\s,/+&-]+$atom)*\\s*(?:\\|\\s*|\\s+[-–—]\\s+)", RegexOption.IGNORE_CASE)
    val trailingLabel = Regex("(?:\\s*\\|\\s*|\\s+[-–—]\\s+)$atom(?:[\\s,/+&-]+$atom)*$", RegexOption.IGNORE_CASE)
    var title = source.title.trim()
    for (pass in 0 until 8) {
        val normalized = if (series) providerSeriesTmdbTitle(title) else providerTmdbTitle(title)
        var next = normalized
        leadingGroup.find(next)?.takeIf { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { metadata.matches(it.trim()) } == true
        }?.let { next = next.removeRange(it.range).trimStart(' ', '|', ':', '-', '–', '—') }
        trailingGroup.find(next)?.takeIf { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { metadata.matches(it.trim()) } == true
        }?.let { next = next.removeRange(it.range).trimEnd(' ', '|', ':', '-', '–', '—') }
        next = next.replace(leadingLabel, "").replace(trailingLabel, "").trim()
        // A name consisting of a metadata word is still a name, not an empty query.
        if (next.isBlank() || next == title) break
        title = next
    }
    return AlternateSourceTarget(title = title, series = series,
        tmdbId = source.externalTmdbId?.takeIf { it > 0 }
            ?: source.id.removePrefix(if (series) "tmdb-series:" else "tmdb-movie:")
                .takeIf { source.id.startsWith(if (series) "tmdb-series:" else "tmdb-movie:") }
                ?.toIntOrNull()?.takeIf { it > 0 },
        year = if (series) null else Regex("[\\[(]((?:19|20)\\d{2})[)\\]]").find(source.title)?.groupValues?.get(1)?.toIntOrNull(),
        season = if (series) (request.season ?: request.item.seasonNumber ?: 1).coerceAtLeast(0) else null,
        episode = if (series) (request.episode ?: request.item.episodeNumber ?: 1).coerceAtLeast(1) else null)
}

/** An edited title is a new identity; retain only explicitly entered release-year hints. */
internal fun alternateEditedTarget(target: AlternateSourceTarget, enteredTitle: String): AlternateSourceTarget {
    if (enteredTitle.trim() == target.title) return target
    val request = AlternateSourceRequest(com.nikhil.niktv.model.MediaItem("alternate-query", enteredTitle.trim(), null, null),
        if (target.series) CatalogType.SERIES else CatalogType.MOVIES, season = target.season, episode = target.episode)
    return alternateTargetFor(request).copy(provider = target.provider)
}

/** Stable across expiring URLs and quality changes, and isolated from provider progress. */
internal fun alternateProgressKey(profile: String, target: AlternateSourceTarget): String {
    val identity = target.tmdbId?.let { "tmdb:$it" } ?: "${target.title.trim().lowercase(java.util.Locale.ROOT)}:${target.year ?: 0}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    return "$profile:alternate:${if (target.series) "tv" else "movie"}:$digest:${target.season ?: 0}:${target.episode ?: 0}"
}

internal fun alternateRetryStream(source: com.nikhil.niktv.model.AlternatePlaybackSource,
    streams: List<AlternateStream>): AlternateStream? {
    val previous = source.streams.firstOrNull { it.id == source.selectedId }
    val height = previous?.height
    return streams.firstOrNull { it.id == source.selectedId && it.route == previous?.route &&
            (height == null || it.height == height) }
        ?: previous?.height?.let { wanted -> streams.firstOrNull { it.height == wanted && it.route == previous.route } }
        ?: streams.firstOrNull { it.route == previous?.route } ?: streams.firstOrNull()
}

@Composable
internal fun AlternateSourceDialog(state: AlternateSourcePickerState, close: () -> Unit,
    search: (AlternateSourceTarget) -> Unit, play: (String) -> Unit) {
    var title by remember(state.request) { mutableStateOf(state.target.title) }
    var season by remember(state.request) { mutableStateOf((state.target.season ?: 1).toString()) }
    var episode by remember(state.request) { mutableStateOf((state.target.episode ?: 1).toString()) }
    val validInput = title.isNotBlank() && (!state.target.series ||
        (season.toIntOrNull()?.let { it >= 0 } == true && episode.toIntOrNull()?.let { it > 0 } == true))
    fun editedTarget(provider: AlternateSourceProvider = state.target.provider) =
        alternateEditedTarget(state.target, title).copy(provider = provider,
            season = if (state.target.series) season.toIntOrNull() else null,
            episode = if (state.target.series) episode.toIntOrNull() else null)
    val entry = remember { FocusRequester() }
    LaunchedEffect(state.loading, state.streams, state.error) {
        withFrameNanos { }; runCatching { entry.requestFocus() }
    }
    Dialog(onDismissRequest = close) {
        Surface(color = Color(0xFF181B22), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
            Column(Modifier.widthIn(max = 620.dp).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Play from another source", style = MaterialTheme.typography.titleLarge)
                Text("Plays in NikTV. This selection does not use IPTV lookup.", color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AlternateSourceProvider.entries.forEach { provider ->
                        NikTvSecondaryActionButton({ search(editedTarget(provider)) }, Modifier.weight(1f),
                            enabled = validInput && provider != state.target.provider) {
                            Text(if (provider == AlternateSourceProvider.AUTO) "Auto sources" else "Movy (native)")
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true,
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth())
                if (state.target.series) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(season, { season = it.filter(Char::isDigit).take(3) }, label = { Text("Season") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.loading, modifier = Modifier.weight(1f))
                    OutlinedTextField(episode, { episode = it.filter(Char::isDigit).take(5) }, label = { Text("Episode") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.loading, modifier = Modifier.weight(1f))
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = Color.LightGray) }
                if (state.target.provider == AlternateSourceProvider.MOVY && !state.loading && state.streams.isNotEmpty()) {
                    Text(if (state.streams.any { it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE })
                        "Try a native source first. The embedded player is available as a fallback."
                        else "Movy did not return a usable native stream. Retry below, or choose the separate Vidy embedded option.",
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
                state.streams.forEachIndexed { index, stream ->
                    if (stream.route == com.nikhil.niktv.data.AlternatePlaybackRoute.VIDY_EMBEDDED) {
                        Text("Vidy embedded fallback", style = MaterialTheme.typography.titleSmall)
                    }
                    NikTvSecondaryActionButton({ play(stream.id) }, Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(entry) else Modifier)) {
                        Text(stream.label + if (stream.id == state.selectedId) " · Current" else "")
                    }
                }
                NikTvSecondaryActionButton({
                    search(editedTarget())
                }, Modifier.fillMaxWidth().then(if (state.streams.isEmpty()) Modifier.focusRequester(entry) else Modifier),
                    enabled = !state.loading && validInput) {
                    Text(if (state.target.provider == AlternateSourceProvider.MOVY) "Retry Movy native sources"
                        else if (state.streams.isEmpty()) "Find sources" else "Refresh sources")
                }
                NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
