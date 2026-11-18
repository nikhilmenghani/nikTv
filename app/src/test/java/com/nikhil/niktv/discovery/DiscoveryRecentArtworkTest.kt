package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryRecentArtworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val poster = "https://image.tmdb.org/t/p/w342/source.jpg"
    private val backdrop = "https://image.tmdb.org/t/p/w1280/source.jpg"
    private fun recent(title: String = "Source Code - HINDI | ENGLISH DUBB MOVIES", id: String = "source") =
        RecentItem(FavoriteKind.MOVIE, MediaItem(id, title, "https://broken-provider.invalid/poster.jpg", "original-command"), profileKey = "portal")

    @Test fun cachedCoverAndBackdropWinDespiteNonemptyBrokenProviderUrl() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        val card = DiscoveryCard(45612, false, "Source Code", poster = poster, backdrop = backdrop)
        cache.write("feed", Json.encodeToString(ListSerializer(DiscoveryCard.serializer()), listOf(card)))
        val saved = recent()
        var result = emptyMap<String, String>()
        loadRecentArtwork(cache, listOf(saved), 6, lookup = { _, _ -> error("Cache hit must not search") }, publish = { result = it })
        assertEquals(listOf(poster), result.values.toList())
        assertEquals(card, readProviderHeroMetadata(cache, saved.media, CatalogType.MOVIES))
        assertEquals("original-command", saved.media.command)
        assertEquals("https://broken-provider.invalid/poster.jpg", saved.media.logo)
    }

    @Test fun titleCleanupReusesExactMetadataButDoesNotAttachAnotherKnownMovie() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        val card = DiscoveryCard(100, false, "Omkara", poster = poster, backdrop = backdrop)
        cache.write("feed", Json.encodeToString(ListSerializer(DiscoveryCard.serializer()), listOf(card)))
        val media = recent("Omkara (Hindi) - HINDI | OLD COLLECTION").media
        assertEquals(card, readProviderHeroMetadata(cache, media, CatalogType.MOVIES))
        assertNull(cachedProviderArtwork(cache, media.copy(externalTmdbId = 999), CatalogType.MOVIES))
        assertNull(readProviderHeroMetadata(cache, media.copy(externalTmdbId = 999), CatalogType.MOVIES))
        assertNull(readProviderHeroMetadata(cache, media, CatalogType.SERIES))
    }

    @Test fun missesPublishImmediatelyThenRepairOnlyTheBoundedHistoryWindow() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        val history = (1..8).map { recent("Movie $it", "$it") }
        val publications = mutableListOf<Map<String, String>>()
        val queried = mutableListOf<String>()
        loadRecentArtwork(cache, history, 2, lookup = { media, type ->
            assertEquals(CatalogType.MOVIES, type)
            queried += media.id
            DiscoveryCard(media.id.toInt(), false, media.title, poster = poster)
        }, publish = { publications += it })
        assertTrue(publications.first().isEmpty())
        assertEquals(listOf("1", "2"), queried)
        assertEquals(2, publications.last().size)
        assertTrue(publications.all { it.size <= 2 })
    }

    @Test fun backgroundAndCancelledRequestsCannotStartOrPublishStaleArtwork() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        loadRecentArtwork(cache, listOf(recent()), 0, lookup = { _, _ -> error("Background lookup") }, publish = {})
        val publications = mutableListOf<Map<String, String>>()
        val job = launch {
            loadRecentArtwork(cache, listOf(recent()), 1, lookup = { _, _ ->
                delay(10_000); DiscoveryCard(1, false, "Source Code", poster = poster)
            }, publish = { publications += it })
        }
        yield(); delay(40); job.cancelAndJoin()
        assertTrue(publications.all { it.isEmpty() })
        assertNull(publicRecentArtwork(DiscoveryCard(5, true, "Source Code", poster = poster), recent().media, CatalogType.MOVIES))
        assertNull(publicRecentArtwork(DiscoveryCard(5, false, "Source Code", poster = poster, adult = true), recent().media, CatalogType.MOVIES))
    }
}
