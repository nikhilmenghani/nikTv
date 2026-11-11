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
    private fun sameRow(a: String, b: String) {
        // A preceding touch test can still be completing its IME resize animation.
        await("$a sits beside $b") {
            val nodes = all()
            val first = nodes.firstOrNull { it.text?.toString() == a }
            val second = nodes.firstOrNull { it.text?.toString() == b }
            first != null && second != null && kotlin.math.abs(bounds(first).centerY() - bounds(second).centerY()) < 12
        }
    }
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
    private fun fixture(touchMode: Boolean = false, test: (PlaybackFixtureHostActivity) -> Unit) {
        check(instrument.targetContext.packageName.endsWith(".regression"))
        instrument.setInTouchMode(touchMode)
        val oldFlags = instrument.uiAutomation.serviceInfo.flags
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try { test(activity) } finally {
            instrument.runOnMainSync { activity.finish() }; instrument.setInTouchMode(true)
            instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply { flags = oldFlags }
        }
    }
    private fun tap(target: AccessibilityNodeInfo) {
        val rect = bounds(target)
        val downAt = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrument.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            Thread.sleep(60)
        }
    }
    private fun softDigits(digits: String) {
        var typed = ""
        for (digit in digits) {
            val ime = instrument.uiAutomation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val keys = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?) { node ?: return; keys += node; for (i in 0 until node.childCount) walk(node.getChild(i)) }
            walk(ime.root)
            val target = keys.firstOrNull { it.text?.toString() == digit.toString() || it.contentDescription?.toString() == digit.toString() }
            assertNotNull("Keyboard key $digit is accessible: ${keys.mapNotNull { it.text ?: it.contentDescription }}", target)
            tap(requireNotNull(target))
            typed += digit
            await("Soft keyboard enters $typed") { find(typed) != null }
        }
    }
    @Test fun nearbyDevicePairingCodeAcceptsTouchAndKeyboardInput() = fixture(touchMode = true) { activity ->
        var pairedCode: String? = null
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(LanPlaybackState(devices = listOf(LanDevice("Nearby tablet", "192.168.1.2", 1234))),
                {}, {}, {}, { _, code -> pairedCode = code }, {})
        } } }
        await("Nearby receiver appears") { find("Nearby tablet") != null }
        tap(button("Nearby tablet")!!)
        await("Code field appears") { all().any { it.className == "android.widget.EditText" } }
        tap(all().single { it.className == "android.widget.EditText" })
        // An immediate focus assertion misses a subsequent loss during IME resize.
        Thread.sleep(700)
        assertTrue("Touch keeps the code field focused after keyboard animation",
            all().single { it.className == "android.widget.EditText" }.isFocused)
        await("Touch opens the on-screen keyboard") {
            instrument.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        softDigits("123456")
        assertTrue("Code field retains focus while typing", all().single { it.className == "android.widget.EditText" }.isFocused)
        await("All six digits reach the code field") { find("123456") != null }
        tap(button("Connect")!!)
        await("Connect receives the touch-entered code") { pairedCode == "123456" }
    }
    @Test fun manualAddressPortAndCodeAcceptTouchFromFreshDialog() = fixture(touchMode = true) { activity ->
        var paired: Pair<LanDevice, String>? = null
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(LanPlaybackState(), {}, {}, {}, { device, code -> paired = device to code }, {})
        } } }
        await("Manual connection action appears") { find("Connect by address") != null }
        tap(button("Connect by address")!!)
        await("All manual fields appear") { all().count { it.className == "android.widget.EditText" } == 3 }
        tap(all().filter { it.className == "android.widget.EditText" }[0])
        Thread.sleep(700)
        assertTrue("Touch focuses IP address", all().filter { it.className == "android.widget.EditText" }[0].isFocused)
        // Hardware typing after touch must not enable a new redirect to Done.
        instrument.sendStringSync("192.168.1.2")
        await("IP address retains typed text") { find("192.168.1.2") != null }
        assertTrue("IP retains focus through input-mode changes", all().filter { it.className == "android.widget.EditText" }[0].isFocused)
        instrument.setInTouchMode(true)
        tap(all().filter { it.className == "android.widget.EditText" }[1])
        Thread.sleep(500)
        assertTrue("Touch focuses Port", all().filter { it.className == "android.widget.EditText" }[1].isFocused)
        softDigits("1234")
        tap(all().filter { it.className == "android.widget.EditText" }[2])
        Thread.sleep(500)
        assertTrue("Touch focuses pairing code", all().filter { it.className == "android.widget.EditText" }[2].isFocused)
        softDigits("654321")
        tap(button("Connect")!!)
        await("Complete manual connection uses every entered field") { paired != null }
        assertEquals("192.168.1.2", paired!!.first.host)
        assertEquals(1234, paired!!.first.port)
        assertEquals("654321", paired!!.second)
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

    @Test fun nearbyPairingFitsThePageWithCodeAndFooterVisible() = fixture(touchMode = true) { activity ->
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(LanPlaybackState(devices = listOf(
                LanDevice("Tablet receiver", "192.168.1.2", 1234), LanDevice("TV receiver", "192.168.1.3", 1234))),
                {}, {}, {}, { _, _ -> }, {})
        } } }
        await("Nearby devices appear") { button("Tablet receiver") != null }
        click("Tablet receiver")
        await("Code entry is available") { all().count { it.className == "android.widget.EditText" } == 1 }
        val root = bounds(instrument.uiAutomation.rootInActiveWindow)
        for (name in listOf("Tablet receiver", "TV receiver", "Connect by address", "Receive on this device", "Done")) {
            val target = requireNotNull(button(name))
            assertTrue("$name stays visible on the same page", target.isVisibleToUser && root.contains(bounds(target)))
        }
        assertTrue("Pairing code stays on the same page", root.contains(bounds(all().single { it.className == "android.widget.EditText" })))
        screenshot("nearby-compact-page")
    }

    @Test fun connectedIconControlsFollowDpadOrderAndKeepTheFooterVisible() = fixture { activity ->
        val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
        instrument.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            LanPlaybackDialog(LanPlaybackState(target = LanTarget("Tablet receiver", "192.168.1.2", 1234, "fixture"),
                remoteVideo = "Remote movie fixture"), {}, {}, { actions += "disconnect" }, { _, _ -> }, { actions += it })
        } } }
        await("Connected remote controls appear") { button("Pause") != null && focused("Done") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up enters Pause") { focused("Pause") }
        for ((label, action) in listOf("Pause" to "pause", "Resume" to "resume", "Stop" to "stop", "Playback status" to "status")) {
            await("D-pad reaches $label") { focused(label) }
            val buttonBounds = bounds(requireNotNull(button(label)))
            val density = instrument.targetContext.resources.displayMetrics.density
            assertTrue("$label retains a 48dp touch target", buttonBounds.width() >= 47 * density && buttonBounds.height() >= 47 * density)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("$label dispatches its own command") { actions.lastOrNull() == action }
            if (label != "Playback status") key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down enters Disconnect") { focused("Disconnect") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right returns to Done") { focused("Done") }
        sameRow("Disconnect", "Done")
        screenshot("connected-compact-controls")
    }
}
