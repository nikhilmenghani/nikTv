package com.nikhil.niktv.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class MovyFallbackTest {
    private val target = AlternateSourceTarget("The Matrix", false, tmdbId = 603, provider = AlternateSourceProvider.MOVY)

    @Test fun failedNativeRequestOffersOnlyAnExplicitEmbeddedRouteWithoutCallingOtherSources() = runBlocking {
        val requests = mutableListOf<String>()
        val resolver = AlternateSourceResolver(fetch = {
            requests += it.url
            AlternateSourceHttpResponse(it.url, 403, "Unavailable")
        })
        val choices = resolver.resolve(target)
        assertEquals(listOf(AlternatePlaybackRoute.VIDY_EMBEDDED), choices.map { it.route })
        assertEquals("https://vidy.st/movie/603", choices.single().url)
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("https://api.wecollege.net/seed?"))
    }

    @Test fun overallNativeTimeoutOffersFallbackButConsumerCancellationDoesNot() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = { delay(60_000); error("Unexpected completion") }, timeoutMillis = 20)
        assertEquals(AlternatePlaybackRoute.VIDY_EMBEDDED, resolver.resolve(target).single().route)
        val started = CompletableDeferred<Unit>()
        val pendingResolver = AlternateSourceResolver(fetch = { started.complete(Unit); delay(60_000); error("Unexpected completion") })
        val pending = async { pendingResolver.resolve(target) }
        started.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    @Test fun unknownOrMismatchedIdentityCannotOpenAnEmbeddedTitle() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = { error("No request should be needed") })
        assertTrue(resolver.resolve(target.copy(tmdbId = null)).isEmpty())
        val mismatch = AlternateSourceResolver(identityLookup = { it.copy(series = true) }, fetch = { error("Unexpected fetch") })
        try { mismatch.resolve(target.copy(tmdbId = null)); fail("Mismatched identity accepted") }
        catch (error: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, error.kind) }
    }
}
