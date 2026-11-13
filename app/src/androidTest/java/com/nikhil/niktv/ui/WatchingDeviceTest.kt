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
import androidx.compose.runtime.mutableStateOf
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private fun await(message: String, diagnostics: (() -> String)? = null, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(100) }
        fail(message + diagnostics?.invoke()?.let { "; $it" }.orEmpty())
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
    @Test fun twoHourCheckPausesAndContinuesMedia3() = exercise(PlaybackEngine.MEDIA3, false, false)
    @Test fun fourEpisodesHoldAutoplayUntilRemoteConfirmationVlc() = exercise(PlaybackEngine.VLC, false, true)
    @Test fun twoHourCheckPausesAndContinuesVlc() = exercise(PlaybackEngine.VLC, false, false)
    @Test fun twoHourCheckCanStopWatchingMedia3() = exercise(PlaybackEngine.MEDIA3, false, false, stop = true)
    @Test fun fourEpisodeCheckCanStopWatchingVlc() = exercise(PlaybackEngine.VLC, false, true, stop = true)
    @Test fun twoHourCheckPausesAndContinuesSportsVlc() = exercise(PlaybackEngine.VLC, true, false)
    @Test fun continueRefreshesSourceThatExpiredWhileQuestionPausedMedia3() = exercisePausedExpiry(PlaybackEngine.MEDIA3)
    @Test fun continueRefreshesSourceThatExpiredWhileQuestionPausedVlc() = exercisePausedExpiry(PlaybackEngine.VLC)

    private fun exercisePausedExpiry(engine: PlaybackEngine) {
        check(context.packageName.endsWith(".regression"))
        val preferences = WatchingPreferences.preferences(context, owner = null)
        val old = WatchingPreferences.read(preferences)
        WatchingPreferences.save(preferences, WatchingConfiguration())
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        PlaybackEngineFixtureTest().FixtureServer().use { server ->
            server.requiredHeaders = mapOf("User-Agent" to "watching-expired-token")
            // Let startup render, but hold a pending fetch so its rejection arrives
            // after the question has paused playback rather than during startup.
            server.beforeResponse = { path ->
                val segment = path.removePrefix("seg").removeSuffix(".ts").toIntOrNull()
                if (segment != null && segment >= 2) {
                    blocked.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Fixture response was not released" }
                }
            }
            val episode = MediaItem("watch-expiry-one", "Paused expiry fixture", null, null, seasonNumber = 1, episodeNumber = 1)
            val series = MediaItem("watch-expiry-series", "Paused expiry series", null, null)
            val initial = PlayingMedia(episode, "${server.base}/vod-hls", CatalogType.SERIES,
                series = series, progressKey = "watch-expiry", profileKey = "watch-profile",
                streamRequestHeaders = server.requiredHeaders)
            val current = mutableStateOf(initial)
            val session = WatchingSession().apply { begin("${initial.userScope}:${initial.profileKey}:${series.id}", episode.id) }
            val frames = AtomicInteger()
            val progress = AtomicLong()
            val requested = AtomicBoolean(true)
            val retries = AtomicInteger()
            val authRetries = AtomicInteger()
            val retryPosition = AtomicLong()
            val refreshSource: (Long, Boolean) -> Unit = { position, authorization ->
                if (authorization) authRetries.incrementAndGet() else retries.incrementAndGet()
                retryPosition.set(position)
                frames.set(0)
                current.value = current.value.copy(url = "${server.base}/vod-hls?fresh=1",
                    streamRequestHeaders = mapOf("User-Agent" to "watching-fresh-token"),
                    playbackAttemptId = java.util.UUID.randomUUID().toString(),
                    authorizationRetryCount = if (authorization) 1 else 0,
                    resumePositionMillis = position, playWhenReady = true)
            }
            fun diagnostics(): String = "engine=$engine rejected=${server.rejected.get()} " +
                "retries=${retries.get()} authRetries=${authRetries.get()} requested=${requested.get()} " +
                "frames=${frames.get()} position=${progress.get()} retryPosition=${retryPosition.get()} " +
                "requests=${synchronized(server.paths) { server.paths.takeLast(8).joinToString(",") }}"
            val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            try {
                main { host.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Landscape fixture is ready") { host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && host.hasWindowFocus() }
                instrumentation.setInTouchMode(false)
                main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                    CompositionLocalProvider(LocalWatchingSession provides session) {
                        PlayerScreen(current.value, onBack = {}, onRetry = { refreshSource(it, false) },
                            onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = { refreshSource(it, true) },
                            onPlayPrevious = {}, onPlayNext = { error("A paused failure cannot complete the episode") },
                            onProgress = { _, position, _ -> progress.set(position) },
                            onPlaybackIntentChanged = { requested.set(it) }, playbackEngine = engine,
                            startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                    }
                } } }
                await("HTTP fixture renders before expiry") { frames.get() > 0 }
                assertTrue("A real segment request is held", blocked.await(15, TimeUnit.SECONDS))
                main {
                    for (second in 0..7199) session.sample(second * 1000L, true)
                    session.sample(7_200_000L, false)
                }
                await("Question pauses the real HTTP source") { node("Are you still watching?") != null && !requested.get() }
                server.requiredHeaders = mapOf("User-Agent" to "watching-fresh-token")
                release.countDown()
                // Media3 retries an HLS load three times before publishing the
                // terminal player error. Answering on the first HTTP rejection
                // tests an in-flight load instead of resuming a failed player.
                val rejectionsBeforeFailure = if (engine == PlaybackEngine.MEDIA3) 4 else 1
                await("The paused source exhausts its native HTTP load retry", ::diagnostics) {
                    server.rejected.get() >= rejectionsBeforeFailure
                }
                // Allow the player listener and its deferred 350 ms authorization
                // callback to run while playback is still explicitly paused.
                Thread.sleep(if (engine == PlaybackEngine.MEDIA3) 750L else 2_000L)
                android.util.Log.i("NikTvFixture", "Watching expiry before Continue: ${diagnostics()}")
                assertNotNull("Transport failure must not dismiss the question; ${diagnostics()}", node("Are you still watching?"))
                assertEquals("A paused player must not reconnect before consent; ${diagnostics()}", 0, retries.get())
                assertEquals("Authorization retry must remain deferred while paused; ${diagnostics()}", 0, authRetries.get())
                assertFalse("The failed source must remain paused until consent; ${diagnostics()}", requested.get())
                // A new fatal overlay must not steal focus from Continue in the question.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("D-pad Continue dismisses the question despite the background failure", ::diagnostics) {
                    node("Are you still watching?") == null
                }
                // Media3 may publish its terminal error only after native resume;
                // in that case the real ViewModel's authorization callback refreshes
                // the source. Both callback paths must perform the same replacement.
                await("Continue refreshes the failed source exactly once", ::diagnostics) { retries.get() + authRetries.get() == 1 }
                await("Fresh authenticated source renders", ::diagnostics) { frames.get() > 0 }
                await("Fresh source advances from the resume point", ::diagnostics) { requested.get() && progress.get() > retryPosition.get() + 1_000L }
                assertEquals("Continue must resolve once across explicit and authorization recovery; ${diagnostics()}", 1,
                    retries.get() + authRetries.get())
                assertNull(node("Are you still watching?"))
                NavigationAuditEvidence.capture("watching-expired-source-recovered-${engine.name.lowercase()}")
            } finally {
                release.countDown()
                main { host.finish() }
                WatchingPreferences.save(preferences, old)
            }
        }
    }

    private fun exercise(engine: PlaybackEngine, sports: Boolean, episodes: Boolean, stop: Boolean = false) {
        check(context.packageName.endsWith(".regression"))
        // Match this fixture's explicit unscoped viewer instead of another test's selected account.
        val preferences = WatchingPreferences.preferences(context, owner = null)
        val old = WatchingPreferences.read(preferences)
        WatchingPreferences.save(preferences, WatchingConfiguration())
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
        val current = mutableStateOf(media)
        val session = WatchingSession().apply {
            // The injected session must already belong to the exact media owner,
            // profile and series; entering a different identity correctly clears it.
            begin("${media.userScope}:${media.profileKey}:${series.id}", media.media.id)

        }
        val progress = AtomicLong()
        val requested = AtomicBoolean(true)
        val advances = AtomicInteger()
        val frames = AtomicInteger()
        val retries = AtomicInteger()
        val refreshSource: (Long) -> Unit = { position ->
            retries.incrementAndGet()
            frames.set(0)
            current.value = current.value.copy(playbackAttemptId = java.util.UUID.randomUUID().toString(),
                decoderSessionId = java.util.UUID.randomUUID().toString(),
                resumePositionMillis = position, playWhenReady = true)
        }
        val exits = AtomicInteger()
        val playerVisible = mutableStateOf(true)
        val exitPlayer = { playerVisible.value = false; exits.incrementAndGet(); Unit }
        val evidence = "${if (sports) "sports" else "iptv"}-${engine.name.lowercase()}-${if (episodes) "episodes" else "time"}-${if (stop) "stop" else "continue"}"
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        main { host.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("Landscape is ready") { host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && host.hasWindowFocus() }
        try {
            instrumentation.setInTouchMode(false)
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalWatchingSession provides session) {
                    if (!playerVisible.value) return@CompositionLocalProvider
                    if (sports) com.nikhil.niktv.sports.player.PlayerScreen(current.value, onBack = exitPlayer, onRetry = refreshSource, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = { advances.incrementAndGet() },
                        onProgress = { _, p, _ -> progress.set(p) }, onPlaybackIntentChanged = { requested.set(it) },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                    else PlayerScreen(current.value, onBack = exitPlayer, onRetry = refreshSource, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = { advances.incrementAndGet() },
                        onProgress = { _, p, _ -> progress.set(p) }, onPlaybackIntentChanged = { requested.set(it) },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                }
            } } }
            await("Actual video renders") { frames.get() > 0 }
            // Seed after the real player owns its session and renders a frame. This
            // tests prompt navigation without a setup identity change clearing the
            // counters, or a near-limit fixture pausing before its first frame.
            main {
                if (episodes) repeat(3) { session.completeEpisode() }
                else for (second in 0..7199) session.sample(second * 1000L, true)
                session.sample(7_200_000, false)
            }
            await("Watch check opens at the configured limit") { node("Are you still watching?") != null }
            await("Prompt pauses playback intent") { !requested.get() }
            assertEquals("Autoplay cannot advance behind the question", 0, advances.get())
            capture("still-watching-$evidence.png")
            NavigationAuditEvidence.capture("watching-prompt-$evidence")
            Thread.sleep(3500)
            assertNotNull("Refresh or time must not dismiss the question", node("Are you still watching?"))
            assertFalse(requested.get())
            val before = progress.get()
            if (stop) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Remote confirmation closes the prompt") { node("Are you still watching?") == null }
            if (stop) {
                await("Stop exits the actual player") { exits.get() == 1 }
                assertFalse("Stopping cannot restart playback", requested.get())
                assertEquals("Stopping cannot advance to another episode", 0, advances.get())
            } else if (episodes) await("Confirmation advances exactly once") { advances.get() == 1 }
            else {
                await("Continue renders video from the resumed session") { frames.get() > 0 }
                await("Time-limit confirmation resumes the same video") { requested.get() && progress.get() > before + 1000 }
                assertEquals(0, advances.get())
            }
            assertEquals("Only IPTV VLC time-limit Continue reopens its paused input",
                if (!sports && engine == PlaybackEngine.VLC && !episodes && !stop) 1 else 0, retries.get())
            assertEquals(0, session.completedEpisodes)
            Thread.sleep(750)
            assertNull("Answering clears the pending prompt", node("Are you still watching?"))
            assertEquals("A single answer must produce a single transition", if (episodes && !stop) 1 else 0, advances.get())
            NavigationAuditEvidence.capture("watching-prompt-confirmed-$evidence")
        } finally { main { host.finish() }; WatchingPreferences.save(preferences, old) }
    }

    @Test fun settingsPersistBothLimitsAndDisableTheCheck() {
        check(context.packageName.endsWith(".regression"))
        // Match this fixture's explicit unscoped viewer instead of another test's selected account.
        val preferences = WatchingPreferences.preferences(context, owner = null)
        val old = WatchingPreferences.read(preferences)
        WatchingPreferences.save(preferences, WatchingConfiguration())
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            main { host.setContent { MaterialTheme { Column(Modifier.width(520.dp)) { WatchingSettingsContent() } } } }
            click("Without interaction: watch time"); click("60 minutes")
            click("Without interaction: episodes"); click("2 episodes")
            assertEquals(WatchingConfiguration(minutes = 60, episodes = 2), WatchingPreferences.read(preferences))
            click("Are you still watching?")
            assertFalse(WatchingPreferences.read(preferences).enabled)
        } finally { main { host.finish() }; WatchingPreferences.save(preferences, old) }
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
