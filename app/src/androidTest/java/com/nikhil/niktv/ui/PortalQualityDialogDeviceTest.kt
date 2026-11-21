package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PortalQualityDialogDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            Thread.sleep(25)
        }
        error("Quality control was not found")
    }
    private fun text(label: String) = awaitNode { it.text?.toString() == label }
    private fun click(label: String) {
        var target = text(label)
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
    }
    @Test fun phoneTouchCanChooseQualityAndBestAvailable() = fixture(false) { selected ->
        click("Hindi / 720p")
        assertEquals("720", selected.get())
        text("Switching quality…")
    }
    @Test fun phoneTouchCanChooseBestAvailable() = fixture(false) { selected ->
        click("✓ Best available")
        assertEquals("best", selected.get())
        text("Switching quality…")
    }
    @Test fun televisionDpadStartsAtSelectedQualityAndKeepsFocusWhileResolving() = fixture(true) { selected ->
        val first = awaitNode { it.isFocused }
        assertTrue("Default Best available receives entry focus", contains(first, "✓ Best available"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitNode { it.isFocused && contains(it, "Hindi / 720p") }
        NavigationAuditEvidence.capture("portal-quality-tv-focus")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        text("Switching quality…")
        assertEquals("720", selected.get())
        assertTrue(contains(awaitNode { it.isFocused }, "Hindi / 720p"))
    }
    @Test fun televisionManualSelectionReceivesEntryFocus() = fixture(true, 720) {
        awaitNode { it.isFocused && contains(it, "✓ Hindi / 720p") }
        NavigationAuditEvidence.capture("portal-quality-tv-manual-entry")
    }
    private fun contains(node: AccessibilityNodeInfo, label: String): Boolean =
        find(node) { it.text?.toString() == label } != null

    private fun fixture(tv: Boolean, preferredHeight: Int = 0, verify: (AtomicReference<String>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val selected = AtomicReference("")
        instrumentation.setInTouchMode(!tv)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        if (tv) { uiMode = Configuration.UI_MODE_TYPE_TELEVISION; screenWidthDp = 960; screenHeightDp = 540 }
                    }
                    val variants = listOf(
                        PortalPlaybackVariant("1080", "Hindi / 1080p", 1080, "Hindi", "file1080"),
                        PortalPlaybackVariant("720", "Hindi / 720p", 720, "Hindi", "file720"))
                    var state by remember { mutableStateOf(PortalQualityPickerState(PlayingMedia(
                        MediaItem("fixture", "Episode", null, "file"), "http://127.0.0.1/fixture", CatalogType.SERIES,
                        portalQuality = PortalPlaybackResolution("", variants,
                            if (preferredHeight > 0) preferredHeight.toString() else "1080", preferredHeight)), 120_000)) }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        MaterialTheme(colorScheme = NikColors) {
                            PortalQualityDialog(state, {}, { id -> selected.set(id ?: "best"); state = state.copy(loading = true) })
                        }
                    }
                }
            }
            text(if (preferredHeight == 0) "✓ Best available" else "Best available")
            verify(selected)
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            instrumentation.setInTouchMode(true)
        }
    }
}
