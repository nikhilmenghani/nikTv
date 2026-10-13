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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real native MPV checks using a local fixture and an isolated app package. */
class MpvPlaybackIntegrationTest {
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun nativeMovieSeekingTracksAndCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Never run synthetic tests in the user's profile package", context.packageName.endsWith(".regression"))
        File(context.cacheDir, "retry.wav").delete()
        // A missing-file regression host provides a foreground window without a
        // second active player or any provider/profile access.
        val host = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
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
