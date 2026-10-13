package com.nikhil.niktv.ui

/** Empty channel guides cool off; repeated empties also quiet the whole profile. */
internal class LiveGuideRetryPolicy {
    private val channelUntil = LinkedHashMap<Pair<String, String>, Long>()
    private val profileUntil = mutableMapOf<String, Long>()
    private val emptyCounts = mutableMapOf<String, Int>()
    fun retryAt(profile: String, channel: String): Long =
        maxOf(channelUntil[profile to channel] ?: 0L, profileUntil[profile] ?: 0L)
    fun record(profile: String, channel: String, hasProgramme: Boolean, now: Long) {
        if (hasProgramme) {
            channelUntil.remove(profile to channel)
            profileUntil.remove(profile)
            emptyCounts[profile] = 0
        } else {
            channelUntil[profile to channel] = now + 5 * 60_000L
            val count = (emptyCounts[profile] ?: 0) + 1
            emptyCounts[profile] = count
            if (count >= 3) profileUntil[profile] = now + 5 * 60_000L
        }
        while (channelUntil.size > 512) channelUntil.remove(channelUntil.keys.first())
    }
}
