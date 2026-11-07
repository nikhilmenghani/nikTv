package com.nikhil.niktv

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AllCategorySearchTest {
    private fun item(id: String, title: String = id) = MediaItem(id, title, null, null)
    private fun page(vararg items: MediaItem, more: Boolean = false) = PortalSearchPage(items.toList(), 1, more)

    @Test fun wildcardFailureStillFindsFriendsInEnglishSeries() = runBlocking {
        val found = item("friends-1994", "Friends (1994)")
        val visited = mutableListOf<String>()
        val result = searchAllCategoryPage(1, listOf("documentaries", "english-series"), search = {
            visited += it
            if (it == "*") throw IOException("Wildcard unsupported")
            if (it == "english-series") page(found) else page(item("other", "Other Friends"))
        })
        assertTrue("English category must be searched after wildcard failure", "english-series" in visited)
        assertTrue(found in result.page.items)
        assertEquals(0, result.failedCategories)
    }

    @Test fun partialWildcardAndMoreThan24MatchesDoNotStopLaterCategoriesOrExactTitle() = runBlocking {
        val friends = item("original", "Friends (1994)")
        val categories = (1..8).map { "category-$it" }
        val visited = mutableListOf<String>()
        val result = searchAllCategoryPage(1, categories, search = { category ->
            visited += category
            when (category) {
                "*" -> page(item("wildcard", "Your Friends & Neighbors"))
                "category-1" -> page(item("exact", "friends"))
                "category-8" -> page(friends)
                else -> PortalSearchPage((1..6).map { item("$category-$it", "Other Friends $it") }, 1, false)
            }
        })
        assertTrue(result.page.items.size > 24)
        assertTrue(friends in result.page.items)
        assertTrue(categories.all { it in visited })
        assertEquals("Exact title should rank first", "exact", result.page.items.rankSearchResults("friends", SearchContentType.SERIES).first().id)
    }

    @Test fun categoryFailurePreservesOtherResultsAndSignalsIncompleteCoverage() = runBlocking {
        val result = searchAllCategoryPage(1, listOf("broken", "english"), search = {
            if (it == "broken") throw IOException("Unavailable category")
            if (it == "english") page(item("friends")) else page()
        })
        assertEquals(listOf("friends"), result.page.items.map { it.id })
        assertEquals(1, result.failedCategories)
    }

    @Test fun pageContinuationAndDeduplicationArePreserved() = runBlocking {
        val result = searchAllCategoryPage(2, listOf("english", "english", "*"), search = {
            page(item("friends"), more = it == "english")
        })
        assertEquals(2, result.page.page)
        assertTrue(result.page.hasMore)
        assertEquals(1, result.page.items.size)
    }

    @Test fun cancelledSearchDoesNotContinueThroughOtherCategories() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            searchAllCategoryPage(1, listOf("english"), search = {
                visited += it; throw CancellationException("Query changed")
            })
            fail("Cancellation must escape")
        } catch (_: CancellationException) { assertEquals(listOf("*"), visited) }
    }

    @Test fun providerRequestsStayWithinThreeConcurrentCategories() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        searchAllCategoryPage(1, (1..10).map(Int::toString), search = {
            val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            delay(5); active.decrementAndGet(); page()
        })
        assertEquals(3, peak.get())
    }

    @Test fun incompleteCachedStalkerMatchesStillRequireCategorySearchButXtreamDoesNot() {
        assertTrue(requiresCategorySearch(PortalType.STALKER, SearchContentType.SERIES, "*", false))
        assertFalse(requiresCategorySearch(PortalType.STALKER, SearchContentType.SERIES, "*", true))
        assertFalse(requiresCategorySearch(PortalType.STALKER, SearchContentType.SERIES, "english", false))
        assertFalse(requiresCategorySearch(PortalType.XTREAM, SearchContentType.SERIES, "*", false))
        assertFalse(requiresCategorySearch(PortalType.STALKER, SearchContentType.ALL, "*", false))
    }

    @Test fun totalProviderFailureIsNotReportedAsAnEmptySuccessfulSearch() = runBlocking {
        try {
            searchAllCategoryPage(1, listOf("english"), search = { throw IOException("Offline") })
            fail("Expected failure")
        } catch (_: IOException) { }
    }
}
