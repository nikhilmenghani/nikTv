package com.nikhil.niktv.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AlternateSourceTarget
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class VidyPlaybackDeviceTest {
    @Test fun publicEmbeddedMovieReportsAdvancingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val position = AtomicLong(-1)
        val duration = AtomicLong(0)
        var activity: PlaybackFixtureHostActivity? = null
        try {
            val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            activity = host
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        VidyEmbeddedPlayer(AlternateSourceTarget("The Matrix", false, tmdbId = 603),
                            onClose = { host.finish() },
                            onProgress = { p, d -> position.set(p); duration.set(d) })
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 75_000
            while (position.get() < 1_000 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Embedded source must report a valid playback position", position.get() >= 1_000)
            assertTrue("Embedded source must report duration", duration.get() > position.get())
            val before = position.get()
            val advancingDeadline = SystemClock.elapsedRealtime() + 12_000
            while (position.get() <= before + 1_000 && SystemClock.elapsedRealtime() < advancingDeadline) Thread.sleep(100)
            assertTrue("Embedded playback must continue advancing", position.get() > before + 1_000)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
