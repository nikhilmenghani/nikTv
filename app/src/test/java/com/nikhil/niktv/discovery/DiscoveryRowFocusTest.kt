package com.nikhil.niktv.discovery

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryRowFocusTest {
    @Test fun offscreenSavedTileIsMaterializedBeforeFocus() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(focusDiscoveryRowEntry("tile-17", (0..29).map { "tile-$it" }, null, false,
            isFullyVisible = { it < 8 }, reveal = { events += "reveal:$it" },
            request = { events += "focus:$it"; true }))
        assertEquals(listOf("reveal:17", "focus:tile-17"), events)
    }

    @Test fun returningToAVisibleSavedTileDoesNotResetHorizontalPosition() = runBlocking {
        var requested: String? = null
        assertTrue(focusDiscoveryRowEntry("b", listOf("a", "b", "c"), null, false,
            isFullyVisible = { it == 1 }, reveal = { fail("Visible selection must retain its offset") },
            request = { requested = it; true }))
        assertEquals("b", requested)
    }

    @Test fun removedSavedTileFallsBackToTheFirstCurrentTileAndRevealsIt() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(focusDiscoveryRowEntry("removed", listOf("new-a", "new-b"), null, false,
            isFullyVisible = { false }, reveal = { events += "reveal:$it" },
            request = { events += "focus:$it"; true }))
        assertEquals(listOf("reveal:0", "focus:new-a"), events)
    }

    @Test fun providerSearchHeaderDoesNotScrollToANonexistentPoster() = runBlocking {
        assertTrue(focusDiscoveryRowEntry("all", listOf("a"), "all", false,
            isFullyVisible = { fail("Header has no lazy item index"); false },
            reveal = { fail("Header has no lazy item index") }, request = { it == "all" }))
    }

    @Test fun appendedViewAllTileCanBeMaterialized() = runBlocking {
        var revealed = -1
        assertTrue(focusDiscoveryRowEntry("all", listOf("a", "b"), "all", true,
            isFullyVisible = { false }, reveal = { revealed = it }, request = { it == "all" }))
        assertEquals(2, revealed)
    }

    @Test fun emptyUnloadedRowDoesNotClaimFocus() = runBlocking {
        assertFalse(focusDiscoveryRowEntry(null, emptyList(), null, false,
            isFullyVisible = { true }, reveal = { fail("Empty row cannot scroll") },
            request = { fail("Empty row cannot receive focus"); true }))
    }

    @Test fun canceledRevealCannotStealFocusFromANewerVerticalRequest() = runBlocking {
        val revealing = CompletableDeferred<Unit>()
        var requested = false
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            focusDiscoveryRowEntry("a", listOf("a"), null, false,
                isFullyVisible = { false }, reveal = { revealing.complete(Unit); awaitCancellation() },
                request = { requested = true; true })
        }
        revealing.await()
        old.cancelAndJoin()
        assertFalse(requested)
    }

    @Test fun failedFocusRequestRemainsRetryable() = runBlocking {
        assertFalse(focusDiscoveryRowEntry(null, listOf("a"), null, false,
            isFullyVisible = { true }, reveal = {}, request = { false }))
    }

    @Test fun horizontalRevealRetainsBothEdgeGutters() {
        assertEquals(0f, discoveryHorizontalFocusScrollDistance(24f, 105f, 960f, 24f), 0f)
        assertEquals(-24f, discoveryHorizontalFocusScrollDistance(0f, 105f, 960f, 24f), 0f)
        assertEquals(9f, discoveryHorizontalFocusScrollDistance(840f, 105f, 960f, 24f), 0f)
    }

    @Test fun oversizedTileDoesNotOscillateTryingToPreserveImpossibleGutters() {
        assertEquals(0f, discoveryHorizontalFocusScrollDistance(-10f, 1000f, 960f, 24f), 0f)
        assertEquals(-5f, discoveryHorizontalFocusScrollDistance(0f, 950f, 960f, 24f), 0f)
    }
}
