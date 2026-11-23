package com.nikhil.niktv.ui

internal fun localPlaybackSource(url: String, offline: Boolean): Boolean = offline ||
    url.startsWith("content://", ignoreCase = true) || url.startsWith("file://", ignoreCase = true)

/** Accumulate quick presses while the decoder is still completing a previous seek. */
internal class PlaybackSeekTarget {
    private var pending: Long? = null
    private var requestedAt = 0L

    fun target(actual: Long, duration: Long, delta: Long, now: Long): Long {
        val base = pending?.takeIf { now - requestedAt < 5_000L } ?: actual
        return absolute(base + delta, duration, now)
    }

    fun absolute(position: Long, duration: Long, now: Long): Long {
        val target = position.coerceIn(0L, duration.coerceAtLeast(0L))
        pending = target
        requestedAt = now
        return target
    }

    fun acknowledge(actual: Long, now: Long? = null) {
        // Playback may advance between the seek and the first UI clock poll.
        val tolerance = 500L + (now?.minus(requestedAt) ?: 0L).coerceIn(0L, 2_000L)
        if (pending?.let { kotlin.math.abs(actual - it) <= tolerance } == true) pending = null
    }

    /** Don't paint an old decoder clock over a seek the user just requested. */
    fun displayPosition(actual: Long, now: Long, acknowledge: Boolean = true): Long {
        if (acknowledge) acknowledge(actual, now)
        return pending?.takeIf { now - requestedAt < 5_000L } ?: actual
    }

    fun isPending(now: Long): Boolean = pending != null && now - requestedAt < 5_000L
}
