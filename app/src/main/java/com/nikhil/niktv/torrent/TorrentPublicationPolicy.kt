package com.nikhil.niktv.torrent

import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.DiscoveryFeedPage
import com.nikhil.niktv.discovery.WatchProviderPage
import java.time.LocalDate
import java.time.ZoneOffset

internal fun torrentCurrentDate(): String = LocalDate.now(ZoneOffset.UTC).toString()

/** Unknown dates cannot establish that a title has actually been released. */
internal fun torrentPublicationDateAvailable(date: String?, today: String = torrentCurrentDate()): Boolean {
    if (date == null || !date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
    val release = runCatching { LocalDate.parse(date) }.getOrNull()?.takeIf { it.year > 0 } ?: return false
    val cutoff = runCatching { LocalDate.parse(today) }.getOrNull() ?: return false
    return !release.isAfter(cutoff)
}

internal fun DiscoveryCard.torrentPublished(today: String = torrentCurrentDate()): Boolean =
    !adult && torrentPublicationDateAvailable(date, today)

/** Apply this to both cached/stale publication and the refreshed response. */
internal fun publishedTorrentFeed(page: DiscoveryFeedPage, today: String = torrentCurrentDate()): DiscoveryFeedPage =
    page.copy(cards = page.cards.filter { it.torrentPublished(today) }.take(20),
        hasMore = page.hasMore || page.cards.size > 20)

internal fun publishedTorrentPage(page: WatchProviderPage, today: String = torrentCurrentDate()): WatchProviderPage =
    page.copy(cards = page.cards.filter { it.torrentPublished(today) })
