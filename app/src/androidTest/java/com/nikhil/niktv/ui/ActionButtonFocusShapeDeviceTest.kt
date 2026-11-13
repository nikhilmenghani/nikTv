package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.nikhil.niktv.discovery.*
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Shared actions must draw the supplied object shape, not an unrelated default outline. */
class ActionButtonFocusShapeDeviceTest {
    private class Probe(private val shape: Shape) : Shape {
        val calls = AtomicInteger()
        val size = AtomicReference(Size.Zero)
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            calls.incrementAndGet()
            this.size.set(size)
            return shape.createOutline(size, layoutDirection, density)
        }
    }
    @Test fun primarySecondaryAndTextActionsRetainObjectGeometryDuringDpadFocus() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val activity = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val probes = listOf(Probe(RoundedCornerShape(4.dp)), Probe(CutCornerShape(8.dp)), Probe(CircleShape))
        val request = FocusRequester()
        fun target(label: String): AccessibilityNodeInfo? {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == label) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            var node = find(ins.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            return node
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(40) }
            fail(label)
        }
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NikTvPrimaryActionButton({}, Modifier.width(160.dp).height(56.dp).focusRequester(request), shape = probes[0]) { Text("Play") }
                    NikTvSecondaryActionButton({}, Modifier.width(260.dp).height(56.dp), shape = probes[1]) { Text("All categories with long label") }
                    NikTvTextActionButton({}, Modifier.width(160.dp).height(56.dp), shape = probes[2]) { Text("Done") }
                }
                LaunchedEffect(Unit) { withFrameNanos { }; request.requestFocus() }
            } } }
            val labels = listOf("Play", "All categories with long label", "Done")
            val before = labels.map { label ->
                await("$label appears") { target(label) != null }
                android.graphics.Rect().also { target(label)!!.getBoundsInScreen(it) }
            }
            labels.forEachIndexed { index, label ->
                await("D-pad focuses $label") { target(label)?.isFocused == true }
                assertTrue("$label uses its supplied shape", probes[index].calls.get() > 0)
                val bounds = android.graphics.Rect().also { target(label)!!.getBoundsInScreen(it) }
                assertEquals("Focus must not resize $label", before[index], bounds)
                assertEquals("Outline width matches $label", bounds.width().toFloat(), probes[index].size.get().width, 1f)
                assertEquals("Outline height matches $label", bounds.height().toFloat(), probes[index].size.get().height, 1f)
                NavigationAuditEvidence.capture("shared-action-focus-$index")
                if (index < labels.lastIndex) ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        } finally { ins.runOnMainSync { activity.finish() }; ins.setInTouchMode(true) }
    }
    @Test fun phoneLandscapeKeepsActionToPosterRatiosCloseToTv() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val phone = mutableStateOf(false)
        val sizes = Array(2) { Array(3) { AtomicReference(0f) } }
        try {
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val config = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (phone.value) 800 else 960
                    screenHeightDp = if (phone.value) 360 else 540
                    smallestScreenWidthDp = if (phone.value) 390 else 540
                    fontScale = 1f
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        if (phone.value) Configuration.UI_MODE_TYPE_NORMAL else Configuration.UI_MODE_TYPE_TELEVISION
                }
                val mode = if (phone.value) 1 else 0
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalConfiguration provides config, LocalMobileGlassEnabled provides true) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompositionLocalProvider(LocalDiscoveryHeaderActionSizing provides true) {
                            DiscoveryAction("Header", modifier = Modifier.onSizeChanged { sizes[mode][0].set(it.height / density) }) {}
                        }
                        CompositionLocalProvider(LocalHeroActionSizing provides true) {
                            DiscoveryAction("Hero", modifier = Modifier.onSizeChanged { sizes[mode][1].set(it.height / density) }) {}
                        }
                        CompositionLocalProvider(LocalDetailActionGlass provides true, LocalHeroActionSizing provides true) {
                            DiscoveryAction("Detail", modifier = Modifier.onSizeChanged { sizes[mode][2].set(it.height / density) }) {}
                        }
                    }
                }
            } } }
            fun awaitSizes(mode: Int) {
                val until = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < until && sizes[mode].any { it.get() == 0f }) Thread.sleep(40)
                assertTrue("Actions have measured geometry", sizes[mode].all { it.get() > 0f })
            }
            awaitSizes(0)
            ins.runOnMainSync { phone.value = true }
            awaitSizes(1)
            val tvPoster = discoveryPosterWidth(960f, 540f) * 1.5f
            val phonePoster = discoveryPosterWidth(800f, 360f) * 1.5f
            for (kind in 0..2) {
                val tvRatio = sizes[0][kind].get() / tvPoster
                val phoneRatio = sizes[1][kind].get() / phonePoster
                assertTrue("Landscape action $kind must be smaller than TV", sizes[1][kind].get() < sizes[0][kind].get())
                assertEquals("Action $kind stays proportional to tiles", tvRatio, phoneRatio, tvRatio * .12f)
            }
            NavigationAuditEvidence.capture("landscape-action-proportions")
        } finally { ins.runOnMainSync { host.finish() } }
    }

    @Test fun episodeGridKeepsEqualHeightsWhenLastWatchedChanges() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val watched = mutableStateOf(true)
        val heights = Array(2) { AtomicInteger() }
        try {
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val config = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 800; screenHeightDp = 360; smallestScreenWidthDp = 390
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL
                }
                CompositionLocalProvider(LocalConfiguration provides config) {
                Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in 0..1) DiscoveryEpisodeTile("Episode with a two line title", null, "S1 · E${i + 1}", "2026-11-12",
                        modifier = Modifier.width(112.dp).onSizeChanged { heights[i].set(it.height) },
                        resume = i == 0 && watched.value, remaining = if (i == 0 && watched.value) "7 min left" else null,
                        onClick = {}, onLongClick = {})
                }
            } } } }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline && heights.any { it.get() == 0 }) Thread.sleep(40)
            assertTrue("Cards have measured sizes", heights.all { it.get() > 0 })
            assertEquals("Last-watched card matches unwatched card", heights[0].get(), heights[1].get())
            val original = heights[0].get()
            ins.runOnMainSync { watched.value = false }
            ins.waitForIdleSync()
            assertEquals("Clearing watch status retains height", original, heights[0].get())
            assertEquals("Both cards retain one geometry", heights[0].get(), heights[1].get())
            NavigationAuditEvidence.capture("episode-grid-equal-heights")
        } finally { ins.runOnMainSync { host.finish() } }
    }

}
