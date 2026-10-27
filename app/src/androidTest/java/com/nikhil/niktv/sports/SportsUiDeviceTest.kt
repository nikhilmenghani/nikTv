package com.nikhil.niktv.sports

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Isolated presentation tests: fake events, no source lookup, profile access or audio. */
class SportsUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        assertTrue(message, condition())
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String) {
        var node = find(text)
        repeat(8) { if (node?.isClickable != true) node = node?.parent }
        assertTrue("Reachable action: $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun focused(text: String): Boolean {
        var node = find(text)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun host(): SportsFixtureHostActivity {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        return instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
    }

    @Test fun sportsCatalogueAndSourceCardsSupportTouchAndDpadWithoutBackgroundPlayback() {
        val activity = host()
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val cricket = SportsEvent("1", "India vs New Zealand", "fixture-cricket", "Cricket", "Fixture international", "", "India", "New Zealand",
            now.minusSeconds(60), now.plusSeconds(3600), listOf("English", "Hindi"))
        val football = cricket.copy(id = "2", title = "United vs City", slug = "fixture-football", sport = "Football", teamA = "United", teamB = "City")
        val model = mutableStateOf(SportsState(events = listOf(cricket, football), loading = false))
        var picked = -1
        var refreshes = 0
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                var sport by remember { mutableStateOf("All sports") }
                var status by remember { mutableStateOf("Now & next") }
                var query by remember { mutableStateOf("") }
                SportsBrowseContent(model.value, now, sport, { sport = it }, status, { status = it }, query, { query = it },
                    back = { model.value = model.value.copy(event = null, streams = emptyList(), resolving = null) },
                    refresh = { refreshes++ }, open = { event -> model.value = model.value.copy(event = event,
                        streams = listOf(SportsStream("https://fixture.invalid/english", "English commentary"),
                            SportsStream("https://fixture.invalid/hindi", "Hindi commentary"))) }, play = { picked = it; model.value = model.value.copy(resolving = it) })
            } } }
            await("Catalogue renders with a stable header focus") { focused("Back") && find("India vs New Zealand") != null }
            assertTrue("Browsing Sports keeps the display awake", activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertEquals(-1, picked)
            click("Search sports")
            await("Search opens on explicit activation") { find("Find a team or event") != null }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable) return node
                for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                return null
            }
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "India") }
            assertTrue(requireNotNull(editable(root)).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            await("Search is local and filters events") { find("1 event") != null && find("United vs City") == null }
            click("Close sports search")
            click("India vs New Zealand")
            await("Compact stream cards appear") { find("English commentary") != null && find("Hindi commentary") != null }
            repeat(8) {
                if (!focused("English commentary")) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                Thread.sleep(100)
            }
            await("D-pad reaches the first stream without selecting it") { focused("English commentary") }
            assertEquals(-1, picked)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK chooses the focused source and retains focus during resolution") { picked == 0 && focused("English commentary") && find("Preparing stream…") != null }
            assertEquals(0, refreshes)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun brightnessAndVolumeTextStayWhiteEvenWithAnInheritedBlackContentColor() {
        val activity = host()
        val brightness = mutableStateOf(true)
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        PlayerLevelFeedback(brightness.value, .5f, compact = false)
                    }
                }
            } } }
            for (label in listOf("Brightness", "Volume")) {
                instrumentation.runOnMainSync { brightness.value = label == "Brightness" }
                await("Gesture label is visible: $label") { find(label) != null }
                instrumentation.waitForIdleSync()
                val bounds = Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var white = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height))
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                            val pixel = bitmap.getPixel(x, y)
                            if (android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220) white++
                        }
                    assertTrue("$label text has an explicit high-contrast white foreground", white > 5)
                } finally { bitmap.recycle() }
            }
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            await("Leaving Sports releases its keep-awake flag") { activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0 }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
