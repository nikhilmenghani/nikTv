package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.BackupMode
import com.nikhil.niktv.data.DeviceBackupIdentity
import com.nikhil.niktv.data.GitHubBackupManager
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.UpdatePackage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Phone-sized composition on the tablet, without changing device display settings or real app data. */
class MobileSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val paneBounds = AtomicReference<RectF>()
    private val categories = listOf("Playback", "Display & controls", "Profiles & access", "Library & backup", "App & updates")

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(40)
        assertTrue(message, condition())
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(activeRoot())
    }

    private fun button(text: String): AccessibilityNodeInfo? {
        var node = find(text)
        repeat(8) {
            if (node?.isClickable == true) return node
            node = node?.parent
        }
        return null
    }

    private fun focused(text: String): Boolean {
        var node = find(text)
        repeat(8) {
            if (node?.isFocused == true) return true
            node = node?.parent
        }
        return false
    }

    private fun tap(text: String) {
        await("Reachable mobile action: $text") { button(text) != null }
        val bounds = Rect().also { requireNotNull(button(text)).getBoundsInScreen(it) }
        assertTrue("Touch target $text is visible", bounds.width() > 0 && bounds.height() > 0)
        val downAt = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun editableField(focusedOnly: Boolean = false): Boolean {
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isEditable && (!focusedOnly || node.isFocused)) return true
            return (0 until node.childCount).any { walk(node.getChild(it)) }
        }
        return walk(activeRoot())
    }

    private fun keyboardVisible() = instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun categoryIndexVisible() = categories.all { button(it) != null }

    private fun assertFitsPane(title: String) {
        val pane = requireNotNull(paneBounds.get())
        val bounds = Rect().also { requireNotNull(button(title)).getBoundsInScreen(it) }
        assertTrue("$title has a visible touch target", bounds.width() > 0 && bounds.height() > 0)
        assertTrue("$title stays inside the phone viewport: $bounds in $pane",
            bounds.left >= pane.left - 1 && bounds.top >= pane.top - 1 && bounds.right <= pane.right + 1 && bounds.bottom <= pane.bottom + 1)
    }

    private fun screenshot(name: String, cropToPhone: Boolean = true) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 3_000)
        instrumentation.uiAutomation.takeScreenshot()?.let { original ->
            val bounds = paneBounds.get()
            val bitmap = if (cropToPhone && bounds != null) {
                val left = bounds.left.toInt().coerceIn(0, original.width - 1)
                val top = bounds.top.toInt().coerceIn(0, original.height - 1)
                val right = bounds.right.toInt().coerceIn(left + 1, original.width)
                val bottom = bounds.bottom.toInt().coerceIn(top + 1, original.height)
                Bitmap.createBitmap(original, left, top, right - left, bottom - top)
            } else original
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (bitmap !== original) bitmap.recycle()
            original.recycle()
        }
    }

    private fun fixture(widthDp: Int, heightDp: Int = 680, fontScale: Float = 1f, verify: (AtomicBoolean, AtomicInteger) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val manager = GitHubBackupManager(context)
        val previousBackupConfiguration = manager.loadConfig()
        manager.saveConfig(previousBackupConfiguration.copy(backupMode = BackupMode.GITHUB, autoBackupIntervalHours = 0), reschedule = false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val closed = AtomicBoolean(false)
        val mutations = AtomicInteger()
        paneBounds.set(null)
        try {
            instrumentation.runOnMainSync { host.setContent {
                val phone = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = widthDp
                    screenHeightDp = heightDp
                    smallestScreenWidthDp = minOf(widthDp, heightDp)
                    this.fontScale = fontScale
                    orientation = if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL
                }
                val view = LocalView.current
                val density = LocalDensity.current
                val profile = remember { PortalProfile("Mobile Settings fixture", "https://mobile-settings.invalid", "00:11:22:33:44:55") }
                val state = remember { NikTvState(restoring = false, savedProfile = profile, profiles = listOf(profile)) }
                val updates = remember { SettingsUpdateStreams(MutableStateFlow(UpdateDownloadState.Idle),
                    MutableStateFlow<UpdateInfo?>(null), MutableStateFlow(false), MutableStateFlow(false), MutableStateFlow(UpdatePackage.AUTO), { null }) }
                MaterialTheme(colorScheme = NikColors) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(widthDp.dp).height(heightDp.dp).onGloballyPositioned { coordinates ->
                                val screen = IntArray(2).also(view::getLocationOnScreen)
                                val window = IntArray(2).also(view::getLocationInWindow)
                                val rect = coordinates.boundsInWindow()
                                paneBounds.set(RectF(rect.left + screen[0] - window[0], rect.top + screen[1] - window[1],
                                    rect.right + screen[0] - window[0], rect.bottom + screen[1] - window[1]))
                            }) {
                                CompositionLocalProvider(LocalConfiguration provides phone,
                                    LocalDensity provides Density(density.density, fontScale), LocalSettingsUpdateStreams provides updates) {
                                    ModernSettingsScreen(state = state, closeSettings = { closed.set(true) },
                                        reauthenticate = { mutations.incrementAndGet() }, editProfile = { mutations.incrementAndGet() }, addProfile = { mutations.incrementAndGet() },
                                        exportBackup = { mutations.incrementAndGet() }, importBackup = { mutations.incrementAndGet() }, importBackupContent = { mutations.incrementAndGet() },
                                        switchProfile = { mutations.incrementAndGet() }, removeProfile = { mutations.incrementAndGet() },
                                        setPreconfiguredProfileEnabled = { _, _ -> mutations.incrementAndGet() }, logout = { mutations.incrementAndGet() },
                                        setCacheIntervalMinutes = {}, setInitialCatalogItems = {}, setPlayerControlsTimeoutSeconds = {}, setKeepAwakeOnlyDuringPlayback = {},
                                        setAutomaticReauthentication = {}, setPlaybackEngine = {}, setSeriesStartSeason = {}, setBrowseLayout = {}, openCategoryManager = {})
                                }
                            }
                        }
                    }
                }
            } }
            await("Mobile category index is rendered") { paneBounds.get() != null && button("Playback") != null && button("Playback engine") == null }
            await("Mobile fixture owns window focus") { host.hasWindowFocus() }
            verify(closed, mutations)
        } catch (error: Throwable) {
            screenshot("settings-mobile-$widthDp-failure.png", cropToPhone = false)
            throw error
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            manager.saveConfig(previousBackupConfiguration, reschedule = false)
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test fun phone320CategoryMenuFitsAndCategoryBackReturnsToMenu() = fixture(320) { closed, mutations ->
        categories.forEach(::assertFitsPane)
        assertNull("Opening Settings starts at the category menu", button("Playback engine"))
        screenshot("settings-mobile-320-categories.png")
        tap("Display & controls")
        await("Display category opens") { button("Brightness") != null }
        key(KeyEvent.KEYCODE_BACK)
        await("Category Back returns to Settings menu") { categoryIndexVisible() }
        assertFalse(closed.get())
        tap("Playback")
        await("Playback uses compact settings controls") { button("Playback engine") != null }
        assertFitsPane("Playback engine")
        key(KeyEvent.KEYCODE_BACK)
        await("Playback Back restores Settings menu") { categoryIndexVisible() }
        key(KeyEvent.KEYCODE_BACK)
        await("Back from the menu closes Settings") { closed.get() }
        assertEquals(0, mutations.get())
    }

    @Test fun phone360BackupNavigationAndExplicitEditingPreserveSetup() = fixture(360) { closed, mutations ->
        val context = instrumentation.targetContext
        val deviceName = DeviceBackupIdentity.name(context)
        tap("Library & backup")
        tap("Back up & restore")
        await("Device backup and destination remain available") { button("Device backup") != null && button("Backup destination") != null }
        assertNull("Catalog cloud backup stays suppressed on mobile", button("Catalog backup"))
        tap("Backup destination")
        await("Compact backup destination opens") { button("Save backups to") != null && button("Backup device name") != null }
        assertFitsPane("Save backups to")
        assertFitsPane("Backup device name")
        assertFalse("Opening the page creates no editable input", editableField())
        assertFalse("Opening the page does not show the keyboard", keyboardVisible())
        instrumentation.setInTouchMode(false)
        assertTrue("Device-name row accepts focus", requireNotNull(button("Backup device name")).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await("Device-name row receives focus") { focused("Backup device name") }
        assertFalse("Row focus alone creates no editable input", editableField())
        assertFalse("Row focus alone does not show the keyboard", keyboardVisible())
        screenshot("settings-mobile-360-backup-destination.png")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK explicitly opens the editor") { editableField(focusedOnly = true) }
        await("Explicit editor activation opens the keyboard") { keyboardVisible() }
        screenshot("settings-mobile-360-editor.png", cropToPhone = false)
        tap("Cancel")
        await("Cancel returns to the row") { !editableField() && button("Backup device name") != null }
        await("Cancel hides the keyboard") { !keyboardVisible() }
        assertEquals("Cancel leaves the device label unchanged", deviceName, DeviceBackupIdentity.name(context))
        key(KeyEvent.KEYCODE_BACK)
        await("Nested Back returns to backup choices") { button("Device backup") != null && button("Backup destination") != null }
        key(KeyEvent.KEYCODE_BACK)
        await("Backup Back returns to Library settings") { button("Catalog updates") != null && button("Back up & restore") != null }
        key(KeyEvent.KEYCODE_BACK)
        await("Library Back returns to the category menu") { categoryIndexVisible() }
        assertFalse(closed.get())
        assertEquals(0, mutations.get())
    }

    @Test fun phone320EnlargedFontKeepsCategoriesAndPickerUsable() = fixture(320, fontScale = 1.3f) { closed, mutations ->
        categories.forEach(::assertFitsPane)
        screenshot("settings-mobile-320-large-font-categories.png")
        tap("Playback")
        await("Enlarged-font playback page opens") { button("Playback engine") != null }
        assertFitsPane("Playback engine")
        tap("Playback engine")
        await("Enlarged-font picker exposes Cancel") { button("Cancel") != null }
        assertFitsPane("Cancel")
        screenshot("settings-mobile-320-large-font-picker.png")
        tap("Cancel")
        await("Cancelling the picker returns to Playback") { button("Playback engine") != null && button("Cancel") == null }
        key(KeyEvent.KEYCODE_BACK)
        await("Back returns to the enlarged-font menu") { categoryIndexVisible() }
        assertFalse(closed.get())
        assertEquals(0, mutations.get())
    }

    @Test fun phoneLandscapeKeepsCategoryMenuAndPickerWithinShortViewport() = fixture(640, heightDp = 360) { closed, mutations ->
        assertNull("Landscape phone starts at the category menu instead of a rail/detail split", button("Playback engine"))
        assertFitsPane("Playback")
        tap("Playback")
        await("Landscape phone Playback opens") { button("Playback engine") != null }
        assertFitsPane("Playback engine")
        tap("Playback engine")
        await("Short landscape picker keeps Cancel reachable") { button("Cancel") != null }
        assertFitsPane("Cancel")
        screenshot("settings-mobile-640x360-picker.png")
        tap("Cancel")
        await("Landscape picker closes") { button("Playback engine") != null && button("Cancel") == null }
        key(KeyEvent.KEYCODE_BACK)
        await("Landscape Back returns to category menu") { button("Playback") != null && button("Playback engine") == null }
        assertFalse(closed.get())
        assertEquals(0, mutations.get())
    }
}
