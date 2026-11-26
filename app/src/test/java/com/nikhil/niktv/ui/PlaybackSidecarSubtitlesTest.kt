package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PlaybackSidecarSubtitle
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

class PlaybackSidecarSubtitlesTest {
    private val caption = PlaybackSidecarSubtitle("included:1", "English", "en",
        "http://127.0.0.1:12345/lease/subtitle/1.srt", "Series/Show.S01E01.en.srt")
    private fun client(body: () -> ResponseBody): OkHttpClient = OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body()).build()
    }.build()
    private suspend fun <T> directory(block: suspend (File) -> T): T {
        val dir = createTempDirectory("niktv-caption-test").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun fetchedCaptionRetainsUtf8AndMatchesOnlyItsPlaybackLease() = runBlocking {
        directory { dir ->
            val text = "1\n00:00:01,000 --> 00:00:03,000\nनमस्ते — café\n"
            val file = PlaybackSidecarSubtitleLoader(dir, client { text.toResponseBody("text/plain".toMediaType()) })
                .load(caption)
            assertEquals(text, file.readText())
            assertEquals("srt", file.extension)
            assertTrue(playbackSidecarMatches(file.absolutePath, caption))
            assertFalse(playbackSidecarMatches(file.absolutePath, caption.copy(id = "included:2")))
            assertFalse(playbackSidecarMatches(file.absolutePath, caption.copy(url = caption.url + "?lease=new")))
            assertEquals("English", playbackSubtitleDisplayName(file, listOf(caption)))
            assertFalse("Caption metadata must not log lease URLs", caption.toString().contains("127.0.0.1"))
        }
    }

    @Test fun unknownContentLengthCannotBypassTheTwoMiBLimit() = runBlocking {
        directory { dir ->
            val body = object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = Buffer().write(ByteArray(2 * 1024 * 1024 + 1) { 65 })
            }
            val failure = runCatching { PlaybackSidecarSubtitleLoader(dir, client { body }).load(caption) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("2 MiB"))
            assertTrue("Partial caption files must be removed", dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun unsupportedExtensionsAreRejectedBeforeAnyRequest() = runBlocking {
        directory { dir ->
            val requests = AtomicInteger()
            val http = client { requests.incrementAndGet(); "bad".toResponseBody() }
            assertTrue(runCatching { PlaybackSidecarSubtitleLoader(dir, http)
                .load(caption.copy(fileName = "caption.exe")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, requests.get())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun malformedTextCannotPretendToBeAnActiveCaption() = runBlocking {
        directory { dir ->
            val failure = runCatching { PlaybackSidecarSubtitleLoader(dir,
                client { "<html>Unavailable</html>".toResponseBody() }).load(caption) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("no readable"))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellingAnInFlightCaptionDeletesItsPartialFile() = runBlocking {
        directory { dir ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val source = object : Source {
                override fun timeout() = Timeout.NONE
                override fun close() { closed.countDown() }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    started.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Fixture timed out")
                    sink.writeUtf8("caption")
                    return 7
                }
            }
            val body = object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source.buffer()
            }
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                PlaybackSidecarSubtitleLoader(dir, client { body }).load(caption)
            }
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                request.cancelAndJoin()
            } finally { release.countDown() }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { while (dir.listFiles().orEmpty().isNotEmpty()) delay(10) }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }
}
