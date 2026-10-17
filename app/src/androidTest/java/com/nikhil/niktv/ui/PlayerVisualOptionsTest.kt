package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real player controls, silent local media and an isolated package; no provider access. */
class PlayerVisualOptionsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun label(value: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == value || it.contentDescription?.toString() == value
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun click(value: String) {
        await("Missing control $value") { label(value) != null }
        var node = label(value)
        while (node != null) { if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; node = node.parent }
        fail("Cannot click $value")
    }
    private fun confirmOrigin(title: String) {
        await("Dialog must close before restoring focus") { label(title) == null }
        Thread.sleep(250)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK must reopen $title from the originating button, without pausing playback") { label(title) != null }
    }
    @Test fun exoVisualOptionsKeepPreviewAndFocusSeparateFromPlayback() = exercise("MEDIA3")
    @Test fun vlcVisualOptionsKeepPreviewAndFocusSeparateFromPlayback() = exercise("VLC")
    private fun exercise(engine: String) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val oldSize = VideoResizePreferences.mode(context)
        val oldPicture = VideoAppearancePreferences.activeId(context)
        VideoResizePreferences.save(context, VideoResizeMode.FIT)
        VideoAppearancePreferences.setActive(context, "default")
        val file = File(context.cacheDir, "visual-options.wav")
        val samples = 8_000 * 120
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        file.writeBytes(bytes.array())
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", engine).putExtra("url", Uri.fromFile(file).toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Local playback must advance") { PlaybackRegressionActivity.progress > 3000L }
            click("More controls")
            click("Video fit: Fit")
            await("Resize choices must be visible") { label("Video resize") != null }
            click("Zoom")
            await("Resize selection must preview Zoom") {
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.startsWith("Preview: Zoom") == true } != null
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, "video-resize-$engine.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            assertEquals("Preview must not save", VideoResizeMode.FIT, VideoResizePreferences.mode(context))
            click("Skip")
            confirmOrigin("Video resize")
            assertEquals("Skip must preserve previous sizing", VideoResizeMode.FIT, VideoResizePreferences.mode(context))
            click("Fill")
            await("Resize selection must preview Fill") {
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.startsWith("Preview: Fill") == true } != null
            }
            click("Save")
            await("Save must remember resize") { VideoResizePreferences.mode(context) == VideoResizeMode.FILL }
            confirmOrigin("Video resize")
            click("Zoom")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            confirmOrigin("Video resize")
            assertEquals("Back must skip the preview", VideoResizeMode.FILL, VideoResizePreferences.mode(context))
            click("Skip")
            await("Resize closes") { label("Video resize") == null }

            var picture = find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.contentDescription?.startsWith("Choose picture mode:") == true
            }
            var pictureClicked = false
            while (picture != null && !pictureClicked) {
                pictureClicked = picture.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                picture = picture.parent
            }
            assertTrue("Picture mode button must be clickable", pictureClicked)
            await("Picture modes open") { label("Picture mode") != null }
            val alternative = VideoAppearancePreferences.profiles(context).first { it.id != "default" }
            click(alternative.name)
            click("Skip")
            confirmOrigin("Picture mode")
            assertEquals("Picture Skip must preserve saved mode", "default", VideoAppearancePreferences.activeId(context))
            click(alternative.name)
            click("Apply")
            confirmOrigin("Picture mode")
            assertEquals(alternative.id, VideoAppearancePreferences.activeId(context))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            confirmOrigin("Picture mode")
            click("Skip")
            val before = PlaybackRegressionActivity.progress
            await("Visual overlays must leave playback running") { PlaybackRegressionActivity.progress > before + 500L }
            assertEquals(0, PlaybackRegressionActivity.enginePreferenceChanges)
            assertEquals(0, PlaybackRegressionActivity.retries)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            file.delete()
            VideoResizePreferences.save(context, oldSize)
            VideoAppearancePreferences.setActive(context, oldPicture)
        }
    }
}
