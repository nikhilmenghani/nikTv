package com.nikhil.niktv.ui

/** One automatic reconnect per channel visit, never an unbounded provider retry loop. */
internal class LiveStreamRecovery {
    var exhausted = false
        private set
    private var lastPosition: Long? = null
    private var lastAdvanceAt = 0L

    fun shouldRecover(now: Long, position: Long, eligible: Boolean, terminated: Boolean): Boolean {
        if (!eligible) {
            lastPosition = null
            return false
        }
        if (lastPosition == null || position != lastPosition) {
            lastPosition = position
            lastAdvanceAt = now
        }
        if (exhausted || (!terminated && now - lastAdvanceAt < 20_000L)) return false
        exhausted = true
        return true
    }
}
