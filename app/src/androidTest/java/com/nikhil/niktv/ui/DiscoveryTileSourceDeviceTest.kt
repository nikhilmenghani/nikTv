package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source labels are presentation only. No profile, provider, trailer or media playback is opened. */
@SdkSuppress(minSdkVersion = 30)
class DiscoveryTileSourceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun label(title: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == title || it.contentDescription?.toString() == title
    }
    private fun state(title: String): String? {
        var node = label(title)
        repeat(8) { node?.stateDescription?.toString()?.takeIf { it.startsWith("Source:") }?.let { return it }; node = node?.parent }
        return null
    }
    private fun focused(title: String): Boolean {
        var node = label(title)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(40) }
        fail(message)
    }

    @Test fun badgesDistinguishAllSourcesWithoutBecomingDpadTargetsOrChangingCardSize() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val entry = FocusRequester()
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DiscoveryTileSource.entries.forEachIndexed { index, source ->
                            DiscoveryPosterTile("Poster ${source.label}", null, "Episode 21", 120.dp, reduceMotion = true,
                                modifier = if (index == 0) Modifier.focusRequester(entry) else Modifier,
                                progress = .45f, matchCount = if (source == DiscoveryTileSource.TMDB) 3 else null,
                                source = source, onClick = {})
                        }
                        DiscoveryPosterTile("No source", null, "Episode 21", 120.dp, reduceMotion = true, progress = .45f, onClick = {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DiscoveryTileSource.entries.forEach { source ->
                            DiscoveryPhoneMediaCard("Phone ${source.label}", null, "Episode 21", 110.dp, false,
                                progress = .45f, matchCount = if (source == DiscoveryTileSource.TMDB) 3 else null,
                                source = source, onClick = {}, onLongClick = null, onFocus = {})
                        }
                    }
                }
            } } }
            await("All sources have accessible labels on poster and phone cards") { DiscoveryTileSource.entries.all {
                state("Poster ${it.label}")?.startsWith("Source: ${it.label}") == true && state("Phone ${it.label}")?.startsWith("Source: ${it.label}") == true
            } }
            assertTrue("Match counts remain accessible alongside the source badge", state("Poster TMDB")?.contains("3 IPTV matches") == true)
            assertTrue("Phone match counts remain accessible alongside the source badge", state("Phone TMDB")?.contains("3 IPTV matches") == true)
            fun bounds(title: String): Rect {
                var node = label(title)!!
                while (!node.isFocusable && node.parent != null) node = node.parent
                return Rect().also(node::getBoundsInScreen)
            }
            assertEquals("Source overlay must not increase the poster footprint", bounds("No source").height(), bounds("Poster IPTV").height())
            instrumentation.runOnMainSync { entry.requestFocus() }
            await("IPTV card receives focus") { focused("Poster IPTV") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right skips decorative badges and reaches Alternate card") { focused("Poster Alternate") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches TMDB card") { focused("Poster TMDB") }
            val image = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.externalCacheDir, "source-badges-tablet.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { image.recycle() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
