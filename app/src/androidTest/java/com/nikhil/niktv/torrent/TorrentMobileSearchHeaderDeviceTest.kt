package com.nikhil.niktv.torrent

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.NativeDiscoveryItem
import com.nikhil.niktv.discovery.NativeDiscoveryRow
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NavigationAuditEvidence
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import org.junit.Assert.*
import org.junit.Test

class TorrentMobileSearchHeaderDeviceTest {
    @Test fun inlineSearchKeepsHeaderAndSurvivesKeyboardDismissal() {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(ins.targetContext.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val store = ViewModelStore()
        lateinit var model: TorrentPortalViewModel
        fun nodes(): List<AccessibilityNodeInfo> {
            ins.uiAutomation.clearCache()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun collect(node: AccessibilityNodeInfo?) {
                node ?: return
                result += node
                repeat(node.childCount) { collect(node.getChild(it)) }
            }
            collect(ins.uiAutomation.rootInActiveWindow)
            return result
        }
        fun find(label: String) = nodes().firstOrNull { it.contentDescription?.toString() == label }
        fun field() = nodes().firstOrNull { it.className?.toString() == "android.widget.EditText" && it.isVisibleToUser }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun await(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 8_000L
            while (SystemClock.uptimeMillis() < until) {
                if (condition()) return
                Thread.sleep(50L)
            }
            NavigationAuditEvidence.capture("torrent-inline-search-failure")
            fail(label + "; visible controls=" + nodes().mapNotNull { it.contentDescription?.toString() ?: it.text?.toString() })
        }
        fun ime() = host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        fun tap(node: AccessibilityNodeInfo) {
            val rect = bounds(node)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    rect.exactCenterX(), rect.exactCenterY(), 0)
                ins.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
        }
        try {
            ins.setInTouchMode(true)
            ins.runOnMainSync {
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                model = TorrentPortalViewModel(ins.targetContext.applicationContext as Application)
                store.put("torrent-search", model)
                host.setContent {
                    MaterialTheme(colorScheme = NikColors) {
                        TorrentPortalScreen(model, "Test", onProfiles = {}, onClose = {})
                    }
                }
            }
            await("Portrait header is ready") { find("Search") != null && find("Switch profile: Test") != null }
            val profileBounds = bounds(requireNotNull(find("Switch profile: Test")))
            val searchBounds = bounds(requireNotNull(find("Search")))
            assertNull(field())
            tap(requireNotNull(find("Search")))
            await("Search click opens the field and IME") { field() != null && ime() }
            ins.sendStringSync("Interstellar")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("First Back dismisses only the IME") { !ime() && host.hasWindowFocus() }
            assertNotNull("The search field remains visible", field())
            assertEquals(profileBounds, bounds(requireNotNull(find("Switch profile: Test"))))
            assertEquals(searchBounds, bounds(requireNotNull(find("Search"))))
            assertEquals("Only one Search icon", 1, nodes().count { it.contentDescription?.toString() == "Search" })
            NavigationAuditEvidence.capture("torrent-inline-search-ime-closed")
            tap(requireNotNull(field()))
            await("Touch reopens the retained editor") { ime() }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Keyboard closes again") { !ime() && host.hasWindowFocus() }
            tap(requireNotNull(find("Search")))
            await("Submission retains a visible field") { field()?.text?.toString() == "Interstellar" }
            assertEquals(profileBounds, bounds(requireNotNull(find("Switch profile: Test"))))
            assertEquals(searchBounds, bounds(requireNotNull(find("Search"))))
            ins.runOnMainSync {
                model.open(DiscoveryCard(1396, series = true, title = "Breaking Bad", date = "2008-01-20"))
            }
            await("Details replace the search editor") { field() == null && find("Back") != null }
            ins.runOnMainSync { model.back() }
            await("Returning to results retains the query") { field()?.text?.toString() == "Interstellar" }
            assertFalse("Return does not open the keyboard by itself", ime())
            tap(requireNotNull(field()))
            await("Returned query is editable by touch") { ime() }
            ins.sendStringSync("Test")
            await("Typing changes the returned query") { field()?.text?.toString()?.contains("Test") == true }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("First Back keeps the returned field") { !ime() && host.hasWindowFocus() && field() != null }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Second Back collapses the editor") { field() == null && !ime() }
            assertEquals(searchBounds, bounds(requireNotNull(find("Search"))))
        } finally {
            ins.runOnMainSync { store.clear(); host.finish() }
        }
    }
    @Test fun categorySkeletonRemainsUntilRealCardsAttach() {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(ins.targetContext.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val rows = mutableStateOf(listOf(NativeDiscoveryRow("genres", "Hollywood genres", emptyList(), loading = true)))
        fun await(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 6_000L
            while (SystemClock.uptimeMillis() < until) {
                var passed = false
                ins.runOnMainSync { passed = condition() }
                if (passed) return
                Thread.sleep(50L)
            }
            fail(label)
        }
        fun skeleton() = host.window.decorView.findViewWithTag<android.view.View>("genres:skeleton")
        try {
            ins.runOnMainSync {
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                host.setContent {
                    MaterialTheme(colorScheme = NikColors) {
                        TorrentPhoneFeed(rows.value, card = null, inList = false,
                            bodyFocus = remember { FocusRequester() }, playFocus = remember { FocusRequester() },
                            open = {}, save = {}, previous = {}, next = {}, index = 0, count = 0,
                            popup = false, reduceMotion = true)
                    }
                }
            }
            await("Loading category has visible skeleton cards") { skeleton()?.isShown == true }
            NavigationAuditEvidence.capture("torrent-category-loading-skeleton")
            ins.runOnMainSync {
                rows.value = listOf(NativeDiscoveryRow("genres", "Hollywood genres",
                    listOf(NativeDiscoveryItem("action", "Action", onClick = {})), loading = false))
            }
            await("Attached content replaces its skeleton") {
                skeleton()?.visibility == android.view.View.GONE &&
                    host.window.decorView.findViewWithTag<android.view.View>("genres:action")?.isShown == true
            }
        } finally {
            ins.runOnMainSync { host.finish() }
        }
    }

}
