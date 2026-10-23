package com.nikhil.niktv.ui

import com.nikhil.niktv.data.*
import org.junit.Assert.*
import org.junit.Test

class CinejoySourceTest {
    @Test fun exactIdentityAndDistinctWebsiteRoute() {
        val movie = AlternateSourceTarget("The Matrix", false, tmdbId = 603, provider = AlternateSourceProvider.CINEJOY)
        val stream = cinejoyWebStream(movie)
        assertEquals("https://cinejoy.pk/watch/movie/603", stream.url)
        assertEquals(AlternatePlaybackRoute.CINEJOY_WEB, stream.route)
        assertEquals("https://cinejoy.pk/watch/tv/1668/0/2",
            cinejoyWebStream(movie.copy(series = true, tmdbId = 1668, season = 0, episode = 2)).url)
        for (bad in listOf(movie.copy(tmdbId = null), movie.copy(series = true), movie.copy(series = true, season = -1, episode = 1))) {
            try { cinejoyWebStream(bad); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun browserNavigationStaysWithTheChosenEpisode() {
        val url = "https://cinejoy.pk/watch/tv/1668/1/2"
        assertTrue(cinejoyNavigationAllowed(url, url))
        assertTrue(cinejoyNavigationAllowed("$url?play=true", url))
        for (bad in listOf(url.replace("https:", "http:"), url.replace("cinejoy.pk", "cinejoy.pk.attacker.invalid"),
            url.replace("/1/2", "/1/3"), "intent://player", "https://user@cinejoy.pk/watch/tv/1668/1/2"))
            assertFalse(cinejoyNavigationAllowed(bad, url))
    }
    @Test fun providerSelectionDoesNotChangeProfileScopedResumeKey() {
        val target = AlternateSourceTarget("Movie", false, tmdbId = 1)
        assertEquals(alternateProgressKey("profile", target), alternateProgressKey("profile", target.copy(provider = AlternateSourceProvider.CINEJOY)))
    }
}
