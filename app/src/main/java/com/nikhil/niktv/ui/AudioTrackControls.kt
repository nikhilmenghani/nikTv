package com.nikhil.niktv.ui

import android.view.Gravity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

internal data class PlayerAudioTrackOption(val id: String, val label: String, val selected: Boolean)

@Composable
internal fun PlayerAudioTrackDialog(
    tracks: List<PlayerAudioTrackOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val requesters = remember(tracks.map { it.id }) { tracks.map { FocusRequester() } }
    LaunchedEffect(tracks) {
        delay(100L)
        val selected = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
        runCatching { requesters.getOrNull(selected)?.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = Color(0xF21A1A1A),
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.END) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Audio language")
                Text("Choose an audio track for this video", color = Color.White.copy(alpha = .62f),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(
                Modifier.widthIn(min = 280.dp, max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                tracks.forEachIndexed { index, track ->
                    NikTvTextActionButton(
                        onClick = { onSelect(track.id) },
                        modifier = Modifier.fillMaxWidth().focusRequester(requesters[index]),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(track.label, Modifier.weight(1f), maxLines = 2)
                            if (track.selected) Icon(Icons.Default.Check, "Selected")
                        }
                    }
                }
            }
        },
        confirmButton = {
            NikTvTextActionButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Close") }
        }
    )
}
