package com.nikhil.niktv.sports

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CricbuzzAuthenticationTest {
    private val now = 1_700_000_000_000L
    private val key = "0123456789abcdef".repeat(4)
    private val url = "https://api.cricbuzz.com/a/home/v1/index"

    @Test fun akHeaderMatchesIndependentFixtureAndRotatesWithExpiry() {
        val config = CricbuzzCredentials(key)
        assertEquals("exp=1700000300~acl=/*~hmac=afd108b81d8024ef8222823686d1d5752f89a4d0",
            CricbuzzAuthentication.header(url, config, now))
        assertNotEquals(CricbuzzAuthentication.header(url, config, now), CricbuzzAuthentication.header(url, config, now + 1000))
        assertTrue(CricbuzzAuthentication.header(url, config, now, 60).startsWith("exp=1700000360~"))
        assertFalse(config.toString().contains(key))
    }

    @Test fun cbModeSignsTheEncodedPathAndQueryWithItsOwnTimestampWindow() {
        val signed = CricbuzzAuthentication.header("$url?pt=fixture", CricbuzzCredentials("fixture-shared-secret", mode = "CB"), now)
        assertEquals("1699999400-vSOM8RhIutRV5QJzAL4+5ezCiNxaMNhVheOvIRLQSxw=", signed)
        assertNotEquals(signed, CricbuzzAuthentication.header(url, CricbuzzCredentials("fixture-shared-secret", mode = "CB"), now))
    }

    @Test fun signingCredentialsCannotBeSentToMediaOrLookalikeHosts() {
        for (target in listOf("https://cdn.example.test/a.m3u8", "https://api.cricbuzz.com.evil.test/", "http://api.cricbuzz.com/", "https://user:pass@api.cricbuzz.com/")) {
            try { CricbuzzAuthentication.header(target, CricbuzzCredentials(key), now); fail("Host must be rejected") }
            catch (_: SportsException) { }
        }
        assertFalse(CricbuzzCredentials("not-hex").configured)
        assertFalse(CricbuzzCredentials(key, appVersion = "6.19\r\ninjected").configured)
    }

    @Test fun directCatalogueSignsEveryRequestAndChecksTheServerRegion() = runBlocking {
        var clock = now
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val direct = CricbuzzDirectClient(CricketHighlightsTransport { target, headers ->
            calls += target to headers
            if (target.endsWith("geo-location")) """{"country":"CA"}""".toByteArray() else home()
        }, { CricbuzzCredentials(key) }, { clock })
        val first = direct.featured().single()
        assertEquals("42", first.id)
        assertEquals("https://cdn.example.test/full.m3u8?ks=fixture", first.fullStream().link)
        clock += 1000
        direct.featured()
        assertEquals(1, calls.count { it.first.endsWith("geo-location") })
        val signatures = calls.filter { it.first.endsWith("index") }.map { it.second["Authentication"] }
        assertNotEquals(signatures[0], signatures[1])
        assertTrue(calls.all { it.second["CB-Loc"] == "CA" && "Bearer" !in it.second.values.joinToString() })
    }

    @Test fun outsideCanadaIsRejectedBeforeLoadingTheCanadianFeed() = runBlocking {
        var calls = 0
        val direct = CricbuzzDirectClient(CricketHighlightsTransport { _, _ -> calls++; """{"country":"US"}""".toByteArray() },
            { CricbuzzCredentials(key) }, { now })
        try { direct.featured(); fail("Region must be respected") }
        catch (error: SportsException) { assertTrue(error.message.orEmpty().contains("Canadian")) }
        assertEquals(1, calls)
    }

    @Test fun trustedServerClockSkewIsCorrectedOnce() = runBlocking {
        val signatures = mutableListOf<String>()
        var calls = 0
        val direct = CricbuzzDirectClient(CricketHighlightsTransport { target, headers ->
            signatures += headers.getValue("Authentication")
            if (++calls == 1) throw CricketAccessDenied(now + 3600_000)
            if (target.endsWith("geo-location")) """{"country":"CA"}""".toByteArray() else home()
        }, { CricbuzzCredentials(key) }, { now })
        assertEquals(1, direct.featured().size)
        assertEquals(3, calls)
        assertTrue(signatures[1].startsWith("exp=1700003900~"))
    }

    @Test fun anOrdinaryAccessRejectionIsNotRetriedAndCancellationPropagates() = runBlocking {
        var calls = 0
        val rejected = CricbuzzDirectClient(CricketHighlightsTransport { _, _ -> calls++; throw CricketAccessDenied(now) },
            { CricbuzzCredentials(key) }, { now })
        try { rejected.featured(); fail("Must reject") } catch (_: CricketAccessDenied) { }
        assertEquals(1, calls)
        val cancelled = CricbuzzDirectClient(CricketHighlightsTransport { _, _ -> throw CancellationException("fixture") },
            { CricbuzzCredentials(key) }, { now })
        try { cancelled.featured(); fail("Must cancel") } catch (_: CancellationException) { }
    }

    @Test fun directPlaybackRefreshesHomeUrlsAndKeepsApiAuthenticationOffMediaRequests() = runBlocking {
        var homes = 0
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = CricketHighlightsTransport { target, headers ->
            calls += target to headers
            when {
                target.endsWith("geo-location") -> """{"country":"CA"}""".toByteArray()
                target.endsWith("index") -> home("fixture-${++homes}")
                target.startsWith("https://cdn.example.test/full.m3u8") ->
                    "#EXTM3U\n#EXTINF:1467.78,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                else -> error("Unexpected request in direct playback")
            }
        }
        val direct = CricbuzzDirectClient(transport, { CricbuzzCredentials(key) }, { now })
        val repo = CricketHighlightsRepository(transport, direct = direct)
        assertEquals("https://cdn.example.test/full.m3u8?ks=fixture-1", repo.fullStream("42").link)
        assertEquals("https://cdn.example.test/full.m3u8?ks=fixture-2", repo.fullStream("42").link)
        assertEquals(2, homes)
        assertTrue(calls.filter { it.first.startsWith("https://api.cricbuzz.com/") }
            .all { it.second.containsKey("Authentication") })
        assertTrue(calls.filter { it.first.startsWith("https://cdn.example.test/") }
            .all { "Authentication" !in it.second && "Authorization" !in it.second })
        assertTrue(calls.none { "/iam/" in it.first || "/detail/" in it.first })
    }

    @Test fun mediaServerRejectionIsPropagatedWithoutBypassingIt() = runBlocking {
        var mediaRequests = 0
        val transport = CricketHighlightsTransport { target, _ ->
            when {
                target.endsWith("geo-location") -> """{"country":"CA"}""".toByteArray()
                target.endsWith("index") -> home()
                else -> { mediaRequests++; throw CricketAccessDenied(now) }
            }
        }
        val direct = CricbuzzDirectClient(transport, { CricbuzzCredentials(key) }, { now })
        try {
            CricketHighlightsRepository(transport, direct = direct).fullStream("42")
            fail("A media-server rejection must propagate")
        } catch (_: CricketAccessDenied) { }
        assertEquals(1, mediaRequests)
    }

    private fun variable(value: Long): ByteArray {
        var number = value
        return ByteArrayOutputStream().apply {
            while (number >= 128) { write(((number and 127) or 128).toInt()); number = number ushr 7 }; write(number.toInt())
        }.toByteArray()
    }
    private fun blob(tag: Int, bytes: ByteArray) = variable((tag.toLong() shl 3) or 2) + variable(bytes.size.toLong()) + bytes
    private fun text(tag: Int, value: String) = blob(tag, value.toByteArray())
    private fun home(session: String = "fixture"): ByteArray {
        val item = text(1, "42") + text(2, "Fixture Canadian highlight") + text(10, "24:27") +
            text(14, "https://cdn.example.test/promo.m3u8") + text(17, "https://cdn.example.test/full.m3u8?ks=$session") + text(18, "Highlights") + variable(27L shl 3) + variable(1)
        return blob(3, blob(1, blob(1, item)))
    }
}
