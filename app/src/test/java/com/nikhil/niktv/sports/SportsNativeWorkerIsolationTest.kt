package com.nikhil.niktv.sports

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SportsNativeWorkerIsolationTest {
    private fun verifyIndependent(sports: CoroutineScope, iptv: CoroutineScope) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val sportsJob = sports.launch { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue("Sports native operation starts", entered.await(2, TimeUnit.SECONDS))
            iptv.launch { completed.countDown() }
            assertTrue("Blocked Sports native work must not queue ahead of IPTV", completed.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); sportsJob.cancel() }
    }

    @Test fun sportsVlcCannotBlockIptvWorker() = verifyIndependent(
        com.nikhil.niktv.sports.player.NativePlaybackWorker.scope,
        com.nikhil.niktv.ui.NativePlaybackWorker.scope)

    @Test fun sportsMpvCannotBlockIptvWorker() = verifyIndependent(
        com.nikhil.niktv.sports.player.MpvNativePlaybackWorker.scope,
        com.nikhil.niktv.ui.MpvNativePlaybackWorker.scope)
}
