package com.nikhil.niktv.ui

import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class VisibleLiveGuideSchedulerTest {
    @Test fun expiryWakeUsesFirstAiringProgrammeEndAndIgnoresStaleEntries() {
        val now = 1_000L
        fun channel(id: String, start: Long, end: Long) = MediaItem(
            id = id,
            title = id,
            logo = null,
            command = id,
            liveSchedule = listOf(LiveProgramme(id, start, end))
        )
        assertEquals(
            1_500L,
            nextVisibleProgrammeExpiry(
                listOf(
                    channel("expired", 100L, 900L),
                    channel("later", 900L, 2_000L),
                    channel("first", 900L, 1_500L)
                ),
                now
            )
        )
        assertNull(nextVisibleProgrammeExpiry(listOf(channel("expired", 100L, 900L)), now))
    }

    @Test fun touchSelectionIsPrioritizedOnReturnAndNewDpadFocusTakesPrecedence() {
        val visible = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), prioritizeVisibleLiveGuides(visible, null, "c"))
        assertEquals(listOf("c", "a", "b"), prioritizeVisibleLiveGuides(visible, "offscreen", "c"))
        assertEquals(listOf("b", "a", "c"), prioritizeVisibleLiveGuides(visible, "b", "c"))
        assertEquals(visible, prioritizeVisibleLiveGuides(visible, null, "offscreen"))
    }
    @Test fun focusedVisibleChannelPrecedesRowsAndPinnedDuplicatesAreSkipped() {
        assertEquals(listOf("c", "a", "b"), prioritizeVisibleLiveGuides(listOf("a", "b", "a", "c"), "c"))
        assertEquals(listOf("a", "b"), prioritizeVisibleLiveGuides(listOf("a", "b"), "offscreen"))
    }

    @Test fun focusReordersPendingWorkWithoutRestartingTheVisibleRequest() = runBlocking {
        val completeFirst = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val loaded = mutableListOf<String>()
        val scheduler = VisibleLiveGuideScheduler<String>(this,
            isDue = { it !in loaded },
            load = {
                loaded += it
                if (it == "a") completeFirst.await()
                if (loaded.size == 3) completed.complete(Unit)
            }, onFailure = { throw it }, spacingMillis = 0)
        scheduler.update(listOf("a", "b", "c"))
        yield()
        scheduler.update(listOf("c", "a", "b"))
        completeFirst.complete(Unit)
        completed.await()
        assertEquals(listOf("a", "c", "b"), loaded)
    }

    @Test fun busyToIdleStartsSkippedWorkWithoutScrolling() = runBlocking {
        val loaded = mutableListOf<String>()
        val scheduler = VisibleLiveGuideScheduler<String>(this, { it !in loaded },
            { loaded += it }, { throw it }, spacingMillis = 0)
        scheduler.update(listOf("a", "b"), paused = true)
        yield()
        assertTrue(loaded.isEmpty())
        scheduler.update(listOf("a", "b"), paused = false)
        yield()
        assertEquals(listOf("a", "b"), loaded)
    }

    @Test fun scrollingCancelsOffscreenWorkAndNeverLoadsItsOldQueue() = runBlocking {
        val loaded = mutableListOf<String>()
        var cancelled = false
        val scheduler = VisibleLiveGuideScheduler<String>(this, { it !in loaded }, {
            if (it == "a") {
                try { awaitCancellation() } finally { cancelled = true }
            }
            loaded += it
        }, { throw it }, spacingMillis = 0)
        scheduler.update(listOf("a", "b"))
        yield()
        scheduler.update(listOf("c", "d"))
        yield()
        assertTrue(cancelled)
        assertEquals(listOf("c", "d"), loaded)
    }

    @Test fun pausingCancelsRequestAndResumesItWhenIdle() = runBlocking {
        var attempts = 0
        var cancelled = false
        var done = false
        val scheduler = VisibleLiveGuideScheduler<String>(this, { !done }, {
            attempts++
            if (attempts == 1) {
                try { awaitCancellation() } finally { cancelled = true }
            }
            done = true
        }, { throw it }, spacingMillis = 0)
        scheduler.update(listOf("a"))
        yield()
        scheduler.pause()
        yield()
        assertTrue(cancelled)
        assertFalse(done)
        scheduler.update(listOf("a"))
        yield()
        assertTrue(done)
        assertEquals(2, attempts)
    }

    @Test fun clockTicksSkipFreshGuidesAndRetryExpiredOrFailedGuidesWhenDue() = runBlocking {
        var now = 0L
        var nextDue = 10L
        var attempts = 0
        var failures = 0
        val scheduler = VisibleLiveGuideScheduler<String>(this, { now >= nextDue }, {
            attempts++
            nextDue = now + 120L
            if (attempts == 1) error("Temporary provider failure")
        }, { failures++ }, spacingMillis = 0)
        scheduler.update(listOf("a"))
        yield()
        assertEquals(0, attempts)
        now = 10L
        scheduler.update(listOf("a"))
        yield()
        assertEquals(1, failures)
        now = 129L
        scheduler.update(listOf("a"))
        yield()
        assertEquals(1, attempts)
        now = 130L
        scheduler.update(listOf("a"))
        yield()
        assertEquals(2, attempts)
    }

    @Test fun leavingScreenCancelsPendingWork() = runBlocking {
        var cancelled = false
        val scheduler = VisibleLiveGuideScheduler<String>(this, { true }, {
            try { awaitCancellation() } finally { cancelled = true }
        }, { throw it }, spacingMillis = 0)
        scheduler.update(listOf("a"))
        yield()
        scheduler.update(emptyList())
        yield()
        assertTrue(cancelled)
    }
}
