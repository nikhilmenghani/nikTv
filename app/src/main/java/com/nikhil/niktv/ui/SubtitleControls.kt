package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nikhil.niktv.data.OnlineSubtitle
import com.nikhil.niktv.data.OpenSubtitlesClient
import com.nikhil.niktv.data.SubtitleSearchRequest
import java.io.File

internal data class SubtitleTrackOption(
    val id: String,
    val label: String,
    val selected: Boolean
)

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
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
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
    val firstActionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchMode) {
        delay(100L)
        runCatching { firstActionFocusRequester.requestFocus() }
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
                Modifier.padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 14.dp),
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
                            modifier = if (!searchMode) Modifier.focusRequester(firstActionFocusRequester) else Modifier
                        ) {
                            Icon(if (searchMode) Icons.Default.Subtitles else Icons.Default.Search, null)
                            if (!compact) Text(if (searchMode) " Tracks" else " Online")
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (internetSearch != null && onExternalSubtitle != null) {
                    if (!searchMode && !compact) Text("Embedded and downloaded tracks", style = MaterialTheme.typography.labelSmall)
                }
                if (searchMode) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Movie or series title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstActionFocusRequester),
                        textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = language,
                            onValueChange = { language = it.take(12) },
                            label = { Text("Languages") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
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
                            enabled = query.isNotBlank() && !searching
                        ) { Icon(Icons.Default.Search, "Search") }
                    }
                    if (searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    searchError?.let { Text(it) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                        items(results, key = { it.id }) { subtitle ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        downloadingId = subtitle.id
                                        searchError = null
                                        runCatching { OpenSubtitlesClient.download(context, subtitle) }
                                            .onSuccess { file -> onExternalSubtitle?.invoke(file); onDismiss() }
                                            .onFailure { searchError = it.message ?: "Subtitle download failed." }
                                        downloadingId = null
                                    }
                                },
                                enabled = downloadingId == null,
                                modifier = Modifier.fillMaxWidth()
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
                            tracks.none { it.selected }
                        ) { onSelect(null) }
                    }
                    items(tracks, key = { it.id }) { track ->
                        SubtitleTrackRow(track.label, track.selected) { onSelect(track.id) }
                    }
                    if (tracks.isEmpty()) {
                        item { Text("No subtitle tracks are available in this stream.", Modifier.padding(12.dp)) }
                    }
                }
                if (!searchMode && !compact) Text("Subtitle timing")
                if (!searchMode) Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onDelayChange((delayMs - 250L).coerceAtLeast(-10_000L)) }) {
                        Icon(Icons.Default.Remove, "Show subtitles earlier")
                    }
                    Text(if (delayMs == 0L) "0 ms" else "%+d ms".format(delayMs))
                    IconButton(onClick = { onDelayChange((delayMs + 250L).coerceAtMost(10_000L)) }) {
                        Icon(Icons.Default.Add, "Show subtitles later")
                    }
                }
                if (!searchMode && delayMs != 0L) TextButton(onClick = { onDelayChange(0L) }) { Text("Reset timing") }
                if (!searchMode && timingRequiresVlc && !compact) {
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
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}
