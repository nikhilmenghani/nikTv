package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.MediaItem as NikItem
import com.nikhil.niktv.sports.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** Synthetic app protobuf feed over a real video player; no user profile or provider access. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CricketScoreOverlayDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(80) }
        fail(label)
    }
    private fun node(text: String, prefix: Boolean = false): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            val desc = n.contentDescription?.toString().orEmpty()
            if ((if (prefix) desc.startsWith(text) else desc == text) || n.text?.toString()?.lineSequence()?.any { it == text } == true) return n
            for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String, prefix: Boolean = false) {
        await("Control is reachable: $text") {
            var n = node(text, prefix)
            repeat(8) { if (n?.isClickable != true) n = n?.parent }
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun bounds(): Rect = Rect().also { node("Live cricket score.", true)!!.getBoundsInScreen(it) }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "scores-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
    private fun showControls(activity: PlaybackFixtureHostActivity) {
        if (node("More controls") == null && node("Hide extra controls") == null) {
            val point = FloatArray(2)
            main { point[0] = activity.window.decorView.width * .5f; point[1] = activity.window.decorView.height * .4f }
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, point[0], point[1], 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
            }
        }
        await("Player controls appear") { node("More controls") != null || node("Hide extra controls") != null }
        if (node("More controls") != null) click("More controls")
    }
    private fun drag(from: Rect) {
        val started = SystemClock.uptimeMillis()
        val x = from.centerX().toFloat(); val y = from.centerY().toFloat()
        for (i in 0..16) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x - i * 13f, y + i * 7f, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            Thread.sleep(22)
        }
    }
    private fun fixtureVideo(): File {
        val target = File(context.cacheDir, "score-fixture.mp4")
        ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
            while (true) { val entry = zip.nextEntry ?: error("No video fixture")
                if (entry.name == "movie.mp4") { target.writeBytes(zip.readBytes()); return target } }
        }
    }
    private fun host(): PlaybackFixtureHostActivity {
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        main { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("Landscape player window is ready") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && activity.hasWindowFocus() }
        return activity
    }

    private class Proto {
        val out = ByteArrayOutputStream()
        private fun vi(value: Long) { var n = value; while (n > 127) { out.write(((n and 127) or 128).toInt()); n = n ushr 7 }; out.write(n.toInt()) }
        fun number(tag: Int, value: Long) { vi((tag shl 3).toLong()); vi(value) }
        fun text(tag: Int, value: String) = bytes(tag, value.toByteArray())
        fun bytes(tag: Int, value: ByteArray) { vi(((tag shl 3) or 2).toLong()); vi(value.size.toLong()); out.write(value) }
        fun double(tag: Int, value: Double) { vi(((tag shl 3) or 1).toLong()); out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()) }
        fun child(tag: Int, block: Proto.() -> Unit) = bytes(tag, proto(block))
        companion object { fun proto(block: Proto.() -> Unit) = Proto().apply(block).out.toByteArray() }
    }

    private fun exercise(sports: Boolean, engine: PlaybackEngine) {
        check(context.packageName.endsWith(".regression"))
        val prefs = context.getSharedPreferences("cricket_score_overlay", 0)
        prefs.edit().clear().commit()
        val revision = AtomicInteger()
        val polls = AtomicInteger()
        val progress = AtomicLong()
        val frames = AtomicInteger()
        val wicketDescription = "The batter edges a rising delivery and is caught behind. " +
            "The umpire confirms the dismissal after checking the catch. ".repeat(20) + "Final wicket sentence remains readable."
        val settings = Proto.proto { child(1) { text(1, "floating_score_widget"); number(2, 1) } }
        val listing = Proto.proto { child(1) { child(2) { child(1) { child(3) { child(1) {
            number(1, 4242); text(3, "Test series"); text(4, "Live match"); text(8, "In Progress")
            child(10) { text(3, "IND") }; child(11) { text(3, "AUS") }
        } } } } } }
        fun score(): ByteArray {
            val stage = revision.get()
            val updated = stage > 0
            return Proto.proto {
                child(3) { text(9, if (stage > 1) "1 W 1" else if (updated) "1 W" else "0 1"); child(10) { child(1) {
                    number(1, 1); text(3, "IND"); number(4, if (stage > 1) 105 else if (updated) 104 else 100); number(5, if (updated) 2 else 1); double(6, 10.0 + stage * .1)
                } } }
                child(4) { text(1, "In Progress"); text(2, "IND batting") }
                child(2) { child(1) { text(1, if (stage == 1) wicketDescription else if (stage > 1) "The next batter takes a single." else "A single to square leg.")
                    number(2, 100L + stage * 100); number(4, 1); double(3, 10.0 + stage * .1); text(5, if (stage == 1) "WICKET" else "NONE") } }
            }
        }
        val repo = CricketLiveScoresRepository(CricbuzzDirectClient(CricketHighlightsTransport { url, _ ->
            when { url.endsWith("settings") -> settings; url.endsWith("live") -> listing
                else -> { polls.incrementAndGet(); score() } }
        }, { CricbuzzCredentials("ab".repeat(32)) }))
        val videoServer = PlaybackEngineFixtureTest().FixtureServer()
        var activity = host()
        fun show() { main { activity.setContent { MaterialTheme(colorScheme = NikColors) {
            CompositionLocalProvider(LocalCricketLiveScoresRepository provides repo) {
                val media = remember { PlayingMedia(NikItem("score-video", "Score overlay test video", null, null), "${videoServer.base}/movie.mp4",
                    CatalogType.MOVIES, progressKey = "score-fixture-$sports-$engine", profileKey = "isolated-score-fixture") }
                if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media, onBack = {}, onRetry = {}, onRetryAlternateDecoder = {},
                    onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, p, _ -> progress.set(p) },
                    playbackEngine = engine, controlsTimeoutSeconds = 30, startFullscreen = true,
                    onVideoFrameRendered = { frames.incrementAndGet() })
                else PlayerScreen(media, onBack = {}, onRetry = {}, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                    onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, p, _ -> progress.set(p) }, playbackEngine = engine,
                    controlsTimeoutSeconds = 30, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
            }
        } } } }
        try {
            show()
            await("Video renders") { frames.get() > 0 }
            showControls(activity)
            click("Live cricket scores")
            await("Match choices are visible before the landscape screenshot") { node("IND vs AUS") != null }
            screenshot("landscape-match-picker-$sports-$engine")
            click("IND vs AUS")
            await("Badge shows initial total") { node("Live cricket score.", true)?.contentDescription?.contains("100/1") == true }
            await("Score starts as a collapsed pill") { node("Expand score badge") != null }
            val pillHeight = bounds().height()
            click("Expand score badge")
            await("Manual expansion shows compact details") { node("Collapse score badge") != null && bounds().height() > pillHeight }
            screenshot("expanded-$sports-$engine")
            click("Collapse score badge")
            await("Manual collapse restores one-line pill") { node("Expand score badge") != null && bounds().height() == pillHeight }
            val initialBounds = bounds()
            screenshot("compact-$sports-$engine")
            val before = progress.get()
            revision.set(1)
            await("Wicket expands its full description") { node("WICKET") != null && node("Live cricket score.", true)?.contentDescription?.contains("104/2") == true }
            val expanded = bounds()
            assertTrue("Event banner expands the badge", expanded.height() > initialBounds.height())
            screenshot("wicket-reading-$sports-$engine")
            revision.set(2)
            val previousPolls = polls.get()
            await("Live feed continues while reading") { polls.get() >= previousPolls + 6 }
            assertNotNull("Full paragraph survives refresh and the old five-second timeout", node(wicketDescription))
            var scrollNode = node(wicketDescription)
            repeat(8) { if (scrollNode?.isScrollable != true) scrollNode = scrollNode?.parent }
            assertTrue("Full commentary can be scrolled rather than truncated", scrollNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            assertTrue("Reading retains the wicket's score snapshot", node("Live cricket score.", true)!!.contentDescription.contains("104/2"))
            click("Refresh commentary")
            await("Manual refresh advances the reading to the latest ball") { node("The next batter takes a single.") != null && node("Live cricket score.", true)!!.contentDescription.contains("105/2") }
            click("Dismiss commentary")
            await("Dismissing restores the chosen pill") { node("Expand score badge") != null && bounds().height() == initialBounds.height() }
            click("Expand score badge"); click("Read last-ball commentary")
            await("Ordinary deliveries can be read on demand") { node("The next batter takes a single.") != null }
            click("Dismiss commentary"); click("Collapse score badge")
            await("Pill collapse finishes before dragging") { node("Expand score badge") != null && bounds().height() == initialBounds.height() }
            assertTrue("Video continues while event displays", progress.get() > before + 3_000)
            val oldPosition = prefs.getFloat("x", .95f)
            drag(bounds())
            await("Dragging saves position") { prefs.getFloat("x", .95f) < oldPosition - .05f }
            val savedX = prefs.getFloat("x", -1f); val savedY = prefs.getFloat("y", -1f)
            click("Live cricket score.", true)
            click("Move badge")
            await("Badge enters movement mode") { node("Move with arrows · OK to save") != null }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            await("Remote movement saves position") { prefs.getFloat("x", 1f) < savedX }
            val remoteX = prefs.getFloat("x", -1f)
            main { activity.finish() }
            Thread.sleep(300)
            activity = host(); show()
            await("Following and position survive player recreation") { node("Live cricket score.", true) != null }
            assertEquals(remoteX, prefs.getFloat("x", -1f))
            assertEquals(savedY, prefs.getFloat("y", -1f))
            click("Live cricket score.", true); click("Turn off live scoring")
            click("Done")
            await("Turning off hides badge") { node("Live cricket score.", true) == null }
            Thread.sleep(300)
            val stoppedAt = polls.get(); Thread.sleep(1_500)
            assertEquals("Turning off cancels polling", stoppedAt, polls.get())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "scores-$sports-$engine.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        } finally { main { activity.finish() }; videoServer.close(); prefs.edit().clear().commit() }
    }

    @Test fun iptvMedia3ScoreBadge() = exercise(false, PlaybackEngine.MEDIA3)
    @Test fun sportsVlcScoreBadge() = exercise(true, PlaybackEngine.VLC)
}
