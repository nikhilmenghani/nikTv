package com.nikhil.niktv

import com.nikhil.niktv.model.cacheFirstSearch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CacheFirstSearchTest {
    @Test fun cacheMissFetchesAndMakesNextSearchLocal() = runBlocking {
        var saved = emptyList<String>()
        var requests = 0
        val displayed = mutableListOf<List<String>>()
        repeat(2) {
            cacheFirstSearch(false, { saved }, { displayed += it }) {
                requests++
                saved = listOf("New provider title")
                displayed += saved
            }
        }
        assertEquals(1, requests)
        assertEquals(listOf(emptyList(), saved, saved), displayed)
    }

    @Test fun explicitProviderSearchStillRunsWithCachedMatches() = runBlocking {
        val events = mutableListOf<String>()
        cacheFirstSearch(true, { listOf("Cached") }, { events += it.single() }) { existing ->
            assertEquals(listOf("Cached"), existing)
            events += "Provider"
        }
        assertEquals(listOf("Cached", "Provider"), events)
    }

    @Test fun changingQueryWhileCacheLoadsDoesNotStartOldProviderSearch() = runBlocking {
        val cacheStarted = CompletableDeferred<Unit>()
        var published = false
        var requested = false
        val oldSearch = launch {
            cacheFirstSearch(false, cached = {
                cacheStarted.complete(Unit)
                awaitCancellation()
            }, publish = { _: List<String> -> published = true }, provider = { requested = true })
        }
        cacheStarted.await()
        oldSearch.cancelAndJoin()
        assertFalse(published)
        assertFalse(requested)
    }

    @Test fun providerFailureKeepsAlreadyPublishedCacheAndCanBeRetried() = runBlocking {
        var displayed = emptyList<String>()
        try {
            cacheFirstSearch(true, { listOf("Cached") }, { displayed = it }) {
                throw java.io.IOException("Offline")
            }
            fail("Expected provider failure")
        } catch (_: java.io.IOException) {
            assertEquals(listOf("Cached"), displayed)
        }
        cacheFirstSearch(true, { displayed }, { displayed = it }) { displayed = it + "Fresh" }
        assertEquals(listOf("Cached", "Fresh"), displayed)
    }
}
