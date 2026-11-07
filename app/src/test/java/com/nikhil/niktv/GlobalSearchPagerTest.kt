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

    @Test fun stalkerPartialWildcardStillVisitsEveryCategory() = runBlocking {
        val pager = GlobalSearchPager(exhaustiveCategories = true)
        val calls = mutableListOf<String>()
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { type, category, page ->
            calls += "$type/$category"
            PortalSearchPage(listOf(media(category)), page, false)
        }
        pager.next(search) { listOf("english", "classic") }
        assertTrue(pager.hasInitialPages)
        while (pager.hasInitialPages) pager.next(search) { error("Already discovered") }
        globalSearchTypes.forEach { type ->
            assertTrue("$type/classic" in calls)
            assertTrue("$type/english" in calls)
        }
        assertFalse(pager.hasMore)
    }

    @Test fun filteredRawPageDoesNotHideLaterAcceptedResults() = runBlocking {
        val pager = GlobalSearchPager()
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { type, _, page ->
            if (type == SearchContentType.SERIES && page == 1)
                PortalSearchPage(emptyList(), page, true, providerItemIds = listOf("rejected-movie"))
            else PortalSearchPage(listOf(media("$type-$page")), page, false)
        }
        pager.next(search) { emptyList() }
        assertTrue(pager.hasMore)
        assertEquals("SERIES-2", pager.next(search) { emptyList() }.items.single().id)
        assertFalse(pager.hasMore)
    }

    @Test fun stalkerWildcardFailureDoesNotPreventCategoryDiscovery() = runBlocking {
        val pager = GlobalSearchPager(exhaustiveCategories = true)
        val search: suspend (SearchContentType, String, Int) -> PortalSearchPage = { _, category, page ->
            if (category == "*") throw java.io.IOException("Wildcard unsupported")
            PortalSearchPage(listOf(media(category)), page, false)
        }
        assertEquals(3, pager.next(search) { listOf("classic") }.failures)
        val found = mutableListOf<MediaItem>()
        repeat(3) { found += pager.next(search) { error("Already discovered") }.items }
        assertEquals(globalSearchTypes.toSet(), found.map { it.searchResultType }.toSet())
        assertFalse("Failed wildcard retries must not auto-loop", pager.hasInitialPages)
    }

    @Test fun cancellationPreservesTheUnfinishedTypeForResume() = runBlocking {
        val pager = GlobalSearchPager()
        try {
            pager.next(search = { _, _, _ -> throw kotlinx.coroutines.CancellationException("Scope changed") }, categories = { emptyList() })
            fail("Cancellation must escape")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        val resumed = pager.next(search = { type, _, page -> PortalSearchPage(listOf(media(type.name)), page, false) }, categories = { emptyList() })
        assertEquals(globalSearchTypes, resumed.items.map { it.searchResultType })
        assertFalse(pager.hasMore)
    }
}
