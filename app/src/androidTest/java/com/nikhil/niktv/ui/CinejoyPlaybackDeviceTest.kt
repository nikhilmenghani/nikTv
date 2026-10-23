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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CinejoyPlaybackDeviceTest {
    @Test fun autoLookupIncludesRealMovyNativeResults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val streams = kotlinx.coroutines.runBlocking {
            com.nikhil.niktv.data.AlternateSourceResolver().resolve(AlternateSourceTarget("The Matrix", false, tmdbId = 603, year = 1999))
        }
        assertTrue("Auto must include real Movy native results", streams.any {
            it.label.contains("Movy") && it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE
        })
        assertTrue("Vidy must not be offered", streams.none { it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.VIDY_EMBEDDED })
    }
    @Test fun publicCinejoyMovieReportsAdvancingPlayback() = exercise(AlternateSourceTarget("The Matrix", false, tmdbId = 603))
    @Test fun publicCinejoyEpisodeReportsAdvancingPlayback() = exercise(AlternateSourceTarget("Friends", true, tmdbId = 1668, season = 1, episode = 1))
    @Test fun cinejoyResolvesWithoutShowingOrPlayingWebVideo() = exercise(AlternateSourceTarget("The Matrix", false, tmdbId = 603), nativeOnly = true)
    @Test fun cinejoyEpisodeResolvesWithoutWebPlayback() = exercise(AlternateSourceTarget("Friends", true, tmdbId = 1668, season = 1, episode = 1), nativeOnly = true)
    @Test fun cnoMovieCanHandOffToNativePlayback() = exercise(AlternateSourceTarget("The Matrix", false, tmdbId = 603, provider = com.nikhil.niktv.data.AlternateSourceProvider.CNO))

    private fun exercise(target: AlternateSourceTarget, nativeOnly: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val position = AtomicLong(-1)
        val duration = AtomicLong(0)
        val detected = java.util.concurrent.atomic.AtomicReference<com.nikhil.niktv.data.AlternateStream?>(null)
        var activity: PlaybackFixtureHostActivity? = null
        try {
            val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            activity = host
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        CinejoyPlayer(target, 0,
                            close = { host.finish() },
                            onProgress = { p, d -> position.set(p); duration.set(d) },
                            onIntent = {}, onNative = { stream, _, _ -> detected.set(stream) }, nativeOnly = nativeOnly)
                    }
                }
            }
            if (nativeOnly) {
                val resolveDeadline = SystemClock.elapsedRealtime() + 35_000
                while (detected.get() == null && SystemClock.elapsedRealtime() < resolveDeadline) Thread.sleep(100)
                assertTrue("Native-only resolution must not play website video", position.get() == -1L)
                fun webView(view: android.view.View): android.webkit.WebView? {
                    if (view is android.webkit.WebView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
                    return null
                }
                val webPlaying = java.util.concurrent.atomic.AtomicReference<String?>(null)
                instrumentation.runOnMainSync {
                    val browser = requireNotNull(webView(host.window.decorView))
                    assertTrue("Resolver browser must not be displayed", browser.alpha == 0f)
                    assertTrue("Web autoplay must be disabled", browser.settings.mediaPlaybackRequiresUserGesture)
                    browser.evaluateJavascript("(function(){let v=document.querySelector('video');return !!v&&!v.paused})()") { webPlaying.set(it) }
                }
                val probeDeadline = SystemClock.elapsedRealtime() + 2_000
                while (webPlaying.get() == null && SystemClock.elapsedRealtime() < probeDeadline) Thread.sleep(50)
                assertTrue("Website video must remain paused during native resolution", webPlaying.get() == "false")
            } else {
            val deadline = SystemClock.elapsedRealtime() + 75_000
            while (position.get() < 1_000 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Embedded source must report a valid playback position", position.get() >= 1_000)
            assertTrue("Embedded source must report duration", duration.get() > position.get())
            val before = position.get()
            val advancingDeadline = SystemClock.elapsedRealtime() + 12_000
            while (position.get() <= before + 1_000 && SystemClock.elapsedRealtime() < advancingDeadline) Thread.sleep(100)
            assertTrue("Embedded playback must continue advancing", position.get() > before + 1_000)
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString()?.equals("Play in NikTV", ignoreCase = true) == true || node.contentDescription?.toString() == "Play in NikTV") return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            var button = find(instrumentation.uiAutomation.rootInActiveWindow)
            val buttonDeadline = SystemClock.elapsedRealtime() + 5_000
            while (button == null && SystemClock.elapsedRealtime() < buttonDeadline) {
                Thread.sleep(100); button = find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            if (button == null) instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "cinejoy-handoff-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }; bitmap.recycle()
            }
            assertTrue("Native handoff button must be reachable", button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            }
            val handoffDeadline = SystemClock.elapsedRealtime() + 5_000
            while (detected.get() == null && SystemClock.elapsedRealtime() < handoffDeadline) Thread.sleep(100)
            val candidate = requireNotNull(detected.get()) { "No compatible HLS request was observed" }
            val streams = kotlinx.coroutines.runBlocking { com.nikhil.niktv.data.validateCinejoyNativeStream(candidate) }
            val stream = streams.first()
            val frame = java.util.concurrent.atomic.AtomicInteger()
            val nativePosition = AtomicLong(-1)
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        PlayerScreen(com.nikhil.niktv.model.PlayingMedia(
                            com.nikhil.niktv.model.MediaItem("cinejoy-fixture", target.title, null, null), stream.url,
                            if (target.series) com.nikhil.niktv.model.CatalogType.SERIES else com.nikhil.niktv.model.CatalogType.MOVIES, streamRequestHeaders = stream.headers, playbackFormat = stream.format),
                            onBack = { host.finish() }, onRetry = {}, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {},
                            onProgress = { _, p, _ -> nativePosition.set(p) }, onVideoFrameRendered = { frame.incrementAndGet() },
                            playbackEngine = com.nikhil.niktv.model.PlaybackEngine.MEDIA3)
                    }
                }
            }
            val nativeDeadline = SystemClock.elapsedRealtime() + 30_000
            while ((frame.get() == 0 || nativePosition.get() < 1_000) && SystemClock.elapsedRealtime() < nativeDeadline) Thread.sleep(100)
            assertTrue("Cinejoy native handoff must render and advance", frame.get() > 0 && nativePosition.get() >= 1_000)

        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
