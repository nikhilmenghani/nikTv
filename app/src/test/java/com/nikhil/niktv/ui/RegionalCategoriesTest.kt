package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class RegionalCategoriesTest {
    @Test fun everyCategoryHasExactlyTwoChoicesWithoutDuplicates() {
        val all = tmdbCategoryPairs(DashboardSurface.HOME).flatMap { listOf(it.hollywood, it.bollywood) }
        assertEquals(TmdbHomeSection.entries.toSet(), all.toSet())
        assertEquals(all.size, all.distinct().size)
        assertEquals(38, all.size)
        assertTrue(tmdbCategoryPairs(DashboardSurface.MOVIES).all { !it.hollywood.series && !it.bollywood.series })
        assertTrue(tmdbCategoryPairs(DashboardSurface.SERIES).all { it.hollywood.series && it.bollywood.series })
        assertTrue(tmdbCategoryPairs(DashboardSurface.LIVE_TV).isEmpty())
    }
    @Test fun pairedQueriesKeepGenreTypeAndReleasePolicyWhileChangingLanguage() {
        for (pair in tmdbCategoryPairs(DashboardSurface.HOME)) {
            val english = tmdbRegionalRequest(pair.hollywood, "2026-10-23")
            val hindi = tmdbRegionalRequest(pair.bollywood, "2026-10-23")
            assertEquals(english.first, hindi.first)
            assertEquals("en", english.second["with_original_language"])
            assertEquals("hi", hindi.second["with_original_language"])
            assertEquals(english.second - "with_original_language", hindi.second - "with_original_language")
            assertEquals("false", hindi.second["include_adult"])
            assertTrue(hindi.first.contains(if (pair.hollywood.series) "tv" else "movie"))
        }
    }
    @Test fun regionalTopRatedUsesRankedDiscoverInsteadOfIgnoringLanguageFilter() {
        val request = tmdbRegionalRequest(TmdbHomeSection.BOLLYWOOD_TOP_RATED_MOVIES, "2026-10-23")
        assertEquals("/discover/movie", request.first)
        assertEquals("vote_average.desc", request.second["sort_by"])
        assertEquals("100", request.second["vote_count.gte"])
        assertEquals("2026-10-23", request.second["primary_release_date.lte"])
    }
}
