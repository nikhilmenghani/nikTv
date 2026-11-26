package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nikhil.niktv.data.PlayerGesturePreferences
import com.nikhil.niktv.data.PlayerSwipeGesture
import com.nikhil.niktv.data.UserScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The player's switches use the same portable viewer preferences as Profile settings. */
@Composable
internal fun PlayerGestureControlsDialog(owner: UserScope, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val controls by rememberPlayerSwipeControls(owner)
    val scope = rememberCoroutineScope()
    val requesters = remember(owner) { PlayerSwipeGesture.entries.associateWith { FocusRequester() } }
    val close = remember(owner) { FocusRequester() }
    var saving by remember(owner) { mutableStateOf<PlayerSwipeGesture?>(null) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    Popup(alignment = Alignment.BottomEnd, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 400.dp)
            .width((configuration.screenWidthDp - 24).coerceAtLeast(180).dp)
            .heightIn(max = (configuration.screenHeightDp - 24).coerceAtLeast(180).dp),
            shape = RoundedCornerShape(12.dp), color = Color(0xED171A20), contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)), shadowElevation = 8.dp) {
            Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                .playerChoiceFocusEntry(requesters.getValue(PlayerSwipeGesture.CHANNELS)),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Gestures", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    PlayerChromeIconButton(Icons.Default.Close, "Close gestures", onDismiss,
                        Modifier.focusRequester(close).focusProperties {
                            down = requesters.getValue(PlayerSwipeGesture.CHANNELS)
                            left = requesters.getValue(PlayerSwipeGesture.CHANNELS)
                        }, size = 36.dp, iconSize = 18.dp, compact = true, scaleOnFocus = false)
                }
                Text("Touch controls for this profile", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = .65f))
                PlayerSwipeGesture.entries.forEachIndexed { index, gesture ->
                    val (title, description) = when (gesture) {
                        PlayerSwipeGesture.CHANNELS -> "Channels and episodes" to "Swipe up to open the playback list"
                        PlayerSwipeGesture.BRIGHTNESS -> "Brightness" to "Swipe vertically on the left side"
                        PlayerSwipeGesture.VOLUME -> "Volume" to "Swipe vertically on the right side"
                    }
                    SettingsControlRow(title, description = description, checked = controls.enabled(gesture),
                        modifier = Modifier.focusRequester(requesters.getValue(gesture)).focusProperties {
                            up = PlayerSwipeGesture.entries.getOrNull(index - 1)?.let(requesters::getValue) ?: close
                            down = PlayerSwipeGesture.entries.getOrNull(index + 1)?.let(requesters::getValue) ?: close
                        }, onToggle = change@{ enabled ->
                            // A pending disk write must not detach the focused row.
                            if (saving != null) return@change
                            saving = gesture; error = null
                            scope.launch {
                                try { PlayerGesturePreferences.setSwipe(context, owner, gesture, enabled) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { error = "Could not save this change. Please try again." }
                                finally { saving = null }
                            }
                        })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
