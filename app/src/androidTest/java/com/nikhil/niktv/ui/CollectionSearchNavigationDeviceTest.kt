package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.DiscoveryCollectionSearch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Filtering remains usable after dismissing the keyboard; browsing never reopens it. */
class CollectionSearchNavigationDeviceTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return i.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (test(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), test)?.let { return it }
        return null
    }
    private fun node(value: String) = find(root()) { it.text?.toString() == value || it.contentDescription?.toString() == value }
    private fun editable() = find(root()) { it.isEditable }
    private fun focused(value: String): Boolean {
        var target = node(value)
        val actual = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        while (target != null) {
            if (target == actual) return true
            target = target.parent
        }
        return false
    }
    private fun keyboard() = i.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    private fun key(code: Int) = i.sendKeyDownUpSync(code)
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(70) }
        fail(message)
    }

    @Test fun doneAndKeyboardBackPreserveFilterAndDpadOnlyEditsAfterActivation() {
        val context = i.targetContext
        check(context.packageName.endsWith(".regression"))
        val flags = i.uiAutomation.serviceInfo.flags
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply {
            this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val query = mutableStateOf("")
        val exited = AtomicBoolean()
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            i.setInTouchMode(false)
            i.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                BackHandler { exited.set(true) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        DiscoveryCollectionSearch(query.value, { query.value = it }, compact = true)
                        Button(onClick = {}) { Text(if (query.value.isBlank()) "Unfiltered title" else "Match: ${query.value}") }
                    }
                }
            } } }
            await("Search control appears") { node("Search titles") != null }
            var search = node("Search titles")
            while (search != null && !search.isFocusable) search = search.parent
            assertTrue(checkNotNull(search).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("Search receives initial focus") { focused("Search titles") }
            assertFalse("Focus does not open keyboard", keyboard())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Explicit activation starts editing") { editable() != null && keyboard() }
            assertTrue(checkNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Test") }))
            await("Filter reaches the result list") { query.value == "Test" && node("Match: Test") != null }
            NavigationAuditEvidence.capture("collection-search-editing")
            if (Build.VERSION.SDK_INT >= 30) {
                assertTrue("IME Done action is available", checkNotNull(editable()).performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            } else key(KeyEvent.KEYCODE_BACK)
            await("Done hides keyboard while keeping the filter") { !keyboard() && editable() == null && query.value == "Test" }
            assertNotNull(node("Close search"))
            assertFalse(exited.get())
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters matching results") { focused("Match: Test") }
            assertFalse("Browsing results leaves keyboard hidden", keyboard())
            NavigationAuditEvidence.capture("collection-search-filtered-results")
            key(KeyEvent.KEYCODE_DPAD_UP)
            Thread.sleep(120)
            NavigationAuditEvidence.capture("collection-search-return-filter")
            await("Up returns to the read-only filter") { focused("Test") }
            assertFalse("Returning focus to filter does not open keyboard", keyboard())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Center explicitly resumes editing") { editable() != null && keyboard() }
            key(KeyEvent.KEYCODE_BACK)
            await("Keyboard Back preserves the entered filter") { !keyboard() && editable() == null && query.value == "Test" }
            key(KeyEvent.KEYCODE_BACK)
            await("Next Back clears filter and restores search icon") { query.value.isEmpty() && focused("Search titles") }
            assertFalse("Clearing the filter does not leave the collection", exited.get())
            NavigationAuditEvidence.capture("collection-search-cleared")
            key(KeyEvent.KEYCODE_BACK)
            await("Back from browsing returns to parent") { exited.get() }
        } finally {
            i.runOnMainSync { host.finish() }
            i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply { this.flags = flags }
        }
    }
}
