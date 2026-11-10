package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** Reproduce a refused connection, then recover the identical URL with the real Media3 player. */
class ConnectionRestartDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        repeat(10) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    @Test fun restartConnectionFailureRecreatesPlayerAndResumesWithoutPortalLookup() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val bytes = ZipInputStream(ins.context.assets.open("playback-fixtures.zip")).use { zip ->
            var found: ByteArray? = null
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "movie.mp4") found = zip.readBytes() }
            requireNotNull(found)
        }
        val port = ServerSocket(0).use { it.localPort }
        val server = object : NanoHTTPD("127.0.0.1", port) {
            override fun serve(session: IHTTPSession): Response {
                val offset = session.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val length = bytes.size - offset
                return newFixedLengthResponse(if (offset > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    "video/mp4", ByteArrayInputStream(bytes, offset, length), length.toLong()).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (offset > 0) addHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                    addHeader("Connection", "close"); setKeepAlive(false)
                }
            }
        }
        val activity = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val oldVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val frames = AtomicInteger(); val position = AtomicLong(); val restarts = AtomicInteger(); val linkRefreshes = AtomicInteger()
        val episode = MediaItem("episode", "Connection recovery fixture", null, "fixture-command")
        val previous = episode.copy(id = "previous", title = "Previous fixture")
        val next = episode.copy(id = "next", title = "Next fixture")
        val original = PlayingMedia(episode,
            "http://127.0.0.1:$port/movie.mp4", CatalogType.SERIES, resumePositionMillis = 5_000,
            progressKey = "connection-recovery", decoderRecoveryCount = 3, playbackFormat = "MP4",
            previousEpisode = previous, nextEpisode = next, episodeQueue = listOf(previous, episode, next))
        lateinit var vm: NikTvViewModel
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync {
                vm = ViewModelProvider(activity)[NikTvViewModel::class.java]
                vm.viewModelScope.coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
                state.value = NikTvState(restoring = false, nowPlaying = original)
                activity.setContent { MaterialTheme(colorScheme = NikColors) {
                    val state by vm.state.collectAsState()
                    val playing = state.nowPlaying
                    if (playing == null) Text("Restarting playback fixture")
                    else PlayerScreen(playing, {}, { linkRefreshes.incrementAndGet() }, {}, {}, {}, {},
                        { _, at, _ -> position.set(at) }, playbackEngine = PlaybackEngine.MEDIA3, controlsTimeoutSeconds = 0,
                        startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() },
                        onRestartDecoderSession = { at -> restarts.incrementAndGet(); vm.restartPlaybackDecoderSession(at, playing) })
                } }
            }
            await("Connection error offers a labelled Restart playback action") { focused("Restart playback") }
            assertEquals(0, frames.get())
            fun rect(label: String) = android.graphics.Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
            val restartBounds = rect("Restart playback")
            val backBounds = rect("Go back")
            val stacked = backBounds.top >= restartBounds.bottom
            if (find("Next") != null && find("Previous") != null)
                assertTrue("Next stays on one line", rect("Next").height() <= rect("Previous").height() + 2)
            ins.sendKeyDownUpSync(if (stacked) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_LEFT)
            await("Back remains reachable beside/below Restart") { focused("Go back") }
            ins.sendKeyDownUpSync(if (stacked) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad returns to labelled Restart") { focused("Restart playback") }
            val screenshot = requireNotNull(ins.uiAutomation.takeScreenshot())
            try {
                java.io.File(context.getExternalFilesDir(null), "connection-restart-popup.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            server.start(5_000, true)
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Restart opens the same source and renders real video") { frames.get() > 0 && position.get() >= 5_000 }
            val resumed = requireNotNull(vm.state.value.nowPlaying)
            assertEquals(1, restarts.get()); assertEquals(0, linkRefreshes.get())
            assertNotEquals(original.playbackAttemptId, resumed.playbackAttemptId)
            assertNotEquals(original.decoderSessionId, resumed.decoderSessionId)
            assertEquals(original.url, resumed.url); assertEquals(original.progressKey, resumed.progressKey)
            assertEquals(0, resumed.decoderRecoveryCount)
            assertTrue(resumed.resumePositionMillis >= 5_000)
            await("Episode playback advances after restart") { position.get() > 6_000 }
            assertNull(find("This title can’t be played right now"))
        } finally {
            ins.runOnMainSync { activity.finish() }
            server.stop()
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, oldVolume, 0)
            ins.setInTouchMode(true)
        }
    }
}
