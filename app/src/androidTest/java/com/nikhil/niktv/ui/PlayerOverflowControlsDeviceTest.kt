package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class PlayerOverflowControlsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isFocusable) node = node.parent
        return node?.isFocused == true
    }
    private fun bounds(label: String): Rect {
        var node = checkNotNull(find(label))
        while (!node.isFocusable && node.parent != null) node = node.parent
        return Rect().also(node::getBoundsInScreen)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("player-overflow-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)

    @Test fun phoneAndTabletOverflowHaveTwoDimensionalNavigationAndRestoreOrigins() {
        for (phone in listOf(false, true)) exercise(phone)
    }

    private fun exercise(phone: Boolean) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val activations = mutableListOf<Int>()
        try {
            ins.runOnMainSync { host.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = if (phone) 400 else 800
                    screenWidthDp = if (phone) 400 else 960
                    screenHeightDp = if (phone) 800 else 600
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) { MaterialTheme(colorScheme = NikColors) {
                    val toggle = remember { FocusRequester() }
                    val choices = remember { List(12) { FocusRequester() } }
                    val scope = rememberCoroutineScope()
                    var open by remember { mutableStateOf(false) }
                    var selected by remember { mutableStateOf<Int?>(null) }
                    fun dismiss() {
                        open = false
                        scope.launch { delay(80L); toggle.requestFocus() }
                    }
                    fun dismissChoice() {
                        val origin = selected
                        selected = null
                        scope.launch { delay(80L); origin?.let { choices[it].requestFocus() } }
                    }
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomEnd) {
                            PlayerChromeIconButton(Icons.Default.Settings, "More controls", { open = true }, Modifier.focusRequester(toggle))
                            PlayerExtraControls(open, choices.first(), ::dismiss) {
                                choices.forEachIndexed { index, requester ->
                                    PlayerChromeIconButton(Icons.Default.Settings, "Setting $index", {
                                        activations.add(index); selected = index
                                    }, Modifier.playerSecondaryFocus(requester, choices, toggle, toggle, toggle))
                                }
                            }
                        }
                    }
                    if (selected != null) AlertDialog(onDismissRequest = ::dismissChoice,
                        title = { Text("Setting choice") }, confirmButton = {
                            TextButton(onClick = ::dismissChoice) { Text("Close choice") }
                        })
                    LaunchedEffect(Unit) { repeat(20) { withFrameNanos { }; if (runCatching { toggle.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
                } }
            } }
            await("More controls owns initial focus") { focused("More controls") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Overflow opens on its first setting") { focused("Setting 0") && find("Playback settings") != null }
            val columns = if (phone) 3 else 4
            for (index in 0..11) assertTrue("Setting $index fits inside the panel", find("Setting $index")?.isVisibleToUser == true)
            assertEquals("The visible row matches the D-pad column count", bounds("Setting 0").top, bounds("Setting ${columns - 1}").top)
            assertTrue("The next setting begins the next visual row", bounds("Setting $columns").top > bounds("Setting 0").top)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches the next setting") { focused("Setting 1") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down retains the same settings column") { focused("Setting ${1 + columns}") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Choosing a setting opens its own dialog") { find("Setting choice") != null }
            key(KeyEvent.KEYCODE_BACK)
            await("Dialog closes back to its originating setting") { find("Setting choice") == null && focused("Setting ${1 + columns}") }
            assertEquals(listOf(1 + columns), activations)
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to the previous settings row") { focused("Setting 1") }
            Thread.sleep(180)
            assertTrue("Late dialog focus restoration must not override the next arrow", focused("Setting 1"))
            key(KeyEvent.KEYCODE_TAB)
            await("Tab can leave the last arrow destination") { focused("Setting 2") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Arrow navigation resumes after Tab") { focused("Setting 1") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to the first setting") { focused("Setting 0") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Top row Up reaches Close") { focused("Close playback settings") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Close Down returns to settings") { focused("Setting 0") }
            NavigationAuditEvidence.capture("player-overflow-${if (phone) "phone" else "tablet"}")
            key(KeyEvent.KEYCODE_BACK)
            await("Back closes overflow and restores More") { find("Playback settings") == null && focused("More controls") }
            assertEquals("Moving focus and closing do not activate other settings", listOf(1 + columns), activations)
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
