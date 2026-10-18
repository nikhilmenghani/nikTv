package com.nikhil.niktv.ui

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native loopback failure followed by silent local playback. No UI or provider session required. */
class VlcFailedEngineRecoveryTest {
    @Test fun refusedConnectionRetiresTheCoreAndRetryPlaysWithoutRestartingTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val options = listOf("--no-video", "--no-osd")
        fun create() = runBlocking {
            withContext(NativePlaybackWorker.dispatcher) { VlcPlaybackSession.create(context, options) }
        }
        fun open(session: VlcPlaybackSession, uri: Uri) = runBlocking {
            withContext(NativePlaybackWorker.dispatcher) {
                val media = Media(session.lib, uri)
                try { session.player.media = media } finally { media.release() }
                session.player.volume = 0
                session.player.play()
            }
        }
        val failed = create()
        val failure = CountDownLatch(1)
        instrumentation.runOnMainSync {
            failed.player.setEventListener {
                if (it.type == MediaPlayer.Event.EncounteredError) {
                    failed.retireAfterFailure()
                    failure.countDown()
                }
            }
        }
        try {
            val closedPort = ServerSocket(0).use { it.localPort }
            open(failed, Uri.parse("http://127.0.0.1:$closedPort/refused.ts"))
            assertTrue("Loopback connection must actually fail", failure.await(15, TimeUnit.SECONDS))
            val retry = create()
            assertNotSame("Retry must not borrow the failed native core", failed.lib, retry.lib)
            runBlocking { failed.close() }
            val fixture = File(context.cacheDir, "silent-vlc-retry.wav")
            // Two seconds of 8 kHz, mono, 16-bit silence; never alter device volume.
            val pcmBytes = 32_000
            val bytes = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes)
            fixture.writeBytes(bytes.array())
            try {
                val playing = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    retry.player.setEventListener { if (it.type == MediaPlayer.Event.Playing) playing.countDown() }
                }
                open(retry, Uri.fromFile(fixture))
                assertTrue("Replacement must play without restarting the app", playing.await(15, TimeUnit.SECONDS))
            } finally {
                runBlocking { retry.close() }
                fixture.delete()
            }
        } finally { runBlocking { failed.close() } }
    }
}
