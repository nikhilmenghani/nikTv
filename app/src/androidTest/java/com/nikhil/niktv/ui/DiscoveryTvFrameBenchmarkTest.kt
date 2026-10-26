package com.nikhil.niktv.ui

import android.app.Application
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Physical-tablet, local-data comparison only. This test deliberately uses only
 * screen/model APIs present in both baseline and candidate target APKs. It does
 * not establish Fire TV performance or issue provider/network/playback actions.
 */
class DiscoveryTvFrameBenchmarkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var inputAutomation: UiAutomation
    private var diagnosticHost: PlaybackFixtureHostActivity? = null
    private var diagnosticModel: DiscoveryViewModel? = null
    private data class Focus(val id: Int, val labels: List<String>)

    @Test fun localTvNavigationPhysicalWindowComparison() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        assumeTrue("FrameMetrics require API24+", Build.VERSION.SDK_INT >= 24)
        assumeTrue("This fixture is tablet-only", context.resources.configuration.smallestScreenWidthDp >= 600 &&
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION &&
            !context.packageManager.hasSystemFeature("android.software.leanback"))
        val arguments = InstrumentationRegistry.getArguments()
        val automationAccessibility = !arguments.getString("automationAccessibility").equals("false", ignoreCase = true)
        val automationFlags = if (automationAccessibility) 0 else UiAutomation.FLAG_DONT_USE_ACCESSIBILITY
        // The default getter would change these flags and silently re-enable the
        // accessibility work this paired control is intended to measure.
        inputAutomation = requireNotNull(instrumentation.getUiAutomation(automationFlags))
        val accessibilityManager = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val label = arguments.getString("runLabel") ?: arguments.getString("tvFrameBenchmarkLabel") ?: "unspecified"
        val sampleMethods = arguments.getString("sampleMethods") == "true"
        val traceNavigation = arguments.getString("traceNavigation") == "true"
        val navigationEvents = Collections.synchronizedList(mutableListOf<String>())
        val navigationTrace: ((String) -> Unit)? = if (traceNavigation) ({ message ->
            synchronized(navigationEvents) { if (navigationEvents.size < 500) navigationEvents.add(message) }
        }) else null
        val directory = File(context.cacheDir, "tv-frame-benchmark-v1").apply { mkdirs() }
        val methodTrace = File(directory, "current-candidate-sampled.trace")
        var tracingMethods = false
        fun stopMethodTrace() {
            if (tracingMethods) {
                try { Debug.stopMethodTracing() } finally { tracingMethods = false }
            }
        }
        val art = artwork(directory)
        val detailsCalls = AtomicInteger()
        val providerCalls = AtomicInteger()
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                (1..20).map { index ->
                    val pair = art[(index - 1) % art.size]
                    DiscoveryCard((if (section.series) 1000 else 0) + index, section.series,
                        title(section.series, index), "Generated local overview for title $index.", pair.first, pair.second,
                        "2020-01-01", 7.5, listOf("Fixture"))
                }
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
                detailsCalls.incrementAndGet(); delay(20)
                return DiscoveryDetails(card.copy(overview = "Local enriched overview ${card.id}"))
            }
            override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
                providerCalls.incrementAndGet(); delay(20)
                return DiscoveryDetails(DiscoveryCard(9000, false, item.title, date = "2020-01-01"))
            }
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        val profile = PortalProfile("Local TV frame benchmark v1", "https://frame-benchmark.invalid", "")
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        diagnosticHost = host
        val frameThread = HandlerThread("tv-frame-benchmark-metrics").apply { start() }
        val sampling = AtomicBoolean(false)
        val startsAt = AtomicLong(Long.MAX_VALUE)
        val total = Collections.synchronizedList(mutableListOf<Long>())
        val ui = Collections.synchronizedList(mutableListOf<Long>())
        val renderPhaseIds = linkedMapOf("unknownDelay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
            "sync" to FrameMetrics.SYNC_DURATION, "commandIssue" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swapBuffers" to FrameMetrics.SWAP_BUFFERS_DURATION)
        val renderPhases = renderPhaseIds.mapValues { Collections.synchronizedList(mutableListOf<Long>()) }
        val drops = AtomicInteger()
        val report = JSONObject().put("label", label).put("device", Build.MODEL)
            .put("automationAccessibility", automationAccessibility).put("automationFlags", automationFlags)
            .put("physicalWindow", true).put("forcedTvUiMode", true).put("providerNetwork", false)
            .put("recording", false).put("startupMeasured", false)
            .put("cacheReset", false).put("semanticLatencyIncludesInjectionAndObserver", true)
            .put("methodTracing", sampleMethods).put("navigationTracing", traceNavigation)
            .put("timingsDiagnosticOnly", sampleMethods || traceNavigation)
        val reports = JSONArray()
        var model: DiscoveryViewModel? = null
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, missedCallbacks ->
            if (sampling.get()) {
                val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                if (intended >= startsAt.get()) {
                    val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    if (duration > 0) total.add(duration)
                    val phases = intArrayOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                        FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).map(metrics::getMetric)
                    if (phases.all { it >= 0 }) ui.add(phases.sum())
                    renderPhaseIds.forEach { (name, id) ->
                        val value = metrics.getMetric(id)
                        if (value >= 0) renderPhases.getValue(name).add(value)
                    }
                    drops.addAndGet(missedCallbacks)
                }
            }
        }
        try {
            await("Fixture window must own focus") { host.hasWindowFocus() }
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                silence(host.window.decorView)
                host.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
                val vm = DiscoveryViewModel(context.applicationContext as Application, source)
                model = vm
                diagnosticModel = vm
                host.setContent {
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                    }
                    CompositionLocalProvider(LocalConfiguration provides configuration,
                        LocalDiscoveryNavigationTrace provides navigationTrace) {
                        MaterialTheme {
                            DiscoveryPreviewScreen(NikTvState(savedProfile = profile,
                                tmdbSectionsBySurface = mapOf(DashboardSurface.HOME to emptyList())),
                                DashboardSurface.HOME, {}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {},
                                { _, _ -> }, { _, _ -> }, vm)
                        }
                    }
                }
            }
            await("Both fake feeds and hero are ready before entry") {
                model?.state?.value?.let { state ->
                    state.profile == profile.cacheKey() && state.hero != null &&
                        state.feeds[TmdbHomeSection.TRENDING_MOVIES]?.isNotEmpty() == true &&
                        state.feeds[TmdbHomeSection.TRENDING_SERIES]?.isNotEmpty() == true
                } == true
            }
            awaitFrames(2)
            await("Initial Home tab focus") { focus(host)?.labels?.contains("Home") == true }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Hero Play") { focus(host)?.labels?.contains("Play") == true }
            await("First movie card is composed before entering its row") { composedCard(host, title(false, 1)) }
            awaitFrames(2) // Let the row's focus-handler registration commit outside sampling.
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First movie tile") { focus(host)?.labels?.contains(title(false, 1)) == true }
            instrumentation.runOnMainSync { silence(host.window.decorView) }
            Thread.sleep(600) // Entry readiness is outside all measured intervals.
            report.put("accessibilityManagerEnabledAtStart", accessibilityManager.isEnabled)
                .put("touchExplorationEnabledAtStart", accessibilityManager.isTouchExplorationEnabled)
            report.put("refreshRateHz", host.window.decorView.display.refreshRate.toDouble())
            report.put("widthPixels", host.window.decorView.width).put("heightPixels", host.window.decorView.height)
            val pattern = List(4) { KeyEvent.KEYCODE_DPAD_RIGHT } + List(4) { KeyEvent.KEYCODE_DPAD_LEFT } +
                List(4) { listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP) }.flatten()
            if (sampleMethods) {
                Debug.startMethodTracingSampling(methodTrace.absolutePath, 8 * 1024 * 1024, 1000)
                tracingMethods = true
                diagnostic("TV_FRAME_METHOD_TRACE_START file=${methodTrace.name} bufferBytes=${8 * 1024 * 1024} sampleIntervalMicros=1000 timingsDiagnosticOnly=true")
            }
            repeat(3) { round ->
                sampling.set(false)
                await("Round $round begins at first movie tile") { focus(host)?.labels?.contains(title(false, 1)) == true }
                Thread.sleep(500)
                total.clear(); ui.clear(); drops.set(0)
                renderPhases.values.forEach { it.clear() }
                startsAt.set(System.nanoTime())
                sampling.set(true)
                val begin = SystemClock.uptimeMillis()
                val issued = JSONArray()
                pattern.forEachIndexed { index, code ->
                    issued.put(SystemClock.uptimeMillis() - begin)
                    send(code)
                    SystemClock.sleep((begin + (index + 1) * 300L - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                Thread.sleep(650) // Include the final visual settling tail in each identical run.
                sampling.set(false)
                await("Round $round returns to original movie tile") { focus(host)?.labels?.contains(title(false, 1)) == true }
                val totals = synchronized(total) { total.toList() }
                val phases = synchronized(ui) { ui.toList() }
                assertTrue("A supported physical FrameMetrics stream is required", totals.isNotEmpty() && phases.isNotEmpty())
                val row = JSONObject().put("round", round).put("phase", if (round == 0) "first-navigation" else "warm-repeat")
                    .put("inputs", pattern.size).put("requestedCadenceMs", 300)
                    .put("inputIssueOffsetsMs", issued)
                    .put("total", statistics(totals)).put("uiPhases", statistics(phases))
                    .put("uiPhaseSumMsPerInput", phases.sumOf { it.toDouble() } / 1_000_000.0 / pattern.size)
                    .put("renderPhases", JSONObject().also { summaries ->
                        renderPhases.forEach { (name, samples) ->
                            summaries.put(name, statistics(synchronized(samples) { samples.toList() }))
                        }
                    })
                    .put("callbackDrops", drops.get()).put("focusCorrect", true)
                reports.put(row); diagnostic("TV_FRAME_ROUND $row")
            }
            stopMethodTrace()
            if (sampleMethods) {
                report.put("methodTraceFile", methodTrace.name).put("methodTraceBytes", methodTrace.length())
                diagnostic("TV_FRAME_METHOD_TRACE_END file=${methodTrace.name} bytes=${methodTrace.length()} timingsDiagnosticOnly=true")
            }
            // Semantic observation traverses the tree. Keep it OUTSIDE timed frame rounds.
            val latencies = mutableListOf<Long>()
            listOf(KeyEvent.KEYCODE_DPAD_RIGHT to title(false, 2), KeyEvent.KEYCODE_DPAD_RIGHT to title(false, 3),
                KeyEvent.KEYCODE_DPAD_LEFT to title(false, 2), KeyEvent.KEYCODE_DPAD_LEFT to title(false, 1),
                KeyEvent.KEYCODE_DPAD_DOWN to title(true, 1), KeyEvent.KEYCODE_DPAD_UP to title(false, 1)).forEach { (code, expected) ->
                latencies.add(semanticLatency(host, code))
                assertTrue("Semantic pass must reach $expected", focus(host)?.labels?.contains(expected) == true)
                Thread.sleep(350)
            }
            report.put("rounds", reports).put("semanticFocusSeparatePass", statistics(latencies))
                .put("semanticObservationIncludedInFrameRounds", false)
                .put("detailsCalls", detailsCalls.get()).put("providerDetailsCalls", providerCalls.get())
                .put("completed", true)
            File(directory, "result-${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json").writeText(report.toString(2))
            diagnostic("TV_FRAME_RESULT $report")
        } finally {
            stopMethodTrace()
            sampling.set(false)
            if (traceNavigation) {
                val events = synchronized(navigationEvents) { navigationEvents.toList() }
                File(directory, "navigation-events-${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt").writeText(events.joinToString("\n"))
            }
            instrumentation.runOnMainSync {
                host.window.removeOnFrameMetricsAvailableListener(listener)
                model?.viewModelScope?.cancel()
                host.finish()
            }
            frameThread.quitSafely(); frameThread.join(1000)
            diagnosticHost = null
            diagnosticModel = null
        }
    }

    private fun send(code: Int) {
        val down = SystemClock.uptimeMillis()
        assertTrue(inputAutomation.injectInputEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), false))
        Thread.sleep(24)
        assertTrue(inputAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), false))
    }
    private fun semanticLatency(host: PlaybackFixtureHostActivity, code: Int): Long {
        val before = focus(host)
        assertNotNull("A real focused node is required", before)
        val done = CountDownLatch(1)
        val result = AtomicLong(-1)
        val begin = SystemClock.elapsedRealtimeNanos()
        send(code)
        instrumentation.runOnMainSync {
            val observer = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val current = focusOnMain(host.window.decorView)
                    if (current != null && (current.id != before?.id || current.labels != before?.labels)) {
                        result.set(SystemClock.elapsedRealtimeNanos() - begin); done.countDown()
                    } else if (SystemClock.elapsedRealtimeNanos() - begin < 3_000_000_000L) {
                        Choreographer.getInstance().postFrameCallback(this)
                    } else done.countDown()
                }
            }
            observer.doFrame(0)
        }
        assertTrue("Semantic focus must change after a directional key", done.await(4, TimeUnit.SECONDS) && result.get() > 0)
        return result.get()
    }
    private fun focus(host: PlaybackFixtureHostActivity): Focus? {
        val value = AtomicReference<Focus?>()
        instrumentation.runOnMainSync { value.set(focusOnMain(host.window.decorView)) }
        return value.get()
    }
    private fun composedCard(host: PlaybackFixtureHostActivity, title: String): Boolean {
        val value = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            value.set(roots(host.window.decorView).any { root ->
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).any { node ->
                    node.config.getOrNull(SemanticsProperties.Focused) != null && title in labels(node) &&
                        node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0
                }
            })
        }
        return value.get()
    }
    private fun awaitFrames(count: Int) {
        val complete = CountDownLatch(1)
        val observer = object : Choreographer.FrameCallback {
            var remaining = count
            override fun doFrame(frameTimeNanos: Long) {
                if (--remaining <= 0) complete.countDown()
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(observer) }
        val ready = complete.await(4, TimeUnit.SECONDS)
        if (!ready) instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(observer) }
        assertTrue("Entry pre-roll requires a running physical frame clock", ready)
    }
    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }
    private fun labels(node: SemanticsNode): List<String> = buildList {
        addAll(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        node.children.forEach { addAll(labels(it)) }
    }.distinct()
    private fun focusOnMain(view: View): Focus? = roots(view).asSequence()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
        .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        ?.let { Focus(it.id, labels(it)) }
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(25) }
        val current = diagnosticHost?.let(::focus)
        val state = diagnosticModel?.state?.value
        val readiness = "profile=${state?.profile} surface=${state?.surface} hero=${state?.hero?.key} " +
            "feeds=${state?.feeds?.mapValues { it.value.size }} loading=${state?.loading} errors=${state?.errors?.keys} " +
            "movieCardComposed=${diagnosticHost?.let { composedCard(it, title(false, 1)) }}"
        inputAutomation.takeScreenshot()?.let { bitmap ->
            val directory = File(instrumentation.targetContext.cacheDir, "tv-frame-benchmark-v1").apply { mkdirs() }
            File(directory, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        diagnostic("TV_FRAME_FAILURE message=$message focused=$current $readiness")
        fail("$message; focused=$current $readiness")
    }
    private fun statistics(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        val sumMs = values.sumOf { it.toDouble() } / 1_000_000.0
        fun percentile(q: Double) = if (sorted.isEmpty()) JSONObject.NULL else
            sorted[((sorted.size - 1) * q).toInt()] / 1_000_000.0
        return JSONObject().put("count", values.size).put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95))
            .put("sumMs", sumMs).put("meanMs", if (values.isEmpty()) JSONObject.NULL else sumMs / values.size)
            .put("maxMs", if (sorted.isEmpty()) JSONObject.NULL else sorted.last() / 1_000_000.0)
            .put("over16Ms", values.count { it > 16_000_000L }).put("over33Ms", values.count { it > 33_000_000L })
            .put("over50Ms", values.count { it > 50_000_000L })
    }
    private fun diagnostic(message: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
    }
    private fun silence(view: View) {
        view.isSoundEffectsEnabled = false; view.isHapticFeedbackEnabled = false
        if (view is ViewGroup) for (index in 0 until view.childCount) silence(view.getChildAt(index))
    }
    private fun title(series: Boolean, index: Int) = "Frame ${if (series) "series" else "movie"} ${index.toString().padStart(2, '0')}"
    private fun artwork(directory: File): List<Pair<String, String>> = (0 until 8).map { index ->
        fun image(name: String, width: Int, height: Int): String {
            val file = File(directory, "$name-$index.jpg")
            if (!file.isFile) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val color = android.graphics.Color.HSVToColor(floatArrayOf(index * 43f, .7f, .8f))
                paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), color, android.graphics.Color.BLACK, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                repeat(32) { stripe ->
                    paint.color = android.graphics.Color.argb(50 + stripe * 5, 230, 220, 210)
                    canvas.drawCircle((stripe * 71 % width).toFloat(), (stripe * 113 % height).toFloat(), width / 9f, paint)
                }
                paint.color = android.graphics.Color.WHITE; paint.textSize = width / 8f
                canvas.drawText("Local $index", width / 12f, height / 2f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
            }
            return android.net.Uri.fromFile(file).toString()
        }
        image("poster", 342, 513) to image("backdrop", 1280, 720)
    }
}
