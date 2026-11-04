package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDiscoveryHeroTest {
    private val a = Category("a", "A", CatalogType.MOVIES)
    private val b = Category("b", "B", CatalogType.MOVIES)
    private fun media(id: String) = MediaItem(id, "Movie $id", null, "original-$id")
    private fun page(vararg ids: String) = ProviderDiscoveryPage(ids.map(::media))
    @Test fun emptyAndFailedFeedsStopTheHeaderLoadingAnimation() {
        val type = CatalogType.MOVIES
        val empty = ProviderDiscoveryState(profile = "profile", categories = mapOf(type to listOf(a)), ready = true,
            pages = mapOf(type to mapOf("a" to ProviderDiscoveryPage(emptyList(), 1, false))))
        assertFalse(providerHeroWaiting("profile", type, listOf(a), empty, true))
        assertFalse(providerHeroWaiting("profile", type, emptyList(), empty, false))
        assertTrue(providerHeroWaiting("profile", type, listOf(a), empty, false))
        assertTrue(providerHeroWaiting("another", type, listOf(a), empty, true))
        assertFalse(providerHeroWaiting("profile", type, listOf(a), empty.copy(errors = mapOf(type to "offline")), false))
    }
    @Test fun delayedEarlierCategoriesAppendWithoutMovingTheSelectedIdentity() {
        val first = appendProviderHeroes(emptyList(), listOf(a, b), mapOf("b" to page("b1", "b2")))
        val next = appendProviderHeroes(first, listOf(a, b), mapOf("a" to page("a1"), "b" to page("b1", "b2")))
        assertEquals(listOf("b1", "b2", "a1"), next.map { it.item.id })
        assertSame(first[1], next[1])
        assertEquals("original-b2", next[1].item.command)
        assertEquals(listOf("b1", "b2"), next[1].queue.map { it.id })
    }
    @Test fun poolDeduplicatesProviderIdentityAndRemainsBounded() {
        val pages = mapOf("a" to page(*(0..20).map(Int::toString).toTypedArray()), "b" to page("0", "1"))
        val first = appendProviderHeroes(emptyList(), listOf(a, b), pages)
        assertEquals(10, first.size)
        assertEquals(10, first.map { it.item.id }.distinct().size)
        assertSame(first, appendProviderHeroes(first, listOf(b), mapOf("b" to page("new"))))
    }
    @Test fun mergedSelectionsAndMissingPagesUseOnlyLoadedUnrestrictedMedia() {
        val group = Category("group", "Merged", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b"))
        val secret = media("private").copy(isAdult = true)
        val result = appendProviderHeroes(emptyList(), listOf(group), mapOf("b" to ProviderDiscoveryPage(listOf(secret, media("ok")))))
        assertEquals(listOf("ok"), result.map { it.item.id })
        assertEquals(listOf("ok"), result.single().queue.map { it.id })
    }

    private fun recent(id: String, kind: FavoriteKind = FavoriteKind.MOVIE, profile: String = "wio", at: Long = 1) =
        RecentItem(kind, media(id).copy(logo = "recent-$id"), profileKey = profile, playedAtMillis = at)

    @Test fun continueWatchingWinsBeforeItsCataloguePageHasLoaded() {
        val watched = recent("watched")
        val fallback = appendProviderHeroes(emptyList(), listOf(a), mapOf("a" to page("catalogue")))
        val result = preferredProviderHeroes("wio", CatalogType.MOVIES, listOf(watched), fallback)
        assertEquals(listOf("watched"), result.map { it.item.id })
        assertSame(watched.media, result.single().item)
        assertEquals(listOf(watched.media), result.single().queue)
        assertEquals("recent-watched", result.single().artwork)
    }

    @Test fun newestUniqueRecentWinsAndKeepsTheLoadedProviderCommandAndQueue() {
        val fallback = appendProviderHeroes(emptyList(), listOf(a), mapOf("a" to page("one", "two")))
        val newest = recent("one", at = 3).copy(media = media("one").copy(command = "old", logo = "better-art"))
        val result = preferredProviderHeroes("wio", CatalogType.MOVIES,
            listOf(recent("one"), recent("two", at = 2), newest), fallback)
        assertEquals(listOf("one", "two"), result.map { it.item.id })
        assertSame(fallback[0].item, result[0].item)
        assertSame(fallback[0].queue, result[0].queue)
        assertSame(newest, result[0].recent)
        assertEquals("better-art", result[0].artwork)
    }

    @Test fun profilesKindsAdultAndNonIptvHistoryCannotReplaceTheFallback() {
        val fallback = appendProviderHeroes(emptyList(), listOf(a), mapOf("a" to page("fallback")))
        val wrong = listOf(recent("other", profile = "nikhil"), recent("series", FavoriteKind.SERIES),
            recent("adult").let { it.copy(media = it.media.copy(isAdult = true)) },
            recent("tmdb-movie:17"), recent("alternate").copy(alternateTarget = com.nikhil.niktv.data.AlternateSourceTarget("Alternate", false)))
        assertSame(fallback, preferredProviderHeroes("wio", CatalogType.MOVIES, wrong, fallback))
        assertSame(fallback, preferredProviderHeroes("", CatalogType.MOVIES, listOf(recent("one", profile = "")), fallback))
    }

    @Test fun seriesHeroUsesTheContainerIdentityAndTheContinueWatchingArtwork() {
        val series = media("show").copy(logo = "low-res-series")
        val episode = media("episode").copy(logo = "episode-art", seasonNumber = 3, episodeNumber = 4)
        val watched = recent("episode", FavoriteKind.SERIES).copy(media = episode, series = series, lastPlayed = episode)
        val result = preferredProviderHeroes("wio", CatalogType.SERIES, listOf(watched), emptyList()).single()
        assertSame(series, result.item)
        assertSame(watched, result.recent)
        assertEquals("episode-art", result.artwork)
        assertSame(episode, result.recent?.lastPlayed)
    }

    @Test fun recentPoolIsBoundedAndEmptyHistoryRestoresTheSameFallback() {
        val fallback = appendProviderHeroes(emptyList(), listOf(a), mapOf("a" to page("fallback")))
        val result = preferredProviderHeroes("wio", CatalogType.MOVIES, (1..30).map { recent("$it", at = it.toLong()) }, fallback)
        assertEquals(10, result.size)
        assertEquals("30", result.first().item.id)
        assertSame(fallback, preferredProviderHeroes("wio", CatalogType.MOVIES, emptyList(), fallback))
    }

    @Test fun randomFallbackSamplesChosenLoadedCategoriesAndKeepsArrivalsStable() {
        val pages = mapOf("a" to page(*(0..29).map(Int::toString).toTypedArray()), "unselected" to page("not-chosen"))
        val selected = appendProviderHeroes(emptyList(), listOf(a), pages, kotlin.random.Random(7))
        assertEquals(10, selected.size)
        assertTrue(selected.any { it.item.id.toInt() >= 10 })
        assertTrue(selected.all { it.queue.map { m -> m.id } == (0..29).map(Int::toString) })
        assertSame(selected, appendProviderHeroes(selected, listOf(a), pages, kotlin.random.Random(8)))
    }
}
