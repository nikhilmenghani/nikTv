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

internal fun seekPreviewPosition(position: Long, direction: Int, duration: Long, stepMillis: Long = 10_000L): Long {
    val limit = duration.coerceAtLeast(0L)
    val current = position.coerceIn(0L, limit)
    val step = stepMillis.coerceAtLeast(0L)
    return if (direction > 0) current + minOf(step, limit - current)
    else current - minOf(step, current)
}

/** Acceleration belongs to a held preview gesture, never the engine's actual seek loop. */
internal class SeekHoldAcceleration {
    private var heldDirection = 0
    private var startedAt = 0L
    val isHeld: Boolean get() = heldDirection != 0

    fun step(direction: Int, eventTimeMillis: Long, repeated: Boolean, duration: Long): Long {
        if (!repeated || heldDirection != direction || eventTimeMillis < startedAt) startedAt = eventTimeMillis
        heldDirection = direction
        val heldFor = (eventTimeMillis - startedAt).coerceAtLeast(0L)
        val proposed = when {
            heldFor < 700L -> 10_000L
            heldFor < 1_800L -> 30_000L
            heldFor < 3_500L -> 60_000L
            else -> maxOf(60_000L, duration.coerceAtLeast(0L) / 50)
        }
        // Short clips remain controllable; long movies never jump more than five minutes per repeat.
        val maximum = (duration.coerceAtLeast(0L) / 20).coerceIn(10_000L, 300_000L)
        return minOf(proposed, maximum)
    }

    fun release(direction: Int) { if (heldDirection == direction) reset() }
    fun reset() { heldDirection = 0; startedAt = 0L }
}
