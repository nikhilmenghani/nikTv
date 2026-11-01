package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.MediaItem as NikItem
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Reproduces EOF -> fresh connection refused -> connection refused -> restored server. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VlcLiveRetryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun errorVisible(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.text?.toString()?.contains("This title can’t be played right now") == true) return true
            for (i in 0 until node.childCount) if (walk(node.getChild(i))) return true
            return false
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            assertFalse("Transient replacement failure must stay in automatic recovery", errorVisible())
            if (condition()) return
            Thread.sleep(150)
        }
        fail(message)
    }
    private fun exercise(sports: Boolean) {
        check(context.packageName.endsWith(".regression"))
        val closedPort = ServerSocket(0).use { it.localPort }
        val frames = AtomicInteger()
        val retries = CopyOnWriteArrayList<Long>()
        val attempt = mutableIntStateOf(0)
        val server = PlaybackEngineFixtureTest().FixtureServer()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val generation = attempt.intValue
                val media = remember(generation) { PlayingMedia(NikItem("retry-channel", "Live reconnect fixture", null, null),
                    if (generation in 1..2) "http://127.0.0.1:$closedPort/refused.mp4" else "${server.base}/movie.mp4?attempt=$generation",
                    CatalogType.LIVE_TV, progressKey = "retry-channel-$sports", profileKey = "isolated-retry-fixture",
                    playbackAttemptId = "retry-$generation", playbackFormat = "MP4") }
                val retry: (Long) -> Unit = { retries.add(SystemClock.elapsedRealtime()); attempt.intValue++ }
                if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media,
                    onBack = {}, onRetry = retry, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                    onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                    playbackEngine = PlaybackEngine.VLC, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                else PlayerScreen(media, onBack = {}, onRetry = retry, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                    onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                    playbackEngine = PlaybackEngine.VLC, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("The original live stream renders", 20_000) { frames.get() > 0 }
            await("Playback resumes automatically after two refused replacement connections", 90_000) { retries.size >= 3 && frames.get() >= 2 }
            assertEquals("Only the three expected reconnects were needed", 3, retries.size)
            assertTrue("Second reconnect waits at least five seconds", retries[1] - retries[0] >= 5_000)
            assertTrue("Third reconnect waits at least ten seconds", retries[2] - retries[1] >= 10_000)
            val before = retries.size
            Thread.sleep(2_500)
            assertEquals("Healthy playback does not launch more retries", before, retries.size)
        } finally { main { activity.finish() }; server.close() }
    }
    @Test fun iptvVlcRetriesRefusedReplacementConnections() = exercise(false)
    @Test fun sportsVlcRetriesRefusedReplacementConnections() = exercise(true)
}
