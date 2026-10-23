package com.nikhil.niktv.ui

import com.nikhil.niktv.data.AlternateSourceTarget
import org.junit.Assert.*
import org.junit.Test

class VidyEmbeddedPlayerPolicyTest {
    private val nonce = "12345678-1234-1234-1234-123456789012"
    private fun event(query: String = "event=timeupdate&position=12.5&duration=120", token: String = nonce) =
        "https://niktv.invalid/__vidy_event?nonce=$token&$query"

    @Test fun exactMovieOrEpisodeIdentityIsRequiredAndNoAutomaticEpisodeSwitchingIsEnabled() {
        val movie = AlternateSourceTarget("Movie", false, 603)
        assertEquals("https://vidy.st/movie/603?color=E50914&autoplay=true&progress=12", vidyEmbeddedUrl(movie, 12_500))
        val episode = AlternateSourceTarget("Friends", true, 1668, season = 0, episode = 2)
        assertEquals("https://vidy.st/tv/1668/0/2?color=E50914&autoplay=false&progress=0", vidyEmbeddedUrl(episode, -1, false))
        assertNull(vidyEmbeddedUrl(movie.copy(tmdbId = null)))
        assertNull(vidyEmbeddedUrl(episode.copy(episode = null)))
        assertNull(vidyEmbeddedUrl(episode.copy(season = -1)))
        assertNull(vidyEmbeddedUrl(episode.copy(episode = 0)))
    }

    @Test fun onlyExactHttpsEmbedHostsCanNavigate() {
        for (url in listOf("https://vidy.st/movie/603", "https://www.vidy.st/tv/1668/1/1", "https://vidy.st:443/movie/603")) {
            assertTrue(url, vidyAllowedEmbedNavigation(url))
        }
        for (url in listOf("http://vidy.st/movie/603", "https://vidy.st.attacker.invalid/movie/603", "https://attacker@vidy.st/movie/603", "https://vidy.st:8080/movie/603", "intent://player", "file:///movie", "javascript:alert(1)")) {
            assertFalse(url, vidyAllowedEmbedNavigation(url))
        }
    }

    @Test fun progressMustComeFromCurrentLocalMainFrameRelay() {
        assertEquals(VidyPlaybackEvent("timeupdate", 12_500, 120_000), vidyPlaybackEvent(event(), nonce, true))
        assertNull(vidyPlaybackEvent(event(), nonce, false))
        assertNull(vidyPlaybackEvent(event(token = "old-session"), nonce, true))
        assertNull(vidyPlaybackEvent(event().replace("niktv.invalid", "vidy.st"), nonce, true))
        assertNull(vidyPlaybackEvent(event().replace("https:", "http:"), nonce, true))
        assertNull(vidyPlaybackEvent(event() + "&nonce=$nonce", nonce, true))
        assertNull(vidyPlaybackEvent(event().replace("/__vidy_event", "/unexpected"), nonce, true))
    }

    @Test fun invalidNumbersAndUnknownEventsCannotOverwriteResume() {
        for (query in listOf("event=next&position=1&duration=120", "event=play&position=NaN&duration=120", "event=pause&position=1&duration=Infinity", "event=timeupdate&position=-1&duration=120", "event=timeupdate&position=200&duration=120", "event=timeupdate&position=1&duration=0", "event=timeupdate&position=1&duration=604801")) {
            assertNull(query, vidyPlaybackEvent(event(query), nonce, true))
        }
        assertEquals(VidyPlaybackEvent("ended", 120_000, 120_000), vidyPlaybackEvent(event("event=ended&position=120.2&duration=120"), nonce, true))
    }

    @Test fun wrapperRestrictsMessagesAndHasNoNativeJavaScriptBridgeOrPopupPermission() {
        val html = vidyHostHtml("https://vidy.st/movie/603?autoplay=true&progress=12", nonce)
        assertTrue(html.contains("message.source!==frame.contentWindow"))
        assertTrue(html.contains("message.origin"))
        assertTrue(html.contains("script-src 'nonce-$nonce'"))
        assertTrue(html.contains("autoplay=true&amp;progress=12"))
        assertFalse(html.contains("allow-popups"))
        assertFalse(html.contains("allow-top-navigation"))
        assertFalse(html.contains("JavascriptInterface"))
        assertFalse(html.contains("episodeSelector"))
    }
}
