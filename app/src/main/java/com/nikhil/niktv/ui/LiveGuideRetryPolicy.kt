package com.nikhil.niktv.ui

/** A missing channel guide is not an outage. Only repeated request failures quiet the profile. */
internal class LiveGuideRetryPolicy {
    private val channelUntil = LinkedHashMap<Pair<String, String>, Long>()
    private val profileUntil = mutableMapOf<String, Long>()
    private data class FailureStreak(val count: Int, val lastAt: Long)
    private val failureCounts = mutableMapOf<String, FailureStreak>()
    fun retryAt(profile: String, channel: String): Long =
        maxOf(channelUntil[profile to channel] ?: 0L, profileUntil[profile] ?: 0L)
    fun record(profile: String, channel: String, hasProgramme: Boolean, now: Long) {
        profileUntil.remove(profile)
        failureCounts.remove(profile)
        if (hasProgramme) {
            channelUntil.remove(profile to channel)
        } else {
            channelUntil[profile to channel] = now + 5 * 60_000L
        }
        trim()
    }

    fun recordFailure(profile: String, channel: String, now: Long) {
        channelUntil[profile to channel] = now + 5 * 60_000L
        val previous = failureCounts[profile]?.takeIf { now - it.lastAt in 0 until 5 * 60_000L }
        val count = (previous?.count ?: 0) + 1
        failureCounts[profile] = FailureStreak(count, now)
        if (count >= 3) profileUntil[profile] = now + 5 * 60_000L
        trim()
    }

    private fun trim() {
        while (channelUntil.size > 512) channelUntil.remove(channelUntil.keys.first())
    }
}
