package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Isolated D-pad season matrix; no accounts, providers, network or playback. */
class SeriesSeasonSidebarDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        return node != null && node == root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(40) }
        capture("failure")
        fail(message)
    }
    private fun capture(suffix: String) {
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(ins.targetContext.getExternalFilesDir(null), "series-sidebar-$suffix.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    @Test fun oneTwoThreeElevenSixteenAndTwentyFiveSeasonSidebarsRemainReachable() = verify(false, listOf(1, 2, 3, 11, 16, 25))
    @Test fun longHorizontalSeasonStripShowsSelectionAndNavigatesBothDirections() = verify(true, listOf(2, 16, 25))
    private fun verify(horizontal: Boolean, counts: List<Int>) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val count = mutableIntStateOf(counts.first())
        val selected = CopyOnWriteArrayList<Int>()
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                key(count.intValue) {
                    val amount = count.intValue
                    var chosen by remember { mutableIntStateOf(amount) }
                    val entry = remember { FocusRequester() }
                    val toolbar = remember { FocusRequester() }
                    val episode = remember { FocusRequester() }
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        NikTvSecondaryActionButton({}, Modifier.focusRequester(toolbar).focusProperties { down = entry }) { Text("Episode toolbar fixture") }
                        Row(Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SeriesSeasonSidebar((amount downTo 1).toList(), chosen, emptyMap(), entry,
                                select = { chosen = it; selected.add(it) }, right = { episode.requestFocus() }, up = { toolbar.requestFocus() },
                                modifier = if (horizontal) Modifier.weight(1f) else Modifier.width(156.dp).fillMaxHeight(), horizontal = horizontal)
                            NikTvSecondaryActionButton({}, Modifier.focusRequester(episode).onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { entry.requestFocus(); true } else false
                            }) { Text("Episode fixture") }
                        }
                    }
                    LaunchedEffect(amount) { repeat(30) { withFrameNanos { }; if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
                }
            } } } }
            for (amount in counts) {
                selected.clear()
                ins.runOnMainSync { count.intValue = amount }
                await("Selected season $amount is initially focused") { focused("Season $amount") }
                if (amount > 1) await("Preceding season remains visible beside selected season $amount") { find("Season ${amount - 1}")?.isVisibleToUser == true }
                capture("${if (horizontal) "horizontal" else "vertical"}-$amount-initial")
                repeat(amount - 1) { key(if (horizontal) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP) }
                await("All $amount seasons can be traversed back to the first") { focused("Season 1") }
                assertTrue("Focus alone must not select/fetch seasons", selected.isEmpty())
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Explicit OK selects first season") { selected.lastOrNull() == 1 && focused("Season 1") }
                key(if (horizontal) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Season entry reaches episodes") { focused("Episode fixture") }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Episodes return to selected Season 1") { focused("Season 1") }
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("First season Up reaches toolbar") { focused("Episode toolbar fixture") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Toolbar Down returns to selected Season 1") { focused("Season 1") }
                repeat(amount - 1) { key(if (horizontal) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN) }
                await("Last season remains reachable after selection") { focused("Season $amount") }
            }
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
