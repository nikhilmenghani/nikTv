package com.nikhil.niktv.ui

/** A bounded reconnect budget, rearmed by a minute of confirmed playback. */
internal class LiveStreamRecovery(private val retryDelaysMillis: List<Long> = listOf(0L)) {
    init { require(retryDelaysMillis.isNotEmpty() && retryDelaysMillis.all { it >= 0 }) }
    val retryLimit get() = retryDelaysMillis.size
    var attemptCount = 0
        private set
    private var failureDetectedAt: Long? = null
    var exhausted = false
        private set
    private var lastPosition: Long? = null
    private var lastAdvanceAt = 0L
    private var lastSampleAt: Long? = null
    private var healthyPlaybackMillis = 0L

    fun reset() {
        exhausted = false
        attemptCount = 0
        failureDetectedAt = null
        lastPosition = null
        lastSampleAt = null
        healthyPlaybackMillis = 0L
    }

    fun claimRecovery(): Boolean {
        if (exhausted) return false
        attemptCount++
        exhausted = attemptCount >= retryLimit
        failureDetectedAt = null
        healthyPlaybackMillis = 0L
        return true
    }

    fun observePlayback(now: Long, position: Long, eligible: Boolean, terminated: Boolean = false) {
        if (!eligible) {
            failureDetectedAt = null
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
        if (!terminated && attemptCount > 0 && healthyPlaybackMillis >= 60_000L) {
            exhausted = false
            attemptCount = 0
            failureDetectedAt = null
            healthyPlaybackMillis = 0L
        }
    }

    /** Exhaustion must also cover engines stuck in READY, not just explicit EOF. */
    fun unrecoverable(now: Long, eligible: Boolean, terminated: Boolean): Boolean =
        eligible && exhausted && (terminated || (lastPosition != null && now - lastAdvanceAt >= 20_000L))

    fun shouldRecover(now: Long, position: Long, eligible: Boolean, terminated: Boolean): Boolean {
        observePlayback(now, position, eligible, terminated)
        if (!eligible) return false
        if (exhausted) return false
        if (!terminated && now - lastAdvanceAt < 20_000L) {
            failureDetectedAt = null
            return false
        }
        val failedAt = failureDetectedAt ?: now.also { failureDetectedAt = it }
        if (now - failedAt < retryDelaysMillis[attemptCount]) return false
        return claimRecovery()
    }

    companion object {
        // Continue through a brief server outage, without reconnecting in a tight loop.
        val VLC_RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L)
    }
}
