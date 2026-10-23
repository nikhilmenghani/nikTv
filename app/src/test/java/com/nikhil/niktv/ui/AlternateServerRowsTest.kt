package com.nikhil.niktv.ui

import com.nikhil.niktv.data.*
import org.junit.Assert.*
import org.junit.Test

class AlternateServerRowsTest {
    @Test fun distinctMirrorChoicesHaveDistinctLabelsWithoutLosingAnyStream() {
        val auto = AlternateStream("t:auto", "Auto · Rive Primevids", "https://media.example.test/t.m3u8", sourceVariant = "tcloud")
        val streams = listOf(auto, auto.copy(id = "t:720", height = 720),
            auto.copy(id = "d:auto", sourceVariant = "dcloud"), auto.copy(id = "ip:auto", sourceVariant = "ipcloud"))
        assertEquals(listOf("Auto · Tcloud", "720p · Tcloud", "Auto · Dcloud", "Auto · Ipcloud"), alternateQualityChoiceLabels(streams))
        assertEquals(streams, alternateServerRows(streams).single().streams)
    }
    @Test fun unnamedMirrorsAreNumberedButUniqueQualitiesStayUnchanged() {
        val auto = AlternateStream("a", "Auto · Server", "https://media.example.test/a.m3u8")
        val streams = listOf(auto, auto.copy(id = "b"), auto.copy(id = "c", height = 1080))
        assertEquals(listOf("Auto 1", "Auto 2", "1080p"), alternateQualityChoiceLabels(streams))
        assertEquals(listOf("Auto"), alternateQualityChoiceLabels(listOf(auto)))
    }
    @Test fun groupsResolutionsByServerAndKeepsStreamIdentities() {
        val auto = AlternateStream("auto", "Auto · Movy Miami", "https://media.example.test/master.m3u8")
        val high = auto.copy(id = "high", label = "1080p · Movy Miami", height = 1080)
        val other = auto.copy(id = "other", label = "720p · Movy Boise", height = 720)
        val rows = alternateServerRows(listOf(auto, high, other))
        assertEquals(listOf("Movy Miami", "Movy Boise"), rows.map { it.title })
        assertEquals(listOf("auto", "high"), rows[0].streams.map { it.id })
        assertEquals(listOf("Auto", "1080p"), rows[0].streams.map(::alternateQualityLabel))
    }
    @Test fun removesVidyAndLabelsWebsiteActionsWithoutInventingQuality() {
        val target = AlternateSourceTarget("Movie", false, tmdbId = 603)
        val rows = alternateServerRows(listOfNotNull(cinejoyWebStream(target), cnoWebStream(target), vidyFallbackStream(target)))
        assertTrue(rows.isEmpty())
        assertEquals(7, nativeSourceProviders.size)
        assertTrue(AlternateSourceProvider.ONLYFLIX in nativeSourceProviders)
        assertFalse(AlternateSourceProvider.CINEJOY in nativeSourceProviders)
        assertFalse(AlternateSourceProvider.CNO in nativeSourceProviders)
    }
    @Test fun cnoUsesExactMovieEpisodeIdentityAndCannotLeaveWatchPage() {
        val target = AlternateSourceTarget("Friends", true, tmdbId = 1668, season = 1, episode = 2)
        val stream = cnoWebStream(target)
        assertEquals("https://cno.to/watch/show/1668/1/2", stream.url)
        assertTrue(cinejoyNavigationAllowed(stream.url, stream.url))
        assertTrue(cinejoyNavigationAllowed("https://cno.to/watch/show/1668-friends/1/2", stream.url))
        assertFalse(cinejoyNavigationAllowed(stream.url.replace("/1/2", "/1/3"), stream.url))
        assertFalse(cinejoyNavigationAllowed("https://attacker.invalid/watch/show/1668/1/2", stream.url))
    }
}
