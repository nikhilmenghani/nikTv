package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.components.*
import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class CompactLiveProgrammeDeviceTest {
    @Test fun longProgrammeFitsCompactCardsAndReorderMatchesTabs() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        fun node(text: String): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                n ?: return null
                if (n.text?.toString() == text || n.contentDescription?.toString() == text) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            return find(ins.uiAutomation.rootInActiveWindow)
        }
        fun bounds(label: String, clickable: Boolean = false): Rect {
            var n = requireNotNull(node(label)) { label }
            if (clickable) while (!n.isClickable) n = requireNotNull(n.parent)
            return Rect().also(n::getBoundsInScreen)
        }
        fun await(message: String, check: () -> Boolean) {
            val until = android.os.SystemClock.uptimeMillis() + 8_000
            while (android.os.SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(30) }
            fail(message)
        }
        val title = "Aquaman and the Lost Kingdom (2023)"
        val now = System.currentTimeMillis()
        for ((tv, font) in listOf(true to 1f, false to 1f, false to 1.3f)) {
            val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            val hasProgramme = mutableStateOf(false)
            val first = FocusRequester()
            val design = FocusRequester()
            var focused = -1
            try {
                ins.setInTouchMode(false)
                LiveChannelCardStyle.set(context, true)
                ins.runOnMainSync { host.setContent {
                    val config = Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = 960; screenHeightDp = if (tv) 540 else 400; fontScale = font
                    }
                    CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(2f, font)) {
                        MaterialTheme(colorScheme = NikColors) {
                            Column(Modifier.width(218.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiveChannelCardDesignSwitch(rememberModernLiveChannelCards(), Modifier.focusRequester(design)) {
                                    LiveChannelCardStyle.set(context, it)
                                }
                                Row {
                                    LiveChannelViewTab("Arrange pinned channels", false, Modifier, Icons.Default.SwapVert) {}
                                    LiveChannelViewTab("All", true, Modifier) {}
                                }
                                repeat(2) { index ->
                                    ModernLiveChannelTile(MediaItem("layout-$index", if (index == 0) "DC (4K)" else "Second channel", null, null,
                                        liveProgramme = if (index == 0 && hasProgramme.value) LiveProgramme(title, now - 600_000, now + 600_000) else null),
                                        onRefreshProgramme = {}, categoryTitle = "Movies", guideNow = now, programmeLoading = false,
                                        programmeWaitingUntil = null, programmeStatusNow = now, showQualityBadge = true,
                                        isFavorite = false, onFavorite = {}, isPinned = index == 0, onTogglePin = {}, onClick = {},
                                        isTv = tv, compact = true, onFocusChange = { if (it) focused = index },
                                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                                }
                            }
                        }
                    }
                } }
                await("Cards attached") { node("DC") != null }
                val before = bounds("DC", true)
                assertEquals(bounds("All", true).height(), bounds("Arrange pinned channels", true).height())
                ins.runOnMainSync { hasProgramme.value = true }
                await("Programme arrives") { node(title) != null }
                val after = bounds("DC", true)
                assertEquals("Guide arrival preserves geometry", before, after)
                val programme = bounds(title)
                assertTrue("Long programme wraps to a second line", programme.height() >= 24 * font * 2)
                assertTrue("Programme uses space beneath the corner badge", programme.width() > after.width() - 48)
                assertTrue("Programme fits above the progress track", programme.bottom < after.bottom - 14)
                assertTrue("Quality badge stays in the header", bounds("4K").bottom <= programme.top + 1)
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                ins.runOnMainSync { first.requestFocus() }
                await("First card focused") { focused == 0 }
                assertEquals("Focus does not resize the card", after, bounds("DC", true))
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reaches the next channel") { focused == 1 }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Up restores the programme card") { focused == 0 }
                ins.waitForIdleSync()
                NavigationAuditEvidence.capture("compact-programme-$tv-$font")
                ins.runOnMainSync { design.requestFocus() }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Switch restores classic card geometry") { bounds("DC", true).height() > after.height() }
                assertFalse(context.getSharedPreferences("live_channel_design", 0).getBoolean("modern_cards", true))
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Switch restores modern geometry without losing focus") { bounds("DC", true) == after }
            } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
        }
    }
}
