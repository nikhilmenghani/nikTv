package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMappingTest {
    @Test
    fun ambiguousSameTitleReturnsAllPlausibleMatchesInRankOrder() {
        val english = MediaItem("100", "Welcome to the Jungle (2003)", null, "/english")
        val hindi = MediaItem("200", "Welcome to the Jungle (2026) Hindi", null, "/hindi")
        val movie = TmdbMovie(
            id = 11111,
            title = "Welcome to the Jungle",
            originalTitle = "Welcome to the Jungle",
            overview = null,
            posterUrl = null,
            backdropUrl = null,
            releaseDate = "2026-12-25",
            voteAverage = null
        )

        val matches = rankTmdbMovieMatches(movie, listOf(english, hindi))

        assertEquals(listOf("200", "100"), matches.map { it.id })
    }

    @Test
    fun providerTmdbIdWinsOverACloserTitleGuess() {
        val exactProviderIdentity = MediaItem(
            id = "42",
            title = "EN | Completely Different Display Name",
            logo = null,
            command = null,
            externalTmdbId = 1234
        )
        val misleadingTitle = MediaItem(
            id = "99",
            title = "The Example Movie (2026)",
            logo = null,
            command = null
        )

        val result = matchTmdbMovie(
            TmdbMovie(
                id = 1234,
                title = "The Example Movie",
                originalTitle = "The Example Movie",
                overview = null,
                posterUrl = null,
                backdropUrl = null,
                releaseDate = "2026-01-01",
                voteAverage = null
            ),
            listOf(misleadingTitle, exactProviderIdentity)
        )

        assertEquals("42", result?.id)
    }
}
