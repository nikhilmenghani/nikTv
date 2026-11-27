package com.nikhil.niktv.torrent

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorrentPreparationDialogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun root() = instrumentation.uiAutomation.rootInActiveWindow
    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (match(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
        return null
    }
    private fun label(value: String) = find(root()) { it.text?.toString() == value }
    private fun file(index: Int) = find(root()) { it.contentDescription?.toString()?.startsWith("Select file: folder-$index/") == true }
    private fun owner(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.first { it.isClickable }
    private fun focused(node: AccessibilityNodeInfo?) = node?.let { owner(it).isFocused } == true
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(50) }
        fail(message)
    }
    private fun key(value: Int) = instrumentation.sendKeyDownUpSync(value)

    @Test fun compactAndScrollableFilesRetainFocusAcrossPreparationOnPhoneTabletAndTvLayouts() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val files = (0..11).map { TorrentVideoFile(it, "folder-$it/The.Avengers.2012.1080p.BluRay.x264.YIFY.mp4", 2_200_000_000L) }
        val app = mutableStateOf(TorrentPortalState(loading = "Checking saved parts", files = files.take(1),
            transfer = TorrentStreamStatus(stage = "Checking downloaded parts", downloadedBytes = 16_000_000L, totalBytes = 2_200_000_000L)))
        var mode by mutableIntStateOf(0)
        var selected = -1
        var cancelled = 0
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val real = LocalConfiguration.current
                val config = Configuration(real).apply {
                    screenWidthDp = if (mode == 0) 360 else 1200
                    screenHeightDp = if (mode == 0) 780 else if (mode == 1) 750 else 360
                    if (mode == 2) uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    key(mode) { TorrentPreparationDialog(app.value, { cancelled++ }, {}, { selected = it.index }) }
                }
            } } }
            for (layout in 0..2) {
                instrumentation.runOnMainSync { mode = layout; app.value = app.value.copy(files = files.take(1), error = null, loading = "Checking saved parts") }
                await("Cancel owns initial focus in layout $layout") { focused(label("Cancel")) }
                await("Single file is present") { file(0) != null }
                val title = bounds(label("Preparing stream")!!)
                val cancel = bounds(owner(label("Cancel")!!))
                val card = bounds(owner(file(0)!!))
                val density = host.resources.displayMetrics.density
                assertTrue("No tall blank area below one file: $title / $cancel", cancel.bottom - title.top < 330 * density)
                assertTrue("Cancel aligns to the trailing edge", cancel.right >= card.right - 8 * density)
                instrumentation.uiAutomation.takeScreenshot().let { image ->
                    File(host.externalCacheDir, "torrent-compact-single-$layout.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                instrumentation.runOnMainSync { app.value = app.value.copy(files = files, loading = null) }
                await("Adding files preserves Cancel focus") { label("Choose video") != null && focused(label("Cancel")) }
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("Up reaches first file") { focused(file(0)) }
                for (index in 1..7) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Down scrolls to file $index in layout $layout") { focused(file(index)) }
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches always-visible Cancel") { focused(label("Cancel")) }
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("Up returns to the same file") { focused(file(7)) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("OK selects the correct file once") { selected == 7 }
                instrumentation.runOnMainSync { app.value = app.value.copy(loading = "Buffering video", transfer = app.value.transfer?.copy(stage = "Buffering video")) }
                await("Preparation keeps the selected file focused") { label("Preparing stream") != null && focused(file(7)) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Cancel remains reachable during preparation") { focused(label("Cancel")) }
                instrumentation.runOnMainSync { app.value = app.value.copy(error = "Temporary connection failure") }
                await("Retry appearing does not steal focus") { label("Retry") != null && focused(label("Cancel")) }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Left reaches Retry") { focused(label("Retry")) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right returns to Cancel") { focused(label("Cancel")) }
                instrumentation.uiAutomation.takeScreenshot().let { image ->
                    File(host.externalCacheDir, "torrent-compact-multiple-$layout.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
            }
            key(KeyEvent.KEYCODE_BACK)
            await("Back cancels the preparation") { cancelled == 1 }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
