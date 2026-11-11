package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.SeriesStartSeason
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Layout changes share one real episode pane and preserve its season/cursor state. */
class SeriesOrientationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun visit(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            value ?: return null
            if (value.text?.toString() == label || value.contentDescription?.toString() == label) return value
            for (index in 0 until value.childCount) visit(value.getChild(index))?.let { return it }
            return null
        }
        return visit(root())
    }
    private fun focused(label: String): Boolean {
        val selected = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var value = node(label)
        while (value != null) { if (value == selected) return true; value = value.parent }
        return false
    }
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (test()) return; Thread.sleep(40) }
        NavigationAuditEvidence.capture("series-orientation-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)

    @Test fun widePortraitWideTransitionKeepsLateSeasonAndUserChosenMiddleEpisode() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val portrait = mutableStateOf(false)
        val card = DiscoveryCard(919016, true, "Orientation series fixture")
        val state = DiscoveryState(profile = "orientation-fixture", detail = card,
            details = DiscoveryDetails(card, seasons = (1..16).toList(), seasonEpisodeCounts = (1..16).associateWith { 30 }),
            season = 11, episodes = (1..30).map { DiscoveryEpisode(11, it, "Orientation episode $it") })
        val entry = FocusRequester()
        val seasonLoads = AtomicInteger()
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (portrait.value) 360 else 800
                    screenHeightDp = if (portrait.value) 800 else 360
                    smallestScreenWidthDp = 360
                    orientation = if (portrait.value) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    Box(Modifier.width(if (portrait.value) 360.dp else 800.dp).height(if (portrait.value) 480.dp else 300.dp)) {
                        DiscoverySeriesEpisodes(state, SeriesStartSeason.FIRST, entry, loadSeason = { seasonLoads.incrementAndGet() },
                            play = { _, _, _ -> fail("Orientation navigation must not play") }, download = { _, _, _ -> fail("Orientation navigation must not download") },
                            onFocus = {}, up = { false }, down = { false }, lastWatched = 11 to 15, loadInitialSeason = false)
                    }
                    LaunchedEffect(portrait.value) { repeat(30) {
                        withFrameNanos { }; if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    } }
                }
            } } }
            await("Landscape phone uses wide toolbar and visible season sidebar") { focused("Search episodes") && node("Season 10") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Resume cursor starts in middle of season11") { focused("Orientation episode 15") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("User moves to episode14") { focused("Orientation episode 14") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("User moves to episode13") { focused("Orientation episode 13") }
            NavigationAuditEvidence.capture("series-orientation-wide-episode13")

            ins.runOnMainSync { portrait.value = true }
            await("Portrait replaces sidebar with season button") { focused("Season 11") && node("Season 10") == null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Portrait re-entry preserves episode13 rather than resume15") { focused("Orientation episode 13") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Portrait navigation updates remembered cursor to12") { focused("Orientation episode 12") }
            NavigationAuditEvidence.capture("series-orientation-portrait-episode12")

            ins.runOnMainSync { portrait.value = false }
            await("Wide layout restores season sidebar without loading another season") { focused("Search episodes") && node("Season 10") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Wide re-entry preserves episode12") { focused("Orientation episode 12") && node("Orientation episode 12")?.isVisibleToUser == true }
            assertEquals("Orientation never reloads or changes selected season", 0, seasonLoads.get())
            NavigationAuditEvidence.capture("series-orientation-wide-restored-episode12")
        } finally {
            ins.runOnMainSync { host.finish() }
            ins.waitForIdleSync()
            ins.setInTouchMode(true)
        }
    }
}
