package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class ProviderSeriesResumeTest {
    private val series = MediaItem("series-one", "Provider series", null, "provider-series")
    private val episode = MediaItem("episode-nine", "Episode nine", null, "provider-episode", seasonNumber = 2, episodeNumber = 9)
    private val recent = RecentItem(FavoriteKind.SERIES, series, lastPlayed = episode, profileKey = "one")
    private val progress = PlaybackProgress("fixture", 120_000, 1_200_000)
    private fun target(entries: List<RecentItem>, value: PlaybackProgress? = progress, profile: String = "one") =
        providerSeriesResumeRecent(profile, series, entries) { value }

    @Test fun validResumeRetainsTheOriginalSeasonEpisodeAndProviderItem() {
        val resumed = mutableListOf<RecentItem>(); val opened = mutableListOf<MediaItem>()
        playProviderSeriesHero("one", series, listOf(recent), { progress }, resumed::add, opened::add)
        assertSame(recent, resumed.single()); assertSame(episode, resumed.single().lastPlayed)
        assertEquals(2, resumed.single().lastPlayed?.seasonNumber); assertEquals(9, resumed.single().lastPlayed?.episodeNumber)
        assertTrue(opened.isEmpty())
    }
    @Test fun wrongProfileKindSeriesAndAlternatePlaybackCannotDriveIptvResume() {
        val alternatives = listOf(recent.copy(profileKey = "two"), recent.copy(kind = FavoriteKind.MOVIE),
            recent.copy(media = series.copy(id = "different")),
            recent.copy(alternateTarget = AlternateSourceTarget("Provider series", true, season = 2, episode = 9)))
        var progressReads = 0
        assertNull(providerSeriesResumeRecent("one", series, alternatives) { progressReads++; progress })
        assertEquals(0, progressReads)
        assertNull(target(listOf(recent), profile = "two"))
        assertNull(target(listOf(recent.copy(profileKey = "")), profile = ""))
    }
    @Test fun absentZeroUnknownAndCompletedProgressOpenExistingSeriesDetails() {
        for (value in listOf(null, progress.copy(positionMillis = 0), progress.copy(positionMillis = -1),
            progress.copy(durationMillis = 0), progress.copy(positionMillis = progress.durationMillis),
            progress.copy(positionMillis = progress.durationMillis + 1),
            progress.copy(action = ViewingAction.COMPLETED), progress.copy(action = ViewingAction.MARK_UNWATCHED))) {
            val opened = mutableListOf<MediaItem>()
            playProviderSeriesHero("one", series, listOf(recent), { value }, { fail("Must not resume invalid progress") }, opened::add)
            assertEquals(listOf(series), opened)
        }
        assertNull(target(listOf(recent.copy(lastPlayed = null))))
    }
    @Test fun newestInvalidRecentDoesNotResurrectAnOlderEpisode() {
        val latest = recent.copy(lastPlayed = null, playedAtMillis = 2)
        val older = recent.copy(playedAtMillis = 1)
        assertNull(target(listOf(latest, older)))
        assertNull(providerSeriesResumeRecent("one", series, listOf(latest.copy(lastPlayed = episode), older)) {
            if (it.playedAtMillis == 2L) null else progress
        })
    }
    @Test fun providerAliasCanResumeButNeverAnAlternateRecentWithTheSameId() {
        val alias = recent.copy(media = series.copy(id = "stored-container"), series = series)
        assertSame(alias, target(listOf(alias)))
        val alternate = recent.copy(alternateTarget = AlternateSourceTarget("Provider series", true))
        assertSame(recent, target(listOf(alternate, recent)))
    }
    @Test fun profileScopedProgressLookupCannotResumeAnotherEpisodeOrProfile() {
        val prefix = requireNotNull(discoveryRecentProgressPrefix(recent))
        val lookup = DiscoveryProgressLookup.build("one", listOf(recent), listOf(
            progress.copy(key = prefix + "original"),
            progress.copy(key = prefix.replace("|one|", "|two|") + "other", positionMillis = 300_000)))
        assertSame(recent, providerSeriesResumeRecent("one", series, listOf(recent), lookup::recent))
        val wrong = DiscoveryProgressLookup.build("two", listOf(recent), listOf(progress.copy(key = prefix)))
        assertNull(providerSeriesResumeRecent("one", series, listOf(recent), wrong::recent))
    }
}
