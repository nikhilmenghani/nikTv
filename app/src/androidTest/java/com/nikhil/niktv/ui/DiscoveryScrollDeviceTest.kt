package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import com.nikhil.niktv.model.*

/** Real nested lazy lists and focus animations, isolated from personal profiles and streams. */
class DiscoveryScrollDeviceTest {
    @Test fun categoryPopupEntersSelectedCategoryWithoutFocusingSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val focusEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                focusEvents += event.text.joinToString(" ")
            }
        }
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                val profile = PortalProfile("Fixture", "https://example.invalid", "")
                CategoryManagerDialog(NikTvState(savedProfile = profile, categoryManagerType = CatalogType.MOVIES,
                    rawCategoriesByType = mapOf(CatalogType.MOVIES to (1..24).map { Category("$it", "Category $it", CatalogType.MOVIES) }),
                    categoryFilters = mapOf("${profile.cacheKey()}|MOVIES" to listOf("8"))), {}, {})
            } } }
            fun hasFocusedCategory(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isFocused) {
                    fun containsTitle(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                        n.text?.toString() == "Category 8" || (0 until n.childCount).any { n.getChild(it)?.let(::containsTitle) == true }
                    if (containsTitle(node)) return true
                }
                return (0 until node.childCount).any { hasFocusedCategory(node.getChild(it)) }
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!hasFocusedCategory(instrumentation.uiAutomation.rootInActiveWindow) && SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
            assertTrue("Popup enters the selected category, including offscreen selections", hasFocusedCategory(instrumentation.uiAutomation.rootInActiveWindow))
            Thread.sleep(100)
            assertFalse("Search must not receive transient focus", focusEvents.any { it.contains("Search categories") })
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            instrumentation.runOnMainSync { host.finish() }
        }
    }
    @Test fun nestedPosterRowsHaveOneBoundedMoveAndNoPostCompletionCorrection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val requesters = List(7) { FocusRequester() }
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var coordinator: DiscoveryScrollCoordinator
        var focused = -1
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                coordinator = remember { DiscoveryScrollCoordinator(scope) }
                DiscoveryVerticalFocusViewport(coordinator) {
                    LazyColumn(Modifier.height(330.dp).fillMaxWidth(), state = list) {
                        items(7) { row ->
                            Column(Modifier.height(190.dp)) {
                                Text("Row $row")
                                StableTileFocusViewport {
                                    LazyRow(contentPadding = PaddingValues(8.dp)) {
                                        items(8) { column ->
                                            DiscoveryPosterTile("Row $row item $column", null, "2020", 88.dp,
                                                modifier = Modifier.padding(4.dp).then(if (column == 0) Modifier.focusRequester(requesters[row]) else Modifier),
                                                onClick = {}, onFocus = {
                                                    focused = row
                                                    coordinator.align(list, row, false)
                                                })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } } }
            val pixels = (190 * context.resources.displayMetrics.density).toInt()
            fun position() = list.firstVisibleItemIndex * pixels + list.firstVisibleItemScrollOffset
            fun moveTo(row: Int) {
                lateinit var job: Job
                var previous = 0
                instrumentation.runOnMainSync {
                    previous = position()
                    job = coordinator.move(list, row, false) { runCatching { requesters[row].requestFocus() }.getOrDefault(false) }
                }
                val direction = if (row * pixels > previous) 1 else -1
                val started = SystemClock.uptimeMillis()
                while (job.isActive && SystemClock.uptimeMillis() - started < 1500) {
                    instrumentation.runOnMainSync {
                        val next = position()
                        assertTrue("Row must not reverse while settling: $previous -> $next", (next - previous) * direction >= -2)
                        previous = next
                    }
                    Thread.sleep(8)
                }
                assertFalse("Navigation finishes without an indefinite settling tail", job.isActive)
                assertTrue("A normal row transition is bounded, including device scheduling", SystemClock.uptimeMillis() - started < 700)
                var settled = 0
                instrumentation.runOnMainSync { assertEquals(row, focused); settled = position() }
                val until = SystemClock.uptimeMillis() + 450
                while (SystemClock.uptimeMillis() < until) {
                    instrumentation.runOnMainSync { assertEquals("No second focus-reveal correction", settled, position()) }
                    Thread.sleep(16)
                }
            }
            Thread.sleep(350)
            listOf(1, 2, 3, 2, 1, 0).forEach(::moveTo)
            instrumentation.runOnMainSync {
                coordinator.move(list, 2, false) { runCatching { requesters[2].requestFocus() }.getOrDefault(false) }
            }
            Thread.sleep(40)
            moveTo(0)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
