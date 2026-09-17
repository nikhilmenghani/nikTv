package com.nikhil.niktv

import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlobalSearchPagerTest {
    private fun media(id: String) = MediaItem(id, "Title $id", null, null)

    @Test fun publishesEachPageBeforeRequestingTheNextType() = runBlocking {
        val pager = GlobalSearchPager()
        val events = mutableListOf<String>()
        val published = mutableListOf<MediaItem>()
        pager.next(
            search = { type, _, page ->
                events += "request:$type"
                PortalSearchPage(listOf(media("shared-id")), page, false)
            },
            onItems = { items ->
                events += "publish:${items.single().searchResultType}"
                published += items
            },
            categories = { emptyList() }
        )
        assertEquals(globalSearchTypes.flatMap { listOf("request:$it", "publish:$it") }, events)
        assertEquals(3, published.map { it.searchIdentity(SearchContentType.ALL) }.toSet().size)
    }

    @Test fun laterFailureDoesNotHideEarlierPublishedResults() = runBlocking {
        val pager = GlobalSearchPager()
        val published = mutableListOf<MediaItem>()
        val batch = pager.next(
            search = { type, _, page ->
                if (type == SearchContentType.MOVIES) throw java.io.IOException("Unavailable")
                PortalSearchPage(listOf(media("1")), page, false)
            },
            onItems = { published += it },
            categories = { emptyList() }
        )
        assertEquals(listOf(SearchContentType.SERIES, SearchContentType.LIVE_TV), published.map { it.searchResultType })
        assertEquals(1, batch.failures)
        assertTrue(pager.hasMore)
    }

    @Test fun pagesAdvanceIndependentlyAndExhaustedTypesAreNotRequestedAgain() = runBlocking {
        val pager = GlobalSearchPager()
        val calls = mutableListOf<Pair<SearchContentType, Int>>()
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { type, _, page ->
            calls += type to page
            PortalSearchPage(listOf(media("$page")), page, type == SearchContentType.SERIES && page == 1)
        }
        assertEquals(3, pager.next(search) { emptyList() }.items.size)
        assertTrue(pager.hasMore)
        val next = pager.next(search) { emptyList() }
        assertEquals(listOf(SearchContentType.SERIES to 2), calls.drop(3))
        assertEquals(SearchContentType.SERIES, next.items.single().searchResultType)
        assertFalse(pager.hasMore)
    }

    @Test fun wildcardFallbackKeepsEveryCategoryAndItsPages() = runBlocking {
        val pager = GlobalSearchPager()
        val calls = mutableListOf<String>()
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { type, category, page ->
            calls += "$type/$category/$page"
            PortalSearchPage(if (category == "*") emptyList() else listOf(media("$category-$page")), page,
                category == "a" && page == 1)
        }
        assertTrue(pager.next(search) { listOf("*", "a", "b") }.items.isEmpty())
        assertTrue(pager.hasMore)
        repeat(4) { pager.next(search) { error("Category discovery should run once per type") } }
        globalSearchTypes.forEach { type ->
            assertTrue("$type/b/1" in calls)
            assertTrue("$type/a/2" in calls)
        }
        assertFalse(pager.hasMore)
    }

    @Test fun failedPageCanRetryWithoutReplayingSuccessfulTypes() = runBlocking {
        val pager = GlobalSearchPager()
        var fail = true
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { type, _, page ->
            if (type == SearchContentType.SERIES && fail) throw java.io.IOException("Offline")
            PortalSearchPage(listOf(media("1")), page, false)
        }
        assertEquals(1, pager.next(search) { emptyList() }.failures)
        fail = false
        assertEquals(1, pager.next(search) { emptyList() }.items.size)
        assertFalse(pager.hasMore)
    }

    @Test fun repeatingProviderPageStopsInsteadOfLoopingForever() = runBlocking {
        val pager = GlobalSearchPager()
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { _, _, page ->
            PortalSearchPage(listOf(media("same")), page, true)
        }
        pager.next(search) { emptyList() }
        assertTrue(pager.next(search) { emptyList() }.items.isEmpty())
        assertFalse(pager.hasMore)
    }
}
