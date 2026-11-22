package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.SubtitleSearchRequest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

/** Dialog focus transitions only. Production player/media checks run separately. */
class SubtitleSelectionNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val focusEvents = CopyOnWriteArrayList<String>()
    private val selected = mutableStateOf<String?>(null)
    private val delay = mutableStateOf(0L)
    private val appearance = mutableStateOf(SubtitleAppearancePreset.STANDARD)

    @Test fun compactLandscapeManyTracksOffTimingAndDismissStayReachable() = fixture(
        trackCount = 24, selectedId = "track-12", landscape = true
    ) {
        await("Selected track is focused even when initially outside the lazy viewport") { focused("Subtitle language 12") }
        NavigationAuditEvidence.capture("subtitle-landscape-selected-track")
        for (number in 11 downTo 1) {
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches subtitle $number") { focused("Subtitle language $number") }
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("First track Up reaches Off") { focused("Off") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Off applies without removing its focused row") { selected.value == null && focused("Off") }
        NavigationAuditEvidence.capture("subtitle-landscape-off")
        for (number in 1..24) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches subtitle $number through the lazy list") { focused(trackLabel(number)) }
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Last subtitle can be selected and retains focus") { selected.value == "track-24" && focused(trackLabel(24)) }
        NavigationAuditEvidence.capture("subtitle-landscape-last-track-long-label")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Last subtitle Down reaches Earlier") { focused("Show subtitles earlier") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Timing Right reaches Later") { focused("Show subtitles later") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Later adds one timing step and composes Reset") {
            delay.value == 250L && node("Reset timing") != null && focused("Show subtitles later")
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Timing Down reaches Reset") { focused("Reset timing") }
        focusEvents.clear()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Reset transfers focus to Earlier before removing itself") { delay.value == 0L && focused("Show subtitles earlier") }
        assertNoUnexpectedFocus("Reset timing", "Show subtitles earlier")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Zero timing Down reaches Appearance") { focused("Appearance: Standard") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Appearance changes while preserving its control") { appearance.value == SubtitleAppearancePreset.LARGE && focused("Appearance: Large") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Appearance Up returns to timing") { focused("Show subtitles earlier") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Timing Up returns to the final track") { focused(trackLabel(24)) }
        focusEvents.clear()
        key(KeyEvent.KEYCODE_BACK)
        await("Closing the dialog restores its originating subtitle action") { focused("Subtitle origin") && node("Off") == null }
        assertNoUnexpectedFocus("Subtitle origin")
        NavigationAuditEvidence.capture("subtitle-landscape-restored-origin")
    }

    @Test fun noTracksOffDownReturnsToMountedHeader() = fixture(trackCount = 0, selectedId = null) {
        await("An empty subtitle list initially focuses Off") { focused("Off") }
        assertNull(node("Show subtitles earlier"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Off Down reaches the mounted Online header when timing is absent") { focused("Find subtitles online") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Header Down returns to Off") { focused("Off") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(selected.value)
        assertTrue(focused("Off"))
        NavigationAuditEvidence.capture("subtitle-empty-off")
        key(KeyEvent.KEYCODE_BACK)
        await("Empty dialog dismissal returns to its origin") { focused("Subtitle origin") && node("Off") == null }
    }

    @Test fun phoneSearchRequiresOkOrTouchAndBackUnwindsEditorSearchThenPopup() = fixture(
        trackCount = 2, selectedId = null
    ) {
        await("Off initially receives focus") { focused("Off") }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up reaches Online") { focused("Find subtitles online") }
        focusEvents.clear()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Online opens while its surviving mode action retains focus") { focused("Show subtitle tracks") && node("Movie title") != null }
        assertNoUnexpectedFocus("Find subtitles online", "Show subtitle tracks")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down focuses the query without editing") { focused("Fixture subtitle query") }
        assertFalse("Directional focus never opens the phone IME", keyboard())
        assertNull("A focused navigation field remains read-only", editable())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down reaches Languages") { focused("en") }
        assertFalse("Directional language focus leaves the keyboard hidden", keyboard())
        key(KeyEvent.KEYCODE_DPAD_UP)
        await("Up returns to query") { focused("Fixture subtitle query") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK activates the query editor and keyboard together") { keyboard() && editable() != null }
        assertTrue(requireNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Revised subtitle query") }))
        await("The title edit is applied") { node("Revised subtitle query") != null }
        NavigationAuditEvidence.capture("subtitle-phone-search-editing")
        focusEvents.clear()
        key(KeyEvent.KEYCODE_BACK)
        await("First Back exits editor and keyboard while retaining query focus") {
            !keyboard() && editable() == null && focused("Revised subtitle query") && node("Show subtitle tracks") != null
        }
        assertNoUnexpectedFocus("Revised subtitle query", "Movie title")
        NavigationAuditEvidence.capture("subtitle-phone-search-editor-back")

        // Switch from remote to touch on the same mounted field, then back again.
        ins.setInTouchMode(true)
        tap(requireNotNull(node("Revised subtitle query")))
        await("Touch explicitly reactivates the editor and keyboard") { keyboard() && editable() != null }
        ins.setInTouchMode(false)
        key(KeyEvent.KEYCODE_BACK)
        await("Back after touch restores the read-only query target") { !keyboard() && editable() == null && focused("Revised subtitle query") }
        focusEvents.clear()
        key(KeyEvent.KEYCODE_BACK)
        await("Next Back exits Online and focuses its surviving header") { node("Movie title") == null && focused("Find subtitles online") }
        assertNoUnexpectedFocus("Find subtitles online", "Show subtitle tracks", "Revised subtitle query", "Movie title")
        focusEvents.clear()
        key(KeyEvent.KEYCODE_BACK)
        await("Final Back closes the popup and restores its origin") { node("Off") == null && focused("Subtitle origin") }
        assertNoUnexpectedFocus("Subtitle origin")
    }

    @Test fun unsupportedNativeControlsHaveOnlyMountedFocusDestinations() {
        fixture(trackCount = 2, selectedId = "track-2", delaySupported = false) {
            await("Selected native track receives focus") { focused("Subtitle language 2") }
            assertNull(node("Show subtitles earlier"))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Unsupported delay is skipped for appearance") { focused("Appearance: Standard") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Appearance Up returns to the last track") { focused("Subtitle language 2") }
        }
        fixture(trackCount = 2, selectedId = "track-2", appearanceSupported = false) {
            await("Selected VLC track receives focus") { focused("Subtitle language 2") }
            assertNull(node("Appearance: Standard"))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Native delay remains reachable") { focused("Show subtitles earlier") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("Delay graph stays on a mounted control", focused("Show subtitles earlier"))
        }
        fixture(trackCount = 2, selectedId = "track-2", delaySupported = false, appearanceSupported = false) {
            await("Selected track receives focus without custom controls") { focused("Subtitle language 2") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Last track returns to header when no adjustment is supported") { focused("Find subtitles online") }
        }
    }

    private fun fixture(trackCount: Int, selectedId: String?, landscape: Boolean = false,
        delaySupported: Boolean = true, appearanceSupported: Boolean = true, test: () -> Unit) {
        check(context.packageName.endsWith(".regression")) { "Run against the isolated regression package" }
        selected.value = selectedId
        delay.value = 0L
        appearance.value = SubtitleAppearancePreset.STANDARD
        val flags = ins.uiAutomation.serviceInfo.flags
        ins.uiAutomation.serviceInfo = ins.uiAutomation.serviceInfo.apply {
            this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && event.packageName?.toString() == context.packageName) {
                focusEvents += labels(event.source).ifBlank { event.text.joinToString() }
            }
        }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.runOnMainSync { host.requestedOrientation = if (landscape)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Fixture uses the requested physical orientation") {
                val view = host.window.decorView
                if (landscape) view.width > view.height else view.height > view.width
            }
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val phone = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (landscape) 800 else 400
                    screenHeightDp = if (landscape) 400 else 800
                    smallestScreenWidthDp = 400
                    orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                CompositionLocalProvider(LocalConfiguration provides phone) {
                    val open = remember { mutableStateOf(false) }
                    val origin = remember { FocusRequester() }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.initialFocusEntry(origin).padding(20.dp)) {
                            Button(onClick = {}) { Text("Unrelated Back") }
                            Button(onClick = { open.value = true }, modifier = Modifier.focusRequester(origin).focusProperties { canFocus = true }) { Text("Subtitle origin") }
                        }
                    }
                    if (open.value) SubtitleSelectionDialog(
                        tracks = (1..trackCount).map { SubtitleTrackOption("track-$it", trackLabel(it), selected.value == "track-$it") },
                        delayMs = delay.value,
                        onSelect = { selected.value = it },
                        onDelayChange = { delay.value = it },
                        onDismiss = { origin.requestFocus(); open.value = false },
                        appearance = appearance.value,
                        onAppearanceChange = { appearance.value = it },
                        internetSearch = SubtitleSearchRequest("Fixture subtitle query"),
                        onExternalSubtitle = { fail("Navigation fixture must not download a subtitle") },
                        delaySupported = delaySupported,
                        appearanceSupported = appearanceSupported
                    )
                }
            } } }
            await("Fixture origin receives initial focus") { focused("Subtitle origin") }
            focusEvents.clear()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            test()
        } catch (error: Throwable) {
            NavigationAuditEvidence.capture("subtitle-navigation-failure")
            throw AssertionError("${error.message}; focus events=${focusEvents.takeLast(16)}", error)
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.uiAutomation.serviceInfo = ins.uiAutomation.serviceInfo.apply { this.flags = flags }
            ins.runOnMainSync { host.finish() }
            ins.setInTouchMode(true)
        }
    }

    private fun trackLabel(number: Int) = if (number == 24) "Subtitle language 24 with a long descriptive track label" else "Subtitle language $number"
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun keyboard() = ins.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (test(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(root()?.takeIf { it.packageName?.toString() == context.packageName })
    }
    private fun node(label: String) = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun editable() = find { it.isEditable }
    private fun focused(label: String): Boolean {
        // A Compose/scroll root can itself report focus. Stop at the actual
        // control owner so that focus on a sibling cannot satisfy this assertion.
        val target = node(label) ?: return false
        val owner = generateSequence(target) { it.parent }
            .firstOrNull { it.isFocusable || it.isClickable || it.isEditable } ?: return false
        return owner.isFocused || owner == root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun labels(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val values = mutableListOf<String>()
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
        for (index in 0 until node.childCount) labels(node.getChild(index)).takeIf(String::isNotBlank)?.let(values::add)
        return values.joinToString(" | ")
    }
    private fun assertNoUnexpectedFocus(vararg allowed: String) {
        ins.waitForIdleSync()
        assertFalse("Focus never falls back to the page's other action: $focusEvents", focusEvents.any { "Unrelated Back" in it })
        assertTrue("Only transition destinations receive reported focus: $focusEvents", focusEvents.all { event ->
            event.isBlank() || allowed.any { it in event }
        })
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("The touch target has visible bounds", bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { ins.sendPointerSync(event) } finally { event.recycle() }
        }
    }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (test()) return
            SystemClock.sleep(40)
        }
        fail(message)
    }
}
