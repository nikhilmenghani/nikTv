package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.AlternatePlaybackSource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the isolated package; live resolution/playback runs only with -e hdhubLive true. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Hdhub4uDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun host(): PlaybackFixtureHostActivity {
        check(context.packageName.endsWith(".regression"))
        return instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }
    private fun await(message: String, timeout: Long = 10_000L, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(80) }
        fail(message)
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun click(label: String) {
        await("Control is present: $label") { find(root(), label) != null }
        var node = find(root(), label)
        while (node != null) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        fail("Could not activate $label")
    }

    @Test fun providerIsSelectableAndQualityKeepsItsStreamIdentity() {
        val host = host()
        val chosen = AtomicReference<AlternateSourceTarget>()
        val played = AtomicReference<String>()
        val target = AlternateSourceTarget("Fixture movie", false, year = 2026)
        val stream = AlternateStream("hdhub4u:fixture", "1080p · HDHub4u · Fixture server",
            "https://media.example.test/movie.m3u8", height = 1080)
        val state = mutableStateOf(AlternateSourcePickerState(
            AlternateSourceRequest(MediaItem("fixture", target.title, null, null), CatalogType.MOVIES), target, "fixture"))
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                AlternateSourceDialog(state.value, {}, search = {
                    chosen.set(it)
                    state.value = state.value.copy(target = it, streams = listOf(stream))
                }, play = { played.set(it) })
            } } }
            click("HDHub4u")
            await("HDHub4u retains the requested title and year") {
                chosen.get()?.let { it.provider == AlternateSourceProvider.HDHUB4U && it.title == target.title && it.year == 2026 } == true
            }
            click("1080p")
            await("The selected HDHub4u stream is opened") { played.get() == stream.id }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun liveMovieRendersThroughNativePlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hdhubLive") == "true")
        live(AlternateSourceTarget("Heart of the Beast", false, year = 2026, provider = AlternateSourceProvider.HDHUB4U))
    }

    @Test fun liveEpisodeRendersThroughNativePlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hdhubLive") == "true")
        live(AlternateSourceTarget("Wednesday", true, year = 2022, season = 2, episode = 1,
            provider = AlternateSourceProvider.HDHUB4U))
    }

    @Test fun liveMovieWithVlcPreferenceRendersInAppPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hdhubLive") == "true")
        live(AlternateSourceTarget("Heart of the Beast", false, year = 2026,
            provider = AlternateSourceProvider.HDHUB4U), PlaybackEngine.VLC)
    }

    @Test fun liveEpisodeWithVlcPreferenceRendersInAppPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hdhubLive") == "true")
        live(AlternateSourceTarget("Wednesday", true, year = 2022, season = 2, episode = 1,
            provider = AlternateSourceProvider.HDHUB4U), PlaybackEngine.VLC)
    }

    private fun live(target: AlternateSourceTarget, requestedEngine: PlaybackEngine = PlaybackEngine.MEDIA3) {
        val streams = runBlocking { AlternateSourceResolver().resolve(target) }
        assertTrue("HDHub4u provides a native source for the exact requested title/episode", streams.isNotEmpty())
        val stream = streams.first()
        assertEquals(AlternatePlaybackRoute.NATIVE, stream.route)
        assertTrue(stream.headers["Referer"]?.startsWith("https://") == true)
        val host = host()
        val frames = AtomicInteger()
        val position = AtomicLong()
        val errors = AtomicInteger()
        val preferenceChanges = AtomicInteger()
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val media = PlayingMedia(MediaItem("hdhub-device-fixture", target.title, null, null), stream.url,
            if (target.series) CatalogType.SERIES else CatalogType.MOVIES,
            progressKey = "hdhub-device-fixture", playbackFormat = stream.format,
            streamRequestHeaders = stream.headers,
            alternateSource = AlternatePlaybackSource(target, streams, stream.id, "fixture"))
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(media = media, onBack = {}, onRetry = { errors.incrementAndGet() },
                    onRetryAlternateDecoder = { errors.incrementAndGet() },
                    onPlaybackAuthorizationFailure = { errors.incrementAndGet() },
                    onPlayPrevious = {}, onPlayNext = {},
                    onProgress = { _, value, _ -> position.set(value) },
                    playbackEngine = requestedEngine,
                    onPlaybackEngineChanged = { preferenceChanges.incrementAndGet() },
                    onVideoFrameRendered = { frames.incrementAndGet() },
                    onTerminalPlaybackFailure = { errors.incrementAndGet() })
            } } }
            await("HDHub4u app playback renders with $requestedEngine selected", 30_000L) {
                assertEquals("No player failure or decoder/auth recovery", 0, errors.get())
                frames.get() > 0 && position.get() >= 1_000
            }
            assertEquals("Stream compatibility does not change the saved player preference", 0, preferenceChanges.get())
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "HDHUB_APP requested=$requestedEngine series=${target.series} season=${target.season} episode=${target.episode} sources=${streams.size} frames=${frames.get()} position=${position.get()}\n") })
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
