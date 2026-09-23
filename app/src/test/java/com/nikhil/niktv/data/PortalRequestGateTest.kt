package com.nikhil.niktv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortalRequestGateTest {
    @Test fun waitingGuideLeavesCapacityAndLockAvailableForPlayback() = runBlocking {
        val resume = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val gate = PortalRequestGate(now = { 0L }, pause = { waits += it; resume.await() })
        repeat(12) { gate.run(background = true, minimumSpacingMillis = 0L) {} }
        var guideStarted = false
        val guide = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(background = true, minimumSpacingMillis = 0L) { guideStarted = true }
        }
        assertEquals(listOf(60_000L), waits)
        var played = false
        gate.run(background = false, minimumSpacingMillis = 0L) { played = true }
        assertTrue(played)
        assertFalse(guideStarted)
        guide.cancelAndJoin()
    }

    @Test fun totalTrafficStillCannotExceedTwentyRequestsPerMinute() = runBlocking {
        var now = 0L
        val resume = CompletableDeferred<Unit>()
        val gate = PortalRequestGate(now = { now }, pause = { resume.await() })
        repeat(20) { gate.run(background = false, minimumSpacingMillis = 0L) {} }
        var started = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(background = false, minimumSpacingMillis = 0L) { started = true }
        }
        assertFalse(started)
        now = 60_000L
        resume.complete(Unit)
        pending.join()
        assertTrue(started)
    }

    @Test fun guideSpacingDoesNotForcePlaybackToWaitAFullSecond() = runBlocking {
        var now = 0L
        val resume = CompletableDeferred<Unit>()
        val gate = PortalRequestGate(now = { now }, pause = { resume.await() })
        gate.run(background = false, minimumSpacingMillis = 0L) {}
        val guide = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(background = true, minimumSpacingMillis = 1_000L) { fail("Guide should still wait") }
        }
        now = 150L
        var played = false
        gate.run(background = false, minimumSpacingMillis = 150L) { played = true }
        assertTrue(played)
        guide.cancelAndJoin()
    }
}
