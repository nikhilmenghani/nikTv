package com.nikhil.niktv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbReleaseFilterTest {
    @Test
    fun moviesAreHiddenUntilTheirReleaseDate() {
        assertTrue(movie("2026-09-26").isReleased("2026-09-27"))
        assertTrue(movie("2026-09-27").isReleased("2026-09-27"))
        assertFalse(movie("2026-12-18").isReleased("2026-09-27"))
    }

    @Test
    fun missingDatesDoNotHideOtherwiseValidTitles() {
        assertTrue(movie(null).isReleased("2026-09-27"))
        assertTrue(series(null).isReleased("2026-09-27"))
    }

    @Test
    fun seriesAreHiddenUntilTheirFirstAirDate() {
        assertTrue(series("2026-09-27").isReleased("2026-09-27"))
        assertFalse(series("2026-09-28").isReleased("2026-09-27"))
    }

    private fun movie(releaseDate: String?) = TmdbMovie(
        id = 1,
        title = "Movie",
        originalTitle = "Movie",
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        releaseDate = releaseDate,
        voteAverage = null
    )

    private fun series(firstAirDate: String?) = TmdbSeries(
        id = 1,
        name = "Series",
        originalName = "Series",
        overview = null,
        posterUrl = null,
        backdropUrl = null,
        firstAirDate = firstAirDate,
        voteAverage = null
    )
}
