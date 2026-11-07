package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
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

    private fun live(target: AlternateSourceTarget) {
        val streams = runBlocking { AlternateSourceResolver().resolve(target) }
        assertTrue("HDHub4u provides a native source for the exact requested title/episode", streams.isNotEmpty())
        val stream = streams.first()
        assertEquals(AlternatePlaybackRoute.NATIVE, stream.route)
        assertTrue(stream.headers["Referer"]?.startsWith("https://") == true)
        val host = host()
        val frames = AtomicInteger()
        val error = AtomicReference<String>()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val source = DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.headers)
                    .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().also { engine ->
                    engine.volume = 0f
                    engine.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                        override fun onPlayerError(failure: PlaybackException) { error.set(failure.errorCodeName) }
                    })
                    host.setContent { AndroidView(modifier = Modifier.fillMaxSize(),
                        factory = { PlayerView(it).apply { useController = false; this.player = engine } }) }
                    engine.setMediaItem(androidx.media3.common.MediaItem.fromUri(stream.url))
                    engine.prepare()
                    engine.playWhenReady = true
                }
            }
            await("HDHub4u native playback produces video frames", 30_000L) {
                assertNull("Native playback error", error.get())
                var progressed = false
                instrumentation.runOnMainSync { progressed = player!!.currentPosition >= 1_000 && player!!.videoSize.width > 0 }
                frames.get() > 0 && progressed
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "HDHUB_NATIVE series=${target.series} season=${target.season} episode=${target.episode} sources=${streams.size} frames=${frames.get()}\n") })
        } finally {
            instrumentation.runOnMainSync { player?.release(); host.finish() }
        }
    }
}
