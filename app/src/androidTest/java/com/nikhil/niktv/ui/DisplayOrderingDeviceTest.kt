package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DisplayOrderingDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (test(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) { if (test()) return; Thread.sleep(35) }
        NavigationAuditEvidence.capture("ordering-failed-$label")
        fail(label)
    }
    private fun click(label: String) {
        val node = find { it.contentDescription?.toString() == label || it.text?.toString() == label }
            ?: error("Missing $label")
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $label" }
    }
    private fun containsDescription(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.contentDescription?.toString() == label) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let { containsDescription(it, label) } == true }
    }
    private fun focused(label: String) = find { node -> node.isFocused &&
        if (node.contentDescription?.startsWith("Moving ") == true) node.contentDescription.toString() == "Moving ${label.removePrefix("Arrange ")}"
        else containsDescription(node, label) } != null
    private fun ordinal(label: String): Int? {
        val parent = find { it.contentDescription?.toString() == label }?.parent ?: return null
        return (0 until parent.childCount).firstNotNullOfOrNull { parent.getChild(it)?.text?.toString()?.toIntOrNull() }
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun host() = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity

    @Test fun remoteMoveRetainsItemFocusAtBothBoundariesAndBackUnwinds() {
        require(ins.targetContext.packageName.endsWith(".regression"))
        val host = host()
        val focusEvents = CopyOnWriteArrayList<String>()
        var backgroundFocus = 0
        var visible by mutableStateOf(true)
        var saved: List<String>? = null
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                focusEvents.add(event.source?.contentDescription?.toString().orEmpty())
            }
        }
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                NikTvSecondaryActionButton({}, Modifier.onFocusChanged { if (it.isFocused) backgroundFocus++ }) { Text("Underlying page") }
                if (visible) ReorderItemsDialog("Arrange test", listOf(OrderChoice("a", "Alpha"), OrderChoice("b", "Bravo"), OrderChoice("c", "Charlie")),
                    { visible = false }, { saved = it })
            } } }
            await("First item focused") { focused("Arrange Alpha") }
            val baselineBackground = backgroundFocus
            val baselineEvents = focusEvents.size
            NavigationAuditEvidence.capture("ordering-unselected")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Move controller owns focus") { find { it.isFocused && it.contentDescription?.toString() == "Moving Alpha" } != null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("First boundary retains Alpha") { focused("Arrange Alpha") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Last boundary retains Alpha") { focused("Arrange Alpha") && ordinal("Arrange Alpha") == 3 }
            val alphaBounds = Rect().also { find { it.contentDescription?.toString() == "Arrange Alpha" }!!.getBoundsInScreen(it) }
            val charlieBounds = Rect().also { find { it.contentDescription?.toString() == "Arrange Charlie" }!!.getBoundsInScreen(it) }
            assertTrue(alphaBounds.top > charlieBounds.top)
            assertEquals(baselineBackground, backgroundFocus)
            assertTrue("No intervening item acquires focus", focusEvents.drop(baselineEvents).all { it.isEmpty() || it == "Arrange Alpha" || it == "Moving Alpha" })
            NavigationAuditEvidence.capture("ordering-moved-focused")
            key(KeyEvent.KEYCODE_BACK)
            await("First Back finishes moving but keeps dialog") { visible && focused("Arrange Alpha") && find { it.contentDescription?.toString() == "Moving Alpha" } == null }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Normal navigation resumes") { focused("Arrange Charlie") }
            click("Apply")
            await("Apply saves the displayed order") { !visible && saved == listOf("b", "c", "a") }
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.runOnMainSync { host.finish() }
        }
    }

    @Test fun touchArrowsAndCancelDoNotSaveUnconfirmedChanges() {
        require(ins.targetContext.packageName.endsWith(".regression"))
        val host = host()
        var visible by mutableStateOf(true)
        var saved = listOf("a", "b", "c")
        try {
            ins.setInTouchMode(true)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                if (visible) ReorderItemsDialog("Arrange test", listOf(OrderChoice("a", "Alpha"), OrderChoice("b", "Bravo"), OrderChoice("c", "Charlie")),
                    { visible = false }, { saved = it })
            } } }
            await("Touch order controls visible") { find { it.contentDescription?.toString() == "Move Charlie up" } != null }
            click("Move Charlie up")
            click("Move Charlie up")
            NavigationAuditEvidence.capture("ordering-touch")
            click("Cancel")
            await("Cancel closes without saving") { !visible }
            assertEquals(listOf("a", "b", "c"), saved)
        } finally { ins.runOnMainSync { host.finish() } }
    }

    @Test fun categoryManagerAndTmdbSettingsExposeOrderWithoutChangingSelection() {
        require(ins.targetContext.packageName.endsWith(".regression"))
        val host = host()
        val portal = PortalProfile("Order fixture", "https://order.invalid", "fixture-mac")
        val state = NikTvState(savedProfile = portal, categoryManagerType = CatalogType.MOVIES,
            rawCategoriesByType = mapOf(CatalogType.MOVIES to listOf(Category("h", "Hindi", CatalogType.MOVIES), Category("e", "English", CatalogType.MOVIES))))
        var filtersChanged = false
        var tmdbChanged = false
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                CategoryManagerDialog(state, {}, { filtersChanged = true })
            } } }
            await("IPTV category manager loaded") { find { it.contentDescription?.toString() == "Arrange categories" } != null }
            click("Arrange categories")
            await("IPTV order opens on a real category") { focused("Arrange Hindi") }
            NavigationAuditEvidence.capture("ordering-category-entry")
            key(KeyEvent.KEYCODE_BACK)
            await("Order closes back to category manager") { find { it.text?.toString() == "IPTV Movies categories" } != null && find { it.text?.toString() == "Arrange Movies categories" } == null }
            assertFalse(filtersChanged)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                TmdbHomeSectionsDialog(portal.cacheKey(), listOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES),
                    DashboardSurface.HOME, {}, { tmdbChanged = true })
            } } }
            await("TMDB settings loaded") { find { it.text?.toString() == "Arrange categories" } != null }
            click("Arrange categories")
            await("TMDB order opens") { find { it.text?.toString() == "Arrange TMDB categories" } != null }
            NavigationAuditEvidence.capture("ordering-tmdb-entry")
            click("Cancel")
            assertFalse(tmdbChanged)
        } finally { ins.runOnMainSync { host.finish() } }
    }

    @Test fun pinnedOrderSupportsMovingAcrossTheViewportAndKeepsMembership() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val host = host()
        val profile = "ordering-fixture-pins"
        val category = Category("order-fixture", "Channels", CatalogType.LIVE_TV)
        val ids = (1..20).map { "channel-$it" }
        IptvPinPreferences.pinnedChannelOrder(context, profile, category.id).forEach {
            IptvPinPreferences.toggleChannel(context, profile, category.id, it)
        }
        ids.forEach { IptvPinPreferences.toggleChannel(context, profile, category.id, it) }
        var visible by mutableStateOf(true)
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                if (visible) PinnedChannelOrderDialog(profile, category, emptyList()) { visible = false }
            } } }
            val first = "Arrange Channel channel-1 (not loaded)"
            await("Pinned order opens on the first pin, including unloaded channels") { focused(first) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Pin move controller owns focus") { find { it.isFocused && it.contentDescription?.toString() == "Moving Channel channel-1 (not loaded)" } != null }
            repeat(23) { step ->
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Moved pin remains focused across viewport") { focused(first) && ordinal(first) == minOf(step + 2, ids.size) }
            }
            ins.waitForIdleSync()
            Thread.sleep(200) // Let screenshot composition catch the final rendered frame, not just the semantics update.
            NavigationAuditEvidence.capture("ordering-pins-scrolled")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            click("Apply")
            await("Pinned order saved") { !visible }
            assertEquals(ids.drop(1) + ids.first(), IptvPinPreferences.pinnedChannelOrder(context, profile, category.id))
        } finally {
            IptvPinPreferences.pinnedChannelOrder(context, profile, category.id).forEach {
                IptvPinPreferences.toggleChannel(context, profile, category.id, it)
            }
            ins.runOnMainSync { host.finish() }
        }
    }
}
