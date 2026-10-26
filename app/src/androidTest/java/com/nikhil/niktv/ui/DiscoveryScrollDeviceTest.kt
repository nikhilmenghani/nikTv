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
    @Test fun revealOnlyNavigationKeepsVisibleRowsStillAndRevealsOnlyClippedEdges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val requesters = List(8) { FocusRequester() }
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var coordinator: DiscoveryScrollCoordinator
        var focused = -1
        var revealPolicy = true
        var rowStep = 0
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.isHapticFeedbackEnabled = false
                host.setContent {
                    list = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    coordinator = remember { DiscoveryScrollCoordinator(scope) }
                    SideEffect { ready.set(true) }
                    DiscoveryVerticalFocusViewport(coordinator) {
                        LazyColumn(Modifier.height(330.dp).fillMaxWidth(), state = list,
                            contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(8) { row ->
                                Box(Modifier.height(100.dp).fillMaxWidth().focusRequester(requesters[row])
                                    .onFocusChanged { state -> if (state.isFocused) {
                                        focused = row
                                        coordinator.align(list, row, false, revealOnly = revealPolicy)
                                    } }.focusable()) { Text("Reveal row $row") }
                            }
                        }
                    }
                }
            }
            fun await(label: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 3000
                while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(12) }
                fail(label)
            }
            await("Synthetic viewport owns focus and has initial rows") {
                ready.get() && host.hasWindowFocus() && list.layoutInfo.visibleItemsInfo.any { it.index == 2 }
            }
            fun position() = list.firstVisibleItemIndex * rowStep + list.firstVisibleItemScrollOffset
            fun waitFor(job: Job, label: String, observeScroll: (() -> Unit)? = null) {
                val deadline = SystemClock.uptimeMillis() + 2500
                while (job.isActive && SystemClock.uptimeMillis() < deadline) {
                    observeScroll?.let { instrumentation.runOnMainSync(it) }
                    Thread.sleep(8)
                }
                assertFalse("$label must finish without stale focus work", job.isActive)
            }
            fun stable(expected: Int, row: Int) {
                val deadline = SystemClock.uptimeMillis() + 220
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        assertEquals("No later implicit reveal or obsolete navigation", expected, position())
                        assertEquals(row, focused)
                    }
                    Thread.sleep(16)
                }
            }
            fun move(row: Int, reduced: Boolean = false, reveal: Boolean = true): Job {
                lateinit var result: Job
                instrumentation.runOnMainSync {
                    result = coordinator.move(list, row, reduced, revealOnly = reveal) {
                        runCatching { requesters[row].requestFocus() }.getOrDefault(false)
                    }
                }
                return result
            }
            instrumentation.runOnMainSync {
                val rows = list.layoutInfo.visibleItemsInfo
                rowStep = rows.first { it.index == 1 }.offset - rows.first { it.index == 0 }.offset
                assertTrue(requesters[0].requestFocus())
            }
            await("Initial reveal-only alignment completes") { !coordinator.isMoving }
            var start = 0
            instrumentation.runOnMainSync { start = position(); assertEquals(0, start) }
            var observedScrollMutation = false
            val visible = move(1)
            waitFor(visible, "Fully visible row") {
                observedScrollMutation = observedScrollMutation || list.isScrollInProgress
            }
            instrumentation.runOnMainSync {
                assertEquals(1, focused)
                assertEquals("A fully visible destination must not move to the top", start, position())
            }
            assertFalse("A fully visible row needs no scroll mutation", observedScrollMutation)
            stable(start, 1)

            var missingBottom = 0
            instrumentation.runOnMainSync {
                val info = list.layoutInfo
                val row = info.visibleItemsInfo.first { it.index == 2 }
                missingBottom = row.offset + row.size - (info.viewportEndOffset - info.afterContentPadding)
                assertTrue("The third row must be clipped by the padded viewport", missingBottom > 0)
            }
            waitFor(move(2), "Clipped row")
            var clippedPosition = 0
            instrumentation.runOnMainSync {
                clippedPosition = position()
                assertEquals("Reveal only the missing bottom portion, including content insets", start + missingBottom, clippedPosition)
                val info = list.layoutInfo
                val row = info.visibleItemsInfo.first { it.index == 2 }
                assertEquals(info.viewportEndOffset - info.afterContentPadding, row.offset + row.size)
                assertTrue("A minimal reveal must not align the row to the top", row.offset > info.viewportStartOffset)
                assertEquals(2, focused)
            }
            stable(clippedPosition, 2)

            var missingTop = 0
            instrumentation.runOnMainSync {
                val info = list.layoutInfo
                missingTop = info.visibleItemsInfo.first { it.index == 0 }.offset - info.viewportStartOffset
                assertTrue("The first row is now slightly clipped at the padded top", missingTop < 0)
            }
            waitFor(move(0, reduced = true), "Reduced-motion minimal reveal")
            instrumentation.runOnMainSync {
                assertEquals(clippedPosition + missingTop, position())
                assertTrue("Reduced motion must preserve a visible row's padded offset", position() > 0)
                assertEquals(0, focused)
            }

            val obsolete = move(6)
            Thread.sleep(40)
            val latest = move(1)
            assertTrue("A rapid reversal cancels obsolete offscreen work", obsolete.isCancelled)
            waitFor(latest, "Latest reveal-only destination")
            var reversedPosition = 0
            instrumentation.runOnMainSync {
                reversedPosition = position()
                val info = list.layoutInfo
                val row = info.visibleItemsInfo.first { it.index == 1 }
                assertTrue(row.offset >= info.viewportStartOffset)
                assertTrue(row.offset + row.size <= info.viewportEndOffset - info.afterContentPadding)
                assertEquals(1, focused)
            }
            stable(reversedPosition, 1)

            // The default policy used by headers, hero and details still aligns.
            revealPolicy = false
            waitFor(move(0, reduced = true, reveal = false), "Default reset")
            waitFor(move(1, reveal = false), "Default alignment")
            instrumentation.runOnMainSync {
                assertEquals(1, list.firstVisibleItemIndex)
                assertEquals(0, list.firstVisibleItemScrollOffset)
                assertEquals(rowStep, position())
                assertEquals(1, focused)
            }

            // An offscreen estimate changes to minimal reveal as the row composes.
            revealPolicy = true
            waitFor(move(5), "Offscreen minimal reveal")
            instrumentation.runOnMainSync {
                val info = list.layoutInfo
                val row = info.visibleItemsInfo.first { it.index == 5 }
                assertTrue("The composed offscreen row must finish fully visible", row.offset >= info.viewportStartOffset)
                assertTrue(row.offset + row.size <= info.viewportEndOffset - info.afterContentPadding)
                assertEquals(5, focused)
            }
            waitFor(move(7, reduced = true), "Offscreen reduced-motion focus")
            instrumentation.runOnMainSync { assertEquals(7, focused) }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

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
            lateinit var firstRetarget: Job
            instrumentation.runOnMainSync {
                firstRetarget = coordinator.move(list, 2, false) { runCatching { requesters[2].requestFocus() }.getOrDefault(false) }
            }
            Thread.sleep(45)
            lateinit var secondRetarget: Job
            instrumentation.runOnMainSync {
                secondRetarget = coordinator.move(list, 4, false) { runCatching { requesters[4].requestFocus() }.getOrDefault(false) }
            }
            assertTrue("A same-direction retarget cancels the previous focus-transfer owner", firstRetarget.isCancelled)
            Thread.sleep(45)
            moveTo(0)
            assertTrue("A reversal cancels the previous destination", secondRetarget.isCancelled)
            lateinit var reduced: Job
            instrumentation.runOnMainSync {
                reduced = coordinator.move(list, 3, true) { runCatching { requesters[3].requestFocus() }.getOrDefault(false) }
            }
            val reducedUntil = SystemClock.uptimeMillis() + 1500
            while (reduced.isActive && SystemClock.uptimeMillis() < reducedUntil) Thread.sleep(10)
            assertFalse("Reduced motion must still finish and focus offscreen rows", reduced.isActive)
            instrumentation.runOnMainSync {
                assertEquals(3, focused)
                assertEquals(3, list.firstVisibleItemIndex)
                assertEquals(0, list.firstVisibleItemScrollOffset)
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
