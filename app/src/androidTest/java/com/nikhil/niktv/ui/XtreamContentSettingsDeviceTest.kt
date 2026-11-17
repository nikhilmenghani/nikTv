package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs in the isolated regression package; no subscription or real profile is changed. */
class XtreamContentSettingsDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(40)
        assertTrue(message, condition())
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(i.uiAutomation.rootInActiveWindow)
    }
    private fun button(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        repeat(8) { if (node?.isClickable == true) return node; node = node?.parent }
        return null
    }
    private fun reveal(label: String) {
        if (button(label) == null) {
            fun scroll(forward: Boolean): Boolean {
                fun container(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isScrollable) return node
                    for (index in 0 until node.childCount) container(node.getChild(index))?.let { return it }
                    return null
                }
                val result = container(i.uiAutomation.rootInActiveWindow)?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
                i.waitForIdleSync(); Thread.sleep(100)
                return result
            }
            for (index in 0 until 12) { if (button(label) != null || !scroll(false)) break }
            for (index in 0 until 12) { if (button(label) != null || !scroll(true)) break }
        }
        await("Visible action: $label") { button(label) != null }
        button(label)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        i.waitForIdleSync()
        var last = Rect()
        var stable = 0
        await("Action settles inside the viewport: $label") {
            val rect = Rect().also { button(label)?.getBoundsInScreen(it) }
            if (rect == last) stable++ else stable = 0
            last = rect
            stable >= 3 && rect.width() > 0 && rect.top >= 0 && rect.bottom <= i.targetContext.resources.displayMetrics.heightPixels
        }
    }
    private fun click(label: String) {
        reveal(label)
        val bounds = Rect().also { button(label)!!.getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(i.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        i.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        i.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(i.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun fixture(tv: Boolean, landscape: Boolean = false) {
        val context = i.targetContext
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val account = "99881122"
        val family = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
        val kids = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.KIDS))
        val portal = PortalProfile("Fixture", "http://fixture.invalid", "", portalType = PortalType.XTREAM)
        runBlocking(Dispatchers.IO) {
            users.selectAccount(account); users.initializeDefaults(account); users.selectProfile(family.profileId)
            XtreamContentPreferences.save(context, family, XtreamContentPolicy())
            XtreamContentPreferences.save(context, kids, XtreamContentPolicy())
            val store = ProfileStore(context)
            for (type in listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) {
                val cats = when (type) {
                    CatalogType.LIVE_TV -> listOf(Category("5", "SPORTS | CRICKET", type), Category("18", "INDIA | SD", type))
                    CatalogType.MOVIES -> listOf(Category("26", "ENGLISH | HINDI DUBBED", type), Category("88", "ARABIC | MOVIES", type))
                    else -> listOf(Category("214", "KIDS", type), Category("803", "CBeebies", type))
                }
                store.saveBrowseCatalogMetadata(BrowseCatalogMetadataCache(portal.cacheKey(), type, System.currentTimeMillis(), cats))
            }
        }
        i.setInTouchMode(true)
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            if (landscape) {
                i.runOnMainSync { host.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Landscape configuration applies") { host.resources.configuration.screenWidthDp > host.resources.configuration.screenHeightDp }
            }
            i.runOnMainSync { host.setContent {
                MaterialTheme(colorScheme = NikColors) {
                    val realConfiguration = LocalConfiguration.current
                    val configuration = remember(realConfiguration, tv) { Configuration(realConfiguration).apply {
                        if (tv) uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                    } }
                    var selected by remember { mutableStateOf(family.profileId) }
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalSettingsPage provides SettingsPage.XTREAM_CONTENT,
                        LocalSettingsTv provides tv) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
                                XtreamContentSettings(selected, listOf(portal)) { selected = it }
                            }
                        }
                    }
                }
            } }
            await("Profile preferences load") { button("Filter Xtream content") != null }
            click("Filter Xtream content")
            await("Profile filtering can be enabled with touch") { button("Filter Xtream content")?.isChecked == true }
            screenshot(if (tv) "xtream-filters-tv-languages.png" else "xtream-filters-languages.png")
            click("Hindi")
            await("Touch toggles Hindi off") { button("Hindi")?.isChecked == false }
            i.setInTouchMode(false)
            assertTrue(button("Hindi")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("Hindi receives remote focus") { button("Hindi")?.isFocused == true }
            val bounds = Rect().also { button("Hindi")!!.getBoundsInScreen(it) }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Remote OK toggles Hindi without losing focus") { button("Hindi")?.let { it.isFocused && it.isChecked } == true }
            assertEquals(bounds, Rect().also { button("Hindi")!!.getBoundsInScreen(it) })
            screenshot(if (tv) "xtream-filters-tv-focused.png" else "xtream-filters-focused.png")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches English") { button("English")?.isFocused == true }
            click("Sports")
            await("Sports choices load") { button("Cricket") != null }
            assertTrue(button("Cricket")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Football") { button("Football")?.isFocused == true }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Football toggles in place") { button("Football")?.let { it.isFocused && it.isChecked } == true }
            screenshot(if (tv) "xtream-filters-tv-sports.png" else "xtream-filters-sports.png")
            click("Exceptions")
            reveal("Category")
            i.setInTouchMode(false)
            assertTrue(button("Category")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Picker opens at the current category") { find("SPORTS | CRICKET") != null && button("INDIA | SD") != null }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Remote moves from selected category to next") { button("INDIA | SD")?.isFocused == true }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Choice returns to category trigger") { button("Category") != null }
            i.setInTouchMode(false)
            assertTrue(button("Save preferences")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Profile settings persist") {
                runBlocking(Dispatchers.IO) { XtreamContentPreferences.read(context, family).let { it.enabled && "football" in it.sports } }
            }
            assertFalse(runBlocking(Dispatchers.IO) { XtreamContentPreferences.read(context, kids).enabled })
            await("Saving retains focus on its fixed-size button") { button("Save preferences")?.isFocused == true }
            assertFalse("No keyboard opens while focusing filter controls", i.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            })
        } catch (failure: Throwable) {
            screenshot("xtream-filters-failure.png")
            val lines = mutableListOf<String>()
            fun record(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null) return
                val bounds = Rect().also(node::getBoundsInScreen)
                lines += "${" ".repeat(depth)}${node.text} click=${node.isClickable} focus=${node.isFocused} $bounds"
                for (index in 0 until node.childCount) record(node.getChild(index), depth + 1)
            }
            record(i.uiAutomation.rootInActiveWindow)
            File(context.cacheDir, "xtream-filters-failure.txt").writeText(lines.joinToString("\n"))
            throw failure
        } finally { i.runOnMainSync { host.finish() } }
    }
    @Test fun touchAndRemoteNavigationInDeviceLayout() = fixture(false)
    @Test fun remoteNavigationInTelevisionPresentation() = fixture(true)
    @Test fun touchAndRemoteNavigationInMobileLandscape() = fixture(false, landscape = true)
}
