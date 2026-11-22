package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Ten distinct silent caption-bearing videos through the production player UI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CaptionMediaMatrixDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(50) }
        fail(label)
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun focused(label: String): Boolean {
        var n = node(label)
        while (n != null && !n.isFocusable) n = n.parent
        return n?.isFocused == true
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun captionText(player: Player): String {
        var text = ""
        main { text = player.currentCues.cues.joinToString("\n") { it.text?.toString().orEmpty() } }
        return text
    }
    private fun focusAction(label: String) {
        await("Visible $label") { node(label) != null }
        var n = node(label)
        while (n != null && !n.isFocusable) n = n.parent
        assertTrue("Focus $label", n?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
        await("Focused $label") { focused(label) }
    }
    private fun openCaptions() {
        if (node("More controls") == null && node("Hide extra controls") == null) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Transport entry owns focus before choosing another control") { focused("Play") || focused("Pause") }
        }
        if (node("Hide extra controls") == null) {
            focusAction("More controls"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Settings opened") { node("Hide extra controls") != null }
        } else focusAction("Hide extra controls")
        for (i in 0..24) {
            if (focused("Subtitles")) break
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            ins.waitForIdleSync()
        }
        assertTrue("D-pad reaches Subtitles without playing a control", focused("Subtitles"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Caption popup") { node("Close") != null }
    }

    @Test fun tenEmbeddedMediaRenderSelectDisableSeekAndReturnWithDpad() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
            .setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val folder = File(context.filesDir, "caption-matrix").apply { mkdirs() }
        ZipInputStream(ins.context.assets.open("caption-fixtures.zip")).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break
                require('/' !in entry.name && '\\' !in entry.name)
                File(folder, entry.name).outputStream().use { zip.copyTo(it) }
            }
        }
        val cases = JSONObject(File(folder, "metadata.json").readText()).getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        val results = JSONArray()
        val failures = mutableListOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val token = case.getString("expectedToken")
            val frames = AtomicInteger()
            val exits = AtomicInteger()
            val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            val result = JSONObject().put("media", name).put("engine", "Media3")
            try {
                main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Fixture window") { host.hasWindowFocus() }
                ins.setInTouchMode(false)
                main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                    PlayerScreen(PlayingMedia(MediaItem(token, name, null, null), File(folder, name).toURI().toString(),
                        CatalogType.MOVIES, progressKey = token, profileKey = "caption-fixture"),
                        onBack = { exits.incrementAndGet() }, onRetry = {}, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                        playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, controlsTimeoutSeconds = 0,
                        onVideoFrameRendered = { frames.incrementAndGet() })
                } } }
                await("$name video renders") { frames.get() > 0 }
                var player: Player? = null
                main { player = playerView(host.window.decorView)?.player }
                val active = requireNotNull(player)
                main { active.pause(); active.seekTo(3_000) }
                var firstLabel = ""
                await("$name exposes embedded captions") {
                    main {
                        firstLabel = active.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
                            ?.getTrackFormat(0)?.let { it.label ?: it.language?.let { lang -> Locale.forLanguageTag(lang).displayName } ?: "Subtitle 1" }.orEmpty()
                    }; firstLabel.isNotBlank()
                }
                openCaptions()
                for (i in 0..4) { if (focused("Off")) break; key(KeyEvent.KEYCODE_DPAD_UP); ins.waitForIdleSync() }
                assertTrue("Off is reachable", focused("Off"))
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("First embedded track is reachable") { focused(firstLabel) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_BACK)
                await("Popup returns directly to its caption origin") { node("Close") == null && focused("Subtitles") }
                main { active.seekTo(3_000) }
                await("$name draws the correct caption cue") { captionText(active).contains(token) }
                result.put("visibleText", captionText(active))
                ins.waitForIdleSync()
                ins.uiAutomation.waitForIdle(200, 3_000)
                val image = ins.uiAutomation.takeScreenshot()
                if (image != null) { File(folder, "$token-visible.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
                main { active.seekTo(7_500) }
                await("Caption clears after its end") { captionText(active).isBlank() }
                if (name.endsWith(".mkv")) {
                    // These deliberately sparse files have one long subtitle packet
                    // in the first cluster. A direct seek into a later cluster omits
                    // it in Media3 (and FFmpeg). Keep the fixture and verify recovery
                    // from the packet rather than claiming arbitrary-seek support.
                    main { active.seekTo(0); active.play() }
                    await("Sparse MKV caption returns when its packet is decoded") { captionText(active).contains(token) }
                    main { active.pause() }
                    result.put("seek", "end clears; start/preroll restores; direct active-cue seek limited by MKV demux")
                } else {
                    main { active.seekTo(3_000) }
                    await("Caption returns after backward seek") { captionText(active).contains(token) }
                    result.put("seek", "passed")
                }
                if (case.getInt("subtitleCount") > 1) {
                    openCaptions()
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    key(KeyEvent.KEYCODE_BACK)
                    main { active.seekTo(0); active.play() }
                    await("Switching embedded languages renders French") { captionText(active).contains("Case07FR") }
                    main { active.pause() }
                    result.put("languageSwitch", "passed")
                }
                openCaptions()
                for (i in 0..4) { if (focused("Off")) break; key(KeyEvent.KEYCODE_DPAD_UP); ins.waitForIdleSync() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_BACK)
                await("Off clears the native caption") { captionText(active).isBlank() }
                assertEquals("Caption controls must not exit playback", 0, exits.get())
                result.put("status", "passed").put("off", "passed").put("dpad", "passed")
            } catch (failure: Throwable) {
                failures += "$name: ${failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName}"
                result.put("status", "failed").put("error", failure.stackTraceToString())
            } finally {
                main { host.finish() }
                results.put(result)
                File(folder, "results.json").writeText(results.toString(2))
            }
        }
        assertTrue("Caption failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }
}
