package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real VLC producers reuse one SurfaceView; a canceled middle session cannot skip teardown. */
class VlcSurfaceHandoffDeviceTest {
    @Test fun replacementWaitsForOldProducerAndCanceledIntermediateBeforeBindingTheSameSurface() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        fun surfaceErrors(): Set<String> {
            val fd = ins.uiAutomation.executeShellCommand("logcat -d --pid=${Process.myPid()} -v threadtime -s libEGL:E VLC:E *:S")
            return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readLines() }
                .filter { "cannot create EGL window surface" in it || "already connected to another API" in it }.toSet()
        }
        val priorErrors = surfaceErrors()
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        lateinit var layout: VLCVideoLayout
        ins.runOnMainSync { layout = VLCVideoLayout(host); host.setContentView(layout,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        val sessions = mutableListOf<VlcPlaybackSession>()
        suspend fun create() = withContext(NativePlaybackWorker.dispatcher) {
            VlcPlaybackSession.create(context, vlcPlaybackOptions(false, false, false)).also(sessions::add)
        }
        suspend fun play(session: VlcPlaybackSession, url: String) {
            val output = CountDownLatch(1)
            ins.runOnMainSync {
                session.player.setEventListener { if (it.type == MediaPlayer.Event.Vout && it.voutCount > 0) output.countDown() }
                session.player.attachViews(layout, null, true, false)
            }
            withContext(NativePlaybackWorker.dispatcher) {
                val media = Media(session.lib, Uri.parse(url)).apply { setHWDecoderEnabled(true, false) }
                try { session.player.media = media } finally { media.release() }
                session.player.volume = 0; session.player.play()
            }
            assertTrue("The replacement produces native video", output.await(10, TimeUnit.SECONDS))
        }
        try { PlaybackEngineFixtureTest().FixtureServer().use { server ->
            val first = create()
            first.awaitPlaybackOwnership()
            play(first, "${server.base}/movie.mp4")
            val middle = create()
            val newest = create()
            val canceledMiddle = async(Dispatchers.Default) { middle.close() }
            withTimeout(10_000) { newest.awaitPlaybackOwnership() }
            assertTrue("Old decoder is fully released before replacement surface binding", first.player.isReleased)
            assertTrue("A canceled intermediate cannot bypass the old producer", middle.player.isReleased)
            canceledMiddle.await()
            play(newest, "${server.base}/movie.mp4")
            first.close() // A late disposer must be idempotent and must not detach the new producer.
            assertFalse(newest.player.isReleased)
            assertTrue("No native-window ownership collision", (surfaceErrors() - priorErrors).isEmpty())
        } } finally {
            sessions.asReversed().forEach { it.close() }
            ins.runOnMainSync { host.finish() }
        }
    }
}
