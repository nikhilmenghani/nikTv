package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PlayerOverflowControlsDeviceTest {
    private val actionsCount = 11
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow?.takeIf { it.refresh() }
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

    @Test fun bottomSettingsKeepDockFixedAndRestoreDpadFocus() = exercise(false)
    @Test fun narrowBottomSettingsScrollWithoutWrappingOrLosingFocus() {
        org.junit.Assume.assumeFalse("Phone-width fixture; Fire TV uses the full-screen row",
            ins.targetContext.isTvLikeDevice(ins.targetContext.resources.configuration))
        exercise(true)
    }

    private fun exercise(narrow: Boolean) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val history = java.util.concurrent.CopyOnWriteArrayList<String>()
        val activations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val paintedWidths = java.util.concurrent.ConcurrentHashMap<PlayerSettingKind, Int>()
        val paintedBounds = java.util.concurrent.ConcurrentHashMap<PlayerSettingKind, Rect>()
        val dockLayoutBounds = java.util.concurrent.ConcurrentHashMap<String, Rect>()
        fun layoutBounds(label: String, coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
            // Focus scales the drawing around its center, not its layout slot.
            // Accessibility bounds include that transient scale and touch inset.
            val center = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            dockLayoutBounds[label] = Rect(kotlin.math.round(center.x - coordinates.size.width / 2f).toInt(),
                kotlin.math.round(center.y - coordinates.size.height / 2f).toInt(),
                kotlin.math.round(center.x + coordinates.size.width / 2f).toInt(),
                kotlin.math.round(center.y + coordinates.size.height / 2f).toInt())
        }
        var fixtureToggle: FocusRequester? = null
        var fixtureScroll: ScrollState? = null
        try {
            await("Fixture window is ready for focus") { host.hasWindowFocus() }
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val toggle = remember { FocusRequester() }
                fixtureToggle = toggle
                val seek = remember { FocusRequester() }
                val play = remember { FocusRequester() }
                val requesters = remember { PlayerSettingKind.entries.associateWith { FocusRequester() } }
                val state = remember { PlayerSettingsRibbonState() }
                var open by remember { mutableStateOf(false) }
                var choice by remember { mutableStateOf<PlayerSettingKind?>(null) }
                val actions = listOf(PlayerSettingKind.SUBTITLES, PlayerSettingKind.AUDIO, PlayerSettingKind.SPEED,
                    PlayerSettingKind.FIT, PlayerSettingKind.PICTURE, PlayerSettingKind.PLAYER, PlayerSettingKind.DOWNLOAD,
                    PlayerSettingKind.INTRO, PlayerSettingKind.TIMEOUT, PlayerSettingKind.CRICKET, PlayerSettingKind.INFORMATION)
                    .map { kind -> PlayerSettingAction(kind, Icons.Default.Settings, kind.title,
                        onClick = { activations.add(kind.title); choice = kind }, requester = requesters.getValue(kind),
                        modifier = Modifier.onGloballyPositioned {
                            paintedWidths[kind] = it.size.width
                            val visible = it.boundsInWindow()
                            paintedBounds[kind] = Rect(kotlin.math.round(visible.left).toInt(), kotlin.math.round(visible.top).toInt(),
                                kotlin.math.round(visible.right).toInt(), kotlin.math.round(visible.bottom).toInt())
                        }
                            .onFocusChanged { if (it.isFocused) history.add(kind.title) }, value = "Current") }
                Surface(Modifier.fillMaxSize().initialFocusEntry(toggle)) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        Column(if (narrow) Modifier.width(360.dp) else Modifier.fillMaxWidth()) {
                            PlayerChromeIconButton(Icons.Default.Settings, "Seek fixture", {}, Modifier.focusRequester(seek)
                                .onGloballyPositioned { layoutBounds("Seek fixture", it) }
                                .focusProperties { down = play }
                                .onFocusChanged { if (it.isFocused) history.add("Seek") })
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                val dockScroll = rememberScrollState().also { fixtureScroll = it }
                                PlayerBottomActions(dockScroll) {
                                    PlayerChromeIconButton(Icons.Default.Settings, "Play fixture", {}, Modifier.focusRequester(play)
                                        .onGloballyPositioned { layoutBounds("Play fixture", it) }
                                        .focusProperties { right = if (open) state.firstOr(toggle) else toggle; up = seek }
                                        .onFocusChanged { if (it.isFocused) history.add("Play") })
                                    Spacer(Modifier.weight(1f))
                                    PlayerSettingsRibbon(open, actions, state, toggle, play, seek, { open = false })
                                }
                                PlayerChromeIconButton(Icons.Default.Settings, "Settings fixture", {
                                    state.open(actions); open = true
                                }, Modifier.focusRequester(toggle)
                                    .focusProperties { left = if (open) state.entry(actions) else play; up = seek }
                                    .playerDpadFocusRoutes(left = if (open) state.entry(actions) else play, up = seek)
                                    .onFocusChanged { if (it.isFocused) history.add("Toggle") })
                            }
                        }
                    }
                }
                if (choice != null) AlertDialog(onDismissRequest = { choice = null }, title = { Text("Setting choice") },
                    confirmButton = { TextButton(onClick = { choice = null }) { Text("Close choice") } })
            } } }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Settings is attached") { find("Settings fixture") != null }
            ins.runOnMainSync { requireNotNull(fixtureToggle).requestFocus() }
            await("Settings owns initial focus") { focused("Settings fixture") }
            val playBefore = checkNotNull(dockLayoutBounds["Play fixture"])
            val seekBefore = checkNotNull(dockLayoutBounds["Seek fixture"])
            val openedAt = history.size
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Opening retains Settings") { focused("Settings fixture") && find("Subtitles") != null }
            assertTrue("Opening must not move focus", history.drop(openedAt).isEmpty())
            assertEquals("Opening leaves Play anchored", playBefore, dockLayoutBounds["Play fixture"])
            assertEquals("Opening leaves seek anchored", seekBefore, dockLayoutBounds["Seek fixture"])
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left enters the physically adjacent last setting") { focused("Stream info") }
            assertEquals("Left must not jump across the row", listOf("Stream info"), history.drop(openedAt))
            repeat(actionsCount - 1) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await("Opening enters the first quick control") { focused("Subtitles") }
            val subtitlesBefore = bounds("Subtitles")
            val paintedSubtitleBefore = paintedWidths[PlayerSettingKind.SUBTITLES]
            val expectedSettingDp = if (ins.targetContext.isTvLikeDevice(ins.targetContext.resources.configuration)) 40 else 44
            assertTrue("Compact settings must respect their requested size rather than the transport minimum",
                kotlin.math.abs(requireNotNull(paintedWidths[PlayerSettingKind.SUBTITLES]) - expectedSettingDp * ins.targetContext.resources.displayMetrics.density) <= 1f)
            assertEquals("Settings share the transport baseline", playBefore.centerY(), subtitlesBefore.centerY())
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters Audio") { focused("Audio") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters Speed") { focused("Speed") }
            assertEquals("Quick controls share a single row", subtitlesBefore.top, bounds("Speed").top)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Setting choice is open") { find("Setting choice") != null }
            val dialogClosedAt = history.size
            key(KeyEvent.KEYCODE_BACK)
            await("Dialog restores its exact origin") { find("Setting choice") == null && focused("Speed") }
            assertTrue("Dialog return retains Speed without visiting another main control",
                history.drop(dialogClosedAt).all { it == "Speed" })
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Navigation after dialog return is immediate") { focused("Video fit") }
            Thread.sleep(180)
            assertTrue("No late correction steals the arrow destination", focused("Video fit"))
            // Every control remains in the same bottom row, including overflow.
            val remaining = listOf("Picture", "Player", "Download", "Intro timing", "Controls timeout", "Cricket scores", "Stream info")
            for (label in remaining) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches $label") { focused(label) }
                assertEquals("Settings stay aligned with the bottom trigger", bounds("Settings fixture").centerY(), bounds(label).centerY())
            }
            val info = bounds("Stream info")
            val toggleBounds = bounds("Settings fixture")
            assertTrue("Overflow scrolls the focused control fully into view", info.right <= toggleBounds.left && info.left >= seekBefore.left)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Last control reaches the settings trigger") { focused("Settings fixture") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Trigger returns to the last setting") { focused("Stream info") }
            val closedAt = history.size
            key(KeyEvent.KEYCODE_BACK)
            await("Back closes and retains the trigger") { find("Subtitles") == null && focused("Settings fixture") }
            assertEquals("Close transfers directly to the surviving trigger", listOf("Toggle"), history.drop(closedAt))
            assertEquals(listOf("Speed"), activations)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Reopening retains Settings") { focused("Settings fixture") && find("Subtitles") != null }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Reopening enters the nearest setting") { focused("Stream info") }
            repeat(actionsCount - 1) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await("Reopening remains reachable") { focused("Subtitles") }
            assertEquals("Focus outline does not resize its cell", paintedSubtitleBefore, paintedWidths[PlayerSettingKind.SUBTITLES])
            if (narrow) {
                // Pixel evidence, rather than semantic bounds alone: an edge
                // focus ring must retain both white vertical strokes after scroll.
                // Focus semantics update before bring-into-view finishes its
                // animation. Inspect the final painted viewport, not that
                // legitimate intermediate scroll frame.
                ins.waitForIdleSync()
                await("Focused setting finishes scrolling into its viewport") { fixtureScroll?.isScrollInProgress == false }
                ins.waitForIdleSync()
                val image = checkNotNull(ins.uiAutomation.takeScreenshot())
                try {
                    // Accessibility expands small controls' touch bounds to
                    // 48dp. Sample the actual 44dp painted object's outline.
                    val cell = checkNotNull(paintedBounds[PlayerSettingKind.SUBTITLES])
                    val folder = java.io.File(ins.targetContext.externalCacheDir, "navigation-audit").apply { mkdirs() }
                    java.io.File(folder, "player-bottom-settings-narrow-outline.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    java.io.File(folder, "player-bottom-settings-narrow-outline.txt").writeText("bounds=$cell density=${ins.targetContext.resources.displayMetrics.density}")
                    val stroke = kotlin.math.ceil(2.5f * ins.targetContext.resources.displayMetrics.density).toInt()
                    fun whitePixels(start: Int, end: Int): Int = (start until end).count { x ->
                        val pixel = image.getPixel(x.coerceIn(0, image.width - 1), cell.centerY().coerceIn(0, image.height - 1))
                        android.graphics.Color.red(pixel) >= 225 && android.graphics.Color.green(pixel) >= 225 && android.graphics.Color.blue(pixel) >= 225
                    }
                    val required = kotlin.math.ceil(1.5f * ins.targetContext.resources.displayMetrics.density).toInt()
                    assertTrue("Left focus stroke is fully painted inside the scroll viewport", whitePixels(cell.left, cell.left + stroke) >= required)
                    assertTrue("Right focus stroke is fully painted inside the scroll viewport", whitePixels(cell.right - stroke, cell.right) >= required)
                } finally { image.recycle() }
            }
            NavigationAuditEvidence.capture("player-bottom-settings-${if (narrow) "narrow" else "wide"}")
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches the seek row") { focused("Seek fixture") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down returns to Play") { focused("Play fixture") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reenters the settings strip") { focused("Subtitles") }
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
