package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real native MPV checks using a local fixture and an isolated app package. */
class MpvPlaybackIntegrationTest {
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun firstFrameEventSurvivesPendingNativeCommand() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val workerEntered = CountDownLatch(1)
        val workerRelease = CountDownLatch(1)
        val frames = AtomicInteger()
        var player: MpvPlayerAdapter? = null
        val blocker = MpvNativePlaybackWorker.scope.launch {
            workerEntered.countDown()
            check(workerRelease.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                player = MpvPlayerAdapter(context, PlayingMedia(
                    MediaItem("frame-event", "Pending frame fixture", null, null),
                    "file:///synthetic", CatalogType.MOVIES), 0L).apply {
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                    })
                    volume = 0.5f // Keep a Media3 command future pending on the blocked worker.
                }
                val observer = MpvPlayerAdapter::class.java.getDeclaredField("observer").apply { isAccessible = true }
                    .get(player) as dev.jdtech.mpv.MPVLib.EventObserver
                observer.event(dev.jdtech.mpv.MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART)
            }
            instrumentation.waitForIdleSync()
            assertEquals("Pending Media3 command defers the first-frame state update", 0, frames.get())
            workerRelease.countDown()
            runBlocking { blocker.join() }
            val until = android.os.SystemClock.elapsedRealtime() + 3_000L
            while (frames.get() == 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20L)
            assertEquals("First-frame notification survives the deferred state read", 1, frames.get())
        } finally {
            workerRelease.countDown()
            instrumentation.runOnMainSync { player?.release() }
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun replacementOnSameSurfaceRetainsVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var old: MpvPlayerAdapter? = null
        var replacement: MpvPlayerAdapter? = null
        val firstFrame = AtomicBoolean()
        val replacementFrame = AtomicBoolean()
        val failure = AtomicReference<PlaybackException?>()
        fun await(label: String, condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15_000L
            while (android.os.SystemClock.elapsedRealtime() < end) {
                assertNull(label, failure.get())
                if (condition()) return
                Thread.sleep(30L)
            }
            fail(label)
        }
        try {
            PlaybackEngineFixtureTest().FixtureServer().use { server ->
                lateinit var surface: android.view.SurfaceView
                val media = PlayingMedia(MediaItem("surface-owner", "Surface owner fixture", null, null),
                    "${server.base}/movie.mp4", catalogType = CatalogType.MOVIES, playbackFormat = "MP4")
                fun listener(frame: AtomicBoolean) = object : Player.Listener {
                    override fun onRenderedFirstFrame() { frame.set(true) }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                }
                instrumentation.runOnMainSync {
                    surface = android.view.SurfaceView(host)
                    host.setContentView(surface)
                    old = MpvPlayerAdapter(context, media, 0L).apply {
                        addListener(listener(firstFrame)); setVideoSurfaceView(surface); prepare(); play()
                    }
                }
                await("Original MPV renders video") { firstFrame.get() }
                instrumentation.runOnMainSync {
                    replacement = MpvPlayerAdapter(context, media, 0L).apply {
                        addListener(listener(replacementFrame)); setVideoSurfaceView(surface); prepare(); play()
                    }
                    // This is Compose's retry ordering: new prepare is already
                    // queued when onDispose releases the previous adapter.
                    old!!.release(); old = null
                }
                await("Replacement must render, rather than silently continue with audio only") { replacementFrame.get() }
                // MPV's restart event is posted directly to main; its video
                // dimensions are queried on the native worker before posting.
                // Either event may arrive first, but both must be observed.
                await("Replacement retains a native video output") {
                    var hasVideo = false
                    instrumentation.runOnMainSync { hasVideo = replacement!!.videoSize.width > 0 }
                    hasVideo
                }
            }
        } finally {
            instrumentation.runOnMainSync { old?.release(); replacement?.release(); host.finish() }
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun nativeMovieSeekingTracksAndCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Never run synthetic tests in the user's profile package", context.packageName.endsWith(".regression"))
        File(context.cacheDir, "retry.wav").delete()
        // An empty foreground host has no second player or audio-focus owner.
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .putExtra("engine", "MEDIA3").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(context.cacheDir, "mpv-fixture.wav")
        val samples = 44_100 * 20
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(0) }
        file.writeBytes(bytes.array())
        val ready = AtomicBoolean()
        val ended = AtomicBoolean()
        val error = AtomicReference<PlaybackException?>()
        var player: MpvPlayerAdapter? = null
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < deadline) {
                assertNull("Native MPV error during $label", error.get())
                if (predicate()) return
                Thread.sleep(100L)
            }
            fail(label)
        }
        fun position(): Long {
            var value = 0L
            instrumentation.runOnMainSync { value = player!!.currentPosition }
            return value
        }
        try {
            instrumentation.runOnMainSync {
                player = MpvPlayerAdapter(context, PlayingMedia(
                    media = MediaItem("synthetic-mpv", "Synthetic MPV fixture", null, null),
                    url = Uri.fromFile(file).toString(), catalogType = CatalogType.MOVIES), 0L)
                val surface = android.view.SurfaceView(host)
                host.setContentView(surface)
                player!!.setVideoSurfaceView(surface)
                player!!.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.set(true)
                        if (state == Player.STATE_ENDED) ended.set(true)
                    }
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure) }
                })
                player!!.prepare()
                player!!.play()
            }
            await("Native MPV must prepare and advance") { ready.get() && position() > 300L }
            instrumentation.runOnMainSync {
                assertEquals(20_000L, player!!.duration)
                assertTrue("Audio tracks must come from the fixture", player!!.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO })
                player!!.pause()
                player!!.seekTo(7000L)
            }
            await("Paused exact seek must reach seven seconds") { position() in 6_850L..7_150L }
            Thread.sleep(500L)
            assertTrue("Paused seek must remain paused", position() in 6_850L..7_150L)
            // Hold the command queue while an old decoder time update replaces
            // the adapter's optimistic position. Seek must retain its own target.
            val workerEntered = CountDownLatch(1)
            val workerRelease = CountDownLatch(1)
            val blocker = MpvNativePlaybackWorker.scope.launch {
                workerEntered.countDown()
                check(workerRelease.await(5, TimeUnit.SECONDS))
            }
            try {
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    player!!.seekTo(12_000L)
                    MpvPlayerAdapter::class.java.getDeclaredField("position").apply { isAccessible = true }
                        .setLong(player, 2_000L)
                }
            } finally { workerRelease.countDown() }
            runBlocking { blocker.join() }
            await("Queued seek retains twelve seconds despite an older native clock update") {
                val native = runBlocking { withContext(MpvNativePlaybackWorker.dispatcher) {
                    val engine = MpvPlayerAdapter::class.java.getDeclaredField("mpv").apply { isAccessible = true }
                        .get(player) as dev.jdtech.mpv.MPVLib
                    engine.getPropertyDouble("time-pos")
                } }
                native != null && native in 11.85..12.15
            }
            instrumentation.runOnMainSync { player!!.seekTo(3000L) }
            await("Backward seek must reach three seconds") { position() in 2_850L..3_150L }
            instrumentation.runOnMainSync { player!!.seekTo(18_500L); player!!.play() }
            await("Normal EOF must end playback instead of becoming a network error") { ended.get() }
            assertNull(error.get())
        } finally {
            instrumentation.runOnMainSync { player?.release(); host.finish() }
            file.delete()
        }
    }
}
