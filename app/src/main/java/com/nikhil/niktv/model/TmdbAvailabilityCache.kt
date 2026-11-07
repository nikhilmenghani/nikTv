package com.nikhil.niktv.model

import kotlinx.serialization.Serializable

const val TMDB_MATCHING_VERSION = 5

@Serializable
data class TmdbAvailabilityEntry(
    val checkedAtMillis: Long,
    val matches: List<MediaItem>,
    val matchingVersion: Int = 1
) {
    fun isFresh(now: Long, intervalMillis: Long): Boolean =
        (matches.isNotEmpty() || matchingVersion >= TMDB_MATCHING_VERSION) && checkedAtMillis <= now && now - checkedAtMillis < intervalMillis
}

@Serializable
data class TmdbAvailabilityCache(val entries: Map<Int, TmdbAvailabilityEntry> = emptyMap())
