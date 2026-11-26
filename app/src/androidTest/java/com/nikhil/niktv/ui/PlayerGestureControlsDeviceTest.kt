package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Local silent fixture; touches no IPTV session or real viewer preferences. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerGestureControlsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isFocusable) { node = node.parent; node?.refresh() }
        // API 30 can retain a stale isFocused flag after an input transition.
        return node != null && node == ins.uiAutomation.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000L
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(40) }
        NavigationAuditEvidence.capture("player-gestures-failure")
        fail(message)
    }
    private fun key(code: Int) {
        ins.sendKeyDownUpSync(code)
        ins.waitForIdleSync()
    }
    @Test fun media3GesturesAndTimeoutRemainReachable() = exercise(PlaybackEngine.MEDIA3)
    @Test fun vlcGesturesAndTimeoutRemainReachable() = exercise(PlaybackEngine.VLC)
    @Test fun mpvGesturesAndTimeoutRemainReachable() {
        assumeTrue(mpvSupportedSdk(android.os.Build.VERSION.SDK_INT))
        exercise(PlaybackEngine.MPV)
    }
    @Test fun sportsMedia3GesturesRemainReachable() = exercise(PlaybackEngine.MEDIA3, sports = true)
    @Test fun sportsVlcGesturesRemainReachable() = exercise(PlaybackEngine.VLC, sports = true)
    private fun exercise(engine: PlaybackEngine, sports: Boolean = false) {
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val previousAccount = users.selectedAccount.value
        val previousOwner = users.selectedScope.value
        val account = "991128"
        val owner = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
        val other = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.KIDS))
        val store = ProfileStore(context).scoped(owner)
        runBlocking {
            users.selectAccount(account); users.initializeDefaults(account); users.selectProfile(owner.profileId)
            for (gesture in PlayerSwipeGesture.entries) {
                PlayerGesturePreferences.setSwipe(context, owner, gesture, false)
                PlayerGesturePreferences.setSwipe(context, other, gesture, false)
            }
            store.setPlayerControlsTimeoutSeconds(3)
        }
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            PlaybackEngineFixtureTest().FixtureServer().use { server ->
                val rendered = AtomicInteger()
                ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                    val scope = rememberCoroutineScope()
                    var timeout by remember { mutableIntStateOf(3) }
                    LaunchedEffect(store) { store.playerControlsTimeoutSeconds.collect { timeout = it } }
                    val media = PlayingMedia(MediaItem("gesture-controls", "Gesture controls", null, null),
                        "${server.base}/movie.mp4", CatalogType.MOVIES, progressKey = "gesture-controls", userScope = owner)
                    if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media,
                        {}, {}, {}, {}, {}, {}, { _, _, _ -> }, playbackEngine = engine, startFullscreen = true,
                        controlsTimeoutSeconds = timeout, onControlsTimeoutChanged = { seconds ->
                            timeout = seconds; scope.launch { store.setPlayerControlsTimeoutSeconds(seconds) }
                        }, onVideoFrameRendered = { rendered.incrementAndGet() })
                    else PlayerScreen(media,
                        {}, {}, {}, {}, {}, {}, { _, _, _ -> }, playbackEngine = engine, startFullscreen = true,
                        controlsTimeoutSeconds = timeout, onControlsTimeoutChanged = { seconds ->
                            timeout = seconds; scope.launch { store.setPlayerControlsTimeoutSeconds(seconds) }
                        }, onVideoFrameRendered = { rendered.incrementAndGet() })
                } } }
                await("$engine fixture renders") { rendered.get() > 0 && host.hasWindowFocus() }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down opens player controls on Pause") { focused("Pause") }
                for (i in 0 until 12) {
                    if (focused("More controls")) break
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
                await("Settings trigger takes focus") { focused("More controls") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Settings remains focused after revealing the row") { focused("Hide extra controls") }
                var reached = false
                for (i in 0 until 16) {
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    if (focused("Gestures")) { reached = true; break }
                }
                assertTrue("Gestures must be reachable with D-pad Left", reached)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Popup initially focuses the first switch") { focused("Channels and episodes") }
                for ((index, gesture) in PlayerSwipeGesture.entries.withIndex()) {
                    if (index > 0) key(KeyEvent.KEYCODE_DPAD_DOWN)
                    val label = when (gesture) {
                        PlayerSwipeGesture.CHANNELS -> "Channels and episodes"
                        PlayerSwipeGesture.BRIGHTNESS -> "Brightness"
                        PlayerSwipeGesture.VOLUME -> "Volume"
                    }
                    await("$label receives focus") { focused(label) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("$label is saved without losing focus") {
                        PlayerGesturePreferences.swipes(context, owner).enabled(gesture) && focused(label)
                    }
                    assertFalse("Changes do not leak to another profile", PlayerGesturePreferences.swipes(context, other).enabled(gesture))
                }
                NavigationAuditEvidence.capture("player-gestures-${engine.name.lowercase()}")
                key(KeyEvent.KEYCODE_BACK)
                await("Back closes one level and returns directly to Gestures") {
                    find("Close gestures") == null && focused("Gestures")
                }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Left continues from the adjacent Timeout action") { focused("Controls timeout: 3s") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Timeout changes the real setting") {
                    runBlocking { store.playerControlsTimeoutSeconds.first() } != 3
                }
                assertEquals("Other profiles keep their default timeout", 3,
                    runBlocking { ProfileStore(context).scoped(other).playerControlsTimeoutSeconds.first() })
                assertEquals(PlayerSwipeControls(true, true, true), PlayerGesturePreferences.swipes(context, owner))
            }
        } finally {
            ins.runOnMainSync { host.finish() }
            runBlocking {
                if (previousAccount == null) users.disconnect() else users.selectAccount(previousAccount)
                previousOwner?.let { users.selectProfile(it.profileId) }
            }
            ins.setInTouchMode(true)
        }
    }
}
