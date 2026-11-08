package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs only in the isolated test package; no account or IPTV credentials are read. */
class LanPlaybackDesignDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(40) }
        assertTrue(label, predicate())
    }
    private fun all(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { n ?: return; out += n; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(instrument.uiAutomation.rootInActiveWindow)
        return out
    }
    private fun find(label: String) = all().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun button(label: String): AccessibilityNodeInfo? {
        var n = find(label)
        repeat(10) { if (n?.isClickable == true) return n; n = n?.parent }
        return null
    }
    private fun click(label: String) { await("Missing $label") { button(label) != null }; assertTrue(button(label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun sameRow(a: String, b: String) { assertTrue("$a sits beside $b", kotlin.math.abs(bounds(find(a)!!).centerY() - bounds(find(b)!!).centerY()) < 12) }
    private fun focused(label: String): Boolean {
        var n = find(label)
        repeat(10) { if (n?.isFocused == true) return true; n = n?.parent }
        return false
    }
    private fun key(k: Int) { instrument.sendKeyDownUpSync(k); Thread.sleep(150) }
    private fun screenshot(name: String) {
        val target = File(instrument.targetContext.getExternalFilesDir(null), "lan-$name.png")
        val bitmap = requireNotNull(instrument.uiAutomation.takeScreenshot())
        try { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun fixture(test: (PlaybackFixtureHostActivity) -> Unit) {
        check(instrument.targetContext.packageName.endsWith(".regression"))
        instrument.setInTouchMode(false)
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try { test(activity) } finally { instrument.runOnMainSync { activity.finish() }; instrument.setInTouchMode(true) }
    }
    @Test fun mobileAndTabletPairingFormsFitWithFooterAndDpadPaths() = fixture { activity ->
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(LanPlaybackState(devices = listOf(LanDevice("Tablet receiver", "192.168.1.2", 1234))), {}, {}, {}, { _, _ -> }, {})
        } } }
        await("Dialog opens") { find("Connect by address") != null }
        click("Connect by address")
        await("Three fields visible") { all().count { it.className == "android.widget.EditText" } == 3 }
        instrument.setInTouchMode(true)
        val fieldBounds = bounds(all().first { it.className == "android.widget.EditText" })
        val downAt = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                fieldBounds.centerX().toFloat(), fieldBounds.centerY().toFloat(), 0)
            try { assertTrue(instrument.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        await("Touch focuses IP field") { all().firstOrNull { it.className == "android.widget.EditText" }?.isFocused == true }
        key(KeyEvent.KEYCODE_BACK)
        val edits = all().filter { it.className == "android.widget.EditText" }
        listOf("192.168.1.2", "1234", "123456").forEachIndexed { i, value ->
            assertTrue(edits[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        }
        await("Connect enabled") { button("Connect")?.isEnabled == true }
        sameRow("Connect", "Done")
        val fields = all().filter { it.className == "android.widget.EditText" }
        assertEquals(bounds(fields[0]).centerY(), bounds(fields[1]).centerY())
        val done = bounds(find("Done")!!)
        val root = bounds(instrument.uiAutomation.rootInActiveWindow)
        assertTrue("Footer stays on page", root.contains(done))
        screenshot("manual-form")
        all().first { it.className == "android.widget.EditText" }.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right enters the adjacent Port field") { all().filter { it.className == "android.widget.EditText" }.getOrNull(1)?.isFocused == true }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down enters the pairing code") { all().filter { it.className == "android.widget.EditText" }.getOrNull(2)?.isFocused == true }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down from code enters footer Connect") { focused("Connect") }
        // Focus is deliberately initialized on Done; footer and fields follow their visual order.
        button("Done")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left from Done enters Connect") { focused("Connect") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right returns to Done") { focused("Done") }
    }
    @Test fun receiverShowsBothPairedDevicesOwnerAndAutomaticCodeUpdate() = fixture { activity ->
        val state = mutableStateOf(LanPlaybackState(receiving = true, pin = "123456", address = "192.168.1.2", port = 1234,
            pinSecondsRemaining = 1, pairedDevices = listOf(LanPairedDevice("a", "Samsung", true), LanPairedDevice("b", "POCO", false))))
        var removed: String? = null
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(state.value, {}, {}, {}, { _, _ -> }, {}, removePaired = { removed = it })
        } } }
        await("Paired list visible") { find("Samsung") != null && find("POCO") != null }
        assertNotNull(find("Controls playback"))
        sameRow("Stop receiving", "Done")
        screenshot("receiver-two-devices")
        instrument.runOnMainSync { state.value = state.value.copy(pin = "654321", pinSecondsRemaining = 300) }
        await("New code appears without reopening dialog") { find("654 321") != null }
        assertNotNull(find("Samsung")); assertNotNull(find("POCO"))
        click("Disconnect POCO")
        assertEquals("b", removed)
        button("Done")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Stop receiving is reachable beside Done") { focused("Stop receiving") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right returns to Done") { focused("Done") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up reaches second paired device") { focused("Disconnect POCO") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up reaches first paired device") { focused("Disconnect Samsung") }
    }
}
