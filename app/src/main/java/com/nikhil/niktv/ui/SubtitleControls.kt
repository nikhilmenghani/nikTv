package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nikhil.niktv.data.OnlineSubtitle
import com.nikhil.niktv.data.OpenSubtitlesClient
import com.nikhil.niktv.data.SubtitleSearchRequest
import com.nikhil.niktv.model.PlayingMedia
import java.io.File

internal data class SubtitleTrackOption(
    val id: String,
    val label: String,
    val selected: Boolean
)

internal fun PlayingMedia.suggestedSubtitleSearchTitle(): String {
    var title = (series?.title ?: media.title).trim()
    val trailingLanguageOrEdition = Regex(
        "\\s*[\\[(](?:english|hindi|french|spanish|german|italian|portuguese|arabic|turkish|urdu|tamil|telugu|korean|japanese|chinese|multi(?:[ -]?audio)?|dubbed|original|en|hi|fr|es|de|it|pt|ar)[\\])]\\s*$",
        RegexOption.IGNORE_CASE
    )
    while (trailingLanguageOrEdition.containsMatchIn(title)) {
        title = title.replace(trailingLanguageOrEdition, "").trim()
    }
    return title.ifBlank { series?.title ?: media.title }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SubtitleSelectionDialog(
    tracks: List<SubtitleTrackOption>,
    delayMs: Long,
    onSelect: (String?) -> Unit,
    onDelayChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    timingRequiresVlc: Boolean = false,
    internetSearch: SubtitleSearchRequest? = null,
    onExternalSubtitle: ((File) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchMode by remember { mutableStateOf(false) }
    var query by remember(internetSearch?.query) { mutableStateOf(internetSearch?.query.orEmpty()) }
    var language by remember { mutableStateOf(internetSearch?.languages ?: "en") }
    var queryEditing by remember { mutableStateOf(false) }
    var languageEditing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var focusSearchOutcome by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val keyboard = LocalSoftwareKeyboardController.current
    /*
     * SUBTITLE_EMPTY_TRACK_FOCUS_V41
     *
     * Timing controls only have meaning when the active stream exposes at
     * least one subtitle track. Keep that same fact in the D-pad focus graph
     * so every explicit destination corresponds to a composed control.
     */
    val hasSubtitleTracks = tracks.isNotEmpty()
    val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        (configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    val compact = !isTv && (configuration.smallestScreenWidthDp < 600 || configuration.screenHeightDp < 600)
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val panelWidthFraction = when {
        isTv -> .42f
        compact && landscape -> .62f
        compact -> .94f
        else -> .62f
    }
    val panelMaxWidth = when {
        isTv -> 720.dp
        compact -> 560.dp
        else -> 660.dp
    }
    val listHeight = when {
        compact && searchMode -> 132.dp
        compact -> 86.dp
        searchMode -> 230.dp
        else -> 190.dp
    }
    val modeFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val queryFocusRequester = remember { FocusRequester() }
    val languageFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val earlierFocusRequester = remember { FocusRequester() }
    val laterFocusRequester = remember { FocusRequester() }
    val resetFocusRequester = remember { FocusRequester() }
    val trackFocusRequesters = remember(tracks.map { it.id }) {
        List(tracks.size + 1) { FocusRequester() }
    }
    val trackHeaderFocusRequester =
        if (internetSearch != null && onExternalSubtitle != null) {
            modeFocusRequester
        } else {
            closeFocusRequester
        }
    val offDownFocusRequester =
        if (hasSubtitleTracks) {
            trackFocusRequesters[1]
        } else {
            trackFocusRequesters[0]
        }
    val resultFocusRequesters = remember(results.map { it.id }) {
        List(results.size) { FocusRequester() }
    }
    LaunchedEffect(searchMode) {
        delay(100L)
        runCatching {
            if (searchMode) searchFocusRequester.requestFocus()
            else trackHeaderFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(searching, results, searchError, focusSearchOutcome) {
        if (!searchMode || searching || !focusSearchOutcome) return@LaunchedEffect
        delay(80L)
        val destination = resultFocusRequesters.firstOrNull() ?: searchFocusRequester
        repeat(4) { attempt ->
            if (runCatching { destination.requestFocus() }.getOrDefault(false)) {
                focusSearchOutcome = false
                return@LaunchedEffect
            }
            delay(40L * (attempt + 1))
        }
        focusSearchOutcome = false
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().padding(
            end = if (compact) 8.dp else 24.dp,
            bottom = if (compact) 8.dp else 20.dp
        ),
        contentAlignment = Alignment.BottomEnd
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(panelWidthFraction)
                .widthIn(max = panelMaxWidth)
                .heightIn(max = (configuration.screenHeightDp - if (compact) 12 else 32).coerceAtLeast(220).dp),
            color = Color(0xF2111317),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(if (compact) 16.dp else 22.dp),
            border = BorderStroke(1.dp, Color(0xFF30343B)),
            shadowElevation = if (compact) 8.dp else 14.dp
        ) {
            Column(
                Modifier
                    .focusGroup()
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (searchMode) "Find subtitles" else "Subtitles",
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (internetSearch != null && onExternalSubtitle != null) {
                        TextButton(
                            onClick = { searchMode = !searchMode },
                            modifier = Modifier
                                .focusRequester(modeFocusRequester)
                                .focusProperties {
                                    left = modeFocusRequester
                                    right = closeFocusRequester
                                    down = if (searchMode) queryFocusRequester else trackFocusRequesters.first()
                                }
                                .remoteFocusFrame(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        ) {
                            Icon(if (searchMode) Icons.Default.Subtitles else Icons.Default.Search, null)
                            if (!compact) Text(if (searchMode) " Tracks" else " Online")
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(closeFocusRequester)
                            .focusProperties {
                                left = if (internetSearch != null && onExternalSubtitle != null) modeFocusRequester else closeFocusRequester
                                right = closeFocusRequester
                                down = if (searchMode) queryFocusRequester else trackFocusRequesters.first()
                            }
                            .remoteFocusFrame(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    ) { Text("Close") }
                }
                if (internetSearch != null && onExternalSubtitle != null) {
                    if (!searchMode && !compact) Text("Embedded and downloaded tracks", style = MaterialTheme.typography.labelSmall)
                }
                if (searchMode) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        readOnly = isTv && !queryEditing,
                        label = {
                            Text(if (internetSearch?.seasonNumber != null || internetSearch?.episodeNumber != null) "Series title" else "Movie title")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(queryFocusRequester)
                            .focusProperties {
                                up = modeFocusRequester
                                down = languageFocusRequester
                            }
                            .onFocusChanged {
                                if (!it.isFocused && queryEditing) {
                                    queryEditing = false
                                    keyboard?.hide()
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (isTv && !queryEditing && event.type == KeyEventType.KeyUp &&
                                    event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                                ) {
                                    queryEditing = true
                                    keyboard?.show()
                                    true
                                } else false
                            },
                        textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
                    )
                    internetSearch?.episodeTitle?.takeIf { it.isNotBlank() }?.let { episodeTitle ->
                        Text(
                            buildString {
                                internetSearch.seasonNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
                                internetSearch.episodeNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
                                if (isNotEmpty()) append(" · ")
                                append(episodeTitle)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB8BBC3),
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = language,
                            onValueChange = { language = it.take(12) },
                            readOnly = isTv && !languageEditing,
                            label = { Text("Languages") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(languageFocusRequester)
                                .focusProperties {
                                    up = queryFocusRequester
                                    right = searchFocusRequester
                                    down = resultFocusRequesters.firstOrNull() ?: languageFocusRequester
                                }
                                .onFocusChanged {
                                    if (!it.isFocused && languageEditing) {
                                        languageEditing = false
                                        keyboard?.hide()
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (isTv && !languageEditing && event.type == KeyEventType.KeyUp &&
                                        event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                                    ) {
                                        languageEditing = true
                                        keyboard?.show()
                                        true
                                    } else false
                                }
                        )
                        IconButton(
                            onClick = {
                                if (searching || query.isBlank()) return@IconButton
                                focusSearchOutcome = true
                                val request = internetSearch?.copy(query = query, languages = language)
                                    ?: SubtitleSearchRequest(query = query, languages = language)
                                scope.launch {
                                    searching = true
                                    searchError = null
                                    results = runCatching { OpenSubtitlesClient.search(request) }
                                        .onFailure { searchError = it.message ?: "Subtitle search failed." }
                                        .getOrDefault(emptyList())
                                    searching = false
                                }
                            },
                            modifier = Modifier
                                .focusRequester(searchFocusRequester)
                                .focusProperties {
                                    up = queryFocusRequester
                                    left = languageFocusRequester
                                    right = searchFocusRequester
                                    down = resultFocusRequesters.firstOrNull() ?: searchFocusRequester
                                }
                                .remoteFocusFrame(androidx.compose.foundation.shape.CircleShape)
                        ) { Icon(Icons.Default.Search, "Search") }
                    }
                    if (searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    searchError?.let { Text(it) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                        items(results.size, key = { results[it].id }) { index ->
                            val subtitle = results[index]
                            TextButton(
                                onClick = {
                                    if (downloadingId != null) return@TextButton
                                    scope.launch {
                                        downloadingId = subtitle.id
                                        searchError = null
                                        runCatching { OpenSubtitlesClient.download(context, subtitle) }
                                            .onSuccess { file -> onExternalSubtitle?.invoke(file); onDismiss() }
                                            .onFailure { searchError = it.message ?: "Subtitle download failed." }
                                        downloadingId = null
                                    }
                                },
                                // Keep the focused result in the graph while its
                                // subtitle file is being downloaded.
                                enabled = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(resultFocusRequesters[index])
                                    .focusProperties {
                                        up = resultFocusRequesters.getOrNull(index - 1) ?: searchFocusRequester
                                        down = resultFocusRequesters.getOrNull(index + 1) ?: resultFocusRequesters[index]
                                        left = resultFocusRequesters[index]
                                        right = resultFocusRequesters[index]
                                    }
                                    .remoteFocusFrame(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            ) {
                                Text(subtitle.displayName, Modifier.weight(1f))
                                if (downloadingId == subtitle.id) CircularProgressIndicator(Modifier.padding(4.dp))
                            }
                        }
                        if (!searching && results.isEmpty() && searchError == null) {
                            item { Text("Search OpenSubtitles using the suggested title or enter your own.", Modifier.padding(12.dp)) }
                        }
                    }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                    item {
                        SubtitleTrackRow(
                            "Off",
                            tracks.none { it.selected },
                            Modifier
                                .focusRequester(trackFocusRequesters[0])
                                .focusProperties {
                                    up = trackHeaderFocusRequester
                                    down = offDownFocusRequester
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        offDownFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                        ) { onSelect(null) }
                    }
                    items(tracks.size, key = { tracks[it].id }) { index ->
                        val track = tracks[index]
                        val requesterIndex = index + 1
                        SubtitleTrackRow(
                            track.label,
                            track.selected,
                            Modifier
                                .focusRequester(trackFocusRequesters[requesterIndex])
                                .focusProperties {
                                    up = trackFocusRequesters[requesterIndex - 1]
                                    down = trackFocusRequesters.getOrNull(requesterIndex + 1) ?: earlierFocusRequester
                                }
                                .onPreviewKeyEvent { event ->
                                    when {
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                            trackFocusRequesters[requesterIndex - 1].requestFocus(); true
                                        }
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                            (trackFocusRequesters.getOrNull(requesterIndex + 1) ?: earlierFocusRequester).requestFocus(); true
                                        }
                                        else -> false
                                    }
                                }
                        ) { onSelect(track.id) }
                    }
                    if (tracks.isEmpty()) {
                        item { Text("No subtitle tracks are available in this stream.", Modifier.padding(12.dp)) }
                    }
                }
                if (!searchMode && hasSubtitleTracks && !compact) Text("Subtitle timing")
                if (!searchMode && hasSubtitleTracks) Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onDelayChange((delayMs - 250L).coerceAtLeast(-10_000L)) },
                        modifier = Modifier
                            .focusRequester(earlierFocusRequester)
                            .focusProperties {
                                up = trackFocusRequesters.last()
                                left = earlierFocusRequester
                                right = laterFocusRequester
                                down = if (delayMs != 0L) resetFocusRequester else earlierFocusRequester
                            }
                            .remoteFocusFrame(androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, "Show subtitles earlier")
                    }
                    Text(if (delayMs == 0L) "0 ms" else "%+d ms".format(delayMs))
                    IconButton(
                        onClick = { onDelayChange((delayMs + 250L).coerceAtMost(10_000L)) },
                        modifier = Modifier
                            .focusRequester(laterFocusRequester)
                            .focusProperties {
                                up = trackFocusRequesters.last()
                                left = earlierFocusRequester
                                right = laterFocusRequester
                                down = if (delayMs != 0L) resetFocusRequester else laterFocusRequester
                            }
                            .remoteFocusFrame(androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(Icons.Default.Add, "Show subtitles later")
                    }
                }
                if (!searchMode && hasSubtitleTracks && delayMs != 0L) TextButton(
                    onClick = { onDelayChange(0L) },
                    modifier = Modifier
                        .focusRequester(resetFocusRequester)
                        .focusProperties {
                            up = earlierFocusRequester
                            down = resetFocusRequester
                        }
                        .remoteFocusFrame(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                ) { Text("Reset timing") }
                if (!searchMode && hasSubtitleTracks && timingRequiresVlc && !compact) {
                    Text("Changing timing switches this playback session to VLC while preserving your position.")
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().remoteFocusFrame(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}
