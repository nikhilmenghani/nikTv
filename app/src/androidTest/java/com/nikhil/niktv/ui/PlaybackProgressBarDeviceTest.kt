package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real shared scrubber input lifecycle; no provider, decoder, or user profile data. */
class PlaybackProgressBarDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private class Harness {
        val active = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val identity = mutableStateOf("episode:1")
        val position = mutableLongStateOf(30_000L)
        val progress = FocusRequester()
        val other = FocusRequester()
        val seeks = CopyOnWriteArrayList<Pair<String, Long>>()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(20) }
        fail(label)
    }
    private fun fixture(test: (Harness) -> Unit) {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val state = Harness()
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    if (state.visible.value) {
                        val identity = state.identity.value
                        PlaybackProgressBar(state.position.longValue, 600_000L, onSeek = { target ->
                            state.seeks += identity to target
                            state.position.longValue = target
                        }, mediaKey = identity, active = state.active.value,
                            modifier = Modifier.fillMaxWidth().focusRequester(state.progress).focusProperties { down = state.other })
                    }
                    Text("Other playback control", Modifier.focusRequester(state.other).focusable())
                }
                LaunchedEffect(Unit) { withFrameNanos { }; state.progress.requestFocus() }
            } } }
            ins.waitForIdleSync()
            test(state)
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
    private fun down(code: Int) = ins.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)

    @Test fun hidingControlsCommitsCursorButNewPlaybackAttemptDiscardsIt() = fixture { state ->
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        ins.runOnMainSync { state.active.value = false }
        await("Hidden controls commit the selected time") { state.seeks.size == 1 }
        assertEquals("episode:1" to 40_000L, state.seeks.single())
        Thread.sleep(600)
        assertEquals("Focus/visibility changes do not issue duplicate seeks", 1, state.seeks.size)
        ins.runOnMainSync { state.active.value = true }
        ins.waitForIdleSync()
        ins.runOnMainSync { state.progress.requestFocus() }
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        ins.runOnMainSync { state.identity.value = "episode:1:retry2" }
        Thread.sleep(700)
        assertEquals("A retry must not receive the previous attempt's cursor", 1, state.seeks.size)
    }

    @Test fun focusExitAndConfirmCommitTheLastHeldPreviewOnce() = fixture { state ->
        repeat(4) { down(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Focus exit commits even when direction release was lost") { state.seeks.size == 1 }
        assertEquals(70_000L, state.seeks.single().second)
        Thread.sleep(700)
        assertEquals(1, state.seeks.size)
        ins.runOnMainSync { state.progress.requestFocus() }
        down(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Confirm applies the rewind cursor") { state.seeks.size == 2 }
        assertEquals(60_000L, state.seeks.last().second)
        Thread.sleep(600)
        assertEquals("Delayed commit must not repeat a confirmed seek", 2, state.seeks.size)
    }

    @Test fun removingControlsCommitsPendingPreview() = fixture { state ->
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        ins.runOnMainSync { state.visible.value = false }
        await("Removing the overlay still applies its final cursor") { state.seeks.size == 1 }
        assertEquals("episode:1" to 40_000L, state.seeks.single())
    }
}
