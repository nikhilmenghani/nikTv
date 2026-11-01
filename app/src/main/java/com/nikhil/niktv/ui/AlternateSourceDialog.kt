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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.data.AlternateSourceProvider
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.data.providerTmdbTitle
import com.nikhil.niktv.data.nativeSourceProviders
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
@OptIn(ExperimentalLayoutApi::class)
internal fun AlternateSourceDialog(state: AlternateSourcePickerState, close: () -> Unit,
    search: (AlternateSourceTarget) -> Unit, play: (String) -> Unit) {
    val configuration = LocalConfiguration.current
    val phone = configuration.smallestScreenWidthDp < 600 &&
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION
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
    Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = phone)) {
        Surface(color = Color(0xFF151820), contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFF343945)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.widthIn(max = if (phone) 620.dp else 480.dp).heightIn(max = minOf(if (phone) 680 else 520, configuration.screenHeightDp - 24).dp).padding(if (phone) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (phone) 14.dp else 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0x33E50925)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.padding(10.dp).size(22.dp), tint = Color(0xFFFF6778))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Playback sources", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (phone) Text("Choose a source and quality", fontSize = 12.sp, color = Color(0xFFADB5C5))
                    }
                    SourcePickerButton("Close", close, Modifier.size(if (phone) 48.dp else 40.dp), icon = Icons.Default.Close)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (phone) 14.dp else 8.dp)) {
                nativeSourceProviders.chunked(if (phone) 3 else 4).forEach { providers ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { provider ->
                        SourcePickerButton(
                            when (provider) { AlternateSourceProvider.AUTO -> "Auto"; AlternateSourceProvider.MOVY -> "Movy";
                                AlternateSourceProvider.STREAMING_UNITY -> "Unity"; AlternateSourceProvider.ATLANTIC_ATHENA -> "Athena";
                                AlternateSourceProvider.RIVE_PRIMEVIDS -> "Primevids"; AlternateSourceProvider.RIVE_CITADEL -> "Citadel";
                                AlternateSourceProvider.ONLYFLIX -> "OnlyFlix";
                                AlternateSourceProvider.SEVEN_MOVIES -> "7Movies";
                                AlternateSourceProvider.ABIBLI -> "Abibli";
                                else -> error("Browser sources are not selectable") },
                            { if (provider != state.target.provider) search(editedTarget(provider)) }, Modifier.weight(1f),
                            enabled = validInput, chosen = provider == state.target.provider)
                    }
                    repeat((if (phone) 3 else 4) - providers.size) { Spacer(Modifier.weight(1f)) }
                }
                }
                AlternateSourceInput(title, { title = it }, "Title", !state.loading, Modifier.fillMaxWidth())
                if (state.target.series) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlternateSourceInput(season, { season = it.filter(Char::isDigit).take(3) }, "Season", !state.loading, Modifier.weight(1f), KeyboardType.Number)
                    AlternateSourceInput(episode, { episode = it.filter(Char::isDigit).take(5) }, "Episode", !state.loading, Modifier.weight(1f), KeyboardType.Number)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = Color(0xFFFFB9C0), fontSize = 13.sp) }
                if (state.streams.isNotEmpty()) Text("AVAILABLE SOURCES", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = Color(0xFFADB5C5), letterSpacing = 1.sp)
                alternateServerRows(state.streams).forEachIndexed { rowIndex, row ->
                    Surface(color = Color(0xFF1D222D), contentColor = Color.White, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF323A49))) {
                    val serverTitle = row.title.replace("StreamingUnity", "Streaming Unity")
                        .replace("OnlyFlix Onetouchtv", "OnlyFlix \u00b7 OneTouchTV")
                    val qualities: @Composable (Modifier) -> Unit = { modifier ->
                        BoxWithConstraints(modifier) {
                            val columns = if (phone || maxWidth < 300.dp) 2 else 3
                            val labels = alternateQualityChoiceLabels(row.streams)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.streams.withIndex().toList().chunked(columns).forEach { cells ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        cells.forEach { (index, stream) ->
                                            SourcePickerButton(labels[index], { play(stream.id) },
                                                Modifier.weight(1f).then(if (rowIndex == 0 && index == 0) Modifier.focusRequester(entry) else Modifier),
                                                chosen = stream.id == state.selectedId, compact = true)
                                        }
                                        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    if (phone) {
                        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(serverTitle, Modifier.fillMaxWidth(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            qualities(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(serverTitle, Modifier.weight(.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            qualities(Modifier.weight(1.9f))
                        }
                    }
                    }
                }
                }
                HorizontalDivider(color = Color(0xFF303644))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourcePickerButton("Close", close, Modifier.weight(.8f))
                SourcePickerButton(if (state.streams.isEmpty()) "Find sources" else "Refresh", {
                    search(editedTarget())
                }, Modifier.weight(1.2f).then(if (state.streams.isEmpty()) Modifier.focusRequester(entry) else Modifier),
                    enabled = !state.loading && validInput, icon = Icons.Default.Refresh)
                }
            }
        }
    }
}

@Composable
private fun SourcePickerButton(label: String, click: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, chosen: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, compact: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val dense = config.smallestScreenWidthDp >= 600 || config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (dense) 40.dp else 48.dp) {
    Surface(onClick = click, enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics { selected = chosen },
        shape = RoundedCornerShape(10.dp), contentColor = if (enabled) Color.White else Color(0xFF7E8797),
        color = when { focused -> Color(0xFF354052); chosen -> Color(0xFF55202D); else -> Color(0xFF272D39) },
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            when { focused -> Color.White; chosen -> Color(0xFFED536A); else -> Color(0xFF414B5D) })) {
        Row(Modifier.heightIn(min = if (dense) 40.dp else if (compact) 44.dp else 48.dp).padding(horizontal = if (icon == Icons.Default.Close) 0.dp else if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) Icon(icon, if (icon == Icons.Default.Close) label else null, Modifier.size(18.dp))
            if (icon != Icons.Default.Close) {
                if (icon != null) Spacer(Modifier.width(6.dp))
                Text(label, fontSize = if (compact) 12.sp else 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, softWrap = false)
            }
        }
    }
    }
}

internal data class AlternateServerRow(val title: String, val streams: List<AlternateStream>)
internal fun alternateServerRows(streams: List<AlternateStream>): List<AlternateServerRow> =
    streams.filter { it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE }
        .groupBy { if (it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE) it.label.substringAfter(" · ", it.label)
            else it.label.substringBefore(" · ") }
        .map { (title, choices) -> AlternateServerRow(title, choices) }

internal fun alternateQualityLabel(stream: AlternateStream): String = when {
    stream.route == com.nikhil.niktv.data.AlternatePlaybackRoute.CNO_WEB -> "Web player"
    stream.route != com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE -> "Play"
    stream.height != null -> "${stream.height}p"
    else -> stream.label.substringBefore(" · ").takeIf { it == "Auto" || it.startsWith("Quality ") } ?: "Auto"
}

/** Preserve distinct mirrors, but never present indistinguishable quality choices. */
internal fun alternateQualityChoiceLabels(streams: List<AlternateStream>): List<String> {
    val bases = streams.map { stream ->
        alternateQualityLabel(stream) + (stream.sourceVariant?.let { " · ${it.replaceFirstChar(Char::uppercaseChar)}" } ?: "")
    }
    val counts = bases.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return bases.map { base ->
        if (counts.getValue(base) == 1) base else {
            val number = (seen[base] ?: 0) + 1
            seen[base] = number
            "$base $number"
        }
    }
}

/** Remote focus is navigation; only a completed Select/touch gesture starts editing. */
@Composable
private fun AlternateSourceInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean,
    modifier: Modifier, type: KeyboardType = KeyboardType.Text) {
    var editing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    fun activate() {
        if (!enabled) return
        editing = true
        scope.launch { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
    }
    OutlinedTextField(value, change, label = { Text(label, fontSize = 12.sp) }, singleLine = true, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFFED536A), unfocusedBorderColor = Color(0xFF465064),
            focusedLabelColor = Color(0xFFFF8C9E), unfocusedLabelColor = Color(0xFFADB5C5),
            cursorColor = Color(0xFFFF6778)),
        readOnly = !editing, keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide() }),
        modifier = modifier.focusRequester(focus).onFocusChanged {
            if (!it.isFocused) { editing = false; keyboard?.hide() }
        }.onPreviewKeyEvent {
            if (!editing && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                if (it.type == KeyEventType.KeyUp) activate()
                true
            } else false
        }.pointerInput(enabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) activate()
            }
        })
}

internal fun alternateHistoryProvider(selected: AlternateSourceProvider, label: String): AlternateSourceProvider {
    if (selected != AlternateSourceProvider.AUTO) return selected
    return when {
        label.contains("Abibli", true) -> AlternateSourceProvider.ABIBLI
        label.contains("7Movies", true) -> AlternateSourceProvider.SEVEN_MOVIES
        label.contains("OnlyFlix", true) -> AlternateSourceProvider.ONLYFLIX
        label.contains("Movy", true) -> AlternateSourceProvider.MOVY
        label.contains("Unity", true) -> AlternateSourceProvider.STREAMING_UNITY
        label.contains("Athena", true) -> AlternateSourceProvider.ATLANTIC_ATHENA
        label.contains("Primevid", true) -> AlternateSourceProvider.RIVE_PRIMEVIDS
        label.contains("Citadel", true) -> AlternateSourceProvider.RIVE_CITADEL
        else -> selected
    }
}

internal fun alternateResumeStream(streams: List<AlternateStream>, preferredHeight: Int?): AlternateStream? =
    streams.firstOrNull { preferredHeight != null && it.height == preferredHeight }
        ?: streams.firstOrNull { it.height != null } ?: streams.firstOrNull()
