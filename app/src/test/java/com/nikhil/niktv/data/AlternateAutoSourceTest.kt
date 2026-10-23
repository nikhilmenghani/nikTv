package com.nikhil.niktv.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AlternateAutoSourceTest {
    @Test fun autoFailureReportsEveryCheckedGroupRatherThanOnlyMovy() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = { request ->
            val body = when {
                request.url.contains("zcdn.bluevelvet.space") -> """{"ready":false}"""
                request.url.contains("scrapper.rivestream.app") -> """{"data":null}"""
                request.url.contains("medmedia03.wiki") -> {
                    val provider = if (request.url.contains("onetouchtv")) "onetouchtv" else "vaplayer"
                    """{"provider":"$provider","streams":[]}"""
                }
                else -> "Unavailable"
            }
            AlternateSourceHttpResponse(request.url, if (body == "Unavailable") 403 else 200, body)
        }, movyLookup = { throw AlternateSourceException("Private https://source.invalid/?token=do-not-expose", httpCode = 500) })
        try { resolver.resolve(target); fail("Expected combined Auto failure") }
        catch (failure: AlternateSourceException) {
            val message = failure.message.orEmpty()
            assertTrue(message.contains("6 source groups"))
            assertTrue(message.contains("Movy: HTTP 500"))
            assertTrue(message.contains("Unity / fallback: HTTP 403"))
            for (name in listOf("Athena", "Rive Primevids", "Rive Citadel", "OnlyFlix"))
                assertTrue(message.contains("$name: No results"))
            assertFalse(message.contains("do-not-expose"))
            assertFalse(message.contains("https://"))
        }
    }
    private val target = AlternateSourceTarget("The Matrix", false, tmdbId = 603)
    private val stream = AlternateStream("movy:test", "1080p · Movy Miami", "https://media.example.test/movie.m3u8", height = 1080)
    @Test fun autoIncludesMovyEvenWhenOtherSourceRejectsTheRequestAndCachesTheResult() = runBlocking {
        var calls = 0
        val resolver = AlternateSourceResolver(fetch = { AlternateSourceHttpResponse(it.url, 403, "Unavailable") },
            movyLookup = { assertEquals(AlternateSourceProvider.MOVY, it.provider); calls++; listOf(stream) })
        assertEquals(listOf(stream), resolver.resolve(target))
        assertEquals(listOf(stream), resolver.resolve(target))
        assertEquals(1, calls)
    }
    @Test fun cancelingAutoCancelsItsMovyChild() = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val resolver = AlternateSourceResolver(fetch = { AlternateSourceHttpResponse(it.url, 403, "Unavailable") },
            movyLookup = { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } })
        val work = launch { resolver.resolve(target) }
        started.await(); work.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }
}
