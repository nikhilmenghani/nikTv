package com.nikhil.niktv.ui

import com.nikhil.niktv.model.DashboardSurface
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbSectionsTest {
    @Test
    fun moviesAndSeriesOnlyExposeTheirOwnTmdbSections() {
        val movies = tmdbSectionsForSurface(DashboardSurface.MOVIES)
        val series = tmdbSectionsForSurface(DashboardSurface.SERIES)

        assertTrue(movies.isNotEmpty())
        assertTrue(movies.all { !it.series })
        assertTrue(series.isNotEmpty())
        assertTrue(series.all { it.series })
    }
}
