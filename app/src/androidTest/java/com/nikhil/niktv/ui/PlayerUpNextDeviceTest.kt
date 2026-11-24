package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PlayerUpNextDeviceTest {
    @Test fun longTitleWrapsWithoutCoveringPromptActions() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val fallback = "The Missing Letter"
        val title = "The Morning Everyone Learns the Truth About the Missing Letter and What Really Happened at the Community Celebration"
        val next = MediaItem("next", "Episode 15. 2026-11-14 - $fallback", null, null, episodeNumber = 15)
        val media = PlayingMedia(next.copy(id = "playing"), "fixture", CatalogType.SERIES, nextEpisode = next)
        fun node(label: String): AccessibilityNodeInfo? {
            if (Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.isVisibleToUser && node.text?.toString() == label) return node
                return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
            }
            return find(ins.uiAutomation.rootInActiveWindow?.takeIf { it.packageName == context.packageName })
        }
        fun hasFocus(label: String) = generateSequence(node(label)) { it.parent }.any { it.isFocused }
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 7_000
            while (SystemClock.uptimeMillis() < end) {
                if (condition()) return
                SystemClock.sleep(40)
            }
            NavigationAuditEvidence.capture("up-next-failure")
            fail(message)
        }
        try {
            ins.setInTouchMode(false)
            val widths = listOf(280, 420, 640).filter { it <= host.resources.configuration.screenWidthDp }
            for (width in widths) {
                val cached = MutableStateFlow<String?>(null)
                val cancelled = AtomicBoolean()
                val played = AtomicBoolean()
                ins.runOnMainSync { host.setContent {
                    MaterialTheme(colorScheme = NikColors) {
                        CompositionLocalProvider(LocalUpNextEpisodeTitle provides { cached }) {
                            val play = remember { FocusRequester() }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(width.dp)) {
                                    PlayerUpNextPrompt(media, 15, play, { cancelled.set(true) }, { played.set(true) })
                                }
                            }
                            LaunchedEffect(play) { withFrameNanos {}; play.requestFocus() }
                        }
                    }
                } }
                await("Clean provider fallback is visible at ${width}dp") { node(fallback) != null }
                await("Play now keeps initial focus at ${width}dp") { hasFocus("Play now") }
                if (width == 640) {
                    val shortTitle = Rect().also(requireNotNull(node(fallback))::getBoundsInScreen)
                    val cancel = Rect().also(requireNotNull(node("Cancel"))::getBoundsInScreen)
                    val playNow = Rect().also(requireNotNull(node("Play now"))::getBoundsInScreen)
                    val tolerance = 2 * host.resources.displayMetrics.density
                    assertTrue("The short title lines up with both buttons", shortTitle.right <= cancel.left &&
                        kotlin.math.abs(shortTitle.centerY() - cancel.centerY()) <= tolerance &&
                        kotlin.math.abs(shortTitle.centerY() - playNow.centerY()) <= tolerance)
                }
                cached.value = title
                await("The full cached title and the buttons are visible at ${width}dp") {
                    val heading = node(title)?.let { Rect().also(it::getBoundsInScreen) }
                    val action = node("Play now")?.let { Rect().also(it::getBoundsInScreen) }
                    heading != null && action != null && (heading.bottom <= action.top || heading.right <= action.left) && hasFocus("Play now") &&
                        heading.height() > 26 * host.resources.displayMetrics.density
                }
                val heading = Rect().also(requireNotNull(node(title))::getBoundsInScreen)
                val timer = Rect().also(requireNotNull(node("Up next in 15s"))::getBoundsInScreen)
                val screen = requireNotNull(ins.uiAutomation.takeScreenshot())
                try {
                    assertTrue("Title text is light on the dark player panel", (heading.top until heading.bottom step 3).any { y ->
                        (heading.left until heading.right step 3).any { x ->
                            val pixel = screen.getPixel(x, y)
                            Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200
                        }
                    })
                    assertTrue("The countdown uses the original red accent", (timer.top until timer.bottom step 2).any { y ->
                        (timer.left until timer.right step 2).any { x ->
                            val pixel = screen.getPixel(x, y)
                            Color.red(pixel) > 170 && Color.green(pixel) < 75 && Color.blue(pixel) < 75
                        }
                    })
                } finally { screen.recycle() }
                NavigationAuditEvidence.capture("up-next-${width}dp")
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Left reaches Cancel at ${width}dp") { hasFocus("Cancel") }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Cancel can be activated") { cancelled.get() }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches Play now at ${width}dp") { hasFocus("Play now") }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Play now can be activated") { played.get() }
            }
        } finally {
            ins.runOnMainSync { host.finish() }
        }
    }
}
