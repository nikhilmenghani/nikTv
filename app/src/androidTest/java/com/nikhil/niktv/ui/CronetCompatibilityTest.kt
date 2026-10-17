package com.nikhil.niktv.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.sharedutils.cronet.CronetManager
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercise SmartTube's real native provider without YouTube, accounts or private streams. */
class CronetCompatibilityTest {
    @Test fun smartTubeNativeEngineLoadsAndFollowsAuthenticatedFixtureRedirect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = requireNotNull(CronetManager.getEngine(context))
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val received = Collections.synchronizedList(mutableListOf<List<String>>())
        val bytes = ByteArrayOutputStream()
        val payload = "niktv-native-network-fixture"
        val base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    received += headers
                    val response = if (index == 0)
                        "HTTP/1.1 302 Found\r\nLocation: $base/final\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload"
                    socket.getOutputStream().write(response.toByteArray())
                } }
            } catch (error: Exception) { failure.compareAndSet(null, error); done.countDown() }
        }
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) = request.followRedirect()
            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                if (info.httpStatusCode != 200) failure.set(AssertionError("HTTP ${info.httpStatusCode}"))
                request.read(ByteBuffer.allocateDirect(1024))
            }
            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.write(chunk)
                buffer.clear()
                request.read(buffer)
            }
            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) { done.countDown() }
            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                failure.set(error); done.countDown()
            }
            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) { done.countDown() }
        }
        val request = engine.newUrlRequestBuilder("$base/start", callback, executor)
            .addHeader("Authorization", "Bearer local-fixture-only")
            .build()
        try {
            assertTrue(engine.versionString, engine.versionString.contains("143.0.7445.0"))
            request.start()
            assertTrue("Native request timed out", done.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Native request failed", it) }
            assertEquals(payload, bytes.toString("UTF-8"))
            assertEquals(2, received.size)
            assertTrue(received.all { rows -> rows.any { it.equals("Authorization: Bearer local-fixture-only", true) } })
        } finally {
            request.cancel()
            server.close()
            executor.shutdownNow()
            engine.shutdown()
        }
    }
}
