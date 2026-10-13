package com.nikhil.niktv.ui

/** One automatic reconnect per failure burst, rearmed by a minute of confirmed playback. */
internal class LiveStreamRecovery {
    var exhausted = false
        private set
    private var lastPosition: Long? = null
    private var lastAdvanceAt = 0L
    private var lastSampleAt: Long? = null
    private var healthyPlaybackMillis = 0L

    fun reset() {
        exhausted = false
        lastPosition = null
        lastSampleAt = null
        healthyPlaybackMillis = 0L
    }

    fun claimRecovery(): Boolean {
        if (exhausted) return false
        exhausted = true
        healthyPlaybackMillis = 0L
        return true
    }

    fun observePlayback(now: Long, position: Long, eligible: Boolean, terminated: Boolean = false) {
        if (!eligible) {
            lastPosition = null
            lastSampleAt = null
            healthyPlaybackMillis = 0L
            return
        }
        val previousPosition = lastPosition
        val sampleMillis = (now - (lastSampleAt ?: now)).coerceAtLeast(0L)
        if (terminated || (previousPosition != null && position < previousPosition) || sampleMillis > 5_000L) {
            healthyPlaybackMillis = 0L
        } else if (previousPosition != null && position > previousPosition) {
            if (now - lastAdvanceAt >= 20_000L) healthyPlaybackMillis = 0L
            else healthyPlaybackMillis += sampleMillis
        }
        if (lastPosition == null || position != lastPosition) {
            lastPosition = position
            lastAdvanceAt = now
        }
        lastSampleAt = now
        if (!terminated && exhausted && healthyPlaybackMillis >= 60_000L) {
            exhausted = false
            healthyPlaybackMillis = 0L
        }
    }

    fun shouldRecover(now: Long, position: Long, eligible: Boolean, terminated: Boolean): Boolean {
        observePlayback(now, position, eligible, terminated)
        if (!eligible) return false
        if (exhausted || (!terminated && now - lastAdvanceAt < 20_000L)) return false
        return claimRecovery()
    }
}
