package com.nikhil.niktv.sports

internal class SportsPlaybackWatchdog(private val startupTimeoutMs: Long = 12_000, private val stallTimeoutMs: Long = 8_000) {
    private var waitingSince: Long? = null
    private var lastPosition: Long? = null
    private var lastAdvance: Long? = null
    fun failed(now: Long, intendsToPlay: Boolean, ready: Boolean, ended: Boolean, firstFrame: Boolean, position: Long? = null): Boolean {
        if (!intendsToPlay) { waitingSince = null; lastPosition = null; lastAdvance = null; return false }
        if (ended) return true
        if (ready && firstFrame) {
            waitingSince = null
            if (position == null) return false
            if (lastPosition == null || position != lastPosition) { lastPosition = position; lastAdvance = now }
            return now - (lastAdvance ?: now) >= stallTimeoutMs
        }
        lastPosition = null
        lastAdvance = null
        val began = waitingSince ?: now.also { waitingSince = it }
        return now - began >= if (firstFrame) stallTimeoutMs else startupTimeoutMs
    }
}

internal fun sportsCatalogueNeedsRefresh(lastChecked: Long?, now: Long): Boolean =
    lastChecked == null || now < lastChecked || now - lastChecked >= 120_000
