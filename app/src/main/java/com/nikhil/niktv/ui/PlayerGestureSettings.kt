package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.PlayerGesturePreferences
import com.nikhil.niktv.data.PlayerSwipeControls
import com.nikhil.niktv.data.PlayerSwipeGesture
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.PlayingMedia

@Composable
internal fun rememberPinchToZoomEnabled(owner: UserScope?): State<Boolean> {
    val context = LocalContext.current.applicationContext
    // A different viewer must start disabled, never inherit the prior flow's last value.
    return key(context, owner) {
        val flow = remember { PlayerGesturePreferences.observePinchToZoom(context, owner) }
        flow.collectAsStateWithLifecycle(initialValue = false)
    }
}

@Composable
internal fun rememberPlayerSwipeControls(owner: UserScope?): State<PlayerSwipeControls> {
    val context = LocalContext.current.applicationContext
    return key(context, owner) {
        val flow = remember { PlayerGesturePreferences.observeSwipes(context, owner) }
        flow.collectAsStateWithLifecycle(initialValue = PlayerSwipeControls())
    }
}

@Composable
internal fun PlayerSwipeSettings(controls: PlayerSwipeControls, change: (PlayerSwipeGesture, Boolean) -> Unit) {
    for (gesture in PlayerSwipeGesture.entries) {
        val (title, description) = when (gesture) {
            PlayerSwipeGesture.CHANNELS -> "Swipe up for channels or episodes" to "Open the playback list with an upward swipe. Off by default."
            PlayerSwipeGesture.BRIGHTNESS -> "Swipe to change brightness" to "Swipe up or down on the left side of the player. Off by default."
            PlayerSwipeGesture.VOLUME -> "Swipe to change volume" to "Swipe up or down on the right side of the player. Off by default."
        }
        SettingsControlRow(title, checked = controls.enabled(gesture), description = description,
            onToggle = { change(gesture, it) })
    }
}

@Composable
internal fun rememberPlayerGestureOwner(media: PlayingMedia): UserScope? {
    val context = LocalContext.current.applicationContext
    return remember(context, media.progressKey, media.userScope) {
        media.userScope ?: UserSyncStore.get(context).selectedScope.value
    }
}
