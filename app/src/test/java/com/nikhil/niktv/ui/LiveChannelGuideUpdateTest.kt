package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class LiveChannelGuideUpdateTest {
    private val profile = PortalProfile("Test", "https://example.invalid", "00:00:00:00:00:00")
    private val session = PortalSession(profile, "", "", "", "", "")
    private val channel = MediaItem("4311", "Chris Hemsworth", null, "original-command", portalCategoryId = "actors")
    private val programme = LiveProgramme("Spiderhead", 100L, 200L)
    private val enriched = channel.copy(command = "old-command", liveProgramme = programme, liveSchedule = listOf(programme))
    private val cache = BrowseCatalogCache(profile.cacheKey(), CatalogType.LIVE_TV, 7L,
        listOf(Category("actors", "Actors", CatalogType.LIVE_TV)), mapOf("actors" to listOf(channel)),
        pagesByCategory = mapOf("actors" to 6), hasMoreByCategory = mapOf("actors" to true))

    private fun state() = NikTvState(session = session, items = listOf(channel), browseCache = cache,
        browseCachesByType = mapOf(CatalogType.LIVE_TV to cache))

    @Test fun guideArrivingAfterBackUpdatesTileAndBothCachesWithoutReopeningPlayer() {
        val updated = state().withLiveChannelGuide(profile.cacheKey(), enriched)
        assertNull(updated.nowPlaying)
        assertEquals(programme, updated.items.single().liveProgramme)
        assertEquals(programme, updated.browseCache!!.itemsByCategory["actors"]!!.single().liveProgramme)
        val saved = updated.browseCachesByType[CatalogType.LIVE_TV]!!
        assertEquals(listOf(programme), saved.itemsByCategory["actors"]!!.single().liveSchedule)
        assertEquals(cache.pagesByCategory, saved.pagesByCategory)
        assertEquals(cache.hasMoreByCategory, saved.hasMoreByCategory)
        assertEquals("original-command", updated.items.single().command)
    }

    @Test fun playerAndQueueReceiveSameGuideWithoutChangingStream() {
        val playing = PlayingMedia(channel, "current-stream", CatalogType.LIVE_TV, episodeQueue = listOf(channel))
        val updated = state().copy(nowPlaying = playing).withLiveChannelGuide(profile.cacheKey(), enriched)
        assertEquals(programme, updated.nowPlaying!!.media.liveProgramme)
        assertEquals(programme, updated.nowPlaying!!.episodeQueue.single().liveProgramme)
        assertEquals("current-stream", updated.nowPlaying!!.url)
    }

    @Test fun lateGuideForPreviousChannelCannotReplaceNewSelection() {
        val next = channel.copy(id = "other", title = "Other channel")
        val playing = PlayingMedia(next, "next-stream", CatalogType.LIVE_TV, episodeQueue = listOf(channel, next))
        val updated = state().copy(nowPlaying = playing).withLiveChannelGuide(profile.cacheKey(), enriched)
        assertEquals(next, updated.nowPlaying!!.media)
        assertEquals("next-stream", updated.nowPlaying!!.url)
        assertEquals(programme, updated.items.single().liveProgramme)
    }

    @Test fun differentProfileIsUntouchedEvenWithSameChannelId() {
        val original = state().copy(session = session.copy(profile = profile.copy(portalUrl = "https://other.invalid")))
        assertSame(original, original.withLiveChannelGuide(profile.cacheKey(), enriched))
    }

    @Test fun movieWithSameIdIsUntouchedWhileLiveCacheIsUpdated() {
        val playing = PlayingMedia(channel, "movie-stream", CatalogType.MOVIES)
        val movieCache = cache.copy(type = CatalogType.MOVIES)
        val original = state().copy(selectedType = CatalogType.MOVIES, browseCache = movieCache, nowPlaying = playing)
        val updated = original.withLiveChannelGuide(profile.cacheKey(), enriched)
        assertEquals(original.items, updated.items)
        assertEquals(playing, updated.nowPlaying)
        assertEquals(movieCache, updated.browseCache)
        assertEquals(programme, updated.browseCachesByType[CatalogType.LIVE_TV]!!.itemsByCategory["actors"]!!.single().liveProgramme)
    }
    @Test fun unrelatedCategoryRetainsItsListIdentity() {
        val otherItems = listOf(channel.copy(id = "other"))
        val original = state().copy(browseCache = cache.copy(
            itemsByCategory = cache.itemsByCategory + ("other" to otherItems)))
        val updated = original.withLiveChannelGuide(profile.cacheKey(), enriched)
        assertSame(otherItems, updated.browseCache!!.itemsByCategory["other"])
    }

    @Test fun identicalGuideRetainsItemsAndCache() {
        val first = state().withLiveChannelGuide(profile.cacheKey(), enriched)
        val second = first.withLiveChannelGuide(profile.cacheKey(), enriched)
        assertSame(first.items, second.items)
        assertSame(first.browseCache, second.browseCache)
    }
}
