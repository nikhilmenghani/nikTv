package com.nikhil.niktv.discovery

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryHttpTest {
    private class Fake(private val delegate: Call = OkHttpClient().newCall(
        Request.Builder().url("https://example.invalid/movie").build()
    )) : Call by delegate, Call.Factory {
        val req = Request.Builder().url("https://example.invalid/movie").build()
        lateinit var callback: Callback
        var canceled = false
        override fun newCall(request: Request): Call = this
        override fun request() = req
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("Synchronous execute must never be used")
        override fun cancel() { canceled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = Fake()
    }
    private class Body(value: String) : ResponseBody() {
        var closed = false
        private val bytes = value.toByteArray()
        private val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = bytes.size.toLong()
        override fun source() = input
    }
    private fun response(fake: Fake, body: Body, code: Int = 200) = Response.Builder().request(fake.req)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()
    @Test fun successfulResponseIsDecodedAndClosed() = runBlocking {
        val fake = Fake(); val body = Body("{\"title\":\"Fixture\"}")
        val result = async { discoveryHttp(fake, fake.req, Json) }
        yield(); fake.callback.onResponse(fake, response(fake, body))
        assertEquals("Fixture", result.await()["title"]?.jsonPrimitive?.content)
        assertTrue(body.closed); assertFalse(fake.canceled)
    }
    @Test fun canceledRequestCancelsTheCallAndClosesLateResponses() = runBlocking {
        val fake = Fake(); val body = Body("{}")
        val result = async { discoveryHttp(fake, fake.req, Json) }
        yield(); result.cancelAndJoin()
        assertTrue(fake.canceled)
        fake.callback.onResponse(fake, response(fake, body))
        assertTrue(body.closed); assertTrue(result.isCancelled)
    }
    @Test fun httpAndOversizedFailuresCloseResponses() = runBlocking {
        supervisorScope {
            for ((code, content) in listOf(429 to "{}", 200 to "x".repeat(100))) {
                val fake = Fake(); val body = Body(content)
                val result = async { discoveryHttp(fake, fake.req, Json, 32) }
                yield(); fake.callback.onResponse(fake, response(fake, body, code))
                try { result.await(); fail("Response must fail") } catch (_: IllegalStateException) { }
                assertTrue(body.closed)
            }
        }
    }
    @Test fun transportFailureRemainsRetryableRatherThanReturningEmptyMetadata() = runBlocking {
        supervisorScope {
            val fake = Fake()
            val result = async { discoveryHttp(fake, fake.req, Json) }
            yield(); fake.callback.onFailure(fake, IOException("fixture transport failure"))
            try { result.await(); fail("Transport failure must propagate") } catch (_: IOException) { }
        }
    }
}
