package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Production controls and real local decoding; no subscription, history or production data. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDecoderControlsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!test() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
        assertTrue(message, test())
    }
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String, prefix: Boolean = false): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (listOf(node.text?.toString(), node.contentDescription?.toString()).any {
                if (prefix) it?.startsWith(label) == true else it == label }) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun button(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        repeat(8) { if (node?.isClickable == true) return node; node = node?.parent }
        return null
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun focusedLabel(): String? {
        fun label(node: AccessibilityNodeInfo?): String? {
            node ?: return null
            node.contentDescription?.toString()?.let { return it }
            node.text?.toString()?.let { return it }
            for (i in 0 until node.childCount) label(node.getChild(i))?.let { return it }
            return null
        }
        fun focusedNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isFocused) return node
            for (i in 0 until node.childCount) focusedNode(node.getChild(i))?.let { return it }
            return null
        }
        // Android's cached findFocus result can lag behind Compose virtual-node focus.
        return label(focusedNode(root()))
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun activate(label: String) {
        await("Reachable $label") { button(label) != null }
        ins.setInTouchMode(false)
        assertTrue(requireNotNull(button(label)).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await("Focused $label") { focused(label) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun screenshot(name: String) {
        ins.waitForIdleSync()
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun verify(sports: Boolean, requested: Boolean = false) {
        check(context.packageName.endsWith(".regression"))
        val previous = VideoDecoderPreferences.read(context)
        VideoDecoderPreferences.save(context, VideoDecoderMode.AUTO)
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val refreshes = AtomicInteger()
        val frames = AtomicInteger()
        val focusEvents = CopyOnWriteArrayList<String>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val node = event.source
                focusEvents += (node?.contentDescription ?: node?.text ?: event.text.joinToString()).toString()
            }
        }
        fun player(): Player? {
            var result: Player? = null
            main {
                fun walk(view: View) {
                    if (view is PlayerView) result = view.player
                    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                walk(host.window.decorView)
            }
            return result
        }
        fun position(): Long { val current = player(); var at = 0L; main { at = current?.currentPosition ?: 0L }; return at }
        fun assertIntent() { val current = requireNotNull(player()); main { assertEquals("A decoder change preserves play/pause intent", requested, current.playWhenReady) } }
        try { PlaybackEngineFixtureTest().FixtureServer().use { server ->
            val media = PlayingMedia(MediaItem("decoder-controls", "Decoder controls fixture", null, null),
                "${server.base}/movie.mp4", CatalogType.MOVIES, progressKey = "decoder-controls-$sports",
                resumePositionMillis = 12_000, playWhenReady = requested)
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media, {}, { refreshes.incrementAndGet() }, {}, {}, {}, {}, { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, controlsTimeoutSeconds = 0, startFullscreen = true,
                    onVideoFrameRendered = { frames.incrementAndGet() })
                else PlayerScreen(media, {}, { refreshes.incrementAndGet() }, {}, {}, {}, {}, { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, controlsTimeoutSeconds = 0, startFullscreen = true,
                    onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("Local paused video renders") { host.hasWindowFocus() && frames.get() > 0 && position() >= 12_000 }
            assertIntent()
            // Fullscreen starts with its controls hidden. A central tap reveals them.
            val point = FloatArray(2)
            main { point[0] = host.window.decorView.width * .5f; point[1] = host.window.decorView.height * .4f }
            ins.setInTouchMode(true)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
                try { ins.sendPointerSync(event) } finally { event.recycle() }
            }
            activate("More controls")
            await("D-pad enters the expanded settings row") { find("Hide extra controls") != null && focusedLabel() != null && !focused("Hide extra controls") }
            // Phone portrait scrolls the compact settings row; navigate it as a remote user would.
            for (step in 0 until 20) {
                if (focused("Video decoder")) break
                // Accessibility node instances can be refreshed in place; capture an immutable label.
                val prior = focusedLabel()
                assertFalse("Decoder must appear before the end of the settings row", focused("Hide extra controls"))
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right advances the focused settings action from $prior") {
                    focusedLabel()?.let { it != prior } == true
                }
            }
            await("Decoder is reachable through the scrolling settings row") { focused("Video decoder") }
            activate("Video decoder")
            await("Auto is initially focused and actual decoder is shown") { focused("Auto") && find("Current: Hardware", true) != null }
            screenshot("decoder-$sports-auto.png")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Hardware without applying it") { focused("Hardware") }
            assertEquals(VideoDecoderMode.AUTO, VideoDecoderPreferences.read(context))
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Software") { focused("Software") }
            val original = player()
            val beforeChange = position()
            focusEvents.clear()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Software mode replaces the decoder and restores its trigger") {
                VideoDecoderPreferences.read(context) == VideoDecoderMode.SOFTWARE && player() !== original && focused("Video decoder") && frames.get() >= 2
            }
            assertIntent()
            assertTrue("Resume position is preserved", kotlin.math.abs(position() - beforeChange) < 1_500)
            assertFalse("No Back/Home/Play focus flash", focusEvents.any { it in listOf("Back", "Home", "Play", "Pause") })
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Software is selected and actual software codec is displayed") { focused("Software") && find("Current: Software", true) != null }
            screenshot("decoder-$sports-software.png")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Hardware is reachable") { focused("Hardware") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Hardware selection returns focus and renders") { focused("Video decoder") && VideoDecoderPreferences.read(context) == VideoDecoderMode.HARDWARE && frames.get() >= 3 }
            assertIntent()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Actual hardware decoder is restored") { focused("Hardware") && find("Current: Hardware", true) != null }
            screenshot("decoder-$sports-hardware.png")
            key(KeyEvent.KEYCODE_BACK)
            await("Dismiss returns directly to Decoder") { focused("Video decoder") }
            assertEquals("Decoder changes never refresh/re-authenticate the stream", 0, refreshes.get())
        } } catch (error: Throwable) {
            screenshot("decoder-$sports-failure.png")
            throw AssertionError("${error.message}; actual focus=${focusedLabel()}; events=${focusEvents.takeLast(12)}", error)
        }
        finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            main { host.finish() }
            VideoDecoderPreferences.save(context, previous)
        }
    }
    @Test fun iptvDecoderSwitchPreservesPositionPauseAndFocus() = verify(false)
    @Test fun iptvDecoderSwitchPreservesPlayingIntent() = verify(false, requested = true)
    @Test fun sportsDecoderSwitchPreservesPositionPauseAndFocus() = verify(true)
}
