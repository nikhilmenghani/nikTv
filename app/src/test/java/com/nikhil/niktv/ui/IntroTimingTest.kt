package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class IntroTimingTest {
    private fun episode(profile: String = "wio", season: Int? = 1, number: Int? = 1) = PlayingMedia(
        MediaItem("ep-$number", "Episode", null, null, seasonNumber = season, episodeNumber = number),
        "https://example.invalid/movie?token=secret", CatalogType.SERIES,
        series = MediaItem("series", "Series", null, null), profileKey = profile)

    @Test fun noTimingWithoutExplicitProfile() { assertNull(introTarget(episode(profile = ""))) }
    @Test fun liveChannelsNeverHaveIntroTiming() { assertNull(introTarget(episode().copy(catalogType = CatalogType.LIVE_TV))) }
    @Test fun profilesRemainSeparate() {
        assertNotEquals(introTarget(episode("wio")), introTarget(episode("nikhil")))
    }
    @Test fun episodesShareOnlyTheirOwnSeason() {
        val first = introTarget(episode())!!
        val next = introTarget(episode(number = 2))!!
        val otherSeason = introTarget(episode(season = 2))!!
        assertEquals(first.seasonKey, next.seasonKey)
        assertNotEquals(first.episodeKey, next.episodeKey)
        assertNotEquals(first.seasonKey, otherSeason.seasonKey)
        assertNotEquals(first.episodeKey, otherSeason.episodeKey)
    }
    @Test fun unknownSeasonNeverInventsSeasonOne() { assertNull(introTarget(episode(season = null))!!.seasonKey) }
    @Test fun specialsHaveTheirOwnSeason() {
        assertNotNull(introTarget(episode(season = 0))!!.seasonKey)
        assertNotEquals(introTarget(episode(season = 0))!!.seasonKey, introTarget(episode())!!.seasonKey)
    }
    @Test fun keysDoNotChangeWithUrlOrAttemptOrTitle() {
        val original = episode()
        assertEquals(introTarget(original), introTarget(original.copy(url = "file:///other.mp4", playbackAttemptId = "new",
            media = original.media.copy(title = "New title"))))
        assertTrue(introTarget(original)!!.episodeKey.matches(Regex("episode:[0-9a-f]{64}")))
    }
    @Test fun keysUseConfirmedTmdbIdentityAcrossProviderVersions() {
        val original = episode().let { it.copy(series = it.series!!.copy(externalTmdbId = 123)) }
        assertEquals(introTarget(original), introTarget(original.copy(series = original.series!!.copy(id = "different-provider-id"))))
    }
    @Test fun moviesHaveOnlyIndividualTimings() {
        val movie = episode().copy(catalogType = CatalogType.MOVIES, series = null)
        assertNull(introTarget(movie)!!.seasonKey)
        assertEquals("This movie", introTarget(movie)!!.episodeLabel)
    }
    @Test fun rangeIsStartInclusiveAndEndExclusive() {
        val range = IntroRange(10_000, 30_000)
        assertFalse(range.contains(9_999, 120_000))
        assertTrue(range.contains(10_000, 120_000))
        assertTrue(range.contains(29_999, 120_000))
        assertFalse(range.contains(30_000, 120_000))
    }
    @Test fun invalidOrNearCompletionTimingNeverShowsSkip() {
        listOf(IntroRange(-1, 10), IntroRange(100, 100), IntroRange(100, 99), IntroRange(0, 120_000),
            IntroRange(0, 119_001)).forEach { assertFalse(it.validFor(120_000)) }
        assertFalse(IntroRange(0, 30_000).validFor(0))
        assertTrue(IntroRange(0, 119_000).validFor(120_000))
    }
    @Test fun roundedSeekAcknowledgementDoesNotFlashSkipAtTheEnd() {
        val range = IntroRange(0, 30_000)
        assertTrue(range.canOfferSkip(10_000, 120_000))
        assertFalse(range.canOfferSkip(29_999, 120_000))
        assertFalse(range.canOfferSkip(29_500, 120_000))
        assertTrue(range.canOfferSkip(20_000, 120_000)) // A real rewind offers Skip again.
    }
    @Test fun countdownUsesPlaybackSecondsAndExpiresAtTheSameBoundaryAsTheButton() {
        val range = IntroRange(10_000, 30_000)
        assertNull(range.skipSecondsRemaining(9_999, 120_000))
        assertEquals(20L, range.skipSecondsRemaining(10_000, 120_000))
        assertEquals(20L, range.skipSecondsRemaining(10_001, 120_000))
        assertEquals(1L, range.skipSecondsRemaining(29_499, 120_000))
        assertNull(range.skipSecondsRemaining(29_500, 120_000))
        assertNull(range.skipSecondsRemaining(30_000, 120_000))
        assertNull(range.skipSecondsRemaining(75_000, 120_000))
        assertEquals(15L, range.skipSecondsRemaining(15_000, 120_000)) // Real rewind offers Skip again.
    }
    @Test fun pausedOrStalledPlaybackDoesNotSpendCountdownTime() {
        val range = IntroRange(0, 30_000)
        repeat(20) { assertEquals(18L, range.skipSecondsRemaining(12_500, 120_000)) }
        assertNull(range.skipSecondsRemaining(12_500, 0))
        assertNull(range.skipSecondsRemaining(12_500, 20_000))
    }
    @Test fun episodeOverrideWinsUntilRemovedEvenIfInvalidForShorterEpisode() {
        val season = IntroRange(0, 20_000)
        val override = IntroRange(0, 60_000)
        assertEquals(override, IntroTimings(override, season).effective)
        assertFalse(IntroTimings(override, season).effective!!.validFor(40_000))
        assertEquals(season, IntroTimings(null, season).effective)
    }
    @Test fun timeParsingAndFormatting() {
        assertEquals(0L, parseIntroTime("0:00"))
        assertEquals(90_000L, parseIntroTime("1:30"))
        assertEquals(4_805_000L, parseIntroTime("1:20:05"))
        assertEquals("1:30", introTime(90_999))
        assertEquals("1:20:05", introTime(4_805_000))
    }
    @Test fun malformedTimesCannotBecomeSeekPositions() {
        listOf("", "90", "-1:20", "1:60", "1:60:00", "1:2:3:4", "9999999999:00", "1::03", "1.2:30")
            .forEach { assertNull(it, parseIntroTime(it)) }
    }
    @Test fun corruptPreferencesAreIgnored() {
        listOf(null, "", "-1,3,0", "3,3,0", "4,3,0", "x,y,z", "2,4").forEach { assertNull(IntroTimingStore.decode(it)) }
        assertEquals(IntroRange(2000, 4000), IntroTimingStore.decode("2000,4000,123"))
    }
}
