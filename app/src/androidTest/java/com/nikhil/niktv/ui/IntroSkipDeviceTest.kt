package com.nikhil.niktv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Silent local playback only, isolated regression preferences; never opens a provider profile. */
class IntroSkipDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun label(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }
    private fun dialog() = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Intro timing" }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.externalCacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun click(text: String) {
        await("Missing $text") { label(text) != null }
        var node = label(text)
        while (node != null) { if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; node = node.parent }
        fail("Could not activate $text")
    }
    private fun touch(text: String) {
        await("Missing touch target $text") { label(text) != null }
        val rect = Rect().also { label(text)!!.getBoundsInScreen(it) }
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val event = MotionEvent.obtain(time, time + i * 40L, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun focused(text: String): Boolean {
        var node = label(text)
        while (node != null) { if (node.isFocused) return true; node = node.parent }
        return false
    }
    private fun displayedPosition(): Long? = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.contentDescription?.startsWith("Current playback time ") == true
    }?.contentDescription?.toString()?.removePrefix("Current playback time ")?.let(::parseIntroTime)
    private fun displayedIntroCountdown(): Long? = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.startsWith("Skip intro · ") == true
    }?.text?.toString()?.substringAfter("Skip intro · ")?.let(::parseIntroTime)
    private fun fixture() = PlayingMedia(MediaItem("fixture", "Local regression fixture", null, null,
        seasonNumber = 1, episodeNumber = 1), "", CatalogType.SERIES,
        series = MediaItem("intro-series", "Intro fixture series", null, null), profileKey = "intro-fixture")

    @Test fun exoTouchSkipAndEditorPreservePlaybackAndRestoreFocus() = exercise("MEDIA3", paused = false, touch = true, editor = true)
    @Test fun vlcDpadSkipPreservesUserPause() = exercise("VLC", paused = true, touch = false)
    @Test fun mpvDpadSkipPreservesUserPause() = exercise("MPV", paused = true, touch = false)
    @Test fun flowTouchSkipUsesTheSameSavedTiming() = exercise("FLOW", paused = false, touch = true)
    @Test fun exoVideoDpadSkip() = exercise("MEDIA3", paused = false, touch = false, video = true)
    @Test fun vlcVideoDpadSkipWhilePaused() = exercise("VLC", paused = true, touch = false, video = true)
    @Test fun mpvVideoTouchSkipWhilePaused() = exercise("MPV", paused = true, touch = true, video = true)
    @Test fun flowVideoTouchSkip() = exercise("FLOW", paused = false, touch = true, video = true)
    @Test fun exoCountdownExpiresWithoutPressingSkip() = exercise("MEDIA3", false, false, video = true, passiveExpiry = true)
    @Test fun vlcCountdownExpiresWithoutPressingSkip() = exercise("VLC", false, false, video = true, passiveExpiry = true)
    @Test fun mpvCountdownExpiresWithoutPressingSkip() = exercise("MPV", false, false, video = true, passiveExpiry = true)
    @Test fun flowCountdownExpiresWithoutPressingSkip() = exercise("FLOW", false, false, video = true, passiveExpiry = true)

    private fun exercise(engine: String, paused: Boolean, touch: Boolean, editor: Boolean = false, video: Boolean = false,
        passiveExpiry: Boolean = false) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val target = introTarget(fixture())!!
        val preferences = context.getSharedPreferences("player_intro_timings", Context.MODE_PRIVATE)
        val oldEpisode = preferences.getString(target.episodeKey, null)
        val oldSeason = preferences.getString(target.seasonKey!!, null)
        val store = IntroTimingStore { preferences }
        store.save(target, IntroScope.EPISODE, null)
        val skipEnd = if (video) 10_000L else 30_000L
        store.save(target, IntroScope.SEASON, IntroRange(0, skipEnd))
        assertEquals(IntroRange(0, skipEnd), store.read(target).effective)
        val file = File(context.cacheDir, "intro-skip-$engine.${if (video) "mp4" else "wav"}")
        if (video) {
            // Existing generated 20s H.264 fixture: AAC peak/RMS verified -inf (silence).
            ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: error("Missing movie fixture")
                    if (entry.name == "movie.mp4") { file.writeBytes(zip.readBytes()); break }
                }
            }
        } else {
            val samples = 8_000 * 120
            val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            data.putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            data.put("data".toByteArray()).putInt(samples * 2)
            file.writeBytes(data.array()) // All-zero PCM: no audio output even at nonzero device volume.
        }
        instrumentation.setInTouchMode(touch)
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", engine).putExtra("url", Uri.fromFile(file).toString()).putExtra("catalog", "SERIES")
            .putExtra("introFixture", true).putExtra("fullscreen", true).putExtra("playWhenReady", true)
            .putExtra("noAdjacentEpisodes", passiveExpiry)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("$engine must prepare the saved intro overlay") { label("Skip intro") != null }
            if (passiveExpiry) {
                await("$engine must render the countdown alongside the button") { displayedIntroCountdown() != null && PlaybackRegressionActivity.firstFrames > 0 }
                val initial = displayedIntroCountdown()!!
                await("$engine countdown must follow advancing playback, not remain static") {
                    displayedIntroCountdown()?.let { it < initial } == true
                }
                await("$engine must hide Skip intro naturally without any click") { label("Skip intro") == null }
                assertTrue("Playback must continue after intro expiry", PlaybackRegressionActivity.requestedPlayback)
                assertEquals(0, PlaybackRegressionActivity.retries)
                assertEquals(0, PlaybackRegressionActivity.navigations)
                assertEquals(0, PlaybackRegressionActivity.enginePreferenceChanges)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Expiry returns focus to the video, so OK opens controls") { label("Pause") != null }
                await("The actual clock must have reached the intro end") { (displayedPosition() ?: 0L) >= skipEnd - 1_000L }
                return
            }
            if (video && engine == "MEDIA3") {
                var button = label("Skip intro")!!
                while (!button.isClickable && button.parent != null) button = button.parent
                val bounds = Rect().also(button::getBoundsInScreen)
                assertTrue("Skip Intro must be a compact corner button, not a full-width row",
                    bounds.width() in 1 until context.resources.displayMetrics.widthPixels / 2)
                capture("intro-skip-tablet-overlay.png")
            }
            if (paused) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                await("Explicit Pause must reach the prepared engine") { !PlaybackRegressionActivity.requestedPlayback && label("Play") != null }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Hiding controls must retain the paused intro overlay") { label("Skip intro") != null && label("Play") == null }
                // Pause intent is immediate; the native engine and the existing UI clock poll
                // acknowledge it asynchronously. Require a stable clock before measuring it.
                var previousCountdown = displayedIntroCountdown()
                var stableSince = SystemClock.uptimeMillis()
                await("Native pause must settle the countdown") {
                    val current = displayedIntroCountdown()
                    if (current != previousCountdown) { previousCountdown = current; stableSince = SystemClock.uptimeMillis() }
                    current != null && SystemClock.uptimeMillis() - stableSince >= 1_500L
                }
                val pausedCountdown = displayedIntroCountdown()
                Thread.sleep(1_200)
                assertNotNull(pausedCountdown)
                assertEquals("Countdown must freeze while playback is paused", pausedCountdown, displayedIntroCountdown())
            }
            if (touch) touch("Skip intro") else {
                await("Skip intro must be focused automatically, without a Down press") { focused("Skip intro") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            if (paused) {
                // VLC intentionally writes resume/history progress only while playing, not on a paused seek.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Paused seek must update the visible clock") { (displayedPosition() ?: -1L) >= skipEnd - 1_000L }
            } else await("$engine must seek to the saved end, not restart") { PlaybackRegressionActivity.progress >= skipEnd - 500L }
            assertEquals("Skip must preserve user pause intent", !paused, PlaybackRegressionActivity.requestedPlayback)
            await("Skip must disappear outside the intro interval") { label("Skip intro") == null }
            assertEquals(0, PlaybackRegressionActivity.retries)
            assertEquals(0, PlaybackRegressionActivity.navigations)
            assertEquals(0, PlaybackRegressionActivity.enginePreferenceChanges)
            if (paused) {
                val before = displayedPosition()!!
                Thread.sleep(2_500)
                assertEquals(false, PlaybackRegressionActivity.requestedPlayback)
                val after = displayedPosition()!!
                // Text is whole seconds; a native 29.999 -> 30.000 acknowledgement can change it by one.
                assertTrue("Paused skip must not resume playback", kotlin.math.abs(after - before) <= 1_000 &&
                    after in (skipEnd - 1_000)..(skipEnd + 1_000))
            }
            if (editor) {
                instrumentation.setInTouchMode(false)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Controls must appear") { label("Intro timing") != null || label("More controls") != null }
                if (label("Intro timing") == null) click("More controls")
                click("Intro timing")
                await("Intro timing dialog must open") { dialog() != null }
                click("This episode")
                click("Save")
                await("Save must close dialog") { dialog() == null }
                assertEquals(IntroRange(0, 30_000), store.read(target).episode)
                Thread.sleep(300)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Focus must return to Intro timing, so OK reopens it") { dialog() != null }
                click("Remove saved timing")
                await("Removing override must restore season timing") { store.read(target).episode == null && dialog() == null }
                assertEquals(IntroRange(0, 30_000), store.read(target).effective)
                assertEquals(0, PlaybackRegressionActivity.navigations)
            }
        } catch (failure: AssertionError) {
            capture("intro-skip-failure-$engine-${if (video) "video" else "audio"}.png")
            throw AssertionError("${failure.message}; stored=${store.read(target)}, duration=${PlaybackRegressionActivity.knownDuration}, progress=${PlaybackRegressionActivity.progress}, requested=${PlaybackRegressionActivity.requestedPlayback}", failure)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            await("Fixture must finish before the next player test") { activity.isDestroyed }
            file.delete()
            val edit = preferences.edit()
            if (oldEpisode == null) edit.remove(target.episodeKey) else edit.putString(target.episodeKey, oldEpisode)
            if (oldSeason == null) edit.remove(target.seasonKey) else edit.putString(target.seasonKey, oldSeason)
            check(edit.commit())
        }
    }

    @Test fun persistedOverridesAreProfileScopedAndStorageIsBounded() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val name = "intro-storage-test-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = IntroTimingStore { preferences }
        val one = introTarget(fixture())!!
        val two = introTarget(fixture().copy(media = fixture().media.copy(id = "second", episodeNumber = 2)))!!
        val otherProfile = introTarget(fixture().copy(profileKey = "another-profile"))!!
        try {
            store.save(one, IntroScope.SEASON, IntroRange(0, 30_000))
            store.save(one, IntroScope.EPISODE, IntroRange(2_000, 40_000))
            assertEquals(IntroRange(2_000, 40_000), IntroTimingStore { preferences }.read(one).effective)
            assertEquals(IntroRange(0, 30_000), store.read(two).effective)
            assertNull(store.read(otherProfile).effective)
            store.save(one, IntroScope.EPISODE, null)
            assertEquals(IntroRange(0, 30_000), store.read(one).effective)
            repeat(IntroTimingStore.MAX_ENTRIES + 1) {
                store.save(IntroTarget("episode:test-$it", null, "Test", null), IntroScope.EPISODE, IntroRange(0, 10_000))
            }
            assertEquals(IntroTimingStore.MAX_ENTRIES, preferences.all.size)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun markingDraftSurvivesClosingAndEpisodeOverrideCanBeRemoved() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val name = "intro-editor-test-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = IntroTimingStore { preferences }
        val target = IntroTarget("episode:test", "season:test", "This episode", "Season 1")
        val position = mutableLongStateOf(10_000L)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        val scope = rememberCoroutineScope()
                        val settings = remember { IntroSkipSettings(target, store, scope) }
                        LaunchedEffect(settings) { settings.load() }
                        DisposableEffect(settings) { onDispose { settings.dispose() } }
                        Button(onClick = { settings.open(position.longValue) }) { Text("Configure intro") }
                        if (settings.editorOpen) IntroTimingDialog(settings, 120_000L,
                            currentPosition = { position.longValue }, onDismiss = settings::close)
                    }
                }
            }
            Thread.sleep(300)
            click("Configure intro")
            await("Editor must initially focus Mark start") { focused("Mark start") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            click("Close")
            instrumentation.runOnMainSync { position.longValue = 50_000L }
            click("Configure intro")
            click("Mark end")
            capture("intro-skip-tablet-editor.png")
            touch("Save")
            await("Marked season timing must be saved after closing/reopening draft") {
                store.read(target).season == IntroRange(10_000, 50_000) && dialog() == null
            }
            instrumentation.runOnMainSync { position.longValue = 20_000L }
            click("Configure intro")
            click("Mark start")
            click("This episode")
            click("Save")
            await("Episode override must retain its own marked start") { store.read(target).episode == IntroRange(20_000, 50_000) }
            click("Configure intro")
            click("Remove saved timing")
            await("Removing override must reveal the untouched season timing") {
                store.read(target) == IntroTimings(null, IntroRange(10_000, 50_000)) && dialog() == null
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun expiringSkipButtonReturnsDpadFocusToVideo() {
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val visible = mutableStateOf(true)
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme {
                        val video = remember { FocusRequester() }
                        val skip = remember { FocusRequester() }
                        Box {
                            Button(onClick = {}, modifier = Modifier.focusRequester(video)) { Text("Video focus") }
                            if (visible.value) IntroSkipButton(skip, onSkip = {}, onControls = {},
                                remainingSeconds = 10L,
                                onReturnToVideo = { video.requestFocus(); Unit })
                        }
                    }
                }
            }
            await("Skip must receive default D-pad focus") { focused("Skip intro") }
            instrumentation.runOnMainSync { visible.value = false }
            await("Natural intro expiry must not strand focus on a removed button") { focused("Video focus") }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
