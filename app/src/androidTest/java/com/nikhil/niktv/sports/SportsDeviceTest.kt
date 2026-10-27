package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SportsDeviceTest {
    @Test fun liveCatalogueAndNativePlayback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Device test requires the isolated regression application." }
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        try {
            lateinit var vm: SportsViewModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[SportsViewModel::class.java] }
            await(45_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error)
            val events = vm.state.value.events
            assertTrue("Remote catalogue should contain events", events.isNotEmpty())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue("Sports activity is displayed", activity.window.decorView.isShown) }
            val event = events.firstOrNull { it.title.contains("India", true) &&
                (it.title.contains("New Zealand", true) || it.title.contains("NZ", true)) }
                ?: error("The user-requested India/New Zealand match is not currently listed")
            instrumentation.runOnMainSync { vm.open(event) }
            await(30_000) { !vm.state.value.loadingStreams }
            assertNull(vm.state.value.streamError)
            assertTrue("Selected event should have stream choices", vm.state.value.streams.isNotEmpty())
            val indexed = vm.state.value.streams.withIndex().toList()
            val choices = indexed.filter { it.value.type == 7 }.take(2) + indexed.filter { it.value.type == 0 }.take(2)
            assertTrue("A native stream is available", choices.isNotEmpty())
            val outcomes = mutableListOf<String>()
            var rendered = false
            for (choice in choices) {
                instrumentation.runOnMainSync { vm.play(choice.index) }
                await(35_000) { vm.state.value.resolving == null }
                val stream = vm.state.value.playing
                if (stream == null) { outcomes += "type ${choice.value.type}: resolver failure"; continue }
                android.util.Log.i("NikTvSportsTest", "Match source type=${stream.type}; DRM=${stream.drmType.ifBlank { "none" }}")
                val latch = CountDownLatch(1)
                var firstFrame = false
                var exited = false
                instrumentation.runOnMainSync {
                    activity.setContent { MaterialTheme(colorScheme = NikColors) {
                        SportsPlayer(stream, event.title, onBack = { exited = true }, onVideoFrameRendered = {
                            firstFrame = true; latch.countDown()
                        })
                    } }
                }
                latch.await(25, TimeUnit.SECONDS)
                if (firstFrame) {
                    SystemClock.sleep(3_000)
                    fun has(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
                        if (node == null) return false
                        if (node.contentDescription?.toString() == text || node.text?.toString() == text) return true
                        return (0 until node.childCount).any { has(node.getChild(it), text) }
                    }
                    // Touch reveals NikTV's overlay; the video remains full-screen below it.
                    val metrics = context.resources.displayMetrics
                    instrumentation.sendPointerSync(android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_DOWN, metrics.widthPixels / 2f, metrics.heightPixels / 2f, 0))
                    instrumentation.sendPointerSync(android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_UP, metrics.widthPixels / 2f, metrics.heightPixels / 2f, 0))
                    await(3_000) { has(instrumentation.uiAutomation.rootInActiveWindow, "More controls") }
                    assertTrue("The match feed exposes native rewind", has(instrumentation.uiAutomation.rootInActiveWindow, "Back 10 seconds"))
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    assertFalse("First Back hides controls, preserving playback", exited)
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    await(3_000) { has(instrumentation.uiAutomation.rootInActiveWindow, "More controls") }
                }
                instrumentation.runOnMainSync { activity.setContent { Text("Playback check complete") } }
                outcomes += "type ${stream.type}: ${if (firstFrame) "first video frame rendered; touch and D-pad controls verified" else "startup timeout"}"
                if (firstFrame) { rendered = true; break }
            }
            android.util.Log.i("NikTvSportsTest", outcomes.joinToString("; "))
            assertTrue("At least one stream must render video: ${outcomes.joinToString()}", rendered)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(150)
        assertTrue("Timed out waiting for Sports state", condition())
    }
}
