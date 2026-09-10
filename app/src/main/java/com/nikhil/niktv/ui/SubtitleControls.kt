package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

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
    timingRequiresVlc: Boolean = false
) {
    val firstActionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100L)
        runCatching { firstActionFocusRequester.requestFocus() }
    }
    AlertDialog(
        modifier = Modifier.widthIn(min = 360.dp, max = 520.dp),
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    item {
                        SubtitleTrackRow(
                            "Off",
                            tracks.none { it.selected },
                            Modifier.focusRequester(firstActionFocusRequester)
                        ) { onSelect(null) }
                    }
                    items(tracks, key = { it.id }) { track ->
                        SubtitleTrackRow(track.label, track.selected) { onSelect(track.id) }
                    }
                    if (tracks.isEmpty()) {
                        item { Text("No subtitle tracks are available in this stream.", Modifier.padding(12.dp)) }
                    }
                }
                Text("Subtitle timing")
                Row(
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
                Button(onClick = { onDelayChange(0L) }, enabled = delayMs != 0L) { Text("Reset timing") }
                if (timingRequiresVlc) {
                    Text("Changing timing switches this playback session to VLC while preserving your position.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        properties = DialogProperties(usePlatformDefaultWidth = true)
    )
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
