package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*

/** Only an unfinished IPTV episode from this profile may drive the provider hero's Resume. */
internal fun providerSeriesResumeRecent(profile: String, series: MediaItem, recents: List<RecentItem>,
    progress: (RecentItem) -> PlaybackProgress?): RecentItem? {
    if (profile.isBlank()) return null
    val recent = recents.firstOrNull { it.profileKey == profile && it.kind == FavoriteKind.SERIES &&
        it.alternateTarget == null && (it.media.id == series.id || it.series?.id == series.id) } ?: return null
    // An invalid newest entry must not resurrect an older/completed episode.
    return recent.takeIf { it.lastPlayed != null &&
        progress(it)?.let { value -> value.positionMillis > 0 && value.durationMillis > value.positionMillis } == true }
}

/** Existing callbacks retain parental checks, episode queues, resume and provider authorization. */
internal fun playProviderSeriesHero(profile: String, series: MediaItem, recents: List<RecentItem>,
    progress: (RecentItem) -> PlaybackProgress?, resume: (RecentItem) -> Unit, open: (MediaItem) -> Unit) {
    val recent = providerSeriesResumeRecent(profile, series, recents, progress)
    if (recent != null) resume(recent) else open(series)
}
