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

    @Test fun failedEngineIsReplacedBeforeItsLastLeaseHasClosed() {
        val failed = pool.acquire("live")
        val otherLease = pool.acquire("live")
        pool.invalidate("live", failed)
        val replacement = pool.acquire("live")
        assertNotSame(failed, replacement)
        assertTrue(destroyed.isEmpty())
        pool.release("live", failed)
        assertTrue(destroyed.isEmpty())
        pool.release("live", otherLease)
        assertEquals(listOf(failed), destroyed)
        assertSame(replacement, pool.acquire("live"))
    }

    @Test fun lateFailureFromOldEngineCannotRetireItsReplacement() {
        val failed = pool.acquire("live")
        pool.invalidate("live", failed)
        val replacement = pool.acquire("live")
        pool.invalidate("live", failed)
        pool.release("live", failed)
        assertSame(replacement, pool.acquire("live"))
        assertEquals(listOf(failed), destroyed)
    }

    @Test fun idleFailedEngineIsDestroyedOnceAndHealthyOtherConfigurationSurvives() {
        val failed = pool.acquire("live")
        val movie = pool.acquire("movie")
        pool.release("live", failed)
        pool.invalidate("live", failed)
        pool.invalidate("live", failed)
        assertEquals(listOf(failed), destroyed)
        assertSame(movie, pool.acquire("movie"))
        assertNotSame(failed, pool.acquire("live"))
    }
}
