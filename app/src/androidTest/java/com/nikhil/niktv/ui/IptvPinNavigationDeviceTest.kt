package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.components.ModernIptvCollection
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** Real collection controls with synthetic channels; no provider/account requests. */
class IptvPinNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun node(label: String) = find(root(), label)
    private fun tile(label: String): AccessibilityNodeInfo? {
        var value = node(label)
        while (value != null && !value.isClickable) value = value.parent
        return value
    }
    private fun focused(label: String): Boolean {
        val focus = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var value = node(label)
        while (value != null) { if (value == focus) return true; value = value.parent }
        return false
    }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (test()) return; Thread.sleep(40) }
        NavigationAuditEvidence.capture("pin-navigation-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun choosePinAction(label: String) {
        val down = SystemClock.uptimeMillis()
        ins.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        ins.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        await("Channel menu exposes $label") { node(label) != null }
        repeat(24) {
            if (!focused(label)) { key(KeyEvent.KEYCODE_DPAD_DOWN); ins.waitForIdleSync() }
        }
        assertTrue("Dpad reaches $label", focused(label))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun deepPinUnpinAndExternalUpdateKeepOriginalTileAndBrowsingPosition() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600 || context.isTvLikeDevice(context.resources.configuration))
        val profile = PortalProfile("Pin navigation fixture", "https://pin-navigation.invalid", "")
        val profileKey = profile.cacheKey()
        val category = Category("pin-navigation-fixture", "Pin navigation fixture", CatalogType.LIVE_TV)
        fun title(number: Int) = "Pin fixture channel $number"
        val state = NikTvState(savedProfile = profile, selectedCategory = category, selectedType = CatalogType.LIVE_TV,
            categories = listOf(category), items = (1..120).map {
                MediaItem("pin-fixture-$it", title(it), null, null, portalCategoryId = category.id)
            }, restoring = false, loading = false, modernIptvCategoryOpenId = 193065L)
        val preferences = IptvPinPreferences.preferences(context)
        fun clearFixturePins() {
            val editor = preferences.edit()
            preferences.all.keys.filter { it.startsWith("$profileKey|") }.forEach(editor::remove)
            editor.commit()
        }
        clearFixturePins()
        for (number in 117..120) IptvPinPreferences.toggleChannel(context, profileKey, category.id, "pin-fixture-$number")
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val focusHistory = ConcurrentLinkedQueue<String>()
        try {
            ins.setInTouchMode(false)
            ins.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    event.text.filterNotNull().map(CharSequence::toString).forEach(focusHistory::add)
                    event.contentDescription?.toString()?.let(focusHistory::add)
                }
            }
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                ModernIptvCollection(state, category, close = {}, openItem = { fail("Pin navigation must not play") },
                    toggleFavorite = {}, loadMore = {}, refresh = {}, enrichFocusedMetadata = { _, _ -> },
                    enrichVisibleLiveGuides = {}, refreshLiveProgramme = {}, findChannelsInCategory = {}, cancelCategoryFind = {}, isTv = true)
            } } } }
            await("First channel gets remote focus") { focused(title(1)) }
            val firstRow = (1..4).map { number -> Rect().also { requireNotNull(tile(title(number))).getBoundsInScreen(it) } }
            assertTrue("Four channels share the first visible row", firstRow.all { it.top == firstRow.first().top && it.height() > 0 })
            IptvPinPreferences.toggleChannel(context, profileKey, category.id, "pin-fixture-4")
            await("Late pin delivery keeps the first row focused and visible") {
                focused(title(1)) && find(tile(title(4)), "Pinned channel") != null
            }
            assertEquals("The first row retains its vertical position", firstRow.first().top,
                Rect().also { requireNotNull(tile(title(1))).getBoundsInScreen(it) }.top)
            assertFalse("Pins must never take initial focus from All", focusHistory.any { it in (117..120).map(::title) })
            NavigationAuditEvidence.capture("pin-navigation-first-row")
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches the All tab") { focused("All") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches channel search") { focused("Find channel or programme") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches the availability dropdown") { focused("Availability: All channels") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches category refresh") { focused("Refresh category") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down returns to the active channel view") { focused("All") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches the Pinned tab") { focused("Pinned") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Pinned keeps tab focus and shows only shortcuts") { focused("Pinned") && tile(title(117)) != null && tile(title(1)) == null }
            NavigationAuditEvidence.capture("pin-navigation-pinned-tab")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters the first pinned channel") { focused(title(117)) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to the active tab") { focused("Pinned") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to All") { focused("All") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("All keeps tab focus and restores provider order") { focused("All") && tile(title(1)) != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down re-enters the first regular channel") { focused(title(1)) }
            clearFixturePins()
            for (row in 1..16) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Dpad reaches channel ${1 + row * 4}") { focused(title(1 + row * 4)) }
            }
            val before = Rect().also { requireNotNull(tile(title(65))).getBoundsInScreen(it) }
            NavigationAuditEvidence.capture("pin-navigation-deep-before")

            choosePinAction("Pin channel")
            await("Pin action confirms without leaving the browsing position") { node("Channel pinned") != null }
            await("Pinning retains channel 65 focus and adds a pin") {
                focused(title(65)) && IptvPinPreferences.pinnedChannelOrder(context, profileKey, category.id) == listOf("pin-fixture-65")
            }
            val after = Rect().also { requireNotNull(tile(title(65))).getBoundsInScreen(it) }
            assertTrue("Pinning stays in the same browsing area", abs(after.top - before.top) <= before.height() + 24)
            assertTrue("Original tile remains visible", tile(title(65))?.isVisibleToUser == true)
            NavigationAuditEvidence.capture("pin-navigation-deep-after")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Next grid row stays in provider order") { focused(title(69)) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to original65") { focused(title(65)) }
            choosePinAction("Unpin")
            await("Unpin retains original65") {
                focused(title(65)) && IptvPinPreferences.pinnedChannelOrder(context, profileKey, category.id).isEmpty()
            }

            // A preference merge uses the same listener path as a different local surface.
            IptvPinPreferences.toggleChannel(context, profileKey, category.id, "pin-fixture-66")
            await("External pin updates the open grid without stealing focus") {
                focused(title(65)) && find(tile(title(66)), "Pinned channel") != null
            }
            NavigationAuditEvidence.capture("pin-navigation-external-update")
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.runOnMainSync { host.finish() }
            ins.waitForIdleSync()
            clearFixturePins()
            ins.setInTouchMode(true)
        }
    }
}
