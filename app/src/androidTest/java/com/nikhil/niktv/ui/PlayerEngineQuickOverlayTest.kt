package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PlaybackEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlayerEngineQuickOverlayTest {
    @Test fun tabletPickerKeepsFocusAndChangesOnlyOnSelection() = exercisePicker(false)
    @Test fun phonePickerKeepsFocusAndChangesOnlyOnSelection() = exercisePicker(true)

    private fun exercisePicker(phone: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val visible = mutableStateOf(true)
        val selected = mutableStateOf(PlaybackEngine.EXOPLAYER)
        val choices = mutableListOf<PlaybackEngine>()
        var dismissals = 0
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
            return null
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fun dump(node: AccessibilityNodeInfo?): String {
                if (node == null) return "null"
                return "[${node.text}/${node.contentDescription} focus=${node.isFocused} selected=${node.isSelected}]" +
                    (0 until node.childCount).joinToString("") { dump(node.getChild(it)) }
            }
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, "player-picker-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            fail("$label; ${dump(root)}")
        }
        fun shown(label: String) = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == label } != null
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    val configuration = Configuration(context.resources.configuration).apply {
                        smallestScreenWidthDp = if (phone) 400 else 800
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        MaterialTheme {
                            if (visible.value) PlayerEngineQuickOverlay(selected.value,
                                onSelect = { choices.add(it); visible.value = false },
                                onDismiss = { dismissals++; visible.value = false })
                        }
                    }
                }
            }
            await("Player choices must appear") { shown("ExoPlayer") }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Initial Dpad confirmation must select the saved ExoPlayer alias") { choices == listOf(PlaybackEngine.MEDIA3) && !visible.value }
            await("Selection closes the chooser") { !shown("Choose player") }
            instrumentation.runOnMainSync { visible.value = true }
            await("Picker reopens") { shown("ExoPlayer") }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, if (phone) "player-picker-phone.png" else "player-picker-tablet.png")
                .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            instrumentation.sendKeyDownUpSync(if (phone) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(200)
            assertEquals("Moving Dpad focus must not select an engine", 1, choices.size)
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val navigated = if (phone) PlaybackEngine.VLC else PlaybackEngine.FLOW
            await("Dpad confirms the adjacent choice") { choices == listOf(PlaybackEngine.MEDIA3, navigated) && !visible.value }
            await("Confirmed chooser closes") { !shown("Choose player") }
            instrumentation.runOnMainSync { selected.value = PlaybackEngine.VLC; visible.value = true }
            await("Picker reopens on VLC") { shown("VLC") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back dismisses without a selection") { dismissals == 1 && !visible.value }
            await("Back closes the chooser") { !shown("Choose player") }
            assertEquals(2, choices.size)
            instrumentation.runOnMainSync { visible.value = true }
            await("Picker reopens for touch selection") { shown("Flow") }
            val flow = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Flow" })
            val bounds = Rect().also(flow::getBoundsInScreen)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            await("Touch selects exactly one player") { choices == listOf(PlaybackEngine.MEDIA3, navigated, PlaybackEngine.FLOW) && !visible.value }
            await("Touch selection closes the chooser") { !shown("Choose player") }

            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { selected.value = PlaybackEngine.MPV; visible.value = true }
            await("MPV choice is available") { shown("MPV") }
            // This chooser was opened after a touch selection. Starting remote input must
            // restore MPV, rather than leaving keyboard focus on the first (Auto) choice.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            Thread.sleep(200)
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Dpad confirms selected player once") { choices == listOf(PlaybackEngine.MEDIA3, navigated, PlaybackEngine.FLOW, PlaybackEngine.MPV) && !visible.value }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
