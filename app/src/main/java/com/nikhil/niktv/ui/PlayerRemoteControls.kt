package com.nikhil.niktv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Capture media buttons regardless of which overlay or video control has focus. */
internal fun Modifier.playerMediaKeys(
    playbackRequested: Boolean,
    setPlaying: (Boolean) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val requested = when (event.key) {
        Key.MediaPlayPause -> !playbackRequested
        Key.MediaPlay -> true
        Key.MediaPause -> false
        else -> return@onPreviewKeyEvent false
    }
    // Consume release/repeat events too, so a held toggle cannot oscillate or
    // also reach the native player's listener.
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
        setPlaying(requested)
    }
    true
}

internal fun seekPreviewPosition(position: Long, direction: Int, duration: Long): Long {
    val limit = duration.coerceAtLeast(0L)
    val current = position.coerceIn(0L, limit)
    return if (direction > 0) current + minOf(10_000L, limit - current)
    else (current - 10_000L).coerceAtLeast(0L)
}
