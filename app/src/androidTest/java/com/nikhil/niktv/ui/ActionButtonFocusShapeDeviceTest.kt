package com.nikhil.niktv.ui

import android.content.Intent
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
}
