package com.nikhil.niktv.ui

import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class AlternatePlaybackPolicyTest {
    private val show = MediaItem("series", "Friends", null, "private-provider-command", externalTmdbId = 1668)
    @Test fun legacyTmdbTilesKeepTheirKnownIdentityWithoutSearchingMetadata() {
        val movie = show.copy(id = "tmdb-movie:603", title = "The Matrix", externalTmdbId = null)
        val series = show.copy(id = "tmdb-series:1668", externalTmdbId = null)
        assertEquals(603, alternateTargetFor(AlternateSourceRequest(movie, CatalogType.MOVIES)).tmdbId)
        assertEquals(1668, alternateTargetFor(AlternateSourceRequest(series, CatalogType.SERIES)).tmdbId)
        assertNull(alternateTargetFor(AlternateSourceRequest(movie.copy(id = "provider-movie:603"), CatalogType.MOVIES)).tmdbId)
    }
    @Test fun editedQueriesClearPreviousIdentityAndAcceptAnExplicitReleaseYear() {
        val original = AlternateSourceTarget("The Matrix", false, tmdbId = 603, year = 1999)
        assertEquals(original, alternateEditedTarget(original, "The Matrix"))
        val edited = alternateEditedTarget(original, "The Thing (1982) [English]")
        assertEquals("The Thing", edited.title)
        assertEquals(1982, edited.year)
        assertNull(edited.tmdbId)
        assertEquals("1917", alternateEditedTarget(original, "1917").title)
        assertNull(alternateEditedTarget(original, "1917").year)
    }
    @Test fun episodeUsesSeriesIdentityAndExactNumbersWithoutChangingProviderItem() {
        val episode = MediaItem("episode", "Pilot", null, "private-episode-command", seasonNumber = 1, episodeNumber = 1)
        val target = alternateTargetFor(AlternateSourceRequest(episode, CatalogType.SERIES, show))
        assertEquals("Friends", target.title)
        assertEquals(1668, target.tmdbId)
        assertEquals(1, target.season); assertEquals(1, target.episode)
        assertEquals("private-episode-command", episode.command)
    }
    @Test fun numericTitlesAreNotMistakenForReleaseYears() {
        for (title in listOf("1917", "2001: A Space Odyssey", "2012")) {
            assertNull(alternateTargetFor(AlternateSourceRequest(show.copy(title = title), CatalogType.MOVIES)).year)
        }
        assertEquals(2019, alternateTargetFor(AlternateSourceRequest(show.copy(title = "1917 (2019)"), CatalogType.MOVIES)).year)
    }
    @Test fun alternateLookupRemovesActualProviderLanguageSuffixesFromMovieAndSeriesNames() {
        assertEquals("Friends", alternateTargetFor(AlternateSourceRequest(show.copy(title = "Friends (English)"), CatalogType.SERIES)).title)
        assertEquals("Bringing Dad Home", alternateTargetFor(AlternateSourceRequest(show.copy(title = "Bringing Dad Home (English)"), CatalogType.MOVIES)).title)
    }
    @Test fun onlyRecognizedEdgeLanguageAndQualityGroupsAreRemoved() {
        val names = mapOf(
            "English | Friends (FHD)" to "Friends",
            "[English / Hindi] RRR [2160p HDR]" to "RRR",
            "RRR (Dual Audio) - 4K" to "RRR",
            "[Hindi] Sony SAB: Taarak Mehta Ka Ooltah Chashmah (HD)" to "Taarak Mehta Ka Ooltah Chashmah",
            "Friends (The Reunion)" to "Friends (The Reunion)",
            "Friends ( )" to "Friends ( )"
        )
        for ((provider, expected) in names) {
            assertEquals(provider, expected, alternateTargetFor(AlternateSourceRequest(show.copy(title = provider), CatalogType.SERIES)).title)
        }
    }
    @Test fun realLanguageWordsAndNumericMovieNamesArePreserved() {
        for (title in listOf("The English Patient", "English Vinglish", "Hindi Medium", "1917", "2001: A Space Odyssey", "2012", "English")) {
            assertEquals(title, alternateTargetFor(AlternateSourceRequest(show.copy(title = title), CatalogType.MOVIES)).title)
        }
    }
    @Test fun strippingProviderTagsRetainsReleaseYearHintTmdbAndEpisodeIdentity() {
        val movie = alternateTargetFor(AlternateSourceRequest(show.copy(title = "FHD | 1917 (English) (2019)"), CatalogType.MOVIES))
        assertEquals("1917", movie.title)
        assertEquals(2019, movie.year)
        assertEquals(1668, movie.tmdbId)
        val episode = MediaItem("episode", "Episode 2", null, null, seasonNumber = 0, episodeNumber = 2)
        val series = alternateTargetFor(AlternateSourceRequest(episode, CatalogType.SERIES, show.copy(title = "[English] Friends [1080p]")))
        assertEquals("Friends", series.title)
        assertEquals(1668, series.tmdbId)
        assertEquals(0, series.season)
        assertEquals(2, series.episode)
    }
    @Test fun qualityAndExpiringUrlsDoNotChangeResumeButProfileEpisodeAndTypeDo() {
        val target = AlternateSourceTarget("Friends", true, 1668, season = 1, episode = 1)
        val key = alternateProgressKey("a", target)
        assertEquals(key, alternateProgressKey("a", target.copy(title = "Localized title")))
        assertNotEquals(key, alternateProgressKey("b", target))
        assertNotEquals(key, alternateProgressKey("a", target.copy(episode = 2)))
        assertNotEquals(key, alternateProgressKey("a", target.copy(series = false, season = null, episode = null)))
        assertFalse(key.contains("private"))
    }
    @Test fun unmappedRemakesKeepSeparateProgress() {
        val target = AlternateSourceTarget("The Thing", false, year = 1982)
        assertNotEquals(alternateProgressKey("a", target), alternateProgressKey("a", target.copy(year = 2011)))
    }
    @Test fun retryRetainsQualityEvenWhenVariantOrderChanges() {
        val target = AlternateSourceTarget("Movie", false)
        val old = AlternateStream("quality:2", "720p", "https://media.invalid/old.m3u8", height = 720)
        val source = AlternatePlaybackSource(target, listOf(old), old.id, "a")
        val fresh = listOf(old.copy(height = 480), old.copy(id = "quality:1", height = 720))
        assertEquals(720, alternateRetryStream(source, fresh)?.height)
        assertNull(alternateRetryStream(source, emptyList()))
    }
    @Test fun normalPlaybackDefaultsToProviderAndAlternateChoiceIsSessionOnly() {
        val normal = PlayingMedia(show, "https://provider.invalid/media", CatalogType.SERIES)
        assertNull(normal.alternateSource)
        val target = AlternateSourceTarget("Friends", true, 1668, season = 1, episode = 1)
        val stream = AlternateStream("auto", "Auto", "https://media.invalid/master.m3u8")
        val alternate = normal.copy(url = stream.url, alternateSource = AlternatePlaybackSource(target, listOf(stream), stream.id, "a"))
        assertEquals(normal.media, alternate.media)
        assertEquals("https://provider.invalid/media", normal.url)
        assertNull(normal.alternateSource)
        assertEquals("auto", alternate.alternateSource?.selectedId)
    }
}
