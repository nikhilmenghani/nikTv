package com.nikhil.niktv.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryTileArtworkLoaderTest {
    @Test fun stalledProviderRequestsDoNotBlockPublicCovers() = runTest {
        val loader = DiscoveryTileArtworkLoader()
        val blocked = List(2) { async { loader.load("https://provider.invalid/$it.jpg") { awaitCancellation() } } }
        runCurrent()
        val cover = async { loader.load("https://image.tmdb.org/t/p/w342/cover.jpg") { "ready" } }
        runCurrent()
        assertTrue(cover.isCompleted)
        assertEquals("ready", cover.await())
        assertTrue(blocked.none { it.isCompleted })
        blocked.forEach { it.cancelAndJoin() }
    }

    @Test fun providerTimeoutReleasesSlotsForLaterImages() = runTest {
        val loader = DiscoveryTileArtworkLoader(providerTimeoutMillis = 100)
        val blocked = List(2) { async { loader.load("https://provider.invalid/$it.jpg") { awaitCancellation() } } }
        runCurrent()
        val later = async { loader.load("https://provider.invalid/healthy.jpg") { "ready" } }
        runCurrent()
        assertFalse(later.isCompleted)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("ready", later.await())
        blocked.forEach { assertNull(it.await()) }
    }

    @Test fun recycledTileCancellationReleasesItsSlotWithoutWaitingForTimeout() = runTest {
        val loader = DiscoveryTileArtworkLoader()
        val blocked = List(2) { async { loader.load("https://provider.invalid/$it.jpg") { awaitCancellation() } } }
        runCurrent()
        blocked.first().cancelAndJoin()
        assertEquals("ready", loader.load("https://provider.invalid/next.jpg") { "ready" })
        assertTrue(blocked.first().isCancelled)
        assertEquals(0L, currentTime)
        blocked.last().cancelAndJoin()
    }
}
