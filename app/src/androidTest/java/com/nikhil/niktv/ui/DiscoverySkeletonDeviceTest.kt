package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Local fixture package only: no provider, public network, playback or personal data. */
class DiscoverySkeletonDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private var activeHost: PlaybackFixtureHostActivity? = null
    private fun host(): PlaybackFixtureHostActivity {
        check(i.targetContext.packageName.endsWith(".regression"))
        return (i.startActivitySync(Intent(i.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity).also {
            activeHost = it
            await("Fixture window owns input before requesting media focus") { it.hasWindowFocus() }
            i.setInTouchMode(false)
            // On Fire OS the preceding touch-restoration test can leave the
            // window in touch mode despite setInTouchMode(false). Enter through
            // an actual remote event, as production does; local dispatch alone
            // does not change WindowManager's input mode.
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Physical D-pad entry leaves touch mode") { !it.window.decorView.isInTouchMode }
            i.runOnMainSync { it.window.decorView.isSoundEffectsEnabled = false }
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        var diagnostic = ""
        i.runOnMainSync { activeHost?.let { host ->
            diagnostic = "window=${host.hasWindowFocus()}, touch=${host.window.decorView.isInTouchMode}, focus=${host.currentFocus?.javaClass?.simpleName}:${host.currentFocus?.tag}, tags=${views(host.window.decorView).mapNotNull { it.tag as? String }.take(20)}"
        } }
        fail("$message; $diagnostic")
    }
    private fun tag(host: PlaybackFixtureHostActivity): String? {
        var value: String? = null; i.runOnMainSync { value = host.currentFocus?.tag as? String }; return value
    }
    private fun views(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (n in 0 until view.childCount) addAll(views(view.getChildAt(n)))
    }
    private fun key(host: PlaybackFixtureHostActivity, code: Int) = i.runOnMainSync {
        host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    @Test fun passiveProgressRetainsFractionDirectionAndReservedSpace() {
        i.runOnMainSync {
            // The app manifest currently disables RTL. Opt this local drawing
            // fixture in so the mirrored strip is exercised as well.
            val context = object : android.content.ContextWrapper(i.targetContext) {
                override fun getApplicationInfo() = android.content.pm.ApplicationInfo(super.getApplicationInfo()).apply {
                    flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
                }
            }
            val strip = NativeDiscoveryProgress(context)
            strip.layout(0, 0, 100, 3)
            val bitmap = Bitmap.createBitmap(100, 3, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            try {
                assertEquals(View.INVISIBLE, strip.visibility)
                assertFalse(strip.isFocusable)
                strip.fraction = .25f
                strip.draw(canvas)
                assertEquals(View.VISIBLE, strip.visibility)
                assertEquals(Color.rgb(229, 9, 37), bitmap.getPixel(24, 1))
                assertEquals(0x4DFFFFFF, bitmap.getPixel(25, 1))
                val accessibility = strip.createAccessibilityNodeInfo()
                assertEquals("android.widget.ProgressBar", accessibility.className)
                assertEquals(.25f, accessibility.rangeInfo.current, 0f)
                accessibility.recycle()
                bitmap.eraseColor(Color.TRANSPARENT)
                strip.layoutDirection = View.LAYOUT_DIRECTION_RTL
                assertEquals(View.LAYOUT_DIRECTION_RTL, strip.layoutDirection)
                strip.draw(canvas)
                assertEquals(0x4DFFFFFF, bitmap.getPixel(74, 1))
                assertEquals(Color.rgb(229, 9, 37), bitmap.getPixel(75, 1))
                strip.fraction = 2f; assertEquals(1f, strip.fraction)
                strip.fraction = -1f; assertEquals(0f, strip.fraction)
                strip.fraction = Float.NaN; assertEquals(View.INVISIBLE, strip.visibility)
                strip.fraction = .5f; strip.fraction = null
                assertEquals(View.INVISIBLE, strip.visibility)
                assertEquals(3, strip.height)
            } finally { bitmap.recycle() }
        }
    }
    @Test fun loadingRowsAreNonInteractiveAndResolveWithoutFocusOrHeightJump() {
        val host = host(); val loaded = mutableStateOf(false); val failed = mutableStateOf(false)
        val entry = FocusRequester(); val clicks = AtomicInteger()
        fun item(id: String) = NativeDiscoveryItem(id, "Title $id", onClick = { clicks.incrementAndGet() })
        try {
            i.runOnMainSync { host.setContent { MaterialTheme {
                RecycledDiscoveryRows(listOf(
                    NativeDiscoveryRow("cached", "Cached", listOf(item("one")), loading = true),
                    NativeDiscoveryRow("late", "Loading category", if (loaded.value) listOf(item("first"), item("second")) else emptyList(), loading = !loaded.value),
                    NativeDiscoveryRow("failure", "Failed category", emptyList(), loading = !failed.value)
                ), Modifier.fillMaxSize(), entry, {}, containedCaptions = true, showSkeletons = true)
            } } }
            i.waitForIdleSync(); i.runOnMainSync { entry.requestFocus() }
            await("Cached first media owns focus") { tag(host) == "cached:one" }
            key(host, KeyEvent.KEYCODE_DPAD_DOWN)
            await("Loading category retains its stable row focus anchor") { tag(host) == "late:loading" }
            var before = 0
            i.runOnMainSync {
                val all = views(host.window.decorView)
                assertEquals(View.GONE, all.first { it.tag == "cached:skeleton" }.visibility)
                val skeleton = all.first { it.tag == "late:skeleton" }
                assertTrue(skeleton.isShown); assertFalse(skeleton.isFocusable); assertFalse(skeleton.isClickable)
                val rect = Rect(); assertTrue(skeleton.getGlobalVisibleRect(rect))
                assertEquals("Whole loading area stays visible", skeleton.height, rect.height())
                before = (skeleton.parent as View).height
            }
            capture(host)
            key(host, KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(0, clicks.get())
            val handoff = mutableListOf<String>()
            val observer = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, next -> (next?.tag as? String)?.let(handoff::add) }
            i.runOnMainSync { host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(observer); loaded.value = true; failed.value = true }
            await("Arrival enters first real media") { tag(host) == "late:first" }
            i.runOnMainSync {
                host.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(observer)
                val all = views(host.window.decorView); val skeleton = all.first { it.tag == "late:skeleton" }
                assertEquals(View.GONE, skeleton.visibility); assertEquals(before, (skeleton.parent as View).height)
                assertEquals(View.GONE, all.first { it.tag == "failure:skeleton" }.visibility)
                assertFalse(handoff.any { it.endsWith(":skeleton") || it.endsWith(":all") || it.endsWith(":action") })
                assertTrue("Neighboring loading titles must not steal focus: $handoff", handoff.all { it.startsWith("late:") })
            }
            key(host, KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(1, clicks.get())
        } finally { i.runOnMainSync { host.finish() } }
    }
    @Test fun artworkPlaceholderClearsOnFailureAndOldImageCannotReplaceNewSelection() {
        val host = host(); val entry = FocusRequester(); val gate = CompletableDeferred<Unit>()
        val directory = File(i.targetContext.cacheDir, "skeleton-images").apply { mkdirs() }
        fun image(name: String, color: Int): String {
            val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val file = File(directory, name); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            return file.toURI().toString()
        }
        val slow = image("slow.png", Color.RED); val next = image("next.png", Color.BLUE)
        val missing = File(directory, "missing.png").toURI().toString()
        val request = mutableStateOf("slow" to slow)
        val original = SingletonImageLoader.get(i.targetContext)
        val requests = AtomicInteger()
        val loader = original.newBuilder().components { add(Interceptor { chain ->
            requests.incrementAndGet(); if (chain.request.data.toString() == slow) gate.await(); chain.proceed()
        }) }.build()
        SingletonImageLoader.setUnsafe(loader)
        fun imageState(): Pair<Boolean, Boolean> {
            var state = false to false
            i.runOnMainSync { views(host.window.decorView).filterIsInstance<ImageView>().singleOrNull()?.let { state = (it.background != null) to (it.drawable != null) } }
            return state
        }
        try {
            i.runOnMainSync { host.setContent { MaterialTheme {
                RecycledDiscoveryRows(listOf(NativeDiscoveryRow("image", "Artwork", listOf(NativeDiscoveryItem(request.value.first,
                    "Image title", artwork = request.value.second, onClick = {})))), Modifier.fillMaxSize(), entry, {}, containedCaptions = true, showSkeletons = true)
            } } }
            await("Existing image request starts with placeholder") { requests.get() > 0 && imageState().first }
            i.runOnMainSync { request.value = "next" to next }
            await("Replacement image clears its placeholder") { imageState() == (false to true) }
            gate.complete(Unit); Thread.sleep(100)
            i.runOnMainSync {
                val drawable = views(host.window.decorView).filterIsInstance<ImageView>().single().drawable
                assertTrue(drawable is android.graphics.drawable.BitmapDrawable)
                val pixels = (drawable as android.graphics.drawable.BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, false)
                assertEquals("Canceled red image must not replace blue", Color.BLUE, pixels.getPixel(0, 0)); pixels.recycle()
                request.value = "missing" to missing
            }
            await("Failed artwork clears placeholder and retains text fallback") { requests.get() >= 3 && imageState() == (false to false) }
        } finally {
            gate.complete(Unit); i.runOnMainSync { host.finish() }
            SingletonImageLoader.setUnsafe(original); loader.shutdown()
        }
    }
    @Test fun measureStaticSkeletonLoadingCost() {
        assumeTrue("Opt-in deterministic performance run", InstrumentationRegistry.getArguments().getString("measure") == "true")
        val args = InstrumentationRegistry.getArguments()
        val sampleMethods = args.getString("sampleMethods") == "true"
        val detailFrames = args.getString("detailFrames") == "true"
        val rounds = args.getString("bulkRounds")?.toIntOrNull()?.coerceIn(1, 3) ?: 3
        val host = host(); val entry = FocusRequester()
        val tvCache = args.getString("cacheTiles")?.toBooleanStrictOrNull()
            ?: host.packageManager.hasSystemFeature("android.software.leanback")
        val methodTrace = File(i.targetContext.cacheDir, "bulk-arrival-sampled.trace")
        var methodSampling = false
        val mode = mutableStateOf(false); val generation = mutableIntStateOf(0); val ready = mutableStateOf(false)
        val laidOut = AtomicInteger(-1)
        val dataset = (0..24).map { row -> NativeDiscoveryRow("r$row", "Category $row", (0..29).map { NativeDiscoveryItem("i$it", "Title $row $it", onClick = {}) }) }
        val thread = HandlerThread("skeleton-frame-metrics").apply { start() }
        val sampling = AtomicBoolean(false); val start = AtomicLong(Long.MAX_VALUE); val drops = AtomicInteger()
        val totals = Collections.synchronizedList(mutableListOf<Long>()); val ui = Collections.synchronizedList(mutableListOf<Long>())
        val detailed = Collections.synchronizedList(mutableListOf<JSONObject>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, missed -> if (sampling.get() && metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= start.get()) {
            metrics.getMetric(FrameMetrics.TOTAL_DURATION).takeIf { it > 0 }?.let(totals::add)
            val phases = intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).map(metrics::getMetric)
            if (phases.all { it >= 0 }) ui.add(phases.sum()); drops.addAndGet(missed)
            if (detailFrames && detailed.size < 2000) detailed.add(JSONObject()
                .put("atMs", (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) - start.get()) / 1_000_000.0)
                .put("totalMs", metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
                .put("inputMs", phases[0] / 1_000_000.0).put("animationMs", phases[1] / 1_000_000.0)
                .put("layoutMs", phases[2] / 1_000_000.0).put("drawMs", phases[3] / 1_000_000.0))
        } }
        val arrival = Runnable { ready.value = true }; val main = Handler(Looper.getMainLooper()); val result = JSONArray()
        try {
            i.runOnMainSync {
                host.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                host.setContent { MaterialTheme { androidx.compose.runtime.key(generation.intValue) {
                    val frameId = generation.intValue
                    val rows = remember(ready.value) { dataset.mapIndexed { index, row -> if (index in 1..8 && !ready.value) row.copy(items = emptyList(), loading = true) else row } }
                    RecycledDiscoveryRows(rows, Modifier.fillMaxSize().onGloballyPositioned { laidOut.set(frameId) }, entry, {}, containedCaptions = true, cardShadows = true,
                        animateFocus = true, cacheTileDrawing = tvCache, showSkeletons = mode.value)
                } } }
            }
            for ((phase, enabled) in listOf(false, true, false).withIndex()) repeat(rounds) { round ->
                var ticket = 0
                i.runOnMainSync { mode.value = enabled; ready.value = false; generation.intValue++; ticket = generation.intValue }
                await("New sample is laid out") { laidOut.get() == ticket && host.hasWindowFocus() }
                i.runOnMainSync { entry.requestFocus() }
                await("Each sample starts at first real title") { tag(host) == "r0:i0" }
                Thread.sleep(350)
                totals.clear(); ui.clear(); drops.set(0); start.set(System.nanoTime()); sampling.set(true)
                Thread.sleep(400); sampling.set(false)
                val idleFrames = totals.size
                totals.clear(); ui.clear(); detailed.clear(); drops.set(0)
                if (sampleMethods && phase == 0 && round == 0) {
                    Debug.startMethodTracingSampling(methodTrace.absolutePath, 8 * 1024 * 1024, 1000)
                    methodSampling = true
                }
                start.set(System.nanoTime()); sampling.set(true)
                i.runOnMainSync { main.postDelayed(arrival, 1200) }
                val begin = SystemClock.uptimeMillis()
                (List(8) { KeyEvent.KEYCODE_DPAD_DOWN } + List(8) { KeyEvent.KEYCODE_DPAD_UP }).forEachIndexed { index, code ->
                    key(host, code); Thread.sleep((begin + (index + 1) * 160L - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                Thread.sleep(500); sampling.set(false); i.runOnMainSync { main.removeCallbacks(arrival) }
                if (methodSampling) { Debug.stopMethodTracing(); methodSampling = false }
                await("Sample returns to original media") { tag(host) == "r0:i0" }
                val total = synchronized(totals) { totals.toList() }; val work = synchronized(ui) { ui.toList() }
                assertTrue("Physical frame callbacks required", total.isNotEmpty())
                result.put(JSONObject().put("phase", phase).put("round", round).put("skeletons", enabled)
                    .put("frame", statistics(total)).put("uiWork", statistics(work)).put("idleFrames400ms", idleFrames)
                    .put("pssKbAfter", Debug.getPss()).put("droppedCallbacks", drops.get())
                    .put("details", if (detailFrames) JSONArray(synchronized(detailed) { detailed.toList() }) else JSONObject.NULL))
            }
            val report = JSONObject().put("device", Build.MODEL).put("providerNetwork", false).put("recording", false)
                .put("debugCode", com.nikhil.niktv.BuildConfig.DEBUG)
                .put("methodSampling", sampleMethods).put("detailedFrames", detailFrames).put("diagnosticOnly", sampleMethods || detailFrames)
                .put("rows", 25).put("mediaPerLoadedRow", 30).put("arrivalDelayMs", 1200).put("images", false).put("tvTileCache", tvCache)
                .put("input", "Local activity dispatch, 8 Down/8 Up, requested 160 ms").put("samples", result).put("pssKbAfter", Debug.getPss())
            File(i.targetContext.cacheDir, "skeleton-performance.json").writeText(report.toString(2))
            i.sendStatus(2, Bundle().apply { putString("stream", "\nSKELETON_PERFORMANCE ${report}\n") })
            if (sampleMethods) {
                check(methodTrace.isFile && methodTrace.length() in 1..(16L * 1024 * 1024))
                methodTrace.inputStream().buffered().use { input ->
                    val bytes = ByteArray(16 * 1024); var index = 0
                    while (true) {
                        val count = input.read(bytes); if (count < 0) break
                        val data = android.util.Base64.encodeToString(bytes, 0, count, android.util.Base64.NO_WRAP)
                        i.sendStatus(2, Bundle().apply { putString("stream", "\nBULK_TRACE_CHUNK index=${index++} data=$data\n") })
                    }
                }
                i.sendStatus(2, Bundle().apply { putString("stream", "\nBULK_TRACE_END bytes=${methodTrace.length()}\n") })
            }
        } finally {
            if (methodSampling) Debug.stopMethodTracing()
            sampling.set(false); i.runOnMainSync { main.removeCallbacks(arrival); host.window.removeOnFrameMetricsAvailableListener(listener); host.finish() }
            thread.quitSafely(); thread.join(1000)
        }
    }
    private fun statistics(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        fun percentile(q: Double): Any = if (sorted.isEmpty()) JSONObject.NULL else sorted[((sorted.size - 1) * q).toInt()] / 1_000_000.0
        return JSONObject().put("frames", values.size).put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99))
            .put("maxMs", if (sorted.isEmpty()) JSONObject.NULL else sorted.last() / 1_000_000.0).put("over50ms", values.count { it > 50_000_000L })
    }
    private fun capture(host: PlaybackFixtureHostActivity) {
        if (Build.VERSION.SDK_INT < 26) return
        val bitmap = Bitmap.createBitmap(host.window.decorView.width, host.window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = java.util.concurrent.CountDownLatch(1); var status = -1
        PixelCopy.request(host.window, bitmap, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("Local fixture capture completes", done.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, status)
        File(i.targetContext.getExternalFilesDir(null), "skeleton-loading.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
