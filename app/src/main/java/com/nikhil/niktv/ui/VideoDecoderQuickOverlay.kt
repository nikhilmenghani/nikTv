package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Opening/moving focus previews no changes. Only OK/touch selection rebuilds the decoder. */
@Composable
internal fun VideoDecoderQuickOverlay(mode: VideoDecoderMode, active: ActiveVideoDecoder?,
    onSelect: (VideoDecoderMode) -> Unit, onDismiss: () -> Unit) {
    val requesters = remember { VideoDecoderMode.entries.map { FocusRequester() } }
    val close = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp),
        containerColor = Color(0xF21A1A1A),
        title = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Video decoder", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("Current: ${active?.description ?: "No active video decoder"}",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFCDD3DD))
        } },
        text = { Column(Modifier.playerChoiceFocusEntry(requesters[mode.ordinal]),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoDecoderMode.entries.forEachIndexed { index, choice ->
                    var focused by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(10.dp)
                    Surface(onClick = { onSelect(choice) }, shape = shape,
                        modifier = Modifier.weight(1f).height(52.dp).focusRequester(requesters[index])
                            .onFocusChanged { focused = it.isFocused }
                            .focusProperties {
                                left = requesters.getOrNull(index - 1) ?: FocusRequester.Cancel
                                right = requesters.getOrNull(index + 1) ?: FocusRequester.Cancel
                                up = FocusRequester.Cancel; down = close
                            }.semantics { selected = mode == choice },
                        color = if (mode == choice) Color(0xFF303A49) else Color.White.copy(alpha = .055f),
                        border = BorderStroke(if (focused) 2.dp else 1.dp,
                            if (focused) Color.White else if (mode == choice) Color(0xFFE50914) else Color.White.copy(alpha = .16f))) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(choice.label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                        }
                    }
                }
            }
            Text(mode.explanation, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBFC5CE))
            Text("Applies on this device. Changing mode briefly restarts playback.",
                style = MaterialTheme.typography.labelSmall, color = Color(0xFF979EAA))
        } },
        confirmButton = { NikTvActionButton("Close", onClick = onDismiss,
            modifier = Modifier.width(96.dp).height(44.dp).focusRequester(close)
                .focusProperties { up = requesters[mode.ordinal] }) })
}
