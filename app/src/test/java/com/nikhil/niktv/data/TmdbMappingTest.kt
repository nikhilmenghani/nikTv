package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMappingTest {
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
