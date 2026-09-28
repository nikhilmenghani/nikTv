package com.nikhil.niktv.model

import kotlinx.serialization.Serializable

@Serializable
data class TmdbAvailabilityEntry(
    val checkedAtMillis: Long,
    val matches: List<MediaItem>,
    val matchingVersion: Int = 1
) {
    fun isFresh(now: Long, intervalMillis: Long): Boolean =
        (matches.isNotEmpty() || matchingVersion >= 4) && checkedAtMillis <= now && now - checkedAtMillis < intervalMillis
}

@Serializable
data class TmdbAvailabilityCache(val entries: Map<Int, TmdbAvailabilityEntry> = emptyMap())
