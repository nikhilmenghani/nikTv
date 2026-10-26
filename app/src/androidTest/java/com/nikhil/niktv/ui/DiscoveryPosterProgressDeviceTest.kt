package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.DiscoveryPosterTile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Quiet local poster fixture; no accessibility service, network, media or personal app state. */
@SdkSuppress(minSdkVersion = 26)
class DiscoveryPosterProgressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun televisionFocusBorderPreservesVisibleRedProgressAndPosterGeometry() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val actual = context.resources.configuration
        assumeTrue("Physical tablet only", actual.smallestScreenWidthDp >= 600 &&
            (actual.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION &&
            !context.packageManager.hasSystemFeature("android.software.leanback"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val bounds = AtomicReference(Rect())
        val measured = AtomicReference(IntSize.Zero)
        val density = AtomicReference(1f)
        val focused = AtomicBoolean(false)
        val requester = AtomicReference<FocusRequester>()
        try {
            await("Fixture has window focus") { host.hasWindowFocus() }
            instrumentation.runOnMainSync {
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.isHapticFeedbackEnabled = false
                host.setContent {
                    val television = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                    }
                    CompositionLocalProvider(LocalConfiguration provides television) {
                        MaterialTheme {
                            val focus = remember { FocusRequester() }
                            val scale = LocalDensity.current.density
                            SideEffect { requester.set(focus); density.set(scale) }
                            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                DiscoveryPosterTile("Progress fixture", artwork = null, subtitle = "Local movie", width = 160.dp,
                                    progress = .5f, modifier = Modifier.focusRequester(focus)
                                        .onFocusChanged { focused.set(it.isFocused) }
                                        .onSizeChanged { measured.set(it) }
                                        .onGloballyPositioned { coordinates ->
                                            val value = coordinates.boundsInWindow()
                                            bounds.set(Rect(value.left.roundToInt(), value.top.roundToInt(),
                                                value.right.roundToInt(), value.bottom.roundToInt()))
                                        }, onClick = {})
                            }
                        }
                    }
                }
            }
            await("Poster is measured") { measured.get() != IntSize.Zero && !bounds.get().isEmpty && requester.get() != null }
            val before = measured.get()
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { requester.get().requestFocus() }
            await("Poster itself receives focus") { focused.get() }
            frames(3)
            assertTrue("Focused root must remain focused", focused.get())
            assertEquals("Focus must not resize the poster", before, measured.get())
            assertEquals("Poster width remains 160dp", 160f * density.get(), measured.get().width.toFloat(), 1f)
            assertEquals("Poster keeps its 2:3 aspect ratio", measured.get().width * 1.5f, measured.get().height.toFloat(), 1f)

            val area = bounds.get()
            val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
            try {
                val copied = CountDownLatch(1)
                val status = AtomicInteger(-1)
                PixelCopy.request(host.window, area, bitmap, { result -> status.set(result); copied.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue("Fixture pixels are copied in memory", copied.await(4, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, status.get())
                // The 3dp progress canvas must remain visible just above the
                // original 2dp border. A 3dp focused border covers this point.
                val x = (bitmap.width * .25f - .5f).roundToInt().coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height - 2.5f * density.get() - .5f).roundToInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val expected = 0xFFE50925.toInt()
                assertTrue("Progress stays red while focused: ${Integer.toHexString(pixel)} at ($x,$y)",
                    abs(AndroidColor.red(pixel) - AndroidColor.red(expected)) <= 24 &&
                        abs(AndroidColor.green(pixel) - AndroidColor.green(expected)) <= 24 &&
                        abs(AndroidColor.blue(pixel) - AndroidColor.blue(expected)) <= 24)
            } finally { bitmap.recycle() }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; Thread.sleep(15) }
        fail(message)
    }
    private fun frames(count: Int) {
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                var remaining = count
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining <= 0) complete.countDown() else Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue("Physical frame clock advances", complete.await(4, TimeUnit.SECONDS))
    }
}
