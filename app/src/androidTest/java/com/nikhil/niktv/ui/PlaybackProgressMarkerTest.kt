package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaybackProgressMarkerTest {
    @Test fun timestampTracksDpadAndTouchInsteadOfStayingAtEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val position = mutableLongStateOf(90_000L)
        val active = mutableStateOf(true)
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
            return null
        }
        fun await(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(100) }
            fail(label)
        }
        fun snapshot(name: String) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        val requester = remember { FocusRequester() }
                        Box(Modifier.fillMaxSize().padding(32.dp)) {
                            PlaybackProgressBar(position.longValue, 360_000L, { position.longValue = it },
                                "timestamp-fixture", active = active.value, modifier = Modifier.focusRequester(requester))
                        }
                        LaunchedEffect(Unit) { requester.requestFocus() }
                    }
                }
            }
            await("Elapsed timestamp must be exposed") {
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.contentDescription?.contains("Current playback time 1:30") == true } != null
            }
            val slider = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.rangeInfo != null })
            assertEquals(.25f, (slider.rangeInfo.current - slider.rangeInfo.min) / (slider.rangeInfo.max - slider.rangeInfo.min), .01f)
            snapshot("progress-quarter.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Dpad seek must commit once") { position.longValue == 100_000L }
            val bounds = Rect().also(slider::getBoundsInScreen)
            val x = bounds.left + bounds.width() * .75f
            val y = bounds.exactCenterY()
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            await("Touch seek must use the touched position") { position.longValue in 240_000L..300_000L }
            snapshot("progress-three-quarters.png")
            instrumentation.runOnMainSync { active.value = false }
            Thread.sleep(300)
            val before = position.longValue
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(700)
            assertEquals("Inactive controls must not seek", before, position.longValue)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
