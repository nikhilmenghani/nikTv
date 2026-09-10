package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    item {
                        SubtitleTrackRow("Off", tracks.none { it.selected }) { onSelect(null) }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun SubtitleTrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}
