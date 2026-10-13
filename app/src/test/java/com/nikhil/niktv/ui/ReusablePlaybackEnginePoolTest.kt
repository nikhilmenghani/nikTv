package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class ReusablePlaybackEnginePoolTest {
    private var now = 0L
    private val destroyed = mutableListOf<Any>()
    private val pool = ReusablePlaybackEnginePool<String, Any>(
        create = { Any() }, destroy = destroyed::add, clock = { now }, idleMillis = 100L)

    @Test fun reusesCompatibleEngineWithoutDestroyingAnActiveStream() {
        val old = pool.acquire("hls")
        val next = pool.acquire("hls")
        assertSame(old, next)
        pool.release("hls", old)
        now = 500L
        pool.trimIdle()
        assertTrue(destroyed.isEmpty())
        pool.release("hls", next)
        assertSame(next, pool.acquire("hls"))
    }

    @Test fun differentTimingPoliciesNeverShareAnEngine() {
        val hls = pool.acquire("hls")
        val ts = pool.acquire("ts")
        assertNotSame(hls, ts)
        pool.release("hls", hls)
        pool.release("ts", ts)
        assertEquals(listOf(hls), destroyed)
        assertSame(ts, pool.acquire("ts"))
    }

    @Test fun idleExpiryReleasesAnEngineAndNextPlaybackCreatesAFreshOne() {
        val first = pool.acquire("hls")
        pool.release("hls", first)
        now = 101L
        val second = pool.acquire("hls")
        assertNotSame(first, second)
        assertEquals(listOf(first), destroyed)
    }

    @Test fun retainsOnlyOneIdleEngineButDoesNotEvictOtherActivePolicies() {
        val hls = pool.acquire("hls")
        val ts = pool.acquire("ts")
        val movie = pool.acquire("movie")
        pool.release("hls", hls)
        pool.release("movie", movie)
        assertEquals(listOf(hls), destroyed)
        assertSame(ts, pool.acquire("ts"))
    }
}
