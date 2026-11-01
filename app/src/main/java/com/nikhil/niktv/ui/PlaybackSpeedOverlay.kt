package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal val PLAYBACK_SPEED_PRESETS = listOf(.1f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
internal fun normalizedPlaybackSpeed(value: Float): Float = if (value.isFinite()) value.coerceIn(.1f, 4f) else 1f
internal fun playbackSpeedLabel(value: Float): String =
    java.math.BigDecimal(normalizedPlaybackSpeed(value).toString()).stripTrailingZeros().toPlainString() + "×"
internal fun adjustedPlaybackSpeed(value: Float, direction: Int): Float =
    normalizedPlaybackSpeed(((normalizedPlaybackSpeed(value) * 100).roundToInt() + direction.coerceIn(-1, 1) * 10) / 100f)

@Composable
internal fun PlaybackSpeedOverlay(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val columns = 4
    val requesters = remember { PLAYBACK_SPEED_PRESETS.map { FocusRequester() } }
    val close = remember { FocusRequester() }
    val decrease = remember { FocusRequester() }
    val increase = remember { FocusRequester() }
    val slider = remember { FocusRequester() }
    val active = PLAYBACK_SPEED_PRESETS.indexOf(speed).takeIf { it >= 0 } ?: 4
    Popup(alignment = Alignment.BottomEnd, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        val panelWidth = minOf(configuration.screenWidthDp - 24, if (configuration.smallestScreenWidthDp < 600) 308 else 348)
        Surface(Modifier.padding(12.dp).width(panelWidth.coerceAtLeast(180).dp)
            .heightIn(max = (configuration.screenHeightDp - 24).coerceAtLeast(180).dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = Color(0xED171A20), contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .12f)), shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).verticalScroll(rememberScrollState())
                .playerChoiceFocusEntry(requesters[active]), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback speed · ${playbackSpeedLabel(speed)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).focusRequester(close)
                        .focusProperties { down = requesters[active]; left = requesters[active] }) { Icon(Icons.Default.Close, "Close", Modifier.size(18.dp)) }
                }
                PLAYBACK_SPEED_PRESETS.chunked(columns).forEachIndexed { row, values ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        values.forEachIndexed { column, value ->
                            val index = row * columns + column
                            PlayerVisualChoiceButton(playbackSpeedLabel(value), speed == value,
                                Modifier.weight(1f).height(40.dp).focusRequester(requesters[index]).focusProperties {
                                    left = requesters.getOrNull(index - 1).takeIf { column > 0 } ?: FocusRequester.Cancel
                                    right = requesters.getOrNull(index + 1).takeIf { column < columns - 1 } ?: FocusRequester.Cancel
                                    up = requesters.getOrNull(index - columns) ?: FocusRequester.Cancel
                                    down = requesters.getOrNull(index + columns) ?: when (column) { 0 -> decrease; 3 -> increase; else -> slider }
                                }, onClick = { onSpeed(value) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSpeed(adjustedPlaybackSpeed(speed, -1)) },
                        modifier = Modifier.size(36.dp).focusRequester(decrease)
                            .focusProperties { right = slider; up = requesters[PLAYBACK_SPEED_PRESETS.size - columns]; down = close }) {
                        Icon(Icons.Default.Remove, "Decrease speed", Modifier.size(18.dp))
                    }
                    Slider(speed, { onSpeed((it * 10).roundToInt() / 10f) }, valueRange = .1f..4f, steps = 38,
                        modifier = Modifier.weight(1f).height(36.dp).focusRequester(slider)
                            .focusProperties { up = requesters[active]; down = close }
                            .semantics { contentDescription = "Playback speed adjustment" })
                    IconButton(onClick = { onSpeed(adjustedPlaybackSpeed(speed, 1)) },
                        modifier = Modifier.size(36.dp).focusRequester(increase)
                            .focusProperties { left = slider; up = requesters.last(); down = close }) {
                        Icon(Icons.Default.Add, "Increase speed", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
