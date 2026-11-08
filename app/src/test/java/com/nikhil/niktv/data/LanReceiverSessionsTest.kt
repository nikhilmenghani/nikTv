package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LanReceiverSessionsTest {
    @Test fun codeRotatesAfterFiveMinutesWithoutInvalidatingPairedDevicesOrPlaybackOwner() {
        var time = 0L
        val sessions = LanReceiverSessions { time }
        val old = sessions.snapshot().pin
        val token = requireNotNull(sessions.pair(old, "Samsung", "").credential)
        val permit = requireNotNull(sessions.begin(token, "play").first)
        sessions.finish(permit, true)
        time = TimeUnit.MINUTES.toNanos(5)
        val refreshed = sessions.snapshot()
        assertNotEquals(old, refreshed.pin)
        assertEquals(300, refreshed.secondsRemaining)
        assertTrue(sessions.authenticated(token))
        assertTrue(refreshed.devices.single().ownsPlayback)
        assertFalse(sessions.pair(old, "POCO", "").ok)
        assertTrue(sessions.pair(refreshed.pin, "POCO", "").ok)
    }
    @Test fun failedAttemptsResetOnRotationButSuccessfulPairsDoNotConsumeAttempts() {
        var time = 0L
        val sessions = LanReceiverSessions { time }
        val code = sessions.snapshot().pin
        repeat(6) { assertTrue(sessions.pair(code, "Phone", java.util.UUID.randomUUID().toString()).ok) }
        repeat(5) { assertFalse(sessions.pair("wrong", "Phone", "").ok) }
        assertFalse(sessions.pair(code, "Phone", "").ok)
        time += TimeUnit.MINUTES.toNanos(5)
        assertTrue(sessions.pair(sessions.snapshot().pin, "Phone", "").ok)
    }
    @Test fun secondDeviceCannotReplacePauseOrStopOwnersMovie() {
        val sessions = LanReceiverSessions()
        val first = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", "").credential)
        val other = requireNotNull(sessions.pair(sessions.snapshot().pin, "POCO", "").credential)
        sessions.finish(requireNotNull(sessions.begin(first, "play").first), true)
        for (action in listOf("play", "pause", "resume", "stop")) {
            val rejected = sessions.begin(other, action)
            assertNull(rejected.first); assertTrue(requireNotNull(rejected.second).contains("Samsung"))
        }
        sessions.finish(requireNotNull(sessions.begin(first, "stop").first), true)
        assertNotNull(sessions.begin(other, "play").first)
    }
    @Test fun lookupInFlightReservesOwnershipAndIdlePollCannotReleaseIt() {
        val sessions = LanReceiverSessions()
        val first = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", "").credential)
        val other = requireNotNull(sessions.pair(sessions.snapshot().pin, "POCO", "").credential)
        val permit = requireNotNull(sessions.begin(first, "play").first)
        sessions.observePlayback(false)
        assertNull(sessions.begin(other, "play").first)
        sessions.finish(permit, false)
        assertNotNull(sessions.begin(other, "play").first)
    }
    @Test fun removalOfOwnerWaitsForStopAndRevokesOnlyThatDevice() {
        val sessions = LanReceiverSessions()
        val first = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", "").credential)
        val other = requireNotNull(sessions.pair(sessions.snapshot().pin, "POCO", "").credential)
        sessions.finish(requireNotNull(sessions.begin(first, "play").first), true)
        val removal = requireNotNull(sessions.beginRemoval(sessions.snapshot().devices.first().id).first)
        assertNull(sessions.begin(other, "play").first)
        sessions.finishRemoval(removal, true)
        assertFalse(sessions.authenticated(first)); assertTrue(sessions.authenticated(other))
        assertNotNull(sessions.begin(other, "play").first)
    }
    @Test fun failedReceiverStopRetainsOwnerAndCanBeRetried() {
        val sessions = LanReceiverSessions()
        val token = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", "").credential)
        sessions.finish(requireNotNull(sessions.begin(token, "play").first), true)
        val id = sessions.snapshot().devices.single().id
        sessions.finishRemoval(requireNotNull(sessions.beginRemoval(id).first), false)
        assertTrue(sessions.authenticated(token)); assertTrue(sessions.snapshot().devices.single().ownsPlayback)
    }
    @Test fun sameInstallationReplacesItsTokenWithoutDuplicatingDevice() {
        val sessions = LanReceiverSessions()
        val installation = java.util.UUID.randomUUID().toString()
        val first = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", installation).credential)
        val second = requireNotNull(sessions.pair(sessions.snapshot().pin, "Samsung", installation).credential)
        assertEquals(1, sessions.snapshot().devices.size)
        assertFalse(sessions.authenticated(first)); assertTrue(sessions.authenticated(second))
    }
}
