package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.UpdatePackage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic Settings state, isolated app ID, no provider, backup, update or reset actions. */
class CompactSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val reauthenticationChanges = AtomicInteger()
    private val initialLoad = AtomicInteger(14)
    private val safeBottom = AtomicInteger()
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (!test() && SystemClock.uptimeMillis() < end) Thread.sleep(40)
        assertTrue(message, test())
    }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun keyboardVisible() = instrumentation.uiAutomation.windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }
    private fun move(code: Int, target: String) {
        key(code)
        await("D-pad must focus $target") { focused(target) }
    }
    private fun activeRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true ||
                node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(activeRoot())
    }
    private fun button(text: String): AccessibilityNodeInfo? {
        var node = find(text)
        repeat(8) { if (node?.isClickable == true) return node; node = node?.parent }
        return null
    }
    private fun click(text: String) {
        await("Reachable action: $text") { button(text) != null }
        val bounds = Rect().also { requireNotNull(button(text)).getBoundsInScreen(it) }
        val downAt = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun focused(text: String): Boolean {
        var node = find(text)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(400, 3_000)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun fixture(tv: Boolean, test: (AtomicReference<PlaybackEngine>, AtomicBoolean, AtomicInteger) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val engine = AtomicReference(PlaybackEngine.AUTO)
        val closed = AtomicBoolean(false)
        val destructive = AtomicInteger()
        reauthenticationChanges.set(0)
        initialLoad.set(14)
        val oldSystemBrightness = AppBrightnessPreferences.followsSystem(context)
        AppBrightnessPreferences.setFollowsSystem(context, true)
        try {
            instrumentation.runOnMainSync { host.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    if (tv) uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                val profile = remember { PortalProfile("Settings fixture", "https://settings.invalid", "00:11:22:33:44:55") }
                var state by remember { mutableStateOf(NikTvState(restoring = false, savedProfile = profile, profiles = listOf(profile))) }
                val updates = remember { SettingsUpdateStreams(MutableStateFlow(UpdateDownloadState.Idle),
                    MutableStateFlow<UpdateInfo?>(null), MutableStateFlow(false), MutableStateFlow(false),
                    MutableStateFlow(UpdatePackage.AUTO), { null }) }
                CompositionLocalProvider(LocalConfiguration provides config, LocalSettingsUpdateStreams provides updates) { MaterialTheme(colorScheme = NikColors) {
                    Surface(Modifier.fillMaxSize()) {
                    ModernSettingsScreen(
                        state = state, closeSettings = { closed.set(true) }, reauthenticate = {}, editProfile = {}, addProfile = {},
                        exportBackup = {}, importBackup = {}, importBackupContent = {}, switchProfile = {},
                        removeProfile = { destructive.incrementAndGet() }, setPreconfiguredProfileEnabled = { _, _ -> },
                        logout = { destructive.incrementAndGet() }, setCacheIntervalMinutes = {},
                        setInitialCatalogItems = { state = state.copy(initialCatalogItems = it); initialLoad.set(it) },
                        setPlayerControlsTimeoutSeconds = {}, setKeepAwakeOnlyDuringPlayback = { state = state.copy(keepAwakeOnlyDuringPlayback = it) },
                        setAutomaticReauthentication = { state = state.copy(automaticReauthentication = it); reauthenticationChanges.incrementAndGet() },
                        setPlaybackEngine = { state = state.copy(playbackEngine = it); engine.set(it) },
                        setSeriesStartSeason = {}, setBrowseLayout = {}, openCategoryManager = {}
                    )
                    }
                } }
            } }
            await("Settings is rendered") { button("Playback engine") != null }
            await("Fixture owns window focus") { host.hasWindowFocus() }
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                val bottomInset = host.window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
                safeBottom.set(context.resources.displayMetrics.heightPixels - bottomInset)
            }
            instrumentation.waitForIdleSync()
            try { test(engine, closed, destructive) } catch (error: Throwable) {
                screenshot("settings-failure.png")
                fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
                    if (node == null) return
                    android.util.Log.e("SettingsFixture", "${" ".repeat(depth)}${node.text} ${node.contentDescription} focused=${node.isFocused} editable=${node.isEditable}")
                    for (index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
                }
                describe(activeRoot())
                throw error
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            AppBrightnessPreferences.setFollowsSystem(context, oldSystemBrightness)
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test fun tvPickerAndNestedBackRestoreFocus() = fixture(true) { engine, closed, destructive ->
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Rail Right enters the first row") { focused("Playback engine") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Current value is focused in the picker") { focused("Automatic") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Picker invokes the setting callback") { engine.get() == PlaybackEngine.MEDIA3 }
        await("Picker restores row focus") { focused("Playback engine") }
        assertFalse(closed.get())
        screenshot("settings-tv-compact.png")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Player media browser")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Default season")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Compatibility")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Compatibility subpage opens") { button("Continue video if audio fails") != null }
        await("New subpage enters its first control") { focused("Continue video if audio fails") }
        key(KeyEvent.KEYCODE_BACK)
        await("Back restores the originating link") { focused("Compatibility") }
        assertFalse("Nested Back must keep Settings open", closed.get())
        key(KeyEvent.KEYCODE_BACK)
        await("Root Back closes Settings") { closed.get() }
        assertEquals(0, destructive.get())
    }

    @Test fun tabletBackupRoutesAndResetConfirmation() = fixture(false) { _, closed, destructive ->
        click("Library & backup")
        click("Back up & restore")
        assertNull("Catalog cloud backup stays hidden", button("Catalog backup"))
        click("Device backup")
        await("Device backup actions stay available") { button("Export device backup") != null && button("Import device backup") != null }
        key(KeyEvent.KEYCODE_BACK)
        await("Touch Back returns to the backups page") { button("Device backup") != null && button("Backup destination") != null }
        click("Backup destination")
        await("Destination is a compact picker") { button("Save backups to") != null }
        screenshot("settings-tablet-destination.png")
        click("App & updates")
        click("Reset app")
        click("Clear all app data")
        await("Clearing requires a separate confirmation") { find("Clear all app data?") != null }
        assertEquals("Opening reset never clears anything", 0, destructive.get())
        key(KeyEvent.KEYCODE_BACK)
        await("Back dismisses the touch reset confirmation") { find("Clear all app data?") == null }
        assertFalse(closed.get())
        assertEquals(0, destructive.get())
        click("Playback")
        screenshot("settings-tablet-compact.png")
    }

    @Test fun tabletDpadTraversesCategoriesPickersAndSingleStopSwitches() = fixture(false) { _, closed, destructive ->
        await("Playback category has initial focus") { focused("Playback") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Display & controls")
        assertNotNull("Moving rail focus alone must not replace the page", button("Playback engine"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, "Brightness")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Brightness picker focuses its current value") { focused("System") }
        key(KeyEvent.KEYCODE_BACK)
        await("Cancel restores the picker row") { focused("Brightness") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Screen orientation")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Keep screen awake")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Current screen-awake choice is focused") { focused("While NikTV is open") }
        move(KeyEvent.KEYCODE_DPAD_UP, "During playback")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Selection returns to Keep screen awake") { focused("Keep screen awake") }
        assertNotNull(find("During playback"))
        move(KeyEvent.KEYCODE_DPAD_UP, "Screen orientation")
        move(KeyEvent.KEYCODE_DPAD_LEFT, "Display & controls")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Profiles & access")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, "Manage profiles")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Connection")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Connection enters its first interactive row") { focused("Renew expired sessions automatically") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK toggles once") { reauthenticationChanges.get() == 1 }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Re-authenticate")
        move(KeyEvent.KEYCODE_DPAD_UP, "Renew expired sessions automatically")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Second OK toggles once again") { reauthenticationChanges.get() == 2 }
        key(KeyEvent.KEYCODE_BACK)
        await("Nested Back restores Connection") { focused("Connection") }
        move(KeyEvent.KEYCODE_DPAD_LEFT, "Profiles & access")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Library & backup")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, "Catalog updates")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Use local catalog first")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Browse refresh")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Browse refresh enters its first picker") { focused("Refresh cached lists after") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Initial category load")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Initial load picker focuses 14") { focused("14 items") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "28 items")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Initial-load callback and focus both update") { initialLoad.get() == 28 && focused("Initial category load") }
        key(KeyEvent.KEYCODE_BACK)
        await("Back restores Browse refresh") { focused("Browse refresh") }
        screenshot("settings-tablet-dpad-library.png")
        assertFalse(closed.get())
        assertEquals(0, destructive.get())
    }

    @Test fun tabletDpadScrollsLongFormAndCancelsReset() = fixture(false) { _, closed, destructive ->
        await("Playback category has initial focus") { focused("Playback") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Display & controls")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Profiles & access")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Library & backup")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, "Catalog updates")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Use local catalog first")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Browse refresh")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Back up & restore")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Backups enters Device backup") { focused("Device backup") }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Backup destination")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Destination enters its picker") { focused("Save backups to") }
        // Only traverse fields: never edit credentials or start a backup.
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Backup device name")
        assertFalse("Row focus must not activate a keyboard input", hasEditableField())
        assertFalse("Row focus must keep the keyboard hidden", keyboardVisible())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK explicitly opens and focuses the editor") { hasEditableField(requireFocus = true) }
        await("OK explicitly opens the editor keyboard") { keyboardVisible() }
        screenshot("settings-tablet-edit-popup.png")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Cancel")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Cancel restores the field row") { focused("Backup device name") && !hasEditableField() }
        move(KeyEvent.KEYCODE_DPAD_DOWN, "GitHub username")
        assertFalse("Username focus must not open the keyboard", hasEditableField())
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Repository")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Personal access token (PAT)")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Backup password")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Automatic device backup")
        await("D-pad reaches the schedule below the long setup form") { focused("Automatic device backup") }
        await("Scrolled focus outline has clearance above system navigation") {
            val bounds = Rect().also { button("Automatic device backup")?.getBoundsInScreen(it) }
            bounds.top >= 0 && bounds.bottom in 1..(safeBottom.get() - 4)
        }
        screenshot("settings-tablet-dpad-scrolled.png")
        key(KeyEvent.KEYCODE_BACK)
        await("Back restores Backup destination") { focused("Backup destination") }
        key(KeyEvent.KEYCODE_BACK)
        await("Back restores Back up & restore") { focused("Back up & restore") }
        move(KeyEvent.KEYCODE_DPAD_LEFT, "Library & backup")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "App & updates")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        move(KeyEvent.KEYCODE_DPAD_RIGHT, "Check for updates on startup")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Check for updates")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Delete older update APKs")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Update preferences")
        move(KeyEvent.KEYCODE_DPAD_DOWN, "Reset app")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Reset page enters its action") { focused("Clear all app data") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Reset defaults to Cancel") { focused("Cancel") }
        key(KeyEvent.KEYCODE_BACK)
        await("Cancelling reset restores its action") { focused("Clear all app data") && find("Clear all app data?") == null }
        assertEquals("Back cancels reset", 0, destructive.get())
        key(KeyEvent.KEYCODE_BACK)
        await("Back restores Reset app") { focused("Reset app") }
        assertFalse(closed.get())
    }

    private fun hasEditableField(requireFocus: Boolean = false): Boolean {
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isEditable && (!requireFocus || node.isFocused)) return true
            return (0 until node.childCount).any { walk(node.getChild(it)) }
        }
        return walk(activeRoot())
    }

    @Test fun tabletProfileFieldsOnlyEditAfterExplicitActivation() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val saves = AtomicInteger()
        try {
            instrumentation.runOnMainSync { host.setContent {
                val entries = remember { SettingsFocusEntries() }
                val profile = remember { PortalProfile("Profile fixture", "https://settings.invalid", "00:11:22:33:44:55") }
                LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; entries.requestFirst() }
                MaterialTheme(colorScheme = NikColors) { CompositionLocalProvider(LocalSettingsFocusEntries provides entries) {
                    ProfileScreen(profile, listOf(profile), true, false, {}, { saves.incrementAndGet() }, {}, {}, {}, {}, {})
                } }
            } }
            await("Profile name row takes focus") { focused("Profile name") }
            assertFalse("Profile focus must not open an input", hasEditableField())
            move(KeyEvent.KEYCODE_DPAD_DOWN, "Portal URL")
            assertFalse("Portal URL focus must not open an input", hasEditableField())
            move(KeyEvent.KEYCODE_DPAD_UP, "Profile name")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Explicit OK opens profile editor input") { hasEditableField(requireFocus = true) }
            move(KeyEvent.KEYCODE_DPAD_DOWN, "Cancel")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Cancel restores profile row focus") { focused("Profile name") && !hasEditableField() }
            assertEquals(0, saves.get())
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun tabletPinFocusKeepsKeyboardHiddenUntilOk() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun keyboardVisible() = automation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val cancelled = AtomicBoolean()
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                var pin by remember { mutableStateOf("") }
                ParentalPinDialog("PIN fixture", pin, { pin = it }, null, { cancelled.set(true) }, {})
            } } }
            await("PIN input receives focus") {
                automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.className == "android.widget.EditText"
            }
            instrumentation.waitForIdleSync()
            assertFalse("PIN focus must not open the keyboard", keyboardVisible())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK explicitly opens the PIN keyboard") { keyboardVisible() }
            move(KeyEvent.KEYCODE_DPAD_DOWN, "Cancel")
            await("Moving to Cancel hides the keyboard") { !keyboardVisible() }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to the read-only PIN input") {
                automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.className == "android.widget.EditText"
            }
            assertFalse("Returning focus must keep the keyboard hidden", keyboardVisible())
            val bounds = Rect().also {
                requireNotNull(automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).getBoundsInScreen(it)
            }
            val downAt = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            await("Touch explicitly opens the PIN keyboard") { keyboardVisible() }
            move(KeyEvent.KEYCODE_DPAD_DOWN, "Cancel")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("D-pad cancels PIN entry") { cancelled.get() }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
