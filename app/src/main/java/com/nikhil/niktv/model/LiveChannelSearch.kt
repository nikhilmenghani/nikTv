package com.nikhil.niktv.model

private const val UNTIMED_PROGRAMME_FRESHNESS_MS = 5 * 60_000L

/** Do not match tomorrow's schedule or stale "now playing" text from disk. */
fun MediaItem.currentLiveProgramme(now: Long): LiveProgramme? {
    fun LiveProgramme.isAiring(): Boolean {
        if (isMissingLiveProgrammeTitle(title)) return false
        if (startTimeMillis != null && startTimeMillis > now) return false
        if (endTimeMillis != null && endTimeMillis <= now) return false
        if (startTimeMillis != null && endTimeMillis != null) return true
        return observedAtMillis?.let { now - it in 0 until UNTIMED_PROGRAMME_FRESHNESS_MS } == true
    }
    return liveSchedule.firstOrNull {
        it.startTimeMillis != null && it.endTimeMillis != null && it.isAiring()
    } ?: liveProgramme?.takeIf { it.isAiring() }
}

fun MediaItem.matchesLiveChannelQuery(query: String, now: Long): Boolean {
    fun String.matches(): Boolean {
        if (matchesTitleKeywords(query)) return true
        // Providers spell names such as "Shin-chan" and "Shinchan" differently.
        val compactQuery = query.trim().lowercase()
        return compactQuery.length >= 3 && compactQuery.all(Char::isLetterOrDigit) &&
            lowercase().filter(Char::isLetterOrDigit).contains(compactQuery)
    }
    return title.matches() || currentLiveProgramme(now)?.title?.matches() == true
}
