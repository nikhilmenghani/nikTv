package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackEngineFixtureTest
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local silent fixture, no catalogue audit or provider credentials required. */
class SportsFallbackDeviceTest {
    @Test fun unsupportedSourceWaitsForForegroundBeforeRequestingFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        val callbacks = AtomicInteger()
        fun await(label: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(50) }
            fail(label)
        }
        try {
            lateinit var settings: SportsPlaybackSettingsViewModel
            instrumentation.runOnMainSync { settings = androidx.lifecycle.ViewModelProvider(host)[SportsPlaybackSettingsViewModel::class.java] }
            await("Sports settings load") { settings.settings.value != null }
            instrumentation.runOnMainSync { host.moveTaskToBack(true) }
            await("Fixture moves to background") { !host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            instrumentation.runOnMainSync { host.setContent {
                MaterialTheme(colorScheme = NikColors) {
                    SportsPlayer(SportsStream("https://example.test/unsupported.mpd", "Unsupported fixture", drmType = "unsupported-fixture"),
                        "Fixture", {}, onTerminalFailure = { callbacks.incrementAndGet() })
                }
            } }
            SystemClock.sleep(500)
            assertEquals("Background source must not start fallback", 0, callbacks.get())
            instrumentation.runOnMainSync { context.startActivity(Intent(context, SportsFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await("Foreground error reports once") { callbacks.get() == 1 }
            SystemClock.sleep(300)
            assertEquals(1, callbacks.get())
        } finally { instrumentation.runOnMainSync { host.setContent {}; host.finish() } }
    }

    @Test fun failedNativeSourceAdvancesAndFreshRetryKeepsPictureUntilCommit() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val preferences = SportsPlaybackPreferences(context)
        val old = preferences.settings.first()
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var activity: SportsFixtureHostActivity? = null
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            preferences.setPlaybackEngine(PlaybackEngine.MEDIA3)
            preferences.setPlayerControlsTimeoutSeconds(0)
            PlaybackEngineFixtureTest().FixtureServer().use { server ->
                val broken = SportsStream("${server.base}/missing.mp4", "Broken fixture")
                val working = SportsStream("${server.base}/movie.mp4", "Working fixture")
                val retryGate = CompletableDeferred<Unit>()
                var refreshing = false
                var metadataLoads = 0
                val controller = SportsPlaybackController(scope, {
                    metadataLoads++; refreshing = true; listOf(working, broken)
                }, { source -> if (refreshing) retryGate.await(); source })
                val frames = AtomicInteger()
                val renderedFrames = AtomicInteger()
                val host = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
                activity = host
                fun await(label: String, condition: () -> Boolean) {
                    val until = SystemClock.elapsedRealtime() + 25_000
                    while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(50) }
                    fail(label)
                }
                instrumentation.runOnMainSync {
                    controller.start("fixture", listOf(broken, working))
                    host.setContent {
                        val state by controller.state.collectAsState()
                        val stream = state.current
                        if (stream != null) {
                            val attempt = state.attemptId
                            MaterialTheme(colorScheme = NikColors) {
                                SportsPlayer(stream, "Fallback fixture", {}, onVideoFrameRendered = { frames.incrementAndGet() },
                                    streams = state.sources, selectedIndex = state.currentIndex ?: -1,
                                    onVideoFramesRendered = { renderedFrames.set(it) },
                                    sourceAttemptId = attempt, onSelectStream = { controller.start("fixture", state.sources, it) },
                                    onTerminalFailure = { controller.failed(attempt) }, onRetryFresh = controller::retry,
                                    onCancelPendingFallback = controller::cancelPendingFallback)
                            }
                        }
                    }
                }
                await("Working source renders after the final native error") {
                    controller.state.value.currentIndex == 1 && frames.get() > 0
                }
                assertEquals(2, controller.state.value.attempts)
                val before = frames.get()
                val renderedBefore = renderedFrames.get()
                val oldAttempt = controller.state.value.attemptId
                instrumentation.runOnMainSync { controller.retry() }
                await("Fresh metadata reorders the queue") { metadataLoads == 1 && controller.state.value.currentIndex == 0 }
                SystemClock.sleep(1_500)
                assertEquals("Metadata-only update must not recreate the active decoder", before, frames.get())
                assertTrue("Active video advances while fresh metadata resolves", renderedFrames.get() > renderedBefore)
                assertEquals(oldAttempt, controller.state.value.attemptId)
                assertTrue(controller.state.value.resolving)
                retryGate.complete(Unit)
                await("Same-URL fresh retry commits a new rendered session") {
                    controller.state.value.attemptId != oldAttempt && frames.get() > before
                }
                assertEquals(1, metadataLoads)
                assertNull(controller.state.value.error)
                instrumentation.runOnMainSync { controller.stop() }
            }
        } finally {
            instrumentation.runOnMainSync { activity?.setContent {}; activity?.finish(); scope.cancel() }
            preferences.setPlaybackEngine(old.engine); preferences.setPlayerControlsTimeoutSeconds(old.timeout)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
