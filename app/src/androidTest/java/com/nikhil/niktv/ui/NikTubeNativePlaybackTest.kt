package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.NikTubeActivity
import com.nikhil.niktv.data.NikTubeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NikTubeNativePlaybackTest {
    private fun progressBar(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.rangeInfo != null && node.rangeInfo.max > 100_000) return node
        for (index in 0 until node.childCount) progressBar(node.getChild(index))?.let { return it }
        return null
    }

    @Test fun progressBarSeeksForwardAndBackwardInDefaultLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = runBlocking { NikTubeClient().search("Patagonia nature documentary Infinite Planet").first() }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, NikTubeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NikTubeActivity
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                var satisfied = false
                var failure: String? = null
                instrumentation.runOnMainSync { satisfied = predicate(); failure = activity.tubeViewModel.error }
                assertNull("Native playback setup failed", failure)
                if (satisfied) return
                Thread.sleep(250)
            }
            fail("$message; positionMs=${activity.tubeViewModel.nativePlaybackPosition}, frames=${activity.tubeViewModel.decodedVideoFrames}")
        }
        try {
            instrumentation.runOnMainSync { activity.tubeViewModel.play(video, 0L) }
            await("Video must start and automatically enter landscape") {
                activity.tubeViewModel.nativePlaybackPosition >= 10_000 && activity.tubeViewModel.videoFrameRendered &&
                    activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            val duration = activity.tubeViewModel.nativePlaybackDuration
            fun click(label: String) {
                var node = find(instrumentation.uiAutomation.rootInActiveWindow, label)
                var clicked = false
                while (node != null && !clicked) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node = node.parent
                }
                assertTrue("Control must be clickable: $label", clicked)
            }
            for (fraction in listOf(0.35f, 0.08f, 0.65f)) {
                val time = android.os.SystemClock.uptimeMillis()
                val point = android.graphics.Point()
                instrumentation.runOnMainSync { point.set(activity.window.decorView.width / 2, activity.window.decorView.height / 2) }
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action,
                        point.x.toFloat(), point.y.toFloat(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                Thread.sleep(750)
                click("Pause")
                Thread.sleep(750)
                val bar = progressBar(instrumentation.uiAutomation.rootInActiveWindow)
                assertNotNull("The actual progress bar must be accessible", bar)
                val target = duration * fraction
                val frames = activity.tubeViewModel.decodedVideoFrames
                val bounds = android.graphics.Rect().also { bar!!.getBoundsInScreen(it) }
                Log.i("NikTubeSeekTest", "SEEK fraction=$fraction targetMs=$target priorMs=${activity.tubeViewModel.nativePlaybackPosition} frames=$frames bounds=$bounds")
                val downTime = android.os.SystemClock.uptimeMillis()
                val startX = bounds.left + (bounds.width() * (bar!!.rangeInfo.current / duration))
                    .coerceIn(24f, bounds.width() - 24f)
                val targetX = bounds.left + bounds.width() * fraction
                fun touch(action: Int, x: Float) {
                    val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
                    val coordinates = android.view.MotionEvent.PointerCoords().apply { this.x = x; y = bounds.exactCenterY(); pressure = 1f; size = 1f }
                    val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                        action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN, startX)
                repeat(8) { step ->
                    Thread.sleep(60)
                    touch(android.view.MotionEvent.ACTION_MOVE, startX + (targetX - startX) * (step + 1) / 8)
                }
                touch(android.view.MotionEvent.ACTION_UP, targetX)
                Thread.sleep(750)
                val after = progressBar(instrumentation.uiAutomation.rootInActiveWindow)
                val soughtTarget = after!!.rangeInfo.current.toLong()
                assertTrue("Drag must reach the intended part of the video", kotlin.math.abs(soughtTarget - target) < duration * 0.02)
                Log.i("NikTubeSeekTest", "DRAG previewMs=${after?.rangeInfo?.current}")
                click("Play")
                await("Playback must render and advance after seeking to $fraction") {
                    val vm = activity.tubeViewModel
                    vm.decodedVideoFrames > frames && vm.nativePlaybackPosition > soughtTarget + 4000 &&
                        vm.nativePlaybackPosition < soughtTarget + 60_000
                }
                Log.i("NikTubeSeekTest", "PASS video=${video.id} fraction=$fraction positionMs=${activity.tubeViewModel.nativePlaybackPosition} frames=${activity.tubeViewModel.decodedVideoFrames}")
            }
            instrumentation.runOnMainSync { activity.tubeViewModel.closePlayer() }
            await("Leaving playback must restore the original browsing orientation") {
                activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun exercise(orientation: Int, query: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = runBlocking { NikTubeClient().search(query).first() }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, NikTubeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NikTubeActivity
        try {
            instrumentation.runOnMainSync {
                activity.requestedOrientation = orientation
                assertEquals("Tablet must actually be in the requested test orientation",
                    if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) android.content.res.Configuration.ORIENTATION_PORTRAIT
                    else android.content.res.Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
                activity.tubeViewModel.play(video, 0L)
            }
            val deadline = System.currentTimeMillis() + 70_000
            var passed = false
            while (System.currentTimeMillis() < deadline) {
                var failure: String? = null
                instrumentation.runOnMainSync {
                    val vm = activity.tubeViewModel
                    failure = vm.error
                    passed = vm.videoFrameRendered && vm.nativePlaybackPosition >= 10_000 && vm.nativePlaybackDuration > 0
                }
                assertNull("Native playback failed", failure)
                if (passed) break
                Thread.sleep(250)
            }
            assertTrue("A decoded video frame and at least 10 seconds of actual playback are required", passed)
            instrumentation.runOnMainSync {
                val vm = activity.tubeViewModel
                Log.i("NikTubeNativeTest", "PASS orientation=$orientation video=${video.id} rendered=${vm.videoFrameRendered} positionMs=${vm.nativePlaybackPosition} durationMs=${vm.nativePlaybackDuration} source=SABR")
            }
            val screenshot = File(instrumentation.targetContext.getExternalFilesDir(null),
                if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "niktube-native-portrait.png" else "niktube-native-landscape.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                fun position(): Long {
                    var result = 0L
                    instrumentation.runOnMainSync { result = activity.tubeViewModel.nativePlaybackPosition }
                    return result
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                Thread.sleep(5500)
                val pausedAt = position()
                Thread.sleep(5500)
                assertTrue("Pause must stop the playhead", kotlin.math.abs(position() - pausedAt) < 500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.getExternalFilesDir(null), "niktube-native-controls.png")
                        .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                var node = find(instrumentation.uiAutomation.rootInActiveWindow, "Forward 10 seconds")
                var clicked = false
                while (node != null && !clicked) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node = node.parent
                }
                assertTrue("Native seek control must be clickable", clicked)
                Thread.sleep(5500)
                val soughtAt = position()
                assertTrue("Seeking must advance at least 9 seconds", soughtAt >= pausedAt + 9000)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumeDeadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < resumeDeadline && position() < soughtAt + 4000) Thread.sleep(250)
                assertTrue("Native video must resume after seeking", position() >= soughtAt + 4000)
                Log.i("NikTubeNativeTest", "PASS pause/seek/resume video=${video.id} pausedMs=$pausedAt soughtMs=$soughtAt resumedMs=${position()}")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun landscapeVideoActuallyPlays() = exercise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "Patagonia nature documentary Infinite Planet")
    @Test fun portraitVideoActuallyPlays() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "NASA science Veritasium")
}
