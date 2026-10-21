package com.nikhil.niktv.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackWorkerTest {
    @Test fun blockedMpvCleanupCannotBlockCompatibleEnginePreparation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = MpvNativePlaybackWorker.scope.launch {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val prepared = runBlocking {
                withTimeout(2_000L) { withContext(NativePlaybackWorker.dispatcher) { "compatible-player-ready" } }
            }
            assertEquals("compatible-player-ready", prepared)
        } finally {
            release.countDown()
            runBlocking { withTimeout(2_000L) { cleanup.join() } }
        }
    }
}
