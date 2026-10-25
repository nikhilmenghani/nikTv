package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class TmdbMovieMatchPagingTest {
    @Test fun continuesThroughFortyPagesUntilTheProviderReportsTheEnd() {
        val pages = TmdbMovieMatchPaging(listOf("movie" to 1))
        repeat(40) { index ->
            assertEquals("movie" to index + 1, pages.next())
            pages.completed("movie", index + 1, listOf("id-$index"), hasMore = index < 39)
        }
        assertNull(pages.next())
    }
    @Test fun repeatedOrEmptyProviderPagesCannotCreateAnInfiniteRequestLoop() {
        val pages = TmdbMovieMatchPaging(listOf("movie" to 1))
        pages.next(); pages.completed("movie", 1, listOf("same"), true)
        assertEquals("movie" to 2, pages.next())
        pages.completed("movie", 2, listOf("same"), true)
        assertNull(pages.next())
        pages.completed("movie", 3, emptyList(), true)
        assertNull(pages.next())
    }
    @Test fun queriesKeepIndependentProgressAndDuplicateInitialRequestsAreRemoved() {
        val pages = TmdbMovieMatchPaging(listOf("one" to 1, "two" to 1, "one" to 1))
        assertEquals("one" to 1, pages.next()); pages.completed("one", 1, listOf("same"), true)
        assertEquals("two" to 1, pages.next()); pages.completed("two", 1, listOf("same"), true)
        assertEquals("one" to 2, pages.next()); assertEquals("two" to 2, pages.next())
        assertNull(pages.next())
    }
}
