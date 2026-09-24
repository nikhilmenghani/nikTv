package com.nikhil.niktv.data

import com.nikhil.niktv.ui.LatestPlaybackRequest
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class CancellablePortalCallTest {
    private class PendingCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        var started = false
        override fun request() = Request.Builder().url("https://example.invalid/guide").build()
        override fun execute(): Response = error("Must use cancellable enqueue")
        override fun enqueue(responseCallback: Callback) { started = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = started
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall()
        fun respond() = callback.onResponse(this, Response.Builder().request(request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body("guide".toResponseBody()).build())
    }

    @Test fun cancelledSocketDoesNotHoldUpLatestPlaybackOrPublishLateResponse() = runBlocking {
        val call = PendingCall()
        val requests = LatestPlaybackRequest()
        val gate = PortalRequestGate()
        val played = mutableListOf<String>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run {
                gate.run(background = false, minimumSpacingMillis = 0L) {
                    call.readCancellable { it.body!!.string() }
                }
                played += "old"
            }
        }
        requests.run {
            gate.run(background = false, minimumSpacingMillis = 0L) { played += "new" }
        }
        assertTrue(call.cancelled)
        // The provider can finish after cancellation; it must not commit old playback.
        call.respond()
        old.join()
        assertEquals(listOf("new"), played)
    }

    @Test fun cancellationWhileReadingBodyCancelsTheCall() = runBlocking {
        val call = PendingCall()
        val readStarted = java.util.concurrent.CountDownLatch(1)
        val releaseRead = java.util.concurrent.CountDownLatch(1)
        var committed = false
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            call.readCancellable {
                readStarted.countDown()
                check(releaseRead.await(3, java.util.concurrent.TimeUnit.SECONDS))
                "done"
            }
            committed = true
        }
        val response = Thread { call.respond() }.apply { isDaemon = true; start() }
        try {
            assertTrue(readStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            load.cancelAndJoin()
            assertTrue(call.cancelled)
        } finally {
            releaseRead.countDown()
            response.join(3_000)
        }
        assertFalse(committed)
    }

    @Test fun networkFailureStillReachesCaller() = runBlocking {
        val call = PendingCall()
        var error: Throwable? = null
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            try { call.readCancellable { it.code } } catch (e: IOException) { error = e }
        }
        call.callback.onFailure(call, IOException("Connection failed"))
        load.join()
        assertEquals("Connection failed", error?.message)
    }
}
