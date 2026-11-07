package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.NavigationAuditEvidence
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackEngineFixtureTest
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise real Media3 HTTP failures and rendered video through Sports' production callbacks. */
class SportsAuthorizationDeviceTest {
    @Test fun live403FetchesReplacementHeadersAndRendersTheSameSource() = exercise(renewed = true)
    @Test fun unchangedRejectedLinkStopsAfterOneRefreshWithoutPlayingAnotherSource() = exercise(renewed = false)

    private fun exercise(renewed: Boolean) = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val prefs = SportsPlaybackPreferences(context)
        val original = prefs.settings.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var activity: SportsFixtureHostActivity? = null
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < end) {
                if (condition()) return
                SystemClock.sleep(70)
            }
            NavigationAuditEvidence.capture("sports-authorization-failure")
            fail(label)
        }
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            prefs.setPlaybackEngine(PlaybackEngine.MEDIA3)
            prefs.setPlayerControlsTimeoutSeconds(0)
            PlaybackEngineFixtureTest().FixtureServer().use { server ->
                server.requiredHeaders = mapOf("X-Fixture-Authorization" to "fresh")
                val rejected = SportsStream("${server.base}/live-dvr", "Selected event",
                    headers = mapOf("X-Fixture-Authorization" to "expired"))
                val replacement = rejected.copy(headers = mapOf("X-Fixture-Authorization" to "fresh"))
                val unrelated = replacement.copy(title = "Unrequested source", link = "${server.base}/movie.mp4")
                val loads = AtomicInteger()
                val frames = AtomicInteger()
                val renderedAttempt = AtomicLong()
                val refreshes = AtomicInteger()
                val controller = SportsPlaybackController(scope, {
                    loads.incrementAndGet()
                    if (renewed) listOf(unrelated, replacement) else listOf(rejected, unrelated)
                }, { it })
                val host = ins.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
                activity = host
                ins.runOnMainSync {
                    controller.start("event", listOf(rejected, unrelated))
                    host.setContent {
                        val state by controller.state.collectAsState()
                        MaterialTheme(colorScheme = NikColors) {
                            state.current?.let { stream ->
                                val attempt = state.attemptId
                                SportsPlayer(stream, "Authorization fixture", {},
                                    sourceAttemptId = attempt, streams = state.sources,
                                    selectedIndex = state.currentIndex ?: -1,
                                    onVideoFrameRendered = { renderedAttempt.set(attempt) },
                                    onVideoFramesRendered = { frames.set(it) },
                                    onRetryFresh = controller::retry,
                                    onAuthorizationFailure = { refreshes.incrementAndGet(); controller.authorizationFailed(attempt) },
                                    onTerminalFailure = { controller.failed(attempt) },
                                    onCancelPendingFallback = controller::cancelPendingFallback)
                            }
                            state.error?.let { error ->
                                SportsPlaybackErrorDialog(error, true, controller::retry, {}, controller::dismissError)
                            }
                        }
                    }
                }
                if (renewed) {
                    await("403 obtains new credentials and renders real video") {
                        loads.get() == 1 && controller.state.value.current == replacement && frames.get() > 30
                    }
                    val before = frames.get()
                    await("Replacement video continues to advance") { frames.get() > before + 30 }
                    assertEquals(1, controller.state.value.currentIndex)
                    assertEquals(2, controller.state.value.attempts)
                    assertNull(controller.state.value.error)
                    NavigationAuditEvidence.capture("sports-authorization-renewed")
                } else {
                    await("Unchanged rejected credentials produce a bounded provider error") {
                        controller.state.value.error == SPORTS_AUTHORIZATION_MESSAGE
                    }
                    val requests = synchronized(server.paths) { server.paths.size }
                    SystemClock.sleep(2_000)
                    assertEquals("No further network attempts after exhaustion", requests, synchronized(server.paths) { server.paths.size })
                    assertEquals(rejected, controller.state.value.current)
                    assertEquals(0, frames.get())
                    NavigationAuditEvidence.capture("sports-authorization-unchanged")
                }
                assertTrue("The player really received HTTP 403", server.rejected.get() > 0)
                assertEquals("One automatic metadata refresh", 1, loads.get())
                assertEquals("One automatic authorization callback", 1, refreshes.get())
                if (renewed) {
                    fun find(label: String): android.view.accessibility.AccessibilityNodeInfo? {
                        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
                        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                            node ?: return null
                            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
                            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                            return null
                        }
                        return visit(ins.uiAutomation.rootInActiveWindow)
                    }
                    fun click(label: String) {
                        var node = find(label)
                        repeat(8) { if (node?.isClickable != true) node = node?.parent }
                        assertTrue("Clickable $label", node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
                    }
                    val metrics = context.resources.displayMetrics
                    val touch = SystemClock.uptimeMillis()
                    for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(touch, touch, action, metrics.widthPixels * .5f, metrics.heightPixels * .4f, 0)
                        try { ins.sendPointerSync(event) } finally { event.recycle() }
                    }
                    await("Player controls expose refresh") { find("Refresh stream") != null || find("More controls") != null }
                    if (find("Refresh stream") == null) { click("More controls"); await("Refresh action is visible") { find("Refresh stream") != null } }
                    val previousAttempt = controller.state.value.attemptId
                    click("Refresh stream")
                    await("Manual refresh fetches source metadata and renders a new session") {
                        loads.get() == 2 && controller.state.value.attemptId > previousAttempt &&
                            renderedAttempt.get() == controller.state.value.attemptId && frames.get() > 30
                    }
                }
                ins.runOnMainSync { controller.stop(); host.setContent {} }
            }
        } finally {
            ins.runOnMainSync { activity?.setContent {}; activity?.finish(); scope.cancel() }
            prefs.setPlaybackEngine(original.engine)
            prefs.setPlayerControlsTimeoutSeconds(original.timeout)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
