package com.nikhil.niktv.ui

import android.content.Intent
import android.os.*
import android.view.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

/** Physical-window component comparison. No accounts, network, player or personal cache. */
class CatalogRendererPerformanceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private var selected = ""
    private var poster: String? = null
    private var dispatch = 0L
    private var focus = 0L
    private var draw = 0L
    private var highlight = 0L
    private var nativeMode = false
    private fun select(id: String) {
        if (id == selected) return
        selected = id
        if (dispatch != 0L) focus = SystemClock.elapsedRealtimeNanos()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(20) }
        fail("$message; selection=$selected")
    }
    @Composable private fun Legacy(restore: (suspend () -> Unit) -> Unit) {
        val scope = rememberCoroutineScope()
        val list = rememberLazyListState()
        val coordinator = remember { DiscoveryScrollCoordinator(scope) }
        val requests = remember { mutableMapOf<String, FocusRequester>() }
        val entries = remember { mutableMapOf<Int, suspend () -> Boolean>() }
        val configuration = LocalConfiguration.current
        val phone = configuration.smallestScreenWidthDp < 600 && !LocalContext.current.isTvLikeDevice(configuration)
        val width = if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp).dp
            else ((configuration.screenWidthDp - 48 - 72) / 7f).dp
        DisposableEffect(Unit) {
            restore { coordinator.cancel(); list.scrollToItem(0); entries[0]?.invoke() }
            onDispose { coordinator.cancel() }
        }
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF090B10)), state = list) {
            items(14, key = { it }) { row ->
                val model = remember(row) { Rail("row$row", "Selected category $row", (0 until 30).map { item ->
                    RailEntry("row$row:item$item", "A longer movie title $row / $item", null, subtitle = "2025 · IPTV", open = {})
                }) }
                DiscoveryRail(model, width, false, requests, row, phone,
                    popupChanged = {}, prefetch = true, trackPresentation = false, presentationEnabled = false, skeleton = true,
                    register = { entries[row] = it }, unregister = { entries.remove(row) },
                    focused = { select(it.key) }, present = {}, move = { direction ->
                        val target = row + direction
                        if (target in 0..13) { coordinator.move(list, target, false) { entries[target]?.invoke() ?: false }; true } else true
                    }, reenter = { scope.launch { entries[row]?.invoke() } }, viewAll = null,
                    highlightedDraw = { id -> if (id == selected && dispatch != 0L && focus != 0L && highlight == 0L)
                        highlight = SystemClock.elapsedRealtimeNanos() })
            }
        }
    }
    @Test fun comparePhysicalWindowRenderersABBA() {
        check(i.targetContext.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(i.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val report = JSONArray()
        val compareHeader = InstrumentationRegistry.getArguments().getString("compareHeader") == "true"
        val localArtwork = InstrumentationRegistry.getArguments().getString("localArtwork") == "true"
        if (localArtwork) {
            val image = java.io.File(i.targetContext.cacheDir, "catalog-fixture-poster.png")
            val bitmap = android.graphics.Bitmap.createBitmap(320, 480, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(55, 65, 85))
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            poster = image.toURI().toString()
        }
        val thread = HandlerThread("catalog-frames").apply { start() }
        val totals = Collections.synchronizedList(mutableListOf<Long>())
        val ui = Collections.synchronizedList(mutableListOf<Long>())
        var sampleStart = Long.MAX_VALUE
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= sampleStart) {
                totals.add(m.getMetric(FrameMetrics.TOTAL_DURATION))
                ui.add(intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                    FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).sumOf { m.getMetric(it) })
            }
        }
        val observer = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
            (next?.tag as? String)?.takeIf { it.startsWith("row") }?.let(::select)
        }
        val onDraw = android.view.ViewTreeObserver.OnDrawListener {
            if (dispatch != 0L && focus != 0L) {
                if (draw == 0L) draw = SystemClock.elapsedRealtimeNanos()
                if (nativeMode && highlight == 0L) host.currentFocus?.let { view ->
                    if (view.tag == selected && view.getGlobalVisibleRect(android.graphics.Rect())) highlight = SystemClock.elapsedRealtimeNanos()
                }
            }
        }
        try {
            await("Window focus") { host.hasWindowFocus() }
            i.setInTouchMode(false); i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            i.runOnMainSync {
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(observer)
                host.window.decorView.viewTreeObserver.addOnDrawListener(onDraw)
                host.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            for ((block, treatment) in listOf(false, true, true, false).withIndex()) {
                val native = compareHeader || treatment
                nativeMode = native
                val withHeader = compareHeader && treatment
                i.sendStatus(2, Bundle().apply { putString("stream", "\nCATALOG_BLOCK_START=$block native=$native\n") })
                val entry = FocusRequester()
                val heroEntry = FocusRequester(); val heroInfo = FocusRequester()
                var restore: (suspend () -> Unit)? = null
                selected = ""; dispatch = 0L
                i.runOnMainSync { host.setContent { key(block) { MaterialTheme {
                    if (native) RecycledDiscoveryRows((0 until 14).map { row -> NativeDiscoveryRow("row$row", "Selected category $row",
                        (0 until 30).map { item -> NativeDiscoveryItem("item$item", "A longer movie title $row / $item", "2025 · IPTV", onClick = {}) }) },
                        Modifier.fillMaxSize(), entry, {}, containedCaptions = true, showSkeletons = true,
                        cardShadows = true, homeTileProportions = true,
                        animateFocus = !host.isTvLikeDevice(host.resources.configuration),
                        heroHeightPx = if (withHeader) (300 * host.resources.displayMetrics.density).toInt() else null,
                        hero = if (withHeader) ({ focused, down, up ->
                            Box(Modifier.fillMaxSize().background(Color(0xFF182332))) {
                                DiscoveryHero(DiscoveryCard(42, false, "A selected IPTV movie", overview = "Cached description", date = "2025-01-01"),
                                    null, 300.dp, true, true, false, false, false, heroEntry, heroInfo,
                                    {}, {}, {}, { focused("play") }, down, {}, {}, true, 0, 10, onUp = { up(); true })
                            }
                        }) else null, heroFocusEntry = { heroEntry.requestFocus() },
                        cacheTileDrawing = host.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                    else Legacy { restore = it }
                } } } }
                i.waitForIdleSync()
                if (native) i.runOnMainSync { host.lifecycleScopeLaunch { repeat(90) {
                    runCatching { if (host.window.decorView.findViewWithTag<View>("row0:item0")?.requestFocus() != true) entry.requestFocus() }; if (selected.isNotEmpty()) return@lifecycleScopeLaunch
                    kotlinx.coroutines.delay(16)
                } } }
                else i.runOnMainSync { host.lifecycleScopeLaunch { repeat(90) {
                    restore?.invoke(); if (selected.isNotEmpty()) return@lifecycleScopeLaunch
                    kotlinx.coroutines.delay(16)
                } } }
                await("Initial media focus block=$block native=$native") { selected == "row0:item0" }
                Thread.sleep(1500)
                repeat(4) { trial ->
                    val latency = JSONArray()
                    totals.clear(); ui.clear(); sampleStart = System.nanoTime()
                    val pattern = listOf(20, 20, 22, 22, 21, 21, 19, 19)
                    for (key in pattern) {
                        val before = selected
                        i.runOnMainSync {
                            dispatch = SystemClock.elapsedRealtimeNanos(); focus = 0L; draw = 0L; highlight = 0L
                            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                        }
                        await("Key $key reaches another media") { selected != before }
                        Thread.sleep(260)
                        assertTrue("Focused media records a positive-opacity drawing pass: $selected", highlight != 0L)
                        i.runOnMainSync { latency.put(JSONObject().put("key", key).put("selected", selected)
                            .put("focusMs", if (focus == 0L) JSONObject.NULL else (focus - dispatch) / 1e6)
                            .put("firstHighlightDrawMs", (highlight - dispatch) / 1e6)
                            .put("firstDrawMs", if (draw == 0L) JSONObject.NULL else (draw - dispatch) / 1e6)); dispatch = 0L }
                    }
                    sampleStart = Long.MAX_VALUE
                    assertEquals("Matched traversal returns to first media", "row0:item0", selected)
                    Thread.sleep(200)
                    report.put(JSONObject().put("block", block).put("trial", trial).put("native", native).put("header", withHeader).put("artwork", localArtwork).put("phase", if (trial == 0) "first-exposure" else "warm")
                        .put("frames", stats(totals.toList())).put("ui", stats(ui.toList())).put("input", latency))
                }
            }
            i.sendStatus(2, Bundle().apply { putString("stream", "\nCATALOG_RENDERER_RESULT=$report\n") })
        } finally {
            i.runOnMainSync {
                host.window.removeOnFrameMetricsAvailableListener(listener)
                host.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(observer)
                host.window.decorView.viewTreeObserver.removeOnDrawListener(onDraw)
                host.finish()
            }
            thread.quitSafely(); thread.join(1000)
        }
    }
    private fun stats(values: List<Long>): JSONObject {
        val s = values.sorted()
        fun p(q: Double): Any = if (s.isEmpty()) JSONObject.NULL else s[((s.size - 1) * q).toInt()] / 1e6
        return JSONObject().put("count", s.size).put("p95Ms", p(.95)).put("p99Ms", p(.99)).put("maxMs", p(1.0))
            .put("over50Ms", s.count { it > 50_000_000L })
    }
}

private fun PlaybackFixtureHostActivity.lifecycleScopeLaunch(block: suspend () -> Unit) {
    lifecycleScope.launch { block() }
}
