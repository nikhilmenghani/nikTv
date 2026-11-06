package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class TmdbMovieMatchPagingTest {
    @Test fun continuesThroughFortyPagesUntilTheProviderReportsTheEnd() {
        val pages = TmdbMovieMatchPaging(listOf(MovieMatchRequest("movie")))
        repeat(40) { index ->
            val request = MovieMatchRequest("movie", index + 1)
            assertEquals(request, pages.next())
            pages.completed(request, listOf("id-$index"), hasMore = index < 39)
        }
        assertNull(pages.next())
    }
    @Test fun repeatedOrEmptyProviderPagesCannotCreateAnInfiniteRequestLoop() {
        val pages = TmdbMovieMatchPaging(listOf(MovieMatchRequest("movie")))
        pages.next(); pages.completed(MovieMatchRequest("movie"), listOf("same"), true)
        assertEquals(MovieMatchRequest("movie", 2), pages.next())
        pages.completed(MovieMatchRequest("movie", 2), listOf("same"), true)
        assertNull(pages.next())
        pages.completed(MovieMatchRequest("movie", 3), emptyList(), true)
        assertNull(pages.next())
    }
    @Test fun queriesKeepIndependentProgressAndDuplicateInitialRequestsAreRemoved() {
        val pages = TmdbMovieMatchPaging(listOf(MovieMatchRequest("one"), MovieMatchRequest("two"), MovieMatchRequest("one")))
        assertEquals(MovieMatchRequest("one"), pages.next()); pages.completed(MovieMatchRequest("one"), listOf("same"), true)
        assertEquals(MovieMatchRequest("two"), pages.next()); pages.completed(MovieMatchRequest("two"), listOf("same"), true)
        assertEquals(MovieMatchRequest("one", 2), pages.next()); assertEquals(MovieMatchRequest("two", 2), pages.next())
        assertNull(pages.next())
    }
    @Test fun categoryCursorsAreIndependentAndReorderedRepeatsStop() {
        val a = MovieMatchRequest("movie", categoryId = "a")
        val b = a.copy(categoryId = "b")
        val pages = TmdbMovieMatchPaging(listOf(a, b))
        assertEquals(a, pages.next()); pages.completed(a, listOf("1", "2"), true)
        assertEquals(b, pages.next()); pages.completed(b, listOf("1", "2"), true)
        assertEquals(a.copy(page = 2), pages.next())
        pages.completed(a.copy(page = 2), listOf("2", "1"), true)
        assertEquals(listOf(b.copy(page = 2)), pages.remaining())
    }
}
