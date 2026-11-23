package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local silent video and synthetic viewers in the separate regression app only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerPinchZoomDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun fixture(test: (PlaybackFixtureHostActivity, UserScope, UserScope) -> Unit) {
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val previousAccount = users.selectedAccount.value
        val previousOwner = users.selectedScope.value
        val account = "991119"
        val first = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
        val second = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.KIDS))
        runBlocking {
            users.selectAccount(account); users.initializeDefaults(account)
            users.selectProfile(first.profileId)
            PlayerGesturePreferences.setPinchToZoom(context, first, false)
            PlayerGesturePreferences.setPinchToZoom(context, second, false)
            for (gesture in PlayerSwipeGesture.entries) {
                PlayerGesturePreferences.setSwipe(context, first, gesture, false)
                PlayerGesturePreferences.setSwipe(context, second, gesture, false)
            }
        }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try { test(host, first, second) } catch (error: Throwable) {
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "profile-pinch-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw error
        } finally {
            main { host.finish() }
            runBlocking {
                PlayerGesturePreferences.setPinchToZoom(context, first, false)
                if (previousAccount != null) {
                    users.selectAccount(previousAccount)
                    previousOwner?.let { users.selectProfile(it.profileId) }
                } else users.disconnect()
            }
        }
    }
    private fun playerView(root: View): PlayerView? {
        if (root is PlayerView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount)
            playerView(root.getChildAt(i))?.let { return it }
        return null
    }
    private fun scale(host: PlaybackFixtureHostActivity): Float {
        var result = 0f
        main { result = playerView(host.window.decorView)?.videoSurfaceView?.scaleX ?: 0f }
        return result
    }
    private fun pinch(host: PlaybackFixtureHostActivity) {
        ins.setInTouchMode(true)
        val bounds = Rect()
        main { requireNotNull(playerView(host.window.decorView)).getGlobalVisibleRect(bounds) }
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val startSpan = bounds.width() * .18f
        val endSpan = bounds.width() * .8f
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, span: Float, count: Int = 2) {
            val properties = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply {
                x = centerX + if (it == 0) -span / 2 else span / 2
                y = centerY; pressure = 1f; size = 1f
            } }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(ins.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, startSpan, 1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), startSpan)
        for (i in 1..10) { SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, startSpan + i * (endSpan - startSpan) / 10f) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), endSpan)
        event(MotionEvent.ACTION_UP, endSpan, 1)
        ins.waitForIdleSync()
    }
    private fun checkPlayer(sports: Boolean) = fixture { host, first, second ->
        PlaybackEngineFixtureTest().FixtureServer().use { server ->
            val media = mutableStateOf(PlayingMedia(MediaItem("pinch", "Pinch fixture", null, null),
                "${server.base}/movie.mp4", CatalogType.MOVIES, progressKey = "pinch", userScope = first))
            val frames = AtomicInteger()
            val observed = AtomicBoolean(false)
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val enabled = rememberPinchToZoomEnabled(media.value.userScope).value
                SideEffect { observed.set(enabled) }
                if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media.value, {}, {}, {}, {}, {}, {}, { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                else PlayerScreen(media.value, {}, {}, {}, {}, {}, {}, { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("Silent fixture renders") { frames.get() > 0 && host.hasWindowFocus() && scale(host) > 0f }
            val baseline = scale(host)
            pinch(host)
            assertEquals("Pinch is disabled by default", baseline, scale(host), .01f)
            var player: androidx.media3.common.Player? = null
            main { player = playerView(host.window.decorView)?.player }
            runBlocking { PlayerGesturePreferences.setPinchToZoom(context, first, true) }
            await("Enabled viewer preference reaches Compose") { observed.get() }
            ins.waitForIdleSync()
            pinch(host)
            await("Opted-in viewer can zoom without reopening player") { scale(host) > baseline * 1.2f }
            main { media.value = media.value.copy(userScope = second) }
            await("Switching viewer removes pinch zoom") { !observed.get() && kotlin.math.abs(scale(host) - baseline) < .01f }
            pinch(host)
            assertEquals("Second viewer cannot inherit opt-in", baseline, scale(host), .01f)
            main { media.value = media.value.copy(userScope = first) }
            await("First viewer retains opt-in") { observed.get() }
            ins.waitForIdleSync(); pinch(host)
            await("First viewer can zoom again") { scale(host) > baseline * 1.2f }
            runBlocking { PlayerGesturePreferences.setPinchToZoom(context, first, false) }
            await("Disabling resets the current gesture transform") { !observed.get() && kotlin.math.abs(scale(host) - baseline) < .01f }
            pinch(host)
            assertEquals("Disabled again immediately", baseline, scale(host), .01f)
            main { assertSame("Preference changes do not restart playback", player, playerView(host.window.decorView)?.player) }
        }
    }
    @Test fun iptvPinchIsOptInAndDoesNotLeakAcrossViewers() = checkPlayer(false)
    @Test fun sportsPinchIsOptInAndDoesNotLeakAcrossViewers() = checkPlayer(true)

    private fun checkSwipes(sports: Boolean, engine: PlaybackEngine, easy: Boolean = false) = fixture { host, first, second ->
        PlaybackEngineFixtureTest().FixtureServer().use { server ->
            val queue = (1..3).map { MediaItem("swipe-$it", "Swipe item $it", null, null) }
            val media = mutableStateOf(PlayingMedia(queue.first(), "${server.base}/movie.mp4", CatalogType.MOVIES,
                episodeQueue = queue, progressKey = "swipe", userScope = first))
            val observed = java.util.concurrent.atomic.AtomicReference(PlayerSwipeControls())
            val frames = AtomicInteger()
            val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val oldVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val oldBrightness = host.window.attributes.screenBrightness
            fun label(text: String): AccessibilityNodeInfo? {
                if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
                fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    n ?: return null
                    if (n.text?.toString() == text || n.contentDescription?.toString() == text) return n
                    for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
                    return null
                }
                return visit(ins.uiAutomation.rootInActiveWindow)
            }
            fun swipe(x: Float) {
                ins.setInTouchMode(true)
                val metrics = host.resources.displayMetrics
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, y: Float) {
                    val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, metrics.widthPixels * x, metrics.heightPixels * y, 0)
                    try { ins.sendPointerSync(e) } finally { e.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, .70f)
                for (i in 1..10) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, .70f - i * .04f) }
                send(MotionEvent.ACTION_UP, .30f)
                ins.waitForIdleSync()
            }
            fun queueOpen() = if (easy) label("Close") != null && label("Swipe item 3") != null else label("Choose what to play") != null
            try {
                main { host.setContent { CompositionLocalProvider(LocalViewerLayout provides if (easy) ViewerLayout.EASY else ViewerLayout.STANDARD) { MaterialTheme(colorScheme = NikColors) {
                    val controls = rememberPlayerSwipeControls(media.value.userScope).value
                    SideEffect { observed.set(controls) }
                    if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media.value, {}, {}, {}, {}, {}, {}, { _, _, _ -> },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                    else PlayerScreen(media.value, {}, {}, {}, {}, {}, {}, { _, _, _ -> },
                        playbackEngine = engine, startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                } } } }
                await("$engine silent player renders") { frames.get() > 0 && host.hasWindowFocus() }
                val brightness = host.window.attributes.screenBrightness
                val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                swipe(.1f); swipe(.9f); swipe(.5f)
                assertEquals("Disabled brightness swipe has no effect", brightness, host.window.attributes.screenBrightness, .001f)
                assertEquals("Disabled volume swipe has no effect", volume, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                assertFalse("Disabled swipe does not open the queue", queueOpen())
                runBlocking { PlayerGesturePreferences.setSwipe(context, first, PlayerSwipeGesture.BRIGHTNESS, true) }
                await("Only brightness is enabled") { observed.get() == PlayerSwipeControls(brightness = true) }
                swipe(.1f)
                assertTrue("Enabled brightness adjusts the level", kotlin.math.abs(brightness - host.window.attributes.screenBrightness) > .01f)
                // Keep tablet volume at zero throughout. Exercise enabled volume only on Poco.
                if (android.os.Build.MODEL == "M2102J20SI") {
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                    runBlocking { PlayerGesturePreferences.setSwipe(context, first, PlayerSwipeGesture.VOLUME, true) }
                    await("Volume is enabled") { observed.get().volume }
                    swipe(.9f)
                    assertTrue("Enabled volume adjusts the level", audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0)
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, oldVolume, 0)
                }
                runBlocking { PlayerGesturePreferences.setSwipe(context, first, PlayerSwipeGesture.CHANNELS, true) }
                await("Queue gesture is enabled") { observed.get().channels }
                swipe(.5f)
                await("Enabled swipe opens the media list") { queueOpen() }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back closes the list") { !queueOpen() }
                main { media.value = media.value.copy(userScope = second) }
                await("A different viewer starts with every swipe disabled") { observed.get() == PlayerSwipeControls() }
                swipe(.5f)
                assertFalse(queueOpen())
            } finally {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, oldVolume, 0)
                main { host.window.attributes = host.window.attributes.apply { screenBrightness = oldBrightness } }
            }
        }
    }
    @Test fun iptvSwipesAreIndependentAndProfileScoped() = checkSwipes(false, PlaybackEngine.MEDIA3)
    @Test fun sportsSwipesAreIndependentAndProfileScoped() = checkSwipes(true, PlaybackEngine.MEDIA3)
    @Test fun vlcIptvSwipesUseTheSameProfilePolicy() = checkSwipes(false, PlaybackEngine.VLC)
    @Test fun vlcSportsSwipesUseTheSameProfilePolicy() = checkSwipes(true, PlaybackEngine.VLC)
    @Test fun easyPlayerSwipesCanBeExplicitlyEnabled() = checkSwipes(false, PlaybackEngine.MEDIA3, easy = true)

    @Test fun swipeSettingRowsKeepFocusAndChangeOnlyTheEditedViewer() = fixture { host, first, second ->
        main { host.setContent { MaterialTheme(colorScheme = NikColors) {
            val controls by rememberPlayerSwipeControls(first)
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PlayerSwipeSettings(controls) { gesture, enabled -> scope.launch {
                    PlayerGesturePreferences.setSwipe(context, first, gesture, enabled)
                } }
            }
        } } }
        fun row(title: String): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
            fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                n ?: return null
                if (n.text?.toString() == title) return n
                for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
                return null
            }
            var node = visit(ins.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isCheckable) node = node.parent
            return node
        }
        for ((gesture, title) in listOf(PlayerSwipeGesture.CHANNELS to "Swipe up for channels or episodes",
            PlayerSwipeGesture.BRIGHTNESS to "Swipe to change brightness", PlayerSwipeGesture.VOLUME to "Swipe to change volume")) {
            await("$title appears") { row(title) != null }
            assertTrue(requireNotNull(row(title)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Touch enables $title") { row(title)?.isChecked == true }
            assertFalse(PlayerGesturePreferences.swipes(context, second).enabled(gesture))
            ins.setInTouchMode(false)
            assertTrue(requireNotNull(row(title)).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("$title owns focus") { row(title)?.isFocused == true }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK disables $title without moving focus") { row(title)?.isChecked == false && row(title)?.isFocused == true }
        }
        NavigationAuditEvidence.capture("profile-swipe-gesture-settings")
    }

    @Test fun profileSettingSupportsTouchAndDpadWithoutLosingFocus() = fixture { host, first, second ->
        main { host.setContent { MaterialTheme(colorScheme = NikColors) {
            CompositionLocalProvider(LocalSettingsPage provides SettingsPage.VIEWER_PORTALS) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ViewerProfileSettings(first.profileId, emptyList())
                } }
            }
        } } }
        fun row(): AccessibilityNodeInfo? {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == "Pinch to zoom") return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
            var node = find(ins.uiAutomation.rootInActiveWindow)
            repeat(8) { if (node?.isCheckable == true) return node; node = node?.parent }
            return null
        }
        await("Actual profile setting is rendered") { row() != null && host.hasWindowFocus() }
        assertFalse(requireNotNull(row()).isChecked)
        fun screenshot(name: String) {
            ins.waitForIdleSync()
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        screenshot("profile-pinch-unfocused.png")
        val bounds = Rect().also { requireNotNull(row()).getBoundsInScreen(it) }
        ins.setInTouchMode(true)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { ins.sendPointerSync(event) } finally { event.recycle() }
        }
        await("Touch enables only this viewer") { PlayerGesturePreferences.pinchToZoom(context, first) && row()?.isChecked == true }
        assertFalse(PlayerGesturePreferences.pinchToZoom(context, second))
        ins.setInTouchMode(false)
        assertTrue(requireNotNull(row()).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await("Row has D-pad focus") { row()?.isFocused == true }
        screenshot("profile-pinch-focused.png")
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("D-pad OK disables the same viewer") { !PlayerGesturePreferences.pinchToZoom(context, first) && row()?.isChecked == false }
        assertTrue("Toggling retains focus", row()?.isFocused == true)
        screenshot("profile-pinch-focused-off.png")
    }
}
