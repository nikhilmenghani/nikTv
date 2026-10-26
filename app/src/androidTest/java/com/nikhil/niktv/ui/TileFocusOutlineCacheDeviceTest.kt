package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Quiet, synthetic tablet drawing fixture. No credentials, network, media or accessibility service. */
@SdkSuppress(minSdkVersion = 26)
class TileFocusOutlineCacheDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fill = Color(0xFF103050)

    private class CountingShape : Shape {
        val calls = AtomicInteger()
        val lastSize = AtomicReference(Size.Zero)
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            calls.incrementAndGet()
            lastSize.set(size)
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
    }

    private class Probe {
        val shape = CountingShape()
        val progress = mutableFloatStateOf(0f)
        val width = mutableStateOf(160.dp)
        val repaint = mutableIntStateOf(0)
        val draws = AtomicInteger()
        val density = AtomicReference(1f)
        val measured = AtomicReference(IntSize.Zero)
        val bounds = AtomicReference(AndroidRect())
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(15)
        assertTrue(label, condition())
    }

    private fun frames(count: Int = 2) {
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                var remaining = count
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining <= 0) complete.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue("Physical frame clock must advance", complete.await(4, TimeUnit.SECONDS))
    }

    private fun fixture(test: (PlaybackFixtureHostActivity, Probe) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue("Physical tablet only", context.resources.configuration.smallestScreenWidthDp >= 600 &&
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION &&
            !context.packageManager.hasSystemFeature("android.software.leanback"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val probe = Probe()
        try {
            instrumentation.runOnMainSync {
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.isHapticFeedbackEnabled = false
                host.setContent {
                    val density = LocalDensity.current.density
                    SideEffect { probe.density.set(density) }
                    // Keep this modifier instance stable. Repaint state is read only in
                    // the outer draw pass, so a redraw cannot substitute a fresh cache.
                    val outline = remember(probe) {
                        Modifier.tileFocusOutline(true, probe.progress, probe.shape, Color.Red, 4.dp)
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(probe.width.value, 100.dp)
                            .onSizeChanged { probe.measured.set(it) }
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                probe.bounds.set(AndroidRect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                                    bounds.right.roundToInt(), bounds.bottom.roundToInt()))
                            }
                            .drawWithContent {
                                drawRect(if (probe.repaint.intValue % 2 == 0) fill else Color(0xFF204060))
                                drawContent()
                                probe.draws.incrementAndGet()
                            }.then(outline))
                    }
                }
            }
            await("Outline and tile must draw") { probe.draws.get() > 0 && probe.shape.calls.get() > 0 && !probe.bounds.get().isEmpty }
            frames(3)
            test(host, probe)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun unrelatedContentRedrawReusesTheCachedShapeOutline() = fixture { _, probe ->
        val outlines = probe.shape.calls.get()
        repeat(6) {
            val drawn = probe.draws.get()
            instrumentation.runOnMainSync { probe.repaint.intValue++ }
            await("Unrelated content must actually redraw") { probe.draws.get() > drawn }
            frames(1)
            assertEquals("Stable border geometry must not be recreated for content redraws", outlines, probe.shape.calls.get())
        }
    }

    @Test fun progressAndSizeInvalidateGeometryAndUpdateTheVisibleBorder() = fixture { host, probe ->
        fun assertGeometry(strokeDp: Float) {
            val stroke = strokeDp * probe.density.get()
            assertEquals(probe.measured.get().width - stroke, probe.shape.lastSize.get().width, .5f)
            assertEquals(probe.measured.get().height - stroke, probe.shape.lastSize.get().height, .5f)
        }
        assertGeometry(1f)
        // This point is beyond the resting border but within a focused 4dp stroke.
        assertPixel(host, probe, false, fill)
        var previous = probe.shape.calls.get()
        instrumentation.runOnMainSync { probe.progress.floatValue = 1f }
        await("Focus progress must rebuild cached border geometry") { probe.shape.calls.get() > previous }
        frames()
        assertGeometry(4f)
        assertPixel(host, probe, false, Color(0xFFF2F3F5))

        previous = probe.shape.calls.get()
        instrumentation.runOnMainSync { probe.width.value = 200.dp }
        await("Changed size must rebuild the outline") {
            probe.shape.calls.get() > previous && probe.measured.get().width >= (199 * probe.density.get()).toInt()
        }
        frames()
        assertGeometry(4f)
        assertPixel(host, probe, true, Color(0xFFF2F3F5))
    }

    private fun assertPixel(host: PlaybackFixtureHostActivity, probe: Probe, right: Boolean, expected: Color) {
        val bounds = probe.bounds.get()
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            val complete = CountDownLatch(1)
            val result = AtomicInteger(-1)
            PixelCopy.request(host.window, bounds, bitmap, { status -> result.set(status); complete.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("Fixture pixels must be copied", complete.await(4, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result.get())
            val inset = (2 * probe.density.get()).roundToInt().coerceAtLeast(1)
            val x = if (right) bitmap.width - 1 - inset else inset
            val actual = bitmap.getPixel(x, bitmap.height / 2)
            assertTrue("Border red channel", abs(AndroidColor.red(actual) - (expected.red * 255).roundToInt()) <= 24)
            assertTrue("Border green channel", abs(AndroidColor.green(actual) - (expected.green * 255).roundToInt()) <= 24)
            assertTrue("Border blue channel", abs(AndroidColor.blue(actual) - (expected.blue * 255).roundToInt()) <= 24)
        } finally { bitmap.recycle() }
    }
}
