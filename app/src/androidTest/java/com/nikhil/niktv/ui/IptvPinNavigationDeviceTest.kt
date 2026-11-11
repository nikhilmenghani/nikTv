package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
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
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { Surface(Modifier.fillMaxSize()) {
                ModernIptvCollection(state, category, close = {}, openItem = { fail("Pin navigation must not play") },
                    toggleFavorite = {}, loadMore = {}, refresh = {}, enrichFocusedMetadata = { _, _ -> },
                    enrichVisibleLiveGuides = {}, refreshLiveProgramme = {}, findChannelsInCategory = {}, cancelCategoryFind = {}, isTv = true)
            } } } }
            await("First channel gets remote focus") { focused(title(1)) }
            for (row in 1..21) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Dpad reaches channel ${1 + row * 3}") { focused(title(1 + row * 3)) }
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Channel65 original is focused") { focused(title(65)) }
            val before = Rect().also { requireNotNull(tile(title(65))).getBoundsInScreen(it) }
            NavigationAuditEvidence.capture("pin-navigation-deep-before")

            choosePinAction("Pin to top")
            await("Pin action confirms without leaving the browsing position") { node("Pinned to top") != null }
            await("Pinning retains original channel65 focus and adds shortcut") {
                focused(title(65)) && IptvPinPreferences.pinnedChannelOrder(context, profileKey, category.id) == listOf("pin-fixture-65")
            }
            val after = Rect().also { requireNotNull(tile(title(65))).getBoundsInScreen(it) }
            assertTrue("Pinning stays in the same browsing area", abs(after.top - before.top) <= before.height() + 24)
            assertTrue("Original tile remains visible", tile(title(65))?.isVisibleToUser == true)
            NavigationAuditEvidence.capture("pin-navigation-deep-after")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Next grid row stays in provider order") { focused(title(68)) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to original65") { focused(title(65)) }
            choosePinAction("Unpin")
            await("Unpin retains original65") {
                focused(title(65)) && IptvPinPreferences.pinnedChannelOrder(context, profileKey, category.id).isEmpty()
            }

            // A preference merge uses the same listener path as a different local surface.
            IptvPinPreferences.toggleChannel(context, profileKey, category.id, "pin-fixture-64")
            await("External pin updates the open grid without stealing focus") {
                focused(title(65)) && find(tile(title(64)), "Pinned channel") != null
            }
            NavigationAuditEvidence.capture("pin-navigation-external-update")
        } finally {
            ins.runOnMainSync { host.finish() }
            ins.waitForIdleSync()
            clearFixturePins()
            ins.setInTouchMode(true)
        }
    }
}
