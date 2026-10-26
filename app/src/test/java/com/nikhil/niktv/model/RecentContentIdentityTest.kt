package com.nikhil.niktv.model

import com.nikhil.niktv.data.AlternateSourceTarget
import org.junit.Assert.*
import org.junit.Test

class RecentContentIdentityTest {
    private fun show(id: String, tmdb: Int? = null, time: Long = 1, episode: Int = 2, profile: String = "wio") =
        RecentItem(FavoriteKind.SERIES, MediaItem(id, "Kyunki Saas Bhi Kabhi Bahu Thi", null, null, externalTmdbId = tmdb),
            lastPlayed = MediaItem("$id:2:$episode", "Episode $episode", null, null, seasonNumber = 2, episodeNumber = episode,
                externalTmdbId = tmdb), playedAtMillis = time, profileKey = profile)

    @Test fun providerAndAlternateHistoryShareTheSeriesIdentityButKeepTheNewestSource() {
        val alternate = show("tmdb-series:6060", 6060, 1).copy(alternateTarget = AlternateSourceTarget("Show", true, 6060))
        val provider = show("393989", time = 2, episode = 21).let {
            it.copy(lastPlayed = it.lastPlayed!!.copy(title = "Pari Hides Her Scar", externalTmdbId = 6060))
        }
        val original = listOf(alternate, provider)
        assertEquals(listOf(provider), original.distinctRecentContent())
        assertEquals(21, original.distinctRecentContent().single().lastPlayed!!.episodeNumber)
        assertNull(original.distinctRecentContent().single().alternateTarget)
        assertEquals(2, original.size) // Stored history is not modified by presentation.
    }

    @Test fun newerAlternateSelectionPreservesItsSourceAndEpisode() {
        val provider = show("provider", 6060, 1, 21)
        val alternate = show("tmdb-series:6060", 6060, 2, 2).copy(alternateTarget = AlternateSourceTarget("Show", true, 6060))
        assertSame(alternate, listOf(provider, alternate).distinctRecentContent().single())
    }

    @Test fun sameTitleWithDifferentOrUnknownIdentitiesIsNeverGuessed() {
        assertEquals(2, listOf(show("a", 6060), show("b", 9999)).distinctRecentContent().size)
        assertEquals(2, listOf(show("a"), show("b")).distinctRecentContent().size)
    }

    @Test fun profileAndMovieSeriesBoundariesRemainSeparate() {
        val one = show("one", 6060)
        assertEquals(2, listOf(one, one.copy(profileKey = "nikhil")).distinctRecentContent().size)
        assertEquals(2, listOf(one, one.copy(kind = FavoriteKind.MOVIE)).distinctRecentContent().size)
    }

    @Test fun metadataFreeTmdbIdsAndAlternateTargetsStillHaveExplicitIdentity() {
        val provider = show("provider", 6060)
        assertEquals(provider.contentIdentity(), show("tmdb-series:6060").contentIdentity())
        assertEquals(provider.contentIdentity(), show("other").copy(alternateTarget = AlternateSourceTarget("Show", true, 6060)).contentIdentity())
    }

    @Test fun equalTimestampsKeepExistingOrderAndStableKeysAcrossSourceChanges() {
        val one = show("one", 6060)
        val two = show("two", 6060)
        assertSame(two, listOf(two, one).distinctRecentContent().single())
        assertEquals(one.contentIdentity(), two.contentIdentity())
    }

    @Test fun movieIdentityDoesNotUseAnUnrelatedEpisode() {
        val movie = show("movie").copy(kind = FavoriteKind.MOVIE)
        assertNotEquals(movie.contentIdentity(), show("tmdb-movie:6060", 6060).copy(kind = FavoriteKind.MOVIE).contentIdentity())
    }

    @Test fun profileSeparatorsCannotCollideWithProviderIds() {
        val first = show("z", profile = "p|SERIES|source:x").copy(kind = FavoriteKind.MOVIE)
        val second = show("x|MOVIE|source:z", profile = "p")
        assertNotEquals(first.contentIdentity(), second.contentIdentity())
    }
}
