package com.nikhil.niktv.ui

import java.util.Locale

/** Time actually spent playing this channel; buffering and user pauses do not advance it. */
internal class LivePlaybackClock {
    var elapsedMillis: Long = 0L
        private set
    private var lastSampleMillis: Long? = null
    private var wasPlaying = false

    fun sample(nowMillis: Long, playing: Boolean): Long {
        val previous = lastSampleMillis
        if (previous != null && wasPlaying) {
            elapsedMillis += (nowMillis - previous).coerceAtLeast(0L)
        }
        lastSampleMillis = nowMillis
        wasPlaying = playing
        return elapsedMillis
    }
}

internal fun formatLivePlaybackElapsed(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    return if (seconds >= 3_600L) {
        String.format(Locale.US, "%02d:%02d:%02d", seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
    } else {
        String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
}
