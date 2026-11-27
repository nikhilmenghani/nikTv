package com.nikhil.niktv

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderSearchRecoveryTest {
    @Test fun temporaryNetworkFailureRetriesTheSameRequestWithBoundedBackoff() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val found = recoverProviderSearch(retryAfterSeconds = { null }, pause = { waits += it }) {
            if (++attempts < 3) throw IOException("Temporary outage")
            "found"
        }
        assertEquals("found", found)
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(3, attempts)
    }

    @Test fun providerOneHourPauseIsHonoredWithoutDroppingOrAdvancingThePage() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val pager = GlobalSearchPager()
        val batch = pager.next(search = { type, _, page ->
            recoverProviderSearch(retryAfterSeconds = { 3600L }, pause = { waits += it }) {
                if (++attempts == 1) throw IOException("Rate limited")
                PortalSearchPage(listOf(MediaItem(type.name, "Title", null, null)), page, false)
            }
        }, categories = { emptyList() })
        assertEquals(listOf(3_600_000L), waits)
        assertEquals(3, batch.items.size)
        assertEquals(0, batch.failures)
        assertFalse(pager.hasMore)
    }

    @Test fun cancellationWhileWaitingRetainsTheUnfinishedPageForResume() = runBlocking {
        val pager = GlobalSearchPager()
        try {
            pager.next(search = { _, _, _ ->
                recoverProviderSearch<PortalSearchPage>(retryAfterSeconds = { 3600L },
                    pause = { throw CancellationException("Query closed") }) { throw IOException("Rate limited") }
            }, categories = { emptyList() })
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
        val resumed = pager.next(search = { type, _, page ->
            PortalSearchPage(listOf(MediaItem(type.name, "Title", null, null)), page, false)
        }, categories = { emptyList() })
        assertEquals(globalSearchTypes, resumed.items.map { it.searchResultType })
        assertFalse(pager.hasMore)
    }

    @Test fun persistentNetworkFailureStopsAfterThreeAttemptsWithoutPretendingSuccess() = runBlocking {
        var attempts = 0
        try {
            recoverProviderSearch<Unit>(retryAfterSeconds = { null }, pause = {}) {
                attempts++; throw IOException("Offline")
            }
            fail("Must remain a failed page")
        } catch (_: IOException) { assertEquals(3, attempts) }
    }

    @Test fun nonTransientFailureIsNotRepeated() = runBlocking {
        var attempts = 0
        try {
            recoverProviderSearch<Unit>(retryAfterSeconds = { null }, pause = { error("Must not wait") }) {
                attempts++; throw IllegalArgumentException("Invalid response")
            }
            fail("Must remain a failed page")
        } catch (_: IllegalArgumentException) { assertEquals(1, attempts) }
    }

    @Test fun cooldownDoesNotShortenProviderRetryAfter() {
        assertEquals(3600L, providerCooldownSeconds(3600, 1))
        assertEquals(7200L, providerCooldownSeconds(7200, 5))
        assertEquals(30L, providerCooldownSeconds(null, 1))
        assertEquals(480L, providerCooldownSeconds(1, 5))
    }

    @Test fun waitLabelShowsTheActualRemainingTime() {
        assertEquals("1h 0m 0s", providerSearchWaitLabel(3600))
        assertEquals("59m 59s", providerSearchWaitLabel(3599))
        assertEquals("1m 0s", providerSearchWaitLabel(60))
        assertEquals("0s", providerSearchWaitLabel(-1))
    }
}
