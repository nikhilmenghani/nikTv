package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.SeriesStartSeason
import org.junit.Assert.*
import org.junit.Test

/** Isolated synthetic data; never touches live profiles, portals or playback. */
class SharedSeriesControlsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) find(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun focused(text: String): Boolean {
        var node = find(text)
        while (node != null && !node.isFocusable) node = node.parent
        return node?.isFocused == true
    }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; Thread.sleep(50) }
        fail(message)
    }
    private fun key(value: Int) = ins.sendKeyDownUpSync(value)

    @Test fun thousandEpisodesKeepConstantTimeToolbarAccessOnTabletAndTv() {
        check(ins.targetContext.packageName.endsWith(".regression"))
        for (television in listOf(false, true)) {
            val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            ins.setInTouchMode(false)
            val card = DiscoveryCard(1234567, true, "Shared series fixture")
            val state = DiscoveryState(profile = "fixture-only", detail = card,
                details = DiscoveryDetails(card, seasons = listOf(1, 2), seasonEpisodeCounts = mapOf(1 to 1000)),
                season = 1, episodes = (1..1000).map { DiscoveryEpisode(1, it, "Fixture episode $it") })
            try {
                ins.runOnMainSync { host.setContent { MaterialTheme {
                    val entry = remember { FocusRequester() }
                    val play = remember { FocusRequester() }
                    val config = Configuration(LocalConfiguration.current).apply {
                        smallestScreenWidthDp = 960; screenWidthDp = 960; screenHeightDp = 540
                        if (television) uiMode = Configuration.UI_MODE_TYPE_TELEVISION
                    }
                    CompositionLocalProvider(LocalConfiguration provides config) {
                        Box(Modifier.width(960.dp).height(540.dp)) {
                            DiscoverySeriesEpisodes(state, SeriesStartSeason.FIRST, entry,
                                loadSeason = {}, play = { _, _, _ -> fail("Focus must not play") },
                                download = { _, _, _ -> fail("Focus must not download") },
                                onFocus = {}, up = { play.requestFocus() }, down = { false },
                                modifier = Modifier.fillMaxSize(), lastWatched = 1 to 2, loadInitialSeason = false,
                                header = { toolbar ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        NikTvSecondaryActionButton({}, Modifier.focusRequester(play)) { Text("Fixture Play") }
                                        toolbar()
                                    }
                                })
                        }
                    }
                    LaunchedEffect(Unit) { repeat(5) { withFrameNanos { }; runCatching { entry.requestFocus() } } }
                } } }
                await("Season dropdown rendered in TV=$television") { focused("Season 1") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Resume episode 2 in TV=$television") { focused("Fixture episode 2") }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await("One key returns to season in TV=$television") { focused("Season 1") }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Search is reachable in TV=$television") { focused("Search episodes") }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Options are reachable in TV=$television") { focused("Episode options") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Return preserves episode 2 in TV=$television") { focused("Fixture episode 2") }
            } finally { ins.runOnMainSync { host.finish() }; ins.waitForIdleSync() }
        }
    }
}
