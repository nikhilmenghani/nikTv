package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Generated 128 KiB test pattern, 120 seconds, two silent English/Hindi AAC tracks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerAudioSpeedDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (test(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun label(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun owner(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null && !current.isFocusable) { current = current.parent; current?.refresh() }
        return current
    }
    private fun focused(text: String): Boolean = owner(label(text))?.let {
        it == ins.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } == true
    private fun await(message: String, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(40) }
        NavigationAuditEvidence.capture("audio-speed-failure")
        fail(message)
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); ins.waitForIdleSync() }
    private fun navigateTo(text: String, direction: Int, steps: Int = 16) {
        for (i in 0 until steps) {
            if (focused(text)) return
            key(direction)
        }
        await("D-pad reaches $text") { focused(text) }
    }
    private fun language(text: String) = find { it.text?.toString()?.contains(text, ignoreCase = true) == true }
    private fun languageFocused(text: String) = owner(language(text))?.let {
        it == ins.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } == true
    private fun selectedLanguage(text: String): Boolean {
        var row = language(text)
        while (row != null && !row.isClickable) row = row.parent
        fun selected(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.contentDescription?.toString() == "Selected") return true
            for (i in 0 until node.childCount) if (selected(node.getChild(i))) return true
            return false
        }
        return selected(row)
    }
    @Test fun media3AudioSwitchSpeedAndSeekApplyToPlayingVideo() = exercise(PlaybackEngine.MEDIA3)
    @Test fun vlcAudioSwitchSpeedAndSeekApplyToPlayingVideo() = exercise(PlaybackEngine.VLC)
    @Test fun mpvAudioSwitchSpeedAndSeekApplyToPlayingVideo() {
        assumeTrue(mpvSupportedSdk(android.os.Build.VERSION.SDK_INT))
        exercise(PlaybackEngine.MPV)
    }
    private fun exercise(engine: PlaybackEngine) {
        check(context.packageName.endsWith(".regression"))
        val file = File(context.cacheDir, "player-audio-speed.mp4")
        ins.context.assets.open(file.name).use { source -> file.outputStream().use { source.copyTo(it) } }
        val frames = AtomicInteger()
        val failures = AtomicInteger()
        val positionReader = AtomicReference<(() -> Long)?>(null)
        fun position(): Long {
            var value = 0L
            ins.runOnMainSync { value = checkNotNull(positionReader.get()).invoke() }
            return value
        }
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val media = remember { PlayingMedia(MediaItem("audio-speed", "Audio and speed fixture", null, null),
                    Uri.fromFile(file).toString(), CatalogType.MOVIES, progressKey = "audio-speed", externalPlayback = true) }
                PlayerScreen(media, {}, { failures.incrementAndGet() }, { failures.incrementAndGet() },
                    { failures.incrementAndGet() }, {}, {}, { _, _, _ -> }, playbackEngine = engine,
                    controlsTimeoutSeconds = 0, startFullscreen = true,
                    onChooseAlternateSource = { _, reader -> positionReader.set(reader) },
                    onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("$engine renders the local dual-audio video") { frames.get() > 0 }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down opens controls on Pause") { focused("Pause") }
            navigateTo("More controls", KeyEvent.KEYCODE_DPAD_RIGHT, 12)
            await("Right reaches More controls") { focused("More controls") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            navigateTo("Audio language", KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Both real audio tracks are present and English is selected") {
                language("English") != null && language("Hindi") != null && selectedLanguage("English") && languageFocused("English")
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad reaches the alternate Hindi track") { languageFocused("Hindi") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selecting Hindi closes the audio popup onto its origin") {
                label("Choose an audio track for this video") == null && focused("Audio language")
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Reopening verifies the engine selected Hindi") { selectedLanguage("Hindi") && languageFocused("Hindi") }
            key(KeyEvent.KEYCODE_BACK)
            await("Back closes audio settings onto the Audio control") {
                label("Choose an audio track for this video") == null && focused("Audio language")
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches the adjacent Playback speed control") { focused("Playback speed") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Speed popup opens") { label("Playback speed adjustment") != null }
            await("Speed popup focuses the selected 1× preset") { focused("1×") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches 1.25×") { focused("1.25×") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches 1.5×") { focused("1.5×") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Speed selection is reflected without closing the popup") { label("Playback speed · 1.5×") != null }
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns to the Playback speed control") { label("Playback speed adjustment") == null && focused("Playback speed") }
            navigateTo("Source and quality", KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The active engine supplies its real position reader") { positionReader.get() != null && position() > 0L }
            val start = position()
            val started = SystemClock.elapsedRealtime()
            SystemClock.sleep(4_000L)
            val elapsed = SystemClock.elapsedRealtime() - started
            val advanced = position() - start
            assertTrue("1.5× must accelerate actual playback: advanced=$advanced elapsed=$elapsed", advanced > elapsed * 1.2)
            assertTrue("Playback speed must remain near 1.5×", advanced < elapsed * 1.85)
            ins.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "AUDIO_SPEED engine=$engine selected=Hindi speed=1.5 advanced=$advanced elapsed=$elapsed\n") })
            val beforeSeek = position()
            key(KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down from seek returns to Pause") { focused("Pause") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Forward 10 seconds") { focused("Forward 10 seconds") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Seek applies to the active $engine player") { position() > beforeSeek + 8_000L }
            val afterSeek = position()
            await("Playback continues after seek") { position() > afterSeek + 1_000L }
            assertEquals("Audio/speed/seek must not trigger recovery", 0, failures.get())
            NavigationAuditEvidence.capture("player-audio-speed-${engine.name.lowercase()}")
            ins.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "AUDIO_SPEED engine=$engine selected=Hindi speed=1.5 advanced=$advanced elapsed=$elapsed seek=$afterSeek\n") })
        } finally {
            ins.runOnMainSync { host.finish() }
            ins.setInTouchMode(true)
            file.delete()
        }
    }
}
