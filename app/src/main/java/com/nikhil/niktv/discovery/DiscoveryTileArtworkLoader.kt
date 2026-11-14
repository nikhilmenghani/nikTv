package com.nikhil.niktv.discovery

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Provider hosts must not stall already resolved public covers in the same rail. */
internal class DiscoveryTileArtworkLoader(private val providerTimeoutMillis: Long = 8_000L) {
    private val publicImages = Semaphore(2)
    private val providerImages = Semaphore(2)

    suspend fun <T> load(url: String, fetch: suspend () -> T): T? =
        if (url.startsWith("https://image.tmdb.org/")) publicImages.withPermit { fetch() }
        else providerImages.withPermit { withTimeoutOrNull(providerTimeoutMillis) { fetch() } }
}
