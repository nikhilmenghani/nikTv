package com.nikhil.niktv.ui

import com.nikhil.niktv.data.*
import org.junit.Assert.*
import org.junit.Test

class AlternateServerRowsTest {
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
        assertEquals(6, nativeSourceProviders.size)
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
