package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WatchProvidersDeviceTest {
    private val test get() = InstrumentationRegistry.getInstrumentation()
    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString()?.lineSequence()?.any { it == label } == true || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return find(test.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (ready()) return; Thread.sleep(40) }; fail(message)
    }
    private fun action(label: String, action: Int) {
        var n = node(label)
        repeat(8) { if ((action == AccessibilityNodeInfo.ACTION_FOCUS && n?.isFocusable != true) || (action == AccessibilityNodeInfo.ACTION_CLICK && n?.isClickable != true)) n = n?.parent }
        assertTrue("Reachable $label", n?.performAction(action) == true)
    }
    private fun focused(label: String): Boolean {
        var n = node(label); repeat(8) { if (n?.isFocused == true) return true; n = n?.parent }; return false
    }
    private fun host(): PlaybackFixtureHostActivity {
        val context = test.targetContext; check(context.packageName.endsWith(".regression"))
        return test.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }
    @Test fun lazyRowsSupportRemotePagingTouchFiltersAndBoundedMetadata() {
        val activity = host(); test.setInTouchMode(false)
        var model: WatchProvidersModel? = null
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        var opened = ""
        val source = object : WatchProvidersSource {
            override suspend fun regions() = listOf(WatchRegion("IN", "India"))
            override suspend fun providers(region: String, series: Boolean, refresh: Boolean) = (1..30).map { WatchProvider(it, "Provider $it") }
            override suspend fun titles(region: String, series: Boolean, provider: Int, page: Int, stale: (WatchProviderPage) -> Unit, refresh: Boolean): WatchProviderPage {
                requests += "$provider:$page:$series:refresh=$refresh"
                return WatchProviderPage((1..10).map { DiscoveryCard(page * 100 + it, series, "${if (series) "Show" else "Film"} $provider-$page-$it", date = "2020-01-01") }, page, 2)
            }
        }
        try {
            test.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val scope = rememberCoroutineScope()
                val controller = remember { WatchProvidersModel(scope, source).also { model = it } }
                val state by controller.state.collectAsState()
                var query by remember { mutableStateOf("") }; var search by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { controller.select() }
                WatchProviderRows(state, controller::load, controller::leave, query, search, { query = it },
                    remember { FocusRequester() }, remember { FocusRequester() }, {}, { search = !search },
                    { controller.select(series = it) }, controller::refresh, { card, _ -> opened = card.title })
            } } }
            await("First provider ready") { node("Film 1-1-1") != null }
            assertTrue("Opening tab must not fetch all 30 provider feeds", requests.size < 8)
            action("Film 1-1-1", AccessibilityNodeInfo.ACTION_FOCUS)
            await("First film focused") { focused("Film 1-1-1") }
            val before = Rect(); node("Film 1-1-1")!!.getBoundsInScreen(before)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters adjacent title") { focused("Film 1-1-2") }
            val after = Rect(); node("Film 1-1-2")!!.getBoundsInScreen(after)
            assertTrue("Horizontal focus must not vertically jump", kotlin.math.abs(before.top - after.top) < 12)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK opens selected details callback") { opened == "Film 1-1-2" }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches second provider") { focused("Film 2-1-2") || focused("Film 2-1-1") }
            action("Film 2-1-1", AccessibilityNodeInfo.ACTION_CLICK)
            await("Touch opens selected title") { opened == "Film 2-1-1" }
            // Reset viewport without relying on a particular remote scroll timing.
            test.runOnMainSync { model!!.select(series = true) }
            await("Media type change rejects movie rows") { node("Show 1-1-1") != null }
            assertNull(node("Film 1-1-1"))
            action("Refresh providers", AccessibilityNodeInfo.ACTION_CLICK)
            await("Refresh reloads same-region rows even with immediate cached catalogue") { requests.any { it.startsWith("1:1:true:") && it.endsWith("refresh=true") } }
            action("Filter providers", AccessibilityNodeInfo.ACTION_CLICK)
            await("Search expands only on click") { node("Filter providers") != null }
            action("Show 1-1-1", AccessibilityNodeInfo.ACTION_FOCUS)
            repeat(10) { test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(40) }
            await("More receives focus at row end") { focused("More") }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider pagination appends next page") { model!!.state.value.rows[1]?.page == 2 }
            await("New page focuses its first title") { focused("Show 1-2-1") }
            assertEquals(20, model!!.state.value.rows[1]!!.cards.size)
        } finally { test.runOnMainSync { activity.finish() } }
    }
    @Test fun smallPhoneNavigationKeepsProvidersAccessibleWithoutTruncatedInactiveLabels() {
        val activity = host(); var selected = DiscoveryPhoneDestination.HOME
        try {
            test.runOnMainSync { activity.setContent { MaterialTheme {
                val config = android.content.res.Configuration(LocalConfiguration.current).apply { screenWidthDp = 360; smallestScreenWidthDp = 360 }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    var active by remember { mutableStateOf(DiscoveryPhoneDestination.HOME) }
                    Box(Modifier.width(360.dp)) { DiscoveryPhoneBottomNavigation(active, { active = it; selected = it }) }
                }
            } } }
            await("All six destinations are accessible") { DiscoveryPhoneDestination.entries.all { node(it.title) != null } }
            action("Providers", AccessibilityNodeInfo.ACTION_CLICK)
            await("Providers selected") { selected == DiscoveryPhoneDestination.PROVIDERS }
            val rect = Rect(); node("Providers")!!.getBoundsInScreen(rect)
            assertTrue(rect.width() > 0 && rect.height() > 0)
        } finally { test.runOnMainSync { activity.finish() } }
    }
    @Test fun countryChooserStartsAtSelectionAndKeyboardRequiresActivation() {
        val activity = host(); test.setInTouchMode(false)
        val automation = test.uiAutomation
        val original = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun ime() = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        try {
            test.runOnMainSync { activity.setContent { MaterialTheme {
                WatchRegionDialog(listOf(WatchRegion("AL", "Albania"), WatchRegion("CA", "Canada"),
                    WatchRegion("IN", "India"), WatchRegion("US", "United States")), "IN", {}, {})
            } } }
            await("Selected region is focused") { focused("India (IN)") }
            Thread.sleep(200)
            assertFalse("Country navigation must not open keyboard", ime())
            action("Filter countries", AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(200)
            assertFalse("Focusing filter must not open keyboard", ime())
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK explicitly opens keyboard") { ime() }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back hides keyboard") { !ime() }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = original }
            test.runOnMainSync { activity.finish() }
        }
    }

}
