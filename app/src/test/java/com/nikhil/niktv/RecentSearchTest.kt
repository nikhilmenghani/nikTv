package com.nikhil.niktv

import com.nikhil.niktv.model.RecentSearch
import com.nikhil.niktv.model.SearchContentType
import com.nikhil.niktv.model.canonicalSearchQuery
import com.nikhil.niktv.model.deduplicatedRecentSearches
import com.nikhil.niktv.model.normalizedSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchTest {
    @Test
    fun normalizationIgnoresCaseAndRepeatedWhitespace() {
        assertEquals("The Office", "  The \t Office \n".canonicalSearchQuery())
        assertEquals("the office", "  The \t Office \n".normalizedSearchQuery())
    }

    @Test
    fun deduplicationKeepsLatestEquivalentSearchPerType() {
        val searches = listOf(
            RecentSearch("  The   Office ", SearchContentType.SERIES, 3),
            RecentSearch("the office", SearchContentType.SERIES, 2),
            RecentSearch("THE OFFICE", SearchContentType.MOVIES, 1)
        )

        val result = searches.deduplicatedRecentSearches()

        assertEquals(2, result.size)
        assertEquals(3, result[0].searchedAtMillis)
        assertEquals(SearchContentType.MOVIES, result[1].type)
    }

    @Test
    fun deduplicationPreservesLatestFirstOrderAndLimit() {
        val searches = (0..24).map {
            RecentSearch("query $it", SearchContentType.LIVE_TV, 100L - it)
        }

        val result = searches.deduplicatedRecentSearches()

        assertEquals(20, result.size)
        assertEquals("query 0", result.first().query)
        assertEquals("query 19", result.last().query)
    }
}
