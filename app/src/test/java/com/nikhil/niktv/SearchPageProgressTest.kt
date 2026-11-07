package com.nikhil.niktv

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class SearchPageProgressTest {
    @Test fun rejectedOrDuplicateVisibleRowsStillAllowFreshRawPages() {
        val progress = SearchPageProgress()
        assertTrue(progress.hasNext(PortalSearchPage(emptyList(), 1, true, listOf("movie"))))
        assertTrue(progress.hasNext(PortalSearchPage(emptyList(), 2, true, listOf("episode"))))
        assertFalse(progress.hasNext(PortalSearchPage(emptyList(), 3, false, listOf("series"))))
    }
    @Test fun repeatedRawPageStopsEvenWhenProviderClaimsMore() {
        val progress = SearchPageProgress()
        assertTrue(progress.hasNext(PortalSearchPage(emptyList(), 1, true, listOf("a", "b"))))
        assertFalse(progress.hasNext(PortalSearchPage(emptyList(), 2, true, listOf("b", "a"))))
        assertFalse(progress.hasNext(PortalSearchPage(emptyList(), 3, true)))
    }
}
