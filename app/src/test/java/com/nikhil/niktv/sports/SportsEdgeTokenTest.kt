package com.nikhil.niktv.sports

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SportsEdgeTokenTest {
    @Test fun refreshesAtHalfLifeAndAllowsOnlyStillValidFallback() {
        var clock = 1_000L
        var calls = 0
        var failing = false
        val state = SportsEdgeTokenState({ calls++; if (failing) throw IOException("offline"); SportsEdgeToken("token$calls", 120) }, { clock })
        assertEquals("token1", state.token())
        clock = 60_999; assertEquals("token1", state.token()); assertEquals(1, calls)
        clock = 61_000; assertEquals("token2", state.token())
        failing = true
        clock = 121_000; assertEquals("token2", state.token())
        clock = 178_999; assertEquals("token2", state.token())
        clock = 179_000
        assertThrows(IOException::class.java) { state.token() }
        failing = false; assertEquals("token6", state.token())
    }

    @Test fun rejectedTokenIsRenewedWithoutInvalidatingANewerConcurrentToken() {
        var clock = 0L
        var calls = 0
        val state = SportsEdgeTokenState({ SportsEdgeToken("token${++calls}", 1) }, { clock })
        assertEquals("token1", state.token())
        clock = 9_999; assertEquals("token1", state.token())
        clock = 10_000; assertEquals("token2", state.token()) // minimum TTL is 20s
        state.invalidate("token1"); assertEquals("token2", state.token())
        state.invalidate("token2"); assertEquals("token3", state.token())
    }

    @Test fun edgePageProducesManifestApiAndOriginWithoutEvaluatingJavascript() {
        val page = """<script>const EDGE='https://edge.example.test/'; const CHANNEL='match 1'; fetch('/api/playlist-token');</script>"""
        val edge = sportsEdgePlayer(page, "https://player.example.test/watch/1")!!
        assertEquals("https://edge.example.test/match%201/index.m3u8", edge.manifest)
        assertEquals("https://edge.example.test/api/playlist-token?slug=match%201", edge.tokenApi)
        assertEquals("https://player.example.test", edge.origin)
        assertNull(sportsEdgePlayer(page.replace("https://edge.example.test/", "https://user:pass@edge.example.test/"), "https://player.example.test/"))
        assertNull(sportsEdgePlayer(page + "<script>const SRC='https://unrelated.example.test/master.m3u8';</script>", "https://player.example.test/"))
    }
}
