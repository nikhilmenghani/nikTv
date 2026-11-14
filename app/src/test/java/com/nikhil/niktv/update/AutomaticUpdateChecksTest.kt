package com.nikhil.niktv.update

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticUpdateChecksTest {
    private val update = UpdateInfo("99.0.0", "https://example.invalid/update.apk")

    @Test fun startupFailureRetriesAndPublishesLaterRelease() = runTest {
        var attempts = 0
        var finished = 0
        var found: UpdateInfo? = null
        val job = launch {
            automaticUpdateChecks({ if (++attempts == 1) error("Offline at startup") else update },
                { found = it }, { finished++ })
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, finished)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(update, found)
        assertEquals(2, finished)
        assertTrue(job.isCompleted)
    }

    @Test fun releasePublishedAfterStartupIsFoundWithoutManualCheck() = runTest {
        var attempts = 0
        var found: UpdateInfo? = null
        launch { automaticUpdateChecks({ if (++attempts == 1) null else update }, { found = it }) }
        runCurrent()
        assertNull(found)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(2, attempts)
        assertEquals(update, found)
    }

    @Test fun leavingForegroundOrStartingPlaybackCancelsChecks() = runTest {
        var attempts = 0
        val job = launch { automaticUpdateChecks({ attempts++; null }, { fail("No update expected") }) }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(60 * 60_000L)
        assertEquals(1, attempts)
    }
}
