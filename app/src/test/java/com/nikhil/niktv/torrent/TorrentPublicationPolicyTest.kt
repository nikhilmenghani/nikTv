package com.nikhil.niktv.torrent

import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.DiscoveryFeedPage
import com.nikhil.niktv.discovery.WatchProviderPage
import com.nikhil.niktv.model.TmdbHomeSection
import org.junit.Assert.*
import org.junit.Test

class TorrentPublicationPolicyTest {
    private val today = "2026-11-26"
    private fun card(date: String?, series: Boolean = false, id: Int = 1) = DiscoveryCard(id, series, "Title", date = date)

    @Test fun releasedTodayAndEarlierTitlesAreAvailableButTomorrowIsNot() {
        assertTrue(card(today).torrentPublished(today))
        assertTrue(card("1888-10-14").torrentPublished(today))
        assertFalse(card("2026-11-27").torrentPublished(today))
        assertFalse(card("2099-01-01").torrentPublished(today))
    }

    @Test fun missingMalformedAndImpossibleDatesCannotConfirmARelease() {
        for (date in listOf(null, "", "2026", "2026-11", "2026-2-3", "2026-02-29", "2026-13-01", "0000-01-01", "Coming soon")) {
            assertFalse(date, card(date).torrentPublished(today))
        }
        assertTrue(card("2024-02-29").torrentPublished(today))
        assertFalse(card(today).torrentPublished("invalid cutoff"))
    }

    @Test fun futureFirstAirDatesAndAdultTitlesAreAlsoExcluded() {
        assertFalse(card("2026-11-27", series = true).torrentPublished(today))
        assertTrue(card(today, series = true).torrentPublished(today))
        assertFalse(card(today).copy(adult = true).torrentPublished(today))
    }

    @Test fun cachedFeedAndCollectionPublicationFilterBeforeExposingCards() {
        val past = card("2020-01-01")
        val future = card("2099-01-01", id = 2)
        val unknown = card(null, id = 3)
        val feed = publishedTorrentFeed(DiscoveryFeedPage(listOf(past, future, unknown), hasMore = true), today)
        assertEquals(listOf(past), feed.cards)
        assertTrue(feed.hasMore)
        val collection = publishedTorrentPage(WatchProviderPage(listOf(future, unknown), 3, 8), today)
        assertTrue(collection.cards.isEmpty())
        assertEquals(3, collection.page)
        assertEquals(8, collection.totalPages)
    }

    @Test fun heroSelectionCannotReintroduceFutureOrUnknownCachedTitles() {
        val past = card("1888-10-14")
        val home = TorrentHomeState(shelves = listOf(
            TorrentHomeShelf(TmdbHomeSection.TRENDING_MOVIES, listOf(card("2099-01-01", id = 2), card(null, id = 3), past)),
            TorrentHomeShelf(TmdbHomeSection.TRENDING_SERIES, listOf(card("2099-01-01", true, 4)))))
        assertEquals(listOf(past), home.heroes)
        assertEquals(past, home.hero)
    }
}
