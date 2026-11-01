package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun node(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (n.text?.toString() == text || n.contentDescription?.toString() == text) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun click(text: String) {
        await("Visible control: $text") { node(text) != null }
        var n = node(text)
        while (n != null) { if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; n = n.parent }
        fail("Could not activate $text")
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.externalCacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun fourEpisodesHoldAutoplayUntilRemoteConfirmationMedia3() = exercise(PlaybackEngine.MEDIA3, false, true)
    @Test fun twoHourCheckPausesAndContinuesSportsVlc() = exercise(PlaybackEngine.VLC, true, false)

    private fun exercise(engine: PlaybackEngine, sports: Boolean, episodes: Boolean) {
        check(context.packageName.endsWith(".regression"))
        val old = WatchingPreferences.read(WatchingPreferences.preferences(context))
        WatchingPreferences.save(context, WatchingConfiguration())
        val video = File(context.cacheDir, "watching-fixture.mp4")
        ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
            while (true) { val entry = zip.nextEntry ?: error("Missing video fixture")
                if (entry.name == "movie.mp4") { video.writeBytes(zip.readBytes()); break } }
        }
        val series = MediaItem("watch-series", "Watching fixture", null, null)
        val episode = MediaItem("watch-one", "Episode one", null, null, seasonNumber = 1, episodeNumber = 1)
        val next = episode.copy(id = "watch-two", title = "Episode two", episodeNumber = 2)
        val media = PlayingMedia(episode, Uri.fromFile(video).toString(), CatalogType.SERIES,
            series = series, nextEpisode = next, progressKey = "watch-fixture", profileKey = "watch-profile")
        val session = WatchingSession().apply {
            begin("watch-profile:watch-series")
            if (episodes) repeat(3) { completeEpisode() }
            else for (second in 0..7199) sample(second * 1000L, true)
            // Reset the sampling clock without resetting accumulated watch time.
            sample(7_200_000, false)
        }
        val progress = AtomicLong()
        val requested = AtomicBoolean(true)
        val advances = AtomicInteger()
        val frames = AtomicInteger()
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        main { host.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("Landscape is ready") { host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && host.hasWindowFocus() }
        try {
            instrumentation.setInTouchMode(false)
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalWatchingSession provides session) {
                    if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media, onBack = {}, onRetry = {}, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = { advances.incrementAndGet() },
                        onProgress = { _, p, _ -> progress.set(p) }, onPlaybackIntentChanged = { requested.set(it) },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                    else PlayerScreen(media, onBack = {}, onRetry = {}, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = { advances.incrementAndGet() },
                        onProgress = { _, p, _ -> progress.set(p) }, onPlaybackIntentChanged = { requested.set(it) },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                }
            } } }
            await("Actual video renders") { frames.get() > 0 }
            await("Watch check opens at the configured limit") { node("Are you still watching?") != null }
            await("Prompt pauses playback intent") { !requested.get() }
            assertEquals("Autoplay cannot advance behind the question", 0, advances.get())
            capture("still-watching-$engine.png")
            Thread.sleep(3500)
            assertNotNull("Refresh or time must not dismiss the question", node("Are you still watching?"))
            assertFalse(requested.get())
            val before = progress.get()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Remote confirmation closes the prompt") { node("Are you still watching?") == null }
            if (episodes) await("Confirmation advances exactly once") { advances.get() == 1 }
            else {
                await("Time-limit confirmation resumes the same video") { requested.get() && progress.get() > before + 1000 }
                assertEquals(0, advances.get())
            }
            assertEquals(0, session.completedEpisodes)
        } finally { main { host.finish() }; WatchingPreferences.save(context, old) }
    }

    @Test fun settingsPersistBothLimitsAndDisableTheCheck() {
        check(context.packageName.endsWith(".regression"))
        val old = WatchingPreferences.read(WatchingPreferences.preferences(context))
        WatchingPreferences.save(context, WatchingConfiguration())
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            main { host.setContent { MaterialTheme { Column(Modifier.width(520.dp)) { WatchingSettingsContent() } } } }
            click("Without interaction: watch time"); click("60 minutes")
            click("Without interaction: episodes"); click("2 episodes")
            assertEquals(WatchingConfiguration(minutes = 60, episodes = 2), WatchingPreferences.read(WatchingPreferences.preferences(context)))
            click("Are you still watching?")
            assertFalse(WatchingPreferences.read(WatchingPreferences.preferences(context)).enabled)
        } finally { main { host.finish() }; WatchingPreferences.save(context, old) }
    }
    @Test fun remoteCanChooseStopWatchingWithoutAdvancing() {
        check(context.packageName.endsWith(".regression"))
        val check = WatchingCheck(WatchingSession(), true).apply {
            config = WatchingConfiguration(episodes = 1)
            completeEpisode { error("Must wait for confirmation") }
        }
        val stopped = AtomicInteger()
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.setInTouchMode(false)
            main { host.setContent { MaterialTheme { StillWatchingPrompt(check,
                onContinue = { error("Stop must not continue playback") }, onStop = { check.confirm(); stopped.incrementAndGet() }) } } }
            await("Prompt opens") { node("Are you still watching?") != null }
            Thread.sleep(250)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Stop closes the prompt") { stopped.get() == 1 && node("Are you still watching?") == null }
        } finally { main { host.finish() } }
    }

}
