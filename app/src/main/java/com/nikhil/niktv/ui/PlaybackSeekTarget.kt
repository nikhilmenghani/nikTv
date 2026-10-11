package com.nikhil.niktv.ui

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

    fun acknowledge(actual: Long) {
        if (pending?.let { kotlin.math.abs(actual - it) <= 500L } == true) pending = null
    }
}
