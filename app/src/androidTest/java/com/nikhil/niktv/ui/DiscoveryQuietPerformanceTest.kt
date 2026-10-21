package com.nikhil.niktv.ui

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.InputDevice
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.widget.FrameLayout
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.data.CatalogDiskCache
import android.app.Application
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Explicit opt-in quiet fixture: no provider, decoder, real profile, audio, shell
 * input, wake request or default-display fallback. Virtual-display measurements
 * cannot establish physical-panel presentation latency.
 *
 * Run this probe before the navigation benchmark; Fire OS may prohibit private
 * presentation windows or suspend them while asleep. Such a device is unsupported,
 * not a passed performance check.
 */
class DiscoveryQuietPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val quietKeyboardMode = object : InputModeManager {
        override val inputMode = InputMode.Keyboard
        // Test-only local mode: never calls View.requestFocusFromTouch/global touch mode.
        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }

    @Test fun privatePresentationRendersWithoutWakingTelevision() {
        requireOptIn()
        QuietDisplay().use { fixture ->
            fixture.open()
            val contentDraws = AtomicInteger()
            instrumentation.runOnMainSync {
                fixture.host!!.setContent {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101B2A)).drawWithContent {
                        drawContent()
                        contentDraws.incrementAndGet()
                    }) {
                        Text("Quiet secondary-display fixture", color = Color.White)
                    }
                }
                silence(fixture.host!!.fixtureWindow.decorView)
            }
            fixture.awaitRendering { contentDraws.get() > 0 }
            fixture.assertStillAsleep()
            diagnostic("QUIET_PROBE supported: private Presentation display=${fixture.displayId}, frames=${fixture.frames.get()}, no Activity/task launched; default display remained asleep. This is not a navigation performance result.")
        }
    }

    /** Fake metadata + generated artwork, but the real modern screen/input/scroll code. */
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun modernNavigationFrameMetricsWithoutWakingTelevision() {
        requireOptIn()
        QuietDisplay().use { fixture ->
            fixture.open()
            val artDirectory = File(instrumentation.targetContext.cacheDir, "quiet-discovery-fixture-${SystemClock.uptimeMillis()}")
            artDirectory.mkdirs()
            val arguments = InstrumentationRegistry.getArguments()
            val artworkVariants = arguments.getString("quietArtworkVariants")?.toIntOrNull()?.coerceIn(3, 48) ?: 8
            val artwork = generateArtwork(artDirectory, artworkVariants)
            val navigationRounds = arguments.getString("quietNavigationRounds")?.toIntOrNull()?.coerceIn(1, 3) ?: 3
            val correctnessRounds = arguments.getString("quietCorrectnessRounds")?.toIntOrNull()?.coerceIn(1, 3) ?: 3
            val sampleMethods = arguments.getString("quietSampleMethods") == "true"
            val metadataDelay = arguments.getString("quietMetadataDelayMs")?.toLongOrNull()?.coerceIn(0, 5_000) ?: 20L
            val imageDelay = arguments.getString("quietImageDelayMs")?.toLongOrNull()?.coerceIn(0, 5_000) ?: 0L
            val staleFirst = arguments.getString("quietStaleMetadata") == "true"
            val recordKnownIdentityFailure = arguments.getString("quietRecordKnownIdentityFailure") == "true"
            val traceNavigation = arguments.getString("quietTraceNavigation") == "true"
            // Baseline production need not contain the optional diagnostic hook.
            // Reflection is never attempted in ordinary measured runs.
            val traceLocal = if (traceNavigation) findNavigationTraceLocal() else null
            val navigationTrace = java.util.ArrayDeque<String>()
            val streamedTraceEvents = AtomicInteger()
            val traceCollector: ((String) -> Unit)? = if (traceNavigation) ({ message ->
                synchronized(navigationTrace) {
                    if (navigationTrace.size == 200) navigationTrace.removeFirst()
                    navigationTrace.addLast(message)
                }
                val index = streamedTraceEvents.incrementAndGet()
                if (index <= 500) diagnostic("QUIET_FOCUS_EVENT index=$index $message")
                else if (index == 501) diagnostic("QUIET_FOCUS_EVENT truncated_after=500")
            }) else null
            val source = LocalDiscoverySource(artwork, metadataDelay, staleFirst)
            val originalImageLoader = SingletonImageLoader.get(instrumentation.targetContext)
            val fixtureImageLoader = originalImageLoader.newBuilder().components {
                add(Interceptor { chain ->
                    val data = chain.request.data.toString()
                    if (imageDelay > 0) {
                        val path = if (data.startsWith("file:")) android.net.Uri.parse(data).path.orEmpty() else data
                        if (path.startsWith(artDirectory.absolutePath) && path.contains("backdrop-")) delay(imageDelay)
                    }
                    chain.proceed()
                })
            }.build()
            SingletonImageLoader.setUnsafe(fixtureImageLoader)
            val profile = PortalProfile("Quiet navigation fixture", "https://fixture.invalid", "")
            val largeWorkload = InstrumentationRegistry.getArguments().getString("quietLargeWorkload") == "true"
            val workload = LocalWorkload(profile, artwork, largeWorkload)
            workload.prime(instrumentation.targetContext)
            val selected = mutableStateOf(DashboardSurface.HOME)
            val navigationId = mutableLongStateOf(0L)
            val draws = AtomicInteger()
            lateinit var model: DiscoveryViewModel
            var modelCreated = false
            val frameSamples = Collections.synchronizedList(mutableListOf<Long>())
            val componentIds = linkedMapOf(
                "input_handling" to FrameMetrics.INPUT_HANDLING_DURATION,
                "animation" to FrameMetrics.ANIMATION_DURATION,
                "layout_measure" to FrameMetrics.LAYOUT_MEASURE_DURATION,
                "draw" to FrameMetrics.DRAW_DURATION,
                "unknown_delay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
                "sync" to FrameMetrics.SYNC_DURATION,
                "command_issue" to FrameMetrics.COMMAND_ISSUE_DURATION,
                "swap_buffers" to FrameMetrics.SWAP_BUFFERS_DURATION)
            val componentSamples = componentIds.mapValues { Collections.synchronizedList(mutableListOf<Long>()) }
            val uiPhaseSamples = Collections.synchronizedList(mutableListOf<Long>())
            val frameDrops = AtomicInteger()
            val sampleFrames = AtomicBoolean(false)
            val frameThread = HandlerThread("quiet-frame-metrics").apply { start() }
            val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                if (sampleFrames.get()) {
                    frameSamples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                    val components = componentIds.mapValues { (_, id) -> metrics.getMetric(id) }
                    components.forEach { (name, duration) -> if (duration >= 0) componentSamples.getValue(name).add(duration) }
                    val uiPhases = listOf("input_handling", "animation", "layout_measure", "draw").map { components.getValue(it) }
                    if (uiPhases.all { it >= 0 }) uiPhaseSamples.add(uiPhases.sum())
                    frameDrops.addAndGet(dropped)
                }
            }
            val latencies = mutableListOf<Pair<String, Long>>()
            val detailLatencies = mutableListOf<Pair<String, Long>>()
            val identityFailures = mutableListOf<String>()
            var navigationCompleted = false
            var correctnessCompleted = false
            var correctnessRoundsCompleted = 0
            val played = AtomicReference<DiscoveryCard?>()
            val memory = mutableListOf<Long>()
            val roundReports = mutableListOf<String>()
            val methodTraceFile = if (sampleMethods) File(instrumentation.targetContext.filesDir, "quiet-navigation-round0.trace") else null
            var methodSamplingStarted = false
            var methodSamplingActive = false
            var methodSamplingFailure: Throwable? = null
            var failureContext: String? = null
            var failureTrace: List<String> = emptyList()
            var reported = false
            fun stopMethodSampling() {
                if (!methodSamplingActive) return
                try {
                    android.os.Debug.stopMethodTracing()
                    methodSamplingActive = false
                } catch (failure: Throwable) {
                    methodSamplingFailure = failure
                    diagnostic("QUIET_METHOD_TRACE_ERROR stop=${failure.javaClass.simpleName}")
                }
            }
            fun report(completed: Boolean): Pair<List<Long>, List<Long>> {
                val frames = synchronized(frameSamples) { frameSamples.toList() }
                val uiPhases = synchronized(uiPhaseSamples) { uiPhaseSamples.toList() }
                val components = componentSamples.mapValues { (_, values) -> synchronized(values) { values.toList() } }
                val refresh = fixture.refreshRate.takeIf { it > 0 } ?: 60f
                val frameBudget = (1_000_000_000.0 / refresh).toLong()
                val focus = latencies.map { it.second }
                val report = buildString {
                    append("QUIET_NAVIGATION completed=$completed physical_panel=false surface_saveable_boundary=true virtual_readiness_view=true native_window_focus=${fixture.host!!.nativeWindowFocused} entry_settle_frames=4 resolution=1920x1080 density=${fixture.densityDpi} refresh=$refresh large_workload=$largeWorkload ")
                    append("metadata_delay_ms=$metadataDelay image_delay_ms=$imageDelay stale_first=$staleFirst artwork_variants=$artworkVariants ")
                    append("method_sampling_requested=$sampleMethods timings_diagnostic_only=${sampleMethods || traceNavigation} method_sampling_active=$methodSamplingActive ")
                    append("navigation_rounds_requested=$navigationRounds correctness_rounds_requested=$correctnessRounds correctness_rounds_completed=$correctnessRoundsCompleted ")
                    append("navigation_trace_enabled=$traceNavigation failure_present=${failureContext != null} ")
                    append("record_known_identity_failure=$recordKnownIdentityFailure correctness_completed=$correctnessCompleted ")
                    append("correctness_passed=${correctnessCompleted && identityFailures.isEmpty()} identity_failure_count=${identityFailures.size} ")
                    append("inputs=${focus.size} semantic_focus_p50_ms=${percentileMs(focus, .5)} semantic_focus_p95_ms=${percentileMs(focus, .95)} ")
                    append("focus_over_two_frames=${focus.count { it > frameBudget * 2 }} ")
                    append("frames=${frames.size} total_p95_ms=${percentileMs(frames, .95)} ")
                    append("total_over_refresh_budget=${frames.count { it > frameBudget }} total_over_50ms=${frames.count { it > 50_000_000 }} ")
                    append("ui_phase_frames=${uiPhases.size} ui_phase_p95_ms=${percentileMs(uiPhases, .95)} ui_phase_over50ms=${uiPhases.count { it > 50_000_000 }} ")
                    components.forEach { (name, durations) -> append("${name}_p95_ms=${percentileMs(durations, .95)} ") }
                    append("metrics_callback_drops=${frameDrops.get()} default_display_remained_asleep=true\n")
                    append("pss_kib=${memory.joinToString(",")}\n")
                    append("completed_round_reports=${roundReports.size}\n")
                    roundReports.forEach { append(it).append('\n') }
                    latencies.forEach { (label, ns) -> append("$label=${ns / 1_000_000.0}ms\n") }
                    append("DETAIL_ROUTE_TIMINGS virtual_readiness_only=true physical_window_latency_unmeasured=true excluded_from_navigation_frame_statistics=true\n")
                    detailLatencies.forEach { (label, ns) -> append("$label=${ns / 1_000_000.0}ms\n") }
                    identityFailures.forEach { append("IDENTITY_FAILURE $it\n") }
                    failureContext?.let { append("QUIET_FAILURE_CONTEXT $it\n") }
                    if (failureContext != null && failureTrace.isNotEmpty()) {
                        append("QUIET_FAILURE_TRACE entries=${failureTrace.size}\n")
                        failureTrace.forEach { append(it).append('\n') }
                    }
                }
                File(instrumentation.targetContext.filesDir, "quiet-discovery-metrics.txt").writeText(report)
                diagnostic(report)
                reported = true
                return frames to focus
            }
            fun reportRound(round: Int, focusStart: Int, frameStart: Int, uiStart: Int, memoryStart: Int,
                componentStarts: Map<String, Int>, dropsStart: Int) {
                check(!sampleFrames.get()) { "Round metrics must be taken outside timed frames." }
                // Finish any already-running callback before taking aligned slices.
                val drained = CountDownLatch(1)
                Handler(frameThread.looper).post { drained.countDown() }
                assertTrue("Frame metrics callback queue drains between rounds.", drained.await(2, TimeUnit.SECONDS))
                val focus = latencies.drop(focusStart).map { it.second }
                val frames = synchronized(frameSamples) { frameSamples.drop(frameStart) }
                val uiPhases = synchronized(uiPhaseSamples) { uiPhaseSamples.drop(uiStart) }
                val components = componentSamples.mapValues { (name, values) ->
                    synchronized(values) { values.drop(componentStarts.getValue(name)) }
                }
                val refresh = fixture.refreshRate.takeIf { it > 0 } ?: 60f
                val frameBudget = (1_000_000_000.0 / refresh).toLong()
                val summary = buildString {
                    append("QUIET_NAVIGATION_ROUND round=$round completed=true physical_panel=false surface_saveable_boundary=true virtual_readiness_view=true artwork_variants=$artworkVariants method_sampling_requested=$sampleMethods timings_diagnostic_only=${sampleMethods || traceNavigation} ")
                    append("inputs=${focus.size} semantic_focus_p95_ms=${percentileMs(focus, .95)} ")
                    append("focus_over50ms=${focus.count { it > 50_000_000 }} focus_over_two_frames=${focus.count { it > frameBudget * 2 }} ")
                    append("frames=${frames.size} total_p95_ms=${percentileMs(frames, .95)} total_over50ms=${frames.count { it > 50_000_000 }} ")
                    append("total_over_refresh_budget=${frames.count { it > frameBudget }} ")
                    append("ui_phase_frames=${uiPhases.size} ui_phase_p95_ms=${percentileMs(uiPhases, .95)} ui_phase_over50ms=${uiPhases.count { it > 50_000_000 }} ")
                    components.forEach { (name, durations) -> append("${name}_p95_ms=${percentileMs(durations, .95)} ") }
                    append("metrics_callback_drops=${frameDrops.get() - dropsStart} pss_kib=${memory.drop(memoryStart).joinToString(",")}")
                }
                roundReports += summary
                diagnostic(summary)
                fixture.assertStillAsleep()
            }
            fun verifyIdentity(label: String, correct: Boolean) {
                if (!correct) {
                    identityFailures += label
                    diagnostic("QUIET_IDENTITY_FAILURE $label; record_only=$recordKnownIdentityFailure")
                }
                if (!recordKnownIdentityFailure) assertTrue(label, correct)
            }
            fun phase(surface: DashboardSurface, round: Int, name: String) {
                if (traceNavigation) diagnostic("QUIET_FOCUS_PHASE surface=$surface round=$round phase=$name " +
                    "selected=${selected.value} model_surface=${if (modelCreated) model.state.value.surface else null} " +
                    "nav=${navigationId.longValue} detail=${if (modelCreated) model.state.value.detail?.key else null}")
            }
            try {
                instrumentation.runOnMainSync {
                    model = DiscoveryViewModel(instrumentation.targetContext.applicationContext as Application, source)
                    modelCreated = true
                    fixture.host!!.fixtureWindow.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper))
                    fixture.host!!.setContent {
                        WithOptionalNavigationTrace(traceLocal, traceCollector) {
                            CompositionLocalProvider(LocalInputModeManager provides quietKeyboardMode,
                                LocalProviderDiscoveryPageLoader provides { _, category, page, _ ->
                                // Only generated fixture records, even if production tries a refresh.
                                delay(30)
                                PortalCatalogPage(workload.items[category.id].orEmpty(), page, false)
                            }) { MaterialTheme {
                                Box(Modifier.fillMaxSize().drawWithContent { drawContent(); draws.incrementAndGet() }) {
                                    // Match CatalogScreen's profile/surface route boundary:
                                    // ordinary remember/effects dispose on surface change,
                                    // saveable positions restore, and the profile VM is shared.
                                    val browseStateHolder = rememberSaveableStateHolder()
                                    browseStateHolder.SaveableStateProvider("discovery2:${profile.cacheKey()}:${selected.value}") {
                                        DiscoveryPreviewScreen(
                                            workload.app.copy(discoveryNavigationId = navigationId.longValue), selected.value,
                                            { selected.value = it; navigationId.longValue++ }, {}, {}, { played.set(it) }, { card, _, _ -> played.set(card) }, {},
                                            { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, model)
                                    }
                                }
                            } }
                        }
                    }
                    silence(fixture.host!!.fixtureWindow.decorView)
                }
                fixture.awaitRendering { draws.get() > 0 }
                await(fixture, "Initial Home focus") { snapshot(fixture)?.hasLabel("Home 2") == true }
                fixture.capturePreview()?.let { diagnostic("QUIET_PREVIEW ${it.absolutePath}") }
                // Avoid judging first process/class/image initialization as warm navigation.
                Thread.sleep(500)
                val surfaces = listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)
                repeat(navigationRounds) { round ->
                    val focusStart = latencies.size
                    val frameStart = synchronized(frameSamples) { frameSamples.size }
                    val uiStart = synchronized(uiPhaseSamples) { uiPhaseSamples.size }
                    val memoryStart = memory.size
                    val componentStarts = componentSamples.mapValues { (_, values) -> synchronized(values) { values.size } }
                    val dropsStart = frameDrops.get()
                    if (sampleMethods && round == 0) {
                        val trace = checkNotNull(methodTraceFile)
                        check(!trace.exists() || trace.delete()) { "Could not replace generated method trace." }
                        android.os.Debug.startMethodTracingSampling(trace.absolutePath, 8 * 1024 * 1024, 1_000)
                        methodSamplingStarted = true
                        methodSamplingActive = true
                    }
                    for (surface in surfaces) {
                        phase(surface, round, "navigation-menu-before")
                        selectSurfaceThroughMenu(fixture, surface)
                        phase(surface, round, "navigation-menu-dispatched")
                        await(fixture, "${surface.name} tab focus") { snapshot(fixture)?.hasLabel(surface.trialTitle()) == true }
                        awaitLocalEntry(fixture)
                        phase(surface, round, "navigation-menu-ready")
                        sampleFrames.set(true)
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/hero", latencies)
                        // More than two rows ensures offscreen composition and row estimates are exercised.
                        repeat(5) { row ->
                            inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/down$row", latencies)
                            Thread.sleep(280)
                        }
                        repeat(3) { row ->
                            inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_UP, "${surface.name}/$round/up$row", latencies)
                            Thread.sleep(280)
                        }
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_RIGHT, "${surface.name}/$round/right", latencies)
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_LEFT, "${surface.name}/$round/left", latencies)
                        // Direct reversals while the preceding movement is still running.
                        repeat(2) { reversal ->
                            inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/reverse-down$reversal", latencies)
                            inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_UP, "${surface.name}/$round/reverse-up$reversal", latencies)
                        }
                        if (largeWorkload) {
                            repeat(10) { row ->
                                inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/provider-down$row", latencies)
                                Thread.sleep(260)
                            }
                            repeat(10) { row ->
                                inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_UP, "${surface.name}/$round/provider-up$row", latencies)
                                Thread.sleep(260)
                            }
                        }

                        Thread.sleep(400)
                        sampleFrames.set(false)
                        memory.add(android.os.Debug.getPss())
                        phase(surface, round, "navigation-surface-complete")
                    }
                    if (round == 0) stopMethodSampling()
                    reportRound(round, focusStart, frameStart, uiStart, memoryStart, componentStarts, dropsStart)
                }
                navigationCompleted = true
                // Correctness follows every timed navigation sample. Known baseline
                // detail defects cannot truncate or contaminate steady-input metrics.
                repeat(correctnessRounds) { round ->
                    for (surface in surfaces.filter { it != DashboardSurface.HOME }) {
                        phase(surface, round, "detail-menu-before")
                        selectSurfaceThroughMenu(fixture, surface)
                        phase(surface, round, "detail-menu-dispatched")
                        await(fixture, "Correctness ${surface.name} header") { snapshot(fixture)?.hasLabel(surface.trialTitle()) == true }
                        awaitLocalEntry(fixture)
                        phase(surface, round, "detail-menu-ready")

                        // Enter a real TMDB rail afresh, then activate before the
                        // 150ms background-presentation debounce can finish.
                        phase(surface, round, "detail-hero-before")
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/detail-hero", detailLatencies)
                        phase(surface, round, "detail-hero-after")
                        if (largeWorkload) {
                            phase(surface, round, "detail-recents-before")
                            inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/detail-recents", detailLatencies)
                            phase(surface, round, "detail-recents-after")
                            if (surface == DashboardSurface.SERIES) {
                                phase(surface, round, "detail-episodes-before")
                                inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/detail-episodes", detailLatencies)
                                phase(surface, round, "detail-episodes-after")
                            }
                        }
                        phase(surface, round, "detail-target-before")
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_DOWN, "${surface.name}/$round/detail-target", detailLatencies)
                        phase(surface, round, "detail-target-after")
                        // Move beyond the first tile, which can be the same title as
                        // the prepared hero and conceal a stale action/title race.
                        phase(surface, round, "detail-right-before")
                        inputAndMeasure(fixture, KeyEvent.KEYCODE_DPAD_RIGHT, "${surface.name}/$round/detail-next-tile", detailLatencies)
                        phase(surface, round, "detail-right-after")
                        val origin = checkNotNull(snapshot(fixture))
                        assertTrue("Fast detail origin must be a TMDB fixture tile.", origin.labels.any { it.startsWith("Quiet movie ") || it.startsWith("Quiet series ") })
                        val started = SystemClock.elapsedRealtimeNanos()
                        phase(surface, round, "detail-open-before")
                        dispatch(fixture, KeyEvent.KEYCODE_DPAD_CENTER)
                        phase(surface, round, "detail-open-dispatched")
                        await(fixture, "Details opens without playback") { model.state.value.detail != null }
                        await(fixture, "Detail Play focus") { snapshot(fixture)?.hasLabel("Play") == true }
                        phase(surface, round, "detail-open-ready")
                        val opened = model.state.value.detail!!
                        verifyIdentity("${surface.name}/$round/detail-request: selected title must match requested detail", opened.title in origin.labels)
                        verifyIdentity("${surface.name}/$round/detail-title: rendered title must match selected tile", renderedLabel(fixture, opened.title))
                        detailLatencies += "${surface.name}/$round/open-detail" to (SystemClock.elapsedRealtimeNanos() - started)
                        played.set(null)
                        phase(surface, round, "detail-play-before")
                        dispatch(fixture, KeyEvent.KEYCODE_DPAD_CENTER)
                        phase(surface, round, "detail-play-after")
                        // The fixture's callback is synchronous and never starts media.
                        // Missing dispatch is a correctness failure too, rather than an
                        // eight-second wait that would change the matched workload.
                        val actualPlayed = played.get()?.key
                        verifyIdentity("${surface.name}/$round/detail-play: expected=${opened.key}, actual=$actualPlayed", opened.key == actualPlayed)
                        val backStarted = SystemClock.elapsedRealtimeNanos()
                        phase(surface, round, "detail-back-before")
                        instrumentation.runOnMainSync { fixture.host!!.onBackPressedDispatcher.onBackPressed() }
                        phase(surface, round, "detail-back-dispatched")
                        await(fixture, "Back returns to the original item") {
                            model.state.value.detail == null && snapshot(fixture)?.labels == origin.labels
                        }
                        phase(surface, round, "detail-back-ready")
                        detailLatencies += "${surface.name}/$round/back-detail" to (SystemClock.elapsedRealtimeNanos() - backStarted)
                        Thread.sleep(400)
                        // The fast route may be entered while reveal is still moving.
                        // Check exact pixel restoration separately from a settled origin.
                        val settledOrigin = checkNotNull(snapshot(fixture))
                        phase(surface, round, "settled-open-before")
                        dispatch(fixture, KeyEvent.KEYCODE_DPAD_CENTER)
                        phase(surface, round, "settled-open-dispatched")
                        await(fixture, "Settled details opens") { model.state.value.detail?.key == opened.key }
                        await(fixture, "Settled details ready") { snapshot(fixture)?.hasLabel("Play") == true }
                        phase(surface, round, "settled-open-ready")
                        phase(surface, round, "settled-back-before")
                        instrumentation.runOnMainSync { fixture.host!!.onBackPressedDispatcher.onBackPressed() }
                        phase(surface, round, "settled-back-dispatched")
                        await(fixture, "Settled Back restores the exact tile") {
                            model.state.value.detail == null && snapshot(fixture)?.labels == settledOrigin.labels
                        }
                        phase(surface, round, "settled-back-ready")
                        Thread.sleep(400)
                        val returned = snapshot(fixture)!!
                        assertTrue("Back from a settled row must retain its scroll position.", kotlin.math.abs(returned.top - settledOrigin.top) <= 2f)

                    }
                    correctnessRoundsCompleted++
                }
                correctnessCompleted = true
                fixture.assertStillAsleep()
                val (frames, focus) = report(completed = true)
                assertTrue("A rendered fixture is required for measured results.", frames.isNotEmpty())
                val refresh = fixture.refreshRate.takeIf { it > 0 } ?: 60f
                val frameBudget = (1_000_000_000.0 / refresh).toLong()
                // Report budgets honestly; absolute physical-panel targets require awake testing.
                if (InstrumentationRegistry.getArguments().getString("quietAssertBudgets") == "true") {
                    assertTrue("Focus p95 exceeds two virtual-display frames; see metrics.", percentileMs(focus, .95) <= frameBudget * 2 / 1_000_000.0)
                    assertEquals("Animation frames >50ms; see metrics.", 0, frames.count { it > 50_000_000 })
                }
            } catch (failure: Throwable) {
                sampleFrames.set(false)
                stopMethodSampling()
                val context = AtomicReference<String>()
                instrumentation.runOnMainSync {
                    val modelState = if (modelCreated) model.state.value else null
                    val focus = runCatching { snapshotOnMain(fixture) }.getOrNull()
                    context.set("class=${failure.javaClass.simpleName} selected=${selected.value} model_surface=${modelState?.surface} " +
                        "navigation_id=${navigationId.longValue} detail=${modelState?.detail?.key} focus=$focus")
                }
                failureContext = context.get()
                failureTrace = synchronized(navigationTrace) { navigationTrace.toList() }
                diagnostic("QUIET_FAILURE_CONTEXT $failureContext")
                if (failureTrace.isNotEmpty()) diagnostic("QUIET_FAILURE_TRACE entries=${failureTrace.size}\n${failureTrace.joinToString("\n")}")
                runCatching { fixture.capturePreview("quiet-discovery-failure.png") }
                    .onSuccess { file -> file?.let { diagnostic("QUIET_FAILURE_PREVIEW ${it.absolutePath}") } }
                    .onFailure { diagnostic("QUIET_FAILURE_PREVIEW unavailable: ${it.javaClass.simpleName}") }
                reported = false // Include failure-only diagnostics in the final/partial artifact.
                throw failure
            } finally {
                sampleFrames.set(false)
                stopMethodSampling()
                if (!reported && latencies.isNotEmpty()) report(completed = navigationCompleted)
                instrumentation.runOnMainSync {
                    fixture.host?.fixtureWindow?.removeOnFrameMetricsAvailableListener(frameListener)
                    fixture.host?.setContent { }
                }
                frameThread.quitSafely()
                frameThread.join(1_000)
                SingletonImageLoader.setUnsafe(originalImageLoader)
                fixtureImageLoader.shutdown()
                // Only generated fixture files, never profile caches or personal media.
                artDirectory.listFiles()?.forEach { it.delete() }
                artDirectory.delete()
                workload.cleanup(instrumentation.targetContext)
                if (sampleMethods && methodSamplingStarted) {
                    try {
                        emitMethodSamplingTrace(checkNotNull(methodTraceFile))
                    } catch (failure: Throwable) {
                        methodSamplingFailure = failure
                        diagnostic("QUIET_METHOD_TRACE_ERROR emit=${failure.javaClass.simpleName}")
                    }
                }
                if (methodSamplingFailure != null && failureContext == null)
                    throw AssertionError("Requested diagnostic method trace failed.", methodSamplingFailure)
            }
        }
    }

    /** Optional own-process method IDs/timestamps only; never records arguments. */
    private fun emitMethodSamplingTrace(file: File) {
        val limit = 16L * 1024 * 1024 // 8MiB runtime buffer plus bounded method/header tables.
        check(file.isFile && file.length() in 1L..limit) { "Generated method trace is missing, empty or exceeds 16MiB." }
        val chunkBytes = 16 * 1024
        diagnostic("QUIET_METHOD_TRACE_BEGIN file=${file.name} bytes=${file.length()} buffer_bytes=${8 * 1024 * 1024} interval_us=1000 chunk_bytes=$chunkBytes encoding=base64 diagnostic_only=true")
        val digest = MessageDigest.getInstance("SHA-256")
        var chunks = 0
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(chunkBytes)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                check(total <= limit) { "Generated trace grew beyond output bound." }
                digest.update(buffer, 0, count)
                val encoded = android.util.Base64.encodeToString(buffer, 0, count, android.util.Base64.NO_WRAP)
                diagnostic("QUIET_METHOD_TRACE_CHUNK index=$chunks raw_bytes=$count data=$encoded")
                chunks++
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        diagnostic("QUIET_METHOD_TRACE_END chunks=$chunks bytes=$total sha256=$sha256")
    }

    private fun findNavigationTraceLocal(): ProvidableCompositionLocal<((String) -> Unit)?> {
        val holder = try {
            Class.forName("com.nikhil.niktv.discovery.DiscoveryUiKt")
        } catch (failure: ReflectiveOperationException) {
            throw AssertionError("quietTraceNavigation requested, but production navigation trace holder is unavailable.", failure)
        }
        val getter = holder.methods.singleOrNull { method ->
            method.name.startsWith("getLocalDiscoveryNavigationTrace") && method.parameterTypes.isEmpty() &&
                java.lang.reflect.Modifier.isStatic(method.modifiers)
        } ?: throw AssertionError("quietTraceNavigation requested, but production navigation trace accessor is unavailable.")
        val local = getter.invoke(null)
        check(local is ProvidableCompositionLocal<*>) { "Production navigation trace accessor returned an unsupported type." }
        return local as ProvidableCompositionLocal<((String) -> Unit)?>
    }

    @Composable
    private fun WithOptionalNavigationTrace(local: ProvidableCompositionLocal<((String) -> Unit)?>?,
        collector: ((String) -> Unit)?, content: @Composable () -> Unit) {
        if (local == null) content()
        else CompositionLocalProvider(local provides collector, content = content)
    }

    private fun fixtureSections() = mapOf(
        DashboardSurface.HOME to listOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.TOP_RATED_SERIES,
            TmdbHomeSection.ACTION, TmdbHomeSection.ACTION_SERIES, TmdbHomeSection.COMEDY, TmdbHomeSection.COMEDY_SERIES),
        DashboardSurface.MOVIES to listOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.NEW_HOLLYWOOD_MOVIES,
            TmdbHomeSection.ACTION, TmdbHomeSection.COMEDY, TmdbHomeSection.HORROR, TmdbHomeSection.THRILLER),
        DashboardSurface.SERIES to listOf(TmdbHomeSection.TOP_RATED_SERIES, TmdbHomeSection.NEW_HOLLYWOOD_SERIES,
            TmdbHomeSection.ACTION_SERIES, TmdbHomeSection.COMEDY_SERIES, TmdbHomeSection.MYSTERY_SERIES, TmdbHomeSection.FAMILY_SERIES))

    private inner class LocalWorkload(profile: PortalProfile, art: List<Pair<String, String>>, large: Boolean) {
        private val profileKey = profile.cacheKey()
        private val categories = listOf(CatalogType.MOVIES, CatalogType.SERIES).associateWith { type ->
            if (!large) emptyList() else (1..12).map { Category("quiet-${type.name}-$it", "Fixture ${type.title} category $it", type) }
        }
        val items = categories.flatMap { (type, values) -> values.map { category ->
            category.id to (1..30).map { index -> MediaItem("${category.id}:$index", "Fixture IPTV ${type.title} ${category.id.substringAfterLast('-')} title $index",
                art[index % art.size].first, null, description = "Generated local provider metadata.",
                portalCategoryId = category.id, externalTmdbId = 10_000 + index) }
        } }.toMap()
        private val recents = categories.flatMap { (type, values) -> values.take(2).flatMap { category ->
            items[category.id].orEmpty().mapIndexed { index, item ->
                val series = type == CatalogType.SERIES
                RecentItem(if (series) FavoriteKind.SERIES else FavoriteKind.MOVIE, item,
                    series = item.takeIf { series },
                    lastPlayed = item.takeIf { series }?.copy(id = "${item.id}:episode", title = "Fixture last watched episode", seasonNumber = 1, episodeNumber = 1),
                    playedAtMillis = 100_000L + index, profileKey = profileKey, categoryTitle = category.title)
            }
        } }
        private val progress = recents.mapIndexed { index, recent ->
            val series = recent.kind == FavoriteKind.SERIES
            val identity = if (series) "series:${recent.media.id}|s:1|e:1|" else
                "movie:${recent.media.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')}|"
            PlaybackProgress("progress-v2|$profileKey|${if (series) "SERIES" else "MOVIES"}|$identity", (index + 1) * 10_000L, 1_000_000L)
        }
        val app = NikTvState(savedProfile = profile,
            session = if (large) PortalSession(profile, "", "https://fixture.invalid", "", "", "") else null,
            rawCategoriesByType = categories, categoryFilters = categories.map { (type, values) -> "$profileKey|${type.name}" to values.map { it.id } }.toMap(),
            recentlyPlayed = recents, playbackProgress = progress,
            favorites = categories.flatMap { (type, values) -> values.flatMap { category -> items[category.id].orEmpty().map { media ->
                FavoriteItem(if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE, media, profileKey = profileKey)
            } } }.take(500),
            watchedSeries = recents.filter { it.kind == FavoriteKind.SERIES }.take(20).map { recent ->
                WatchedSeries(profileKey, recent.media, recent.categoryTitle,
                    newEpisodes = (2..4).map { number -> recent.media.copy(id = "${recent.media.id}:episode$number", title = "Fixture new episode $number", seasonNumber = 1, episodeNumber = number) })
            }, tmdbSectionsBySurface = fixtureSections())
        private val keys = categories.filterValues { it.isNotEmpty() }.map { (type, _) -> "discovery-provider-v1:$profileKey:${type.name}:rows" }

        fun prime(context: Context) = runBlocking {
            categories.filterValues { it.isNotEmpty() }.forEach { (type, values) ->
                val pages = values.associate { it.id to ProviderDiscoveryPage(items[it.id].orEmpty(), 1, false, System.currentTimeMillis()) }
                CatalogDiskCache.write(context, "discovery-provider-v1:$profileKey:${type.name}:rows", ProviderDiscoveryCache(profileKey, type, pages, preview = true))
            }
        }

        fun cleanup(context: Context) {
            val directory = File(context.cacheDir, "catalogues-v1").canonicalFile
            keys.forEach { key ->
                val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                for (suffix in listOf(".json", ".json.bak", ".json.new")) {
                    val generated = File(directory, name + suffix).canonicalFile
                    check(generated.parentFile == directory)
                    generated.delete()
                }
            }
        }
    }

    private fun generateArtwork(directory: File, count: Int): List<Pair<String, String>> {
        val templates = (0 until minOf(8, count)).map { index ->
            fun write(name: String, width: Int, height: Int): String {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.rgb(20 + index * 18, 30 + index * 9, 70 + index * 13))
                canvas.drawRect(width * .2f, height * .1f, width * .7f, height * .9f,
                    Paint().apply { color = android.graphics.Color.rgb(60 + index * 16, 90, 130) })
                val file = File(directory, "$name-$index.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                return file.absolutePath
            }
            write("poster", 342, 513) to write("backdrop", 1280, 720)
        }
        return (0 until count).map { index ->
            if (index < templates.size) return@map templates[index]
            val poster = File(directory, "poster-$index.png")
            val backdrop = File(directory, "backdrop-$index.png")
            // Different files/URIs exercise decode and cache churn even when
            // the generated palettes repeat. Default eight retain original paths.
            File(templates[index % templates.size].first).copyTo(poster)
            File(templates[index % templates.size].second).copyTo(backdrop)
            android.net.Uri.fromFile(poster).toString() to android.net.Uri.fromFile(backdrop).toString()
        }
    }

    private class LocalDiscoverySource(private val art: List<Pair<String, String>>, private val metadataDelay: Long, private val staleFirst: Boolean) : DiscoveryDataSource {
        private fun card(id: Int, series: Boolean) = DiscoveryCard(id, series,
            "Quiet ${if (series) "series" else "movie"} $id", "Local deterministic synopsis for navigation, details and Back testing.",
            poster = art[id % art.size].first, backdrop = art[id % art.size].second, date = "2020-01-01",
            genres = listOf("Drama", "Adventure"), rating = 7.5)
        override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> {
            delay(12)
            return (1..20).map { card(section.ordinal * 100 + it, section.series) }
        }
        override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
            if (staleFirst) stale(DiscoveryDetails(card.copy(overview = "Cached fixture synopsis")))
            delay(metadataDelay)
            return DiscoveryDetails(card, cast = listOf(DiscoveryPerson("Local actor", "Fixture")), seasons = if (card.series) listOf(1, 2) else emptyList())
        }
        override suspend fun related(card: DiscoveryCard) = (900..919).map { card(it, card.series) }
        override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
            val details = DiscoveryDetails(card(item.externalTmdbId ?: 10_000, type == CatalogType.SERIES).copy(title = item.title))
            if (staleFirst) stale(details.copy(card = details.card.copy(overview = "Cached fixture provider synopsis")))
            delay(metadataDelay)
            return details
        }
        override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
        override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
        override suspend fun episodes(card: DiscoveryCard, season: Int) = (1..12).map {
            DiscoveryEpisode(season, it, "Quiet episode $it", "Local episode synopsis", "2020-01-01", art[it % art.size].second)
        }
    }

    private data class FocusSnapshot(val id: Int, val labels: List<String>, val top: Float) {
        fun hasLabel(label: String) = label in labels
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun nodeLabels(node: SemanticsNode): List<String> = buildList {
        addAll(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        node.children.forEach { addAll(nodeLabels(it)) }
    }.distinct()

    /** Called on main only; no global accessibility traversal or shell input. */
    private fun snapshotOnMain(fixture: QuietDisplay): FocusSnapshot? =
        roots(fixture.host!!.fixtureWindow.decorView).asSequence()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.let { FocusSnapshot(it.id, nodeLabels(it), it.boundsInRoot.top) }

    private fun snapshot(fixture: QuietDisplay): FocusSnapshot? {
        val result = AtomicReference<FocusSnapshot?>()
        instrumentation.runOnMainSync { result.set(snapshotOnMain(fixture)) }
        return result.get()
    }

    /** Visible Text semantics, excluding stale image content descriptions. */
    private fun renderedLabel(fixture: QuietDisplay, label: String): Boolean {
        val result = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            val decor = fixture.host!!.fixtureWindow.decorView
            result.set(roots(decor).any { root ->
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).any { node ->
                    val bounds = node.boundsInRoot
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                        bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.left < decor.width &&
                        bounds.bottom > 0 && bounds.top < decor.height
                }
            })
        }
        return result.get()
    }

    private fun dispatch(fixture: QuietDisplay, code: Int) = instrumentation.runOnMainSync {
        silence(fixture.host!!.fixtureWindow.decorView)
        val time = SystemClock.uptimeMillis()
        val presentation = fixture.host!!
        presentation.dispatchLocalKey(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD))
        presentation.dispatchLocalKey(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD))
    }

    /**
     * Untimed route setup follows the actual header action, including production
     * focus parking before the outer saveable surface is replaced. Assigning the
     * fixture's selected state directly would bypass that application behavior.
     */
    private fun selectSurfaceThroughMenu(fixture: QuietDisplay, destination: DashboardSurface) {
        val tabs = listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)
        fun onHeader(focus: FocusSnapshot?) = focus?.labels?.any { label -> tabs.any { it.trialTitle() == label } } == true
        var steps = 0
        while (!onHeader(snapshot(fixture)) && steps < 30) {
            val before = snapshot(fixture)
            dispatch(fixture, KeyEvent.KEYCODE_DPAD_UP)
            await(fixture, "Menu Up must move focus (step=${steps + 1})") {
                val after = snapshot(fixture)
                onHeader(after) || (after != null && (after.id != before?.id || after.labels != before?.labels))
            }
            steps++
        }
        assertTrue("D-pad Up must reach a trial header before selecting $destination.", onHeader(snapshot(fixture)))
        // Header focus precedes its ordinary alignment animation. Settle outside
        // frame sampling; same-tab selection intentionally does not create a route.
        Thread.sleep(300)
        fixture.assertStillAsleep()
        instrumentation.runOnMainSync {
            val title = destination.trialTitle()
            val action = roots(fixture.host!!.fixtureWindow.decorView).asSequence()
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
                .filter { node -> title in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == title } }
                .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                .firstOrNull()
            assertTrue("The actual $title menu action must be present and accept selection.", action?.invoke() == true)
        }
        fixture.assertStillAsleep()
    }

    private fun inputAndMeasure(fixture: QuietDisplay, code: Int, label: String, results: MutableList<Pair<String, Long>>) {
        fixture.assertStillAsleep()
        val before = snapshot(fixture)
        val complete = CountDownLatch(1)
        val measured = java.util.concurrent.atomic.AtomicLong(-1)
        val started = SystemClock.elapsedRealtimeNanos()
        dispatch(fixture, code)
        instrumentation.runOnMainSync {
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    val current = snapshotOnMain(fixture)
                    if (current != null && (current.id != before?.id || current.labels != before?.labels)) {
                        measured.set(SystemClock.elapsedRealtimeNanos() - started)
                        complete.countDown()
                    } else if (SystemClock.elapsedRealtimeNanos() - started < 3_000_000_000L) {
                        Choreographer.getInstance().postFrameCallback(this)
                    } else complete.countDown()
                }
            }
            callback.doFrame(0)
        }
        assertTrue("Focus transition timed out at $label (before=$before)", complete.await(4, TimeUnit.SECONDS) && measured.get() >= 0)
        results += label to measured.get()
        fixture.assertStillAsleep()
    }

    /**
     * The local readiness view bypasses the deliberately nonfocusable window's
     * native-focus wait. Let ordinary route composition + two entry frames finish
     * before timing input. This does not measure physical window-entry latency.
     */
    private fun awaitLocalEntry(fixture: QuietDisplay) {
        val ready = CountDownLatch(1)
        val callback = object : Choreographer.FrameCallback {
            var frames = 0
            override fun doFrame(frameTimeNanos: Long) {
                if (++frames >= 4) ready.countDown()
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback(callback)
        }
        val completed = ready.await(8, TimeUnit.SECONDS)
        if (!completed) instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
        assertTrue("Local entry pre-roll needs a running virtual frame clock.", completed)
        fixture.assertStillAsleep()
    }

    private fun await(fixture: QuietDisplay, label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            fixture.assertStillAsleep()
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue("$label; focused=${snapshot(fixture)}", false)
    }

    private fun percentileMs(values: List<Long>, quantile: Double): Double =
        if (values.isEmpty()) Double.NaN else values.sorted().let { it[((it.size - 1) * quantile).toInt().coerceAtLeast(0)] / 1_000_000.0 }

    private fun requireOptIn() {
        check(instrumentation.targetContext.packageName.endsWith(".regression")) {
            "Quiet fixture may only use the isolated regression package."
        }
        assumeTrue("Requires explicit quietVirtualDisplay=true; no default-display fallback.",
            InstrumentationRegistry.getArguments().getString("quietVirtualDisplay") == "true")
        assumeTrue("Window FrameMetrics require API 24+.", Build.VERSION.SDK_INT >= 24)
    }

    private fun diagnostic(message: String) {
        println(message)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun silence(view: View) {
        view.isSoundEffectsEnabled = false
        view.isHapticFeedbackEnabled = false
        if (view is ViewGroup) for (index in 0 until view.childCount) silence(view.getChildAt(index))
    }

    private inner class QuietDisplay : AutoCloseable {
        private val context = instrumentation.targetContext
        private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        private val handlerThread = HandlerThread("quiet-image-consumer").apply { start() }
        private val monitor = Executors.newSingleThreadScheduledExecutor()
        private val woke = AtomicBoolean(false)
        private var verifiedAsleep = false
        private val requestedPreview = AtomicReference<File?>()
        private val capturedPreview = AtomicReference<File?>()
        private var reader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null
        var host: QuietPresentation? = null
            private set
        val frames = AtomicInteger(0)
        val displayId get() = virtualDisplay?.display?.displayId ?: Display.INVALID_DISPLAY
        val refreshRate get() = virtualDisplay?.display?.refreshRate ?: 0f
        val densityDpi = (context.resources.displayMetrics.densityDpi * 1920.0 /
            context.resources.displayMetrics.widthPixels.coerceAtLeast(1)).toInt().coerceAtLeast(120)

        fun open() {
            assumeTrue("The physical display must already be asleep; this test never sleeps or wakes it.", !power.isInteractive)
            assumeTrue("Power state must be Asleep before quiet testing.", shell("dumpsys power").contains("mWakefulness=Asleep"))
            verifiedAsleep = true
            monitor.scheduleAtFixedRate({ if (power.isInteractive) woke.set(true) }, 0, 100, TimeUnit.MILLISECONDS)
            reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2).also {
                it.setOnImageAvailableListener({ source ->
                    runCatching { source.acquireLatestImage()?.use { image ->
                        frames.incrementAndGet()
                        requestedPreview.getAndSet(null)?.let { destination ->
                            val plane = image.planes[0]
                            val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                            destination.outputStream().use { output -> cropped.compress(Bitmap.CompressFormat.PNG, 100, output) }
                            if (cropped !== padded) cropped.recycle()
                            padded.recycle()
                            capturedPreview.set(destination)
                        }
                    } }
                }, Handler(handlerThread.looper))
            }
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            virtualDisplay = manager.createVirtualDisplay("NikTV quiet regression", 1920, 1080, densityDpi,
                reader!!.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
            assumeTrue("Fire OS refused a private virtual display.", virtualDisplay != null)
            assertFalse("Virtual display must never be the TV display.", displayId == Display.DEFAULT_DISPLAY)
            assertTrue("Only an app-owned private display may host this fixture.",
                virtualDisplay!!.display.flags and Display.FLAG_PRIVATE != 0)
            assertStillAsleep()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { host = QuietPresentation(context, virtualDisplay!!.display).also { it.show() } }
                catch (error: RuntimeException) { failure.set(error) }
            }
            failure.get()?.let {
                assumeTrue("Private Presentation unsupported: ${it.javaClass.simpleName}: ${it.message}. No Activity/default-display fallback.", false)
            }
            assertEquals("Presentation identity must match the private virtual display immediately.", displayId, host!!.display.displayId)
            // Dialog.show() schedules the first ViewRoot traversal; decorView.display
            // remains null until attachment. Never substitute the physical display.
            var attachedDisplayId: Int? = null
            val attachmentDeadline = SystemClock.uptimeMillis() + 2_000
            while (attachedDisplayId == null && SystemClock.uptimeMillis() < attachmentDeadline) {
                assertStillAsleep()
                instrumentation.runOnMainSync { attachedDisplayId = host!!.fixtureWindow.decorView.display?.displayId }
                attachedDisplayId?.let { assertEquals("Quiet decor must attach only to its private display.", displayId, it) }
                if (attachedDisplayId == null) Thread.sleep(20)
            }
            assumeTrue("Private Presentation did not attach within two seconds while asleep; unsupported, with no Activity/default-display fallback.",
                attachedDisplayId != null)
            val windowFlags = host!!.fixtureWindow.attributes.flags
            assertEquals("Quiet window cannot request wake or keep-screen-on behavior.", 0,
                windowFlags and (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD))
            assertTrue("Quiet window cannot take system input focus.", windowFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertFalse("Only virtual LocalView readiness may be true; native window focus must remain false.", host!!.nativeWindowFocused)
            assertStillAsleep()
        }

        fun awaitRendering(contentDrawn: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < until && (frames.get() == 0 || !contentDrawn())) {
                assertStillAsleep()
                Thread.sleep(40)
            }
            assumeTrue("Private Presentation does not render while asleep; no Activity/physical-screen fallback is allowed.", frames.get() > 0 && contentDrawn())
        }

        /** One generated-fixture screenshot, outside measured input/frame intervals. */
        fun capturePreview(fileName: String = "quiet-discovery-preview.png"): File? {
            val directory = context.getExternalFilesDir(null) ?: return null
            require(File(fileName).name == fileName)
            val destination = File(directory, fileName)
            capturedPreview.set(null)
            requestedPreview.set(destination)
            instrumentation.runOnMainSync { host?.fixtureWindow?.decorView?.invalidate() }
            val until = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < until && capturedPreview.get() == null) {
                assertStillAsleep()
                Thread.sleep(20)
            }
            requestedPreview.set(null)
            return capturedPreview.get()
        }

        fun assertStillAsleep() {
            assertFalse("Quiet test must never wake the physical display.", woke.get() || power.isInteractive)
        }

        override fun close() {
            try {
                host?.let { presentation -> instrumentation.runOnMainSync { presentation.dismiss() } }
            } finally {
                virtualDisplay?.release()
                reader?.setOnImageAvailableListener(null, null)
                reader?.close()
                handlerThread.quitSafely()
                monitor.shutdownNow()
                if (verifiedAsleep) {
                    assertStillAsleep()
                    assertFalse("Default power state changed during quiet fixture.", !shell("dumpsys power").contains("mWakefulness=Asleep"))
                }
            }
        }
    }

    /** Isolated lifecycle/input ownership; never creates or resumes an Android Activity. */
    private class QuietPresentation(context: Context, display: Display) :
        Presentation(context, display, android.R.style.Theme_Material_NoActionBar),
        LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner,
        HasDefaultViewModelProviderFactory, ActivityResultRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val onBackPressedDispatcher = OnBackPressedDispatcher {}
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                // Menus may register launchers, but this quiet fixture never runs
                // external activities, permission prompts or provider intents.
                throw AssertionError("Quiet fixture forbids ActivityResult launch: ${contract.javaClass.simpleName}")
            }
        }
        private val application = context.applicationContext as Application
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        override val defaultViewModelCreationExtras: CreationExtras = MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
        }
        private lateinit var content: ComposeView
        private lateinit var readinessView: FrameLayout
        val fixtureWindow: Window get() = checkNotNull(window)
        val nativeWindowFocused: Boolean get() = content.hasWindowFocus()

        init {
            require(display.displayId != Display.DEFAULT_DISPLAY && display.displayId != Display.INVALID_DISPLAY)
            saved.performAttach()
            saved.performRestore(null)
            fixtureWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            // This window cannot participate in system/global focus or remote input.
            fixtureWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }

        override fun onCreate(state: android.os.Bundle?) {
            super.onCreate(state)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            val expectedDisplayId = this@QuietPresentation.display.displayId
            readinessView = object : FrameLayout(context) {
                // This getter is only a fixture readiness signal. It never requests
                // focus, changes a Window flag or changes system/global input.
                override fun hasWindowFocus(): Boolean = isAttachedToWindow && this.display?.displayId == expectedDisplayId
                // Keep local focus tests invisible to global screen readers/TTS.
                override fun requestSendAccessibilityEvent(child: View, event: android.view.accessibility.AccessibilityEvent): Boolean = false
                override fun sendAccessibilityEventUnchecked(event: android.view.accessibility.AccessibilityEvent) = Unit
            }
            readinessView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            readinessView.setViewTreeLifecycleOwner(this)
            readinessView.setViewTreeViewModelStoreOwner(this)
            readinessView.setViewTreeSavedStateRegistryOwner(this)
            readinessView.setViewTreeOnBackPressedDispatcherOwner(this)
            readinessView.isSoundEffectsEnabled = false
            readinessView.isHapticFeedbackEnabled = false
            content = ComposeView(context)
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content.setViewTreeLifecycleOwner(this)
            content.setViewTreeViewModelStoreOwner(this)
            content.setViewTreeSavedStateRegistryOwner(this)
            content.setViewTreeOnBackPressedDispatcherOwner(this)
            fixtureWindow.decorView.setViewTreeLifecycleOwner(this)
            fixtureWindow.decorView.setViewTreeViewModelStoreOwner(this)
            fixtureWindow.decorView.setViewTreeSavedStateRegistryOwner(this)
            fixtureWindow.decorView.setViewTreeOnBackPressedDispatcherOwner(this)
            fixtureWindow.decorView.isSoundEffectsEnabled = false
            fixtureWindow.decorView.isHapticFeedbackEnabled = false
            fixtureWindow.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content.isSoundEffectsEnabled = false
            content.isHapticFeedbackEnabled = false
            readinessView.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(readinessView)
            fixtureWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun setContent(body: @Composable () -> Unit) = content.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides this@QuietPresentation,
                LocalView provides readinessView) { body() }
        }

        fun dispatchLocalKey(event: KeyEvent): Boolean {
            fun root(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
                return null
            }
            return root(content)?.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(event)) == true
        }

        override fun dismiss() {
            if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            if (::content.isInitialized) content.disposeComposition()
            viewModelStore.clear()
            super.dismiss()
        }
    }
}
