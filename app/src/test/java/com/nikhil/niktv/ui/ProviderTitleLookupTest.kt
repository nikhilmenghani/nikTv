package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProviderTitleLookupTest {
    private val wanted = MediaItem("original", "Old series (1960)", null, null)
    private fun rank(items: List<MediaItem>) = items.filter { it.id == wanted.id }

    @Test fun laterWildcardPageCanFindSeriesAfterRejectedRows() = runBlocking {
        val requests = mutableListOf<Int>()
        val result = lookupProviderTitle(emptyList(), listOf("Old series"), { emptyList() }, ::rank) { _, _, page ->
            requests += page
            if (page < 3) PortalSearchPage(emptyList(), page, true, listOf("rejected-$page"))
            else PortalSearchPage(listOf(wanted), page, false)
        }
        assertEquals(listOf(wanted), result)
        assertEquals(listOf(1, 2, 3), requests)
    }
    @Test fun categoryAliasesAndTheirLaterPagesAreIndependent() = runBlocking {
        val calls = mutableListOf<String>()
        val result = lookupProviderTitle(emptyList(), listOf("Full name", "Alias"), { listOf("*", "classic", "classic") }, ::rank) { query, category, page ->
            calls += "$query/$category/$page"
            when {
                category == "classic" && query == "Alias" && page == 2 -> PortalSearchPage(listOf(wanted), page, false)
                category == "classic" -> PortalSearchPage(emptyList(), page, page == 1, listOf("$query-$page"))
                else -> PortalSearchPage(emptyList(), page, false)
            }
        }
        assertEquals(listOf(wanted), result)
        assertTrue("Alias/classic/2" in calls)
        assertEquals(calls.distinct(), calls)
    }
    @Test fun repeatedRejectedPagesTerminateWithoutFalseMatches() = runBlocking {
        var requests = 0
        val result = lookupProviderTitle(emptyList(), listOf("Name"), { emptyList() }, ::rank) { _, _, page ->
            requests++
            PortalSearchPage(emptyList(), page, true, listOf("same"))
        }
        assertTrue(result.isEmpty())
        assertEquals(2, requests)
    }
    @Test fun cancellationAndProviderErrorsNeverBecomeNotFound() = runBlocking {
        for (error in listOf(CancellationException("Changed title"), java.io.IOException("Offline"))) {
            try {
                lookupProviderTitle(emptyList(), listOf("Name"), { error("Must not continue") }, ::rank) { _, _, _ -> throw error }
                fail("Expected original error")
            } catch (actual: Exception) { assertSame(error, actual) }
        }
    }
}
