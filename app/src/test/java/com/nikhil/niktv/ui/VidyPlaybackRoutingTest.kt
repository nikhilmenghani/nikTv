package com.nikhil.niktv.ui

import com.nikhil.niktv.data.AlternatePlaybackRoute
import com.nikhil.niktv.data.AlternateSourceProvider
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.data.vidyFallbackStream
import com.nikhil.niktv.model.AlternatePlaybackSource
import org.junit.Assert.*
import org.junit.Test

/** Route/identity checks without a WebView, network, portal or decoder. */
class VidyPlaybackRoutingTest {
    private val movie = AlternateSourceTarget("The Matrix", false, tmdbId = 603,
        provider = AlternateSourceProvider.MOVY)

    @Test fun embeddedFallbackRequiresAnExactPublicIdentityAndExplicitEpisode() {
        assertNull(vidyFallbackStream(movie.copy(tmdbId = null)))
        assertNull(vidyFallbackStream(movie.copy(tmdbId = 0)))
        assertNull(vidyFallbackStream(movie.copy(tmdbId = -1)))
        val series = movie.copy(title = "Friends", series = true, tmdbId = 1668)
        assertNull(vidyFallbackStream(series))
        assertNull(vidyFallbackStream(series.copy(season = 1)))
        assertNull(vidyFallbackStream(series.copy(season = -1, episode = 1)))
        assertNull(vidyFallbackStream(series.copy(season = 1, episode = 0)))
        val special = requireNotNull(vidyFallbackStream(series.copy(season = 0, episode = 4)))
        assertEquals(AlternatePlaybackRoute.VIDY_EMBEDDED, special.route)
        assertEquals("embedded", special.format)
        assertEquals("https://vidy.st/tv/1668/0/4", special.url)
        assertEquals("https://vidy.st/movie/603", vidyFallbackStream(movie)?.url)
    }

    @Test fun editedTitleRetainsTheExplicitSourceButDropsTheOldMediaIdentity() {
        val edited = alternateEditedTarget(movie, "The Thing (1982)")
        assertEquals(AlternateSourceProvider.MOVY, edited.provider)
        assertEquals("The Thing", edited.title)
        assertEquals(1982, edited.year)
        assertNull(edited.tmdbId)
        assertNull(vidyFallbackStream(edited))
        assertEquals(movie, alternateEditedTarget(movie, "The Matrix"))
    }

    @Test fun sourceFamilyAndPlaybackRouteDoNotSplitResumeHistory() {
        assertEquals(alternateProgressKey("profile-a", movie),
            alternateProgressKey("profile-a", movie.copy(provider = AlternateSourceProvider.AUTO)))
        assertNotEquals(alternateProgressKey("profile-a", movie), alternateProgressKey("profile-b", movie))
        val episode = movie.copy(series = true, tmdbId = 1668, season = 1, episode = 2)
        assertNotEquals(alternateProgressKey("profile-a", episode),
            alternateProgressKey("profile-a", episode.copy(episode = 3)))
    }

    @Test fun retryKeepsTheSelectedRouteRatherThanTakingTheFirstSameHeightChoice() {
        val native = AlternateStream("movy:auto", "Movy Auto", "https://media.invalid/master.m3u8", height = 720)
        val embedded = requireNotNull(vidyFallbackStream(movie)).copy(height = 720)
        val old = AlternatePlaybackSource(movie, listOf(native, embedded), native.id, "profile-a")
        val renewedNative = native.copy(url = "https://media.invalid/renewed.m3u8")
        assertEquals(renewedNative, alternateRetryStream(old, listOf(embedded, renewedNative)))
        assertEquals(embedded, alternateRetryStream(old.copy(selectedId = embedded.id), listOf(renewedNative, embedded)))
        val changedNativeId = renewedNative.copy(id = "movy:replacement")
        assertEquals(changedNativeId, alternateRetryStream(old, listOf(embedded, changedNativeId)))
        assertNull(alternateRetryStream(old, emptyList()))
    }
}
