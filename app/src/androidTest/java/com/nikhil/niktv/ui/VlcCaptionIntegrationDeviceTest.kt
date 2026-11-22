package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Real production VLC controls, native subtitle surface, and local downloaded overlay. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VlcCaptionIntegrationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private data class Settings(val enabled: Boolean, val delay: Long, val appearance: SubtitleAppearancePreset)
    private var lastSubtitleCopy = "not sampled"
    private var subtitleBoundsInScreen: RectF? = null
    private var topSubtitlePixels = 0
    private var bottomSubtitlePixels = 0

    @Test fun embeddedSelectionPaintsNativeSubtitleSurfaceAndOffClearsIt() = fixture(false) { host, player, folder, _ ->
        pauseAt(player, 3_000, decodeFromStart = true)
        val tracks = player.spuTracks.orEmpty().filter { it.id >= 0 }
        assertEquals("The representative fixture contains English and French", 2, tracks.size)
        val french = tracks.single { it.name?.contains("Case07FR") == true }
        openCaptions()
        assertNotNull("VLC native timing is available", node("Show subtitles later"))
        assertNull("VLC must not offer a native appearance no-op", node("Appearance: Standard"))
        activate(requireNotNull(french.name))
        await("The real native renderer selects French") { player.spuTrack == french.id }
        activate("Show subtitles later")
        await("The timing control reaches VLC in microseconds") { player.spuDelay == 250_000L }
        key(KeyEvent.KEYCODE_BACK)
        await("Native popup returns to Subtitles") { node("Close") == null && focused("Subtitles") }
        pauseAt(player, 3_000, decodeFromStart = true)
        await("The selected native track paints subtitle pixels") { (subtitlePixels(host) ?: 0) > 12 }
        assertCaptionsAvoidControls()
        screenshot(File(folder, "vlc-native-french-visible.png"))

        openCaptions()
        selectOffFrom(requireNotNull(french.name))
        await("Off disables the real native track") { player.spuTrack == -1 }
        key(KeyEvent.KEYCODE_BACK)
        await("Off clears the native subtitle surface") { subtitlePixels(host)?.let { it <= 3 } == true }
        assertTrue("Dismissal restores the mounted caption origin", focused("Subtitles"))
        screenshot(File(folder, "vlc-native-off.png"))
    }

    @Test fun authoredTopAndBottomCaptionsFitBetweenControlsWithoutResizingVideo() = fixture(
        downloaded = true, authoredTop = true
    ) { host, player, folder, _ ->
        await("The authored ASS file is selected natively") {
            val tracks = player.spuTracks.orEmpty().filter { it.id >= 0 }
            tracks.size >= 3 && tracks.any { it.id == player.spuTrack && it.name?.contains("Case07") != true }
        }
        pauseAt(player, 3_000, decodeFromStart = true)
        openCaptions()
        key(KeyEvent.KEYCODE_BACK)
        await("Both authored top and bottom captions paint") {
            subtitlePixels(host)
            topSubtitlePixels > 12 && bottomSubtitlePixels > 12
        }
        assertCaptionsAvoidControls()
        val video = requireNotNull(host.findViewById<SurfaceView>(org.videolan.R.id.surface_video))
        val before = surfaceBounds(video)
        val subtitleSurface = requireNotNull(host.findViewById<SurfaceView>(org.videolan.R.id.surface_subtitles))
        val controlsScale = subtitleSurface.scaleY
        screenshot(File(folder, "vlc-authored-top-bottom-controls.png"))

        if (node("Hide extra controls") != null) {
            activate("Hide extra controls")
            await("The settings ribbon collapses before hiding playback controls") { node("More controls") != null }
        }
        // One Back hides controls. Their nodes remain during the exit fade;
        // another Back in that interval would leave the playback screen.
        key(KeyEvent.KEYCODE_BACK)
        await("Controls hide and restore the larger caption plane") {
            node("Back") == null && subtitleSurface.scaleY > controlsScale
        }
        assertEquals("Caption placement must leave the video bounds untouched", before, surfaceBounds(video))
        assertFalse("Placement must preserve the paused input", player.isPlaying)
        await("Authored top and bottom captions remain present with controls hidden") {
            subtitlePixels(host)
            topSubtitlePixels > 12 && bottomSubtitlePixels > 12
        }
        screenshot(File(folder, "vlc-authored-top-bottom-hidden.png"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Controls return") { node("Back") != null }
        await("Returning controls restore their safe caption plane") {
            subtitlePixels(host)
            kotlin.math.abs(subtitleSurface.scaleY - controlsScale) < .01f
        }
        assertCaptionsAvoidControls()
        assertEquals(before, surfaceBounds(video))
    }

    @Test fun downloadedOverlayTimingAppearanceAndOffUseProductionVlcControls() = fixture(true) { _, player, _, settings ->
        pauseAt(player, 3_000)
        val cue = "Overlay timing cue"
        await("Downloaded SRT is rendered over native video") { node(cue) != null }
        assertEquals("The overlay suppresses embedded native subtitles", -1, player.spuTrack)
        val standardBounds = Rect().also { requireNotNull(node(cue)).getBoundsInScreen(it) }

        openCaptions()
        for (step in 1..8) {
            activate("Show subtitles later")
            await("Later applies timing step $step") { settings.get()?.delay == step * 250L }
        }
        key(KeyEvent.KEYCODE_BACK)
        await("Positive delay removes a cue before its shifted start") { node(cue) == null }
        openCaptions()
        activate("Reset timing")
        await("Reset restores zero timing") { settings.get()?.delay == 0L }
        // Reset removes itself and transfers focus to Earlier. Wait for that
        // layout before navigating; a stale accessibility focus action can race
        // this removal and send OK to the surviving timing control instead.
        await("Reset finishes its focus and layout handoff") {
            node("Reset timing") == null && focused("Show subtitles earlier")
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Earlier Down reaches the mounted appearance action") { focused("Appearance: Standard") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("The downloaded preset changes to Large") {
            settings.get()?.appearance == SubtitleAppearancePreset.LARGE && node("Appearance: Large") != null
        }
        key(KeyEvent.KEYCODE_BACK)
        await("Reset restores the visible downloaded cue") { node(cue) != null }
        val largeBounds = Rect().also { requireNotNull(node(cue)).getBoundsInScreen(it) }
        assertTrue("Large changes the rendered caption size", largeBounds.height() > standardBounds.height())
        assertEquals("Overlay changes never enable embedded captions", -1, player.spuTrack)

        openCaptions()
        selectOffFrom("Downloaded subtitle")
        await("Off updates downloaded selection") { settings.get()?.enabled == false }
        key(KeyEvent.KEYCODE_BACK)
        await("Off removes the actual overlay") { node(cue) == null && node("Close") == null }
        assertEquals(-1, player.spuTrack)
        assertTrue("Overlay popup restores Subtitles", focused("Subtitles"))
    }

    private fun fixture(downloaded: Boolean, authoredTop: Boolean = false,
        verify: (PlaybackFixtureHostActivity, MediaPlayer, File, AtomicReference<Settings>) -> Unit) {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val folder = File(context.filesDir, "vlc-caption-integration").apply { mkdirs() }
        ZipInputStream(ins.context.assets.open("caption-fixtures.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require('/' !in entry.name && '\\' !in entry.name)
                if (entry.name == "case07-multi-en-fr.mkv") File(folder, entry.name).outputStream().use { zip.copyTo(it) }
            }
        }
        val subtitle = when {
            authoredTop -> File(folder, "authored-top-bottom.ass").apply { writeText("""
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 320
                PlayResY: 180
                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.50,0:00:07.00,Default,,0,0,0,,{\an8}Native top title
                Dialogue: 0,0:00:00.50,0:00:07.00,Default,,0,0,0,,{\an2}Native bottom dialogue
            """.trimIndent()) }
            downloaded -> File(folder, "overlay-timing.srt").apply {
                writeText("1\n00:00:02,000 --> 00:00:07,000\nOverlay timing cue\n")
            }
            else -> null
        }
        val media = PlayingMedia(MediaItem("vlc-caption-$downloaded", "VLC caption fixture", null, null),
            android.net.Uri.fromFile(File(folder, "case07-multi-en-fr.mkv")).toString(), CatalogType.MOVIES,
            progressKey = "vlc-caption-$downloaded", profileKey = "caption-fixture")
        val frames = AtomicInteger()
        val exits = AtomicInteger()
        val settings = AtomicReference<Settings>()
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        var active: MediaPlayer? = null
        try {
            main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Fixture window") { host.hasWindowFocus() }
            ins.setInTouchMode(false)
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                VlcPlayerScreen(media, rememberCricketScoreController(), rememberIntroSkipSettings(media),
                    initialResumePosition = 0L, onBack = { exits.incrementAndGet() },
                    onPlayPrevious = {}, onPlayNext = {}, onPlayItem = {}, onProgress = { _, _, _ -> },
                    initialExternalSubtitleFile = subtitle,
                    onSubtitleSettingsChanged = { _, enabled, delay, appearance -> settings.set(Settings(enabled, delay, appearance)) },
                    onRetry = { fail("Local fixture must not retry") }, onPlaybackAuthorizationFailure = { fail("Local fixture needs no authorization") },
                    onSelectPlayer = { _, _ -> fail("Caption controls must not change player preference") },
                    configuredEngine = PlaybackEngine.VLC, startFullscreen = true, controlsTimeoutSeconds = 0,
                    onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("Production VLC video renders") { frames.get() > 0 }
            // Observe the actual screen-owned JNI player without adding a production test hook.
            val ownerField = VlcPlaybackSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            val player = ((ownerField.get(null) as AtomicReference<*>).get() as VlcPlaybackSession).player
            active = player
            player.volume = 0
            verify(host, player, folder, settings)
            assertEquals("Caption controls keep playback open", 0, exits.get())
        } catch (failure: Throwable) {
            val appearanceLabels = SubtitleAppearancePreset.entries.map { "Appearance: ${it.label}" }
                .filter { node(it) != null }
            val diagnostics = "settings=${settings.get()}; appearanceLabels=$appearanceLabels; " +
                "focusedAppearance=${appearanceLabels.filter(::focused)}; $lastSubtitleCopy"
            val evidence = if (downloaded) "vlc-overlay-failure" else "vlc-native-failure"
            File(folder, "$evidence.txt").writeText(diagnostics)
            screenshot(File(folder, "$evidence.png"))
            throw AssertionError("${failure.message}; $diagnostics", failure)
        } finally {
            main { host.finish() }
            ins.setInTouchMode(true)
            active?.let { player -> await("VLC closes its screen-owned input") { player.isReleased } }
            subtitle?.delete()
        }
    }

    private fun pauseAt(player: MediaPlayer, position: Long, decodeFromStart: Boolean = false) {
        // The MKV has one long subtitle packet near zero. Seeking into its
        // middle skips that packet; native rendering must decode from the start.
        main { player.setTime(if (decodeFromStart) 0L else position - 250L, false) }
        val started = SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        await("VLC reaches the cue sample") {
            (!decodeFromStart || SystemClock.elapsedRealtime() - started >= position) &&
                player.time >= position && player.time < position + 1_000L
        }
        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        await("The cue sample remains paused") { !player.isPlaying }
    }

    /** PixelCopy reads only VLC's subtitle plane, so video and Compose controls cannot satisfy this assertion. */
    private fun subtitlePixels(host: PlaybackFixtureHostActivity): Int? {
        topSubtitlePixels = 0
        bottomSubtitlePixels = 0
        subtitleBoundsInScreen = null
        var surface: SurfaceView? = null
        main { surface = host.findViewById(org.videolan.R.id.surface_subtitles) }
        val view = surface ?: run { lastSubtitleCopy = "subtitle surface absent"; return 0 }
        if (!view.isShown || view.width < 2 || view.height < 2 || !view.holder.surface.isValid) {
            lastSubtitleCopy = "surface ${view.width}x${view.height}, shown=${view.isShown}, valid=${view.holder.surface.isValid}"
            return 0
        }
        val width = minOf(640, view.width)
        val bitmap = Bitmap.createBitmap(width, (view.height * width.toFloat() / view.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        val status = AtomicInteger(-1)
        try {
            main { PixelCopy.request(view, bitmap, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
            if (!done.await(2, TimeUnit.SECONDS)) { lastSubtitleCopy = "PixelCopy callback timeout"; return null }
            lastSubtitleCopy = "PixelCopy status=${status.get()}, surface=${view.width}x${view.height}"
            if (status.get() == PixelCopy.ERROR_SOURCE_NO_DATA) return 0
            if (status.get() != PixelCopy.SUCCESS) return null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            topSubtitlePixels = 0; bottomSubtitlePixels = 0
            val white = pixels.countIndexed { index, color ->
                val painted = Color.alpha(color) > 32 && Color.red(color) > 160 && Color.green(color) > 160 && Color.blue(color) > 160
                if (painted) {
                    val x = index % bitmap.width; val y = index / bitmap.width
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                    if (y < bitmap.height / 2) topSubtitlePixels++ else bottomSubtitlePixels++
                }
                painted
            }
            subtitleBoundsInScreen = if (white > 0) RectF(
                left * view.width.toFloat() / bitmap.width, top * view.height.toFloat() / bitmap.height,
                (right + 1) * view.width.toFloat() / bitmap.width, (bottom + 1) * view.height.toFloat() / bitmap.height
            ).also { bounds -> main { mapToScreen(view, bounds) } } else null
            lastSubtitleCopy += ", white=$white, maxAlpha=${pixels.maxOfOrNull(Color::alpha)}"
            return white
        } finally { bitmap.recycle() }
    }

    private inline fun IntArray.countIndexed(predicate: (Int, Int) -> Boolean): Int {
        var count = 0
        for (index in indices) if (predicate(index, this[index])) count++
        return count
    }

    private fun assertCaptionsAvoidControls() {
        val captions = requireNotNull(subtitleBoundsInScreen)
        val header = Rect().also { requireNotNull(node("Back")).getBoundsInScreen(it) }
        val dock = listOf("Forward 10 seconds", "Hide extra controls", "More controls").mapNotNull { label ->
            node(label)?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless(Rect::isEmpty)
        }.minByOrNull { it.top } ?: error("A visible playback dock action is required")
        assertTrue("Authored top captions remain below the header: $captions vs $header", captions.top >= header.bottom)
        assertTrue("Native dialogue remains above the dock: $captions vs $dock", captions.bottom <= dock.top)
    }

    private fun surfaceBounds(view: SurfaceView): RectF = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).also {
        main { mapToScreen(view, it) }
    }

    private fun mapToScreen(view: SurfaceView, bounds: RectF) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val matrix = Matrix()
            view.transformMatrixToGlobal(matrix)
            matrix.mapRect(bounds)
        } else {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds.left *= view.scaleX; bounds.right *= view.scaleX
            bounds.top *= view.scaleY; bounds.bottom *= view.scaleY
            bounds.offset(location[0].toFloat(), location[1].toFloat())
        }
    }

    private fun openCaptions() {
        if (node("More controls") == null && node("Hide extra controls") == null) key(KeyEvent.KEYCODE_DPAD_DOWN)
        if (node("Hide extra controls") == null) {
            activate("More controls")
            await("Settings opened") { node("Hide extra controls") != null }
        }
        activate("Subtitles")
        await("Caption popup opened") { node("Close") != null }
    }

    private fun selectOffFrom(selectedLabel: String) {
        await("The popup initially focuses its selected track") { focused(selectedLabel) }
        // Off can be outside the lazy viewport when the final track is selected.
        // Use the production route to scroll and mount each preceding row.
        for (step in 0 until 8) {
            if (focused("Off")) break
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        assertTrue("D-pad Up scrolls back to Off", focused("Off"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun activate(label: String) {
        await("Visible control: $label") { node(label) != null }
        var target = node(label)
        while (target != null && !target.isFocusable) target = target.parent
        assertTrue("Focus control: $label", target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
        await("Focused control: $label") { focused(label) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            value ?: return null
            if (value.text?.toString() == label || value.contentDescription?.toString() == label) return value
            for (index in 0 until value.childCount) visit(value.getChild(index))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }

    private fun focused(label: String): Boolean {
        var value = node(label)
        while (value != null) {
            if (value.isFocusable || value.isClickable || value.isEditable) return value.isFocused
            value = value.parent
        }
        return false
    }
    private fun screenshot(file: File) {
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); ins.waitForIdleSync() }
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(50L) }
        fail("$label; last subtitle copy: $lastSubtitleCopy")
    }
}
