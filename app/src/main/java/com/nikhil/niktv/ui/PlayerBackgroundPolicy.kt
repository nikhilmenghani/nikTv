package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// ON_STOP means the player is no longer visible. ON_PAUSE can also occur for
// temporary system overlays, so those should not interrupt TV playback.
@Composable
internal fun PauseTvPlaybackInBackground(pause: () -> Unit) {
    val tv = rememberPlayerTvDevice()
    val owner = LocalLifecycleOwner.current
    val currentPause = rememberUpdatedState(pause)
    DisposableEffect(owner, tv) {
        val observer = LifecycleEventObserver { _, event ->
            if (tv && event == Lifecycle.Event.ON_STOP) currentPause.value()
        }
        if (tv) {
            owner.lifecycle.addObserver(observer)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentPause.value()
        }
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
