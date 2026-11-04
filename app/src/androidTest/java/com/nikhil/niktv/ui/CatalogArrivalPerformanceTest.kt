package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in local metadata arrival fixture. Never a provider/startup/network/image-readiness benchmark. */
class CatalogArrivalPerformanceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private data class Entry(val id: String, val title: String, val subtitle: String)
    private data class Row(val id: String, val title: String, val items: List<Entry>)
    private val selected = AtomicReference("")
    private val submitted = AtomicLong()
    private val focused = AtomicLong()
    private val highlighted = AtomicLong()
    private val decorAfterFocus = AtomicLong()
    private var phoneLayout = false
    private var anchor: Button? = null
    private var poster = ""
    private var nativeMode = false
    private fun select(id: String) {
        selected.set(id)
        if (id == "row0:item0" && submitted.get() != 0L) focused.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
    }
    private fun highlight(id: String) {
        if (id == "row0:item0" && selected.get() == id && focused.get() != 0L && submitted.get() != 0L)
            highlighted.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(10) }
        fail("$message; selected=${selected.get()} submitted=${submitted.get()} focus=${focused.get()} draw=${highlighted.get()}")
    }
    private fun payload(): String = JSONArray().apply { repeat(14) { row -> put(JSONObject()
        .put("id", "row$row").put("title", "Selected category $row")
        .put("items", JSONArray().apply { repeat(30) { item -> put(JSONObject().put("id", "item$item")
            .put("title", "A long fixture title with enough words to exercise the contained caption $row / $item")
            .put("subtitle", "2025 · IPTV · selected category $row / $item")) } })) } }.toString()
    /** Instrumentation worker thread only: deterministic synthetic cache decoding, not provider parsing. */
    private fun hydrate(payload: String): List<Row> = JSONArray(payload).let { rows -> (0 until rows.length()).map { n ->
        val row = rows.getJSONObject(n); val entries = row.getJSONArray("items")
        Row(row.getString("id"), row.getString("title"), (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index); Entry(item.getString("id"), item.getString("title"), item.getString("subtitle"))
        })
    } }
    @Composable private fun Legacy(rows: List<Row>) {
        val scope = rememberCoroutineScope(); val grid = rememberLazyListState()
        val coordinator = remember { DiscoveryScrollCoordinator(scope) }
        val requesters = remember { mutableMapOf<String, FocusRequester>() }
        val entries = remember { mutableMapOf<Int, suspend () -> Boolean>() }
        val config = LocalConfiguration.current
        val phone = usesDiscoveryPhoneLayout(config.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(config))
        val width = if (phone) phoneDiscoveryPosterWidth(config.screenWidthDp).dp else ((config.screenWidthDp - 120) / 7f).dp
        DisposableEffect(Unit) { onDispose { coordinator.cancel() } }
        LaunchedEffect(rows) { if (rows.isNotEmpty()) repeat(120) {
            grid.scrollToItem(0)
            if (entries[0]?.invoke() == true) return@LaunchedEffect
            withFrameNanos { }
        } }
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF090B10)), state = grid) {
            items(14, key = { it }) { index ->
                val model = remember(rows, index) { Rail("row$index", "Selected category $index", rows.getOrNull(index)?.items.orEmpty().map { item ->
                    RailEntry("row$index:${item.id}", item.title, poster, subtitle = item.subtitle, progress = .4f,
                        source = DiscoveryTileSource.IPTV, open = {})
                }) }
                DiscoveryRail(model, width, false, requesters, index, phone,
                    popupChanged = {}, prefetch = true, trackPresentation = false, presentationEnabled = false, skeleton = true,
                    register = { entries[index] = it }, unregister = { entries.remove(index) }, focused = { select(it.key) }, present = {},
                    move = { direction -> val next = index + direction
                        if (next in 0..13) coordinator.move(grid, next, false) { entries[next]?.invoke() ?: false }
                        true }, reenter = {}, viewAll = null, highlightedDraw = ::highlight)
            }
        }
    }
    @Composable private fun Content(host: PlaybackFixtureHostActivity, rows: List<Row>, native: Boolean, fontScale: Float) {
        val original = LocalContext.current
        val configuration = LocalConfiguration.current
        val config = remember(configuration, fontScale) { Configuration(configuration).apply { this.fontScale = fontScale } }
        val context = remember(original, config) { original.createConfigurationContext(config) }
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, LocalDensity provides Density(density, fontScale)) {
            MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
                AndroidView(factory = { Button(it).apply { text = "Catalogue fixture focus anchor"; anchor = this } }, modifier = Modifier.fillMaxWidth().height(48.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!native) Legacy(rows) else {
                        val entry = remember { FocusRequester() }
                        val models = remember(rows) { (0 until 14).map { index -> NativeDiscoveryRow("row$index", "Selected category $index",
                            rows.getOrNull(index)?.items.orEmpty().map { item -> NativeDiscoveryItem(item.id, item.title, item.subtitle,
                                artwork = poster, onClick = {}, progress = .4f, source = DiscoveryTileSource.IPTV) }, loading = rows.isEmpty()) } }
                        RecycledDiscoveryRows(models, Modifier.fillMaxSize(), entry, {}, containedCaptions = true,
                            cardShadows = true, homeTileProportions = false, showSkeletons = true,
                            restoreTouchSelection = true, reconcileSkeletons = true,
                            cacheTileDrawing = context.isTvLikeDevice(config), animateFocus = !context.isTvLikeDevice(config))
                        LaunchedEffect(rows) { if (rows.isNotEmpty()) repeat(120) {
                            val card = host.window.decorView.findViewWithTag<View>("row0:item0")
                            if (card?.requestFocus() == true) return@LaunchedEffect
                            runCatching { entry.requestFocus() }
                            if (selected.get() == "row0:item0") return@LaunchedEffect
                            withFrameNanos { }
                        } }
                    }
                }
            } }
        }
    }
    private fun openHost(): PlaybackFixtureHostActivity {
        check(i.targetContext.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(i.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        await("Fixture window owns input") { host.hasWindowFocus() }
        i.setInTouchMode(false); i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        i.runOnMainSync { host.window.decorView.isSoundEffectsEnabled = false }
        phoneLayout = usesDiscoveryPhoneLayout(host.resources.configuration.smallestScreenWidthDp,
            host.isTvLikeDevice(host.resources.configuration))
        poster = catalogFixturePoster(i.targetContext)
        return host
    }
    private fun mount(host: PlaybackFixtureHostActivity, key: String, native: Boolean, data: MutableState<List<Row>>, font: Float = 1f) {
        nativeMode = native; anchor = null; selected.set(""); submitted.set(0); focused.set(0); highlighted.set(0); decorAfterFocus.set(0)
        i.runOnMainSync { host.setContent { key(key) { Content(host, data.value, native, font) } } }
        i.waitForIdleSync(); await("Common focus anchor attached") { anchor != null }
    }
    private fun observers(host: PlaybackFixtureHostActivity): Pair<ViewTreeObserver.OnGlobalFocusChangeListener, ViewTreeObserver.OnDrawListener> {
        val focus = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
            (next?.tag as? String)?.takeIf { it.startsWith("row") }?.let(::select)
        }
        val draw = ViewTreeObserver.OnDrawListener {
            if (submitted.get() != 0L && focused.get() != 0L && selected.get() == "row0:item0")
                decorAfterFocus.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
            if (nativeMode && submitted.get() != 0L) host.currentFocus?.let { card ->
            val bounds = Rect()
            if (card.tag == "row0:item0" && card.isFocused && card.getGlobalVisibleRect(bounds) && bounds.width() >= card.width - 2 && bounds.height() >= card.height - 2)
                highlight("row0:item0")
        } }
        i.runOnMainSync { host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focus); host.window.decorView.viewTreeObserver.addOnDrawListener(draw) }
        return focus to draw
    }
    @Test fun compareSkeletonToPopulatedMetadataArrivalABBA() {
        assumeTrue("Opt-in arrival measurement", InstrumentationRegistry.getArguments().getString("arrivalPerformance") == "true")
        val host = openHost(); val report = JSONArray(); val payload = payload()
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val ui = Collections.synchronizedList(mutableListOf<Long>())
        val misses = Collections.synchronizedList(mutableListOf<Boolean>())
        val start = AtomicLong(Long.MAX_VALUE); val stop = AtomicLong(Long.MIN_VALUE)
        val thread = HandlerThread("catalog-arrival-frames").apply { start() }
        val period = (1e9 / host.windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (intended >= start.get() && intended < stop.get()) {
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION); frames.add(duration)
                ui.add(intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                    FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).sumOf { metrics.getMetric(it) })
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: period else period
                misses.add(duration > deadline)
            }
        }
        val (focusObserver, drawObserver) = observers(host)
        try {
            i.runOnMainSync { host.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            for ((block, native) in listOf(false, true, true, false).withIndex()) {
                // Actual bitmap verification happens before ALL arrival timing for this block.
                val proof = mutableStateOf(hydrate(payload))
                mount(host, "proof-$block", native, proof)
                await("Proof fixture first media owns focus") { selected.get() == "row0:item0" }
                verifyCatalogFixtureArtwork(host, "arrival-artwork-proof-$block")
                repeat(4) { trial ->
                    val data = mutableStateOf(emptyList<Row>())
                    mount(host, "arrival-$block-$trial", native, data)
                    i.runOnMainSync { assertTrue(anchor!!.requestFocus()); selected.set("") }
                    Thread.sleep(150) // Empty/skeleton composition is settled and outside the arrival window.
                    val hydrationStart = SystemClock.elapsedRealtimeNanos(); val ready = hydrate(payload)
                    val hydrationEnd = SystemClock.elapsedRealtimeNanos()
                    frames.clear(); ui.clear(); misses.clear()
                    i.runOnMainSync {
                        submitted.set(SystemClock.elapsedRealtimeNanos()); focused.set(0); highlighted.set(0); decorAfterFocus.set(0)
                        start.set(System.nanoTime()); stop.set(start.get() + 1_000_000_000L)
                        data.value = ready
                    }
                    await("Arrival produces a focused visible drawing pass") { selected.get() == "row0:item0" && decorAfterFocus.get() != 0L &&
                        ((!native && phoneLayout) || highlighted.get() != 0L) }
                    Thread.sleep(1_150)
                    start.set(Long.MAX_VALUE)
                    val frameValues = synchronized(frames) { frames.toList() }; val uiValues = synchronized(ui) { ui.toList() }
                    report.put(JSONObject().put("block", block).put("trial", trial).put("native", native)
                        .put("phase", if (trial == 0) "first-arrival" else "warm-arrival").put("rows", 14).put("itemsPerRow", 30)
                        .put("artworkVerifiedBeforeTiming", true).put("fontScale", 1.0)
                        .put("syntheticHydrationMs", (hydrationEnd - hydrationStart) / 1e6)
                        .put("workerReadyToSubmissionMs", (submitted.get() - hydrationEnd) / 1e6)
                        .put("submissionToFocusMs", (focused.get() - submitted.get()) / 1e6)
                        .put("submissionToFirstFocusReadyDecorDrawMs", (decorAfterFocus.get() - submitted.get()) / 1e6)
                        .put("hydrationToFirstFocusReadyDecorDrawMs", (decorAfterFocus.get() - hydrationStart) / 1e6)
                        .put("firstHighlightProbeAvailable", native || !phoneLayout)
                        .put("submissionToFirstHighlightedDrawMs", if (highlighted.get() == 0L) JSONObject.NULL else (highlighted.get() - submitted.get()) / 1e6)
                        .put("hydrationToFirstHighlightedDrawMs", if (highlighted.get() == 0L) JSONObject.NULL else (highlighted.get() - hydrationStart) / 1e6)
                        .put("frames", stats(frameValues)).put("ui", stats(uiValues))
                        .put("deadlineMisses", synchronized(misses) { misses.count { it } }).put("displayPeriodMs", period / 1e6))
                }
            }
            i.sendStatus(2, Bundle().apply { putString("stream", "\nCATALOG_ARRIVAL_RESULT=$report\n") })
        } finally {
            i.runOnMainSync {
                host.window.removeOnFrameMetricsAvailableListener(listener)
                host.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
                host.window.decorView.viewTreeObserver.removeOnDrawListener(drawObserver); host.finish()
            }
            thread.quitSafely(); thread.join(1000)
        }
    }
    @Test fun largerFontLongTitlesRemainFocusableInScopedContext() {
        assumeTrue("Opt-in font functional fixture", InstrumentationRegistry.getArguments().getString("arrivalFontCheck") == "true")
        val host = openHost(); val report = JSONArray(); val (focusObserver, drawObserver) = observers(host)
        try {
            for (native in listOf(false, true)) {
                val data = mutableStateOf(hydrate(payload()))
                mount(host, "font-$native", native, data, 1.3f)
                await("Long title remains focusable at fontScale1.3") { selected.get() == "row0:item0" }
                verifyCatalogFixtureArtwork(host, "arrival-font-1.3-$native")
                if (native) i.runOnMainSync {
                    val card = requireNotNull(host.currentFocus)
                    assertEquals(1.3f, card.resources.configuration.fontScale, .01f)
                    val bounds = Rect(); assertTrue(card.getGlobalVisibleRect(bounds))
                    fun checkChildren(view: View) {
                        if (view is TextView && view.visibility == View.VISIBLE) {
                            val child = Rect(); assertTrue(view.getGlobalVisibleRect(child)); assertTrue("Native caption/source stays inside tile", bounds.contains(child))
                        }
                        if (view is ViewGroup) for (index in 0 until view.childCount) checkChildren(view.getChildAt(index))
                    }
                    checkChildren(card)
                }
                report.put(JSONObject().put("native", native).put("fontScale", 1.3).put("selectedMedia", selected.get())
                    .put("nativeCaptionBoundsChecked", native).put("legacyCaptionContainmentMeasured", false))
            }
            i.sendStatus(2, Bundle().apply { putString("stream", "\nCATALOG_FONT_FUNCTIONAL_RESULT=$report\n") })
        } finally { i.runOnMainSync {
            host.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
            host.window.decorView.viewTreeObserver.removeOnDrawListener(drawObserver); host.finish()
        } }
    }
    private fun stats(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        fun percentile(q: Double): Any = if (sorted.isEmpty()) JSONObject.NULL else sorted[((sorted.size - 1) * q).toInt()] / 1e6
        return JSONObject().put("count", sorted.size).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
            .put("maxMs", percentile(1.0)).put("over50Ms", sorted.count { it > 50_000_000L })
    }
}
