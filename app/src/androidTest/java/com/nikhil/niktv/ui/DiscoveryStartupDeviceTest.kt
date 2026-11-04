package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Execute the actual DEX methods on ART; JVM tests cannot detect a VerifyError. */
class DiscoveryStartupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(80) }
        fail(label)
    }

    @Test fun homeMoviesAndSeriesRenderInOriginalAndTrialPresenters() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                listOf(DiscoveryCard(1, section.series, "Startup fixture", date = "2020-01-01"))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card)
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        val surface = mutableStateOf(DashboardSurface.HOME)
        val trial = mutableStateOf(false)
        val rendered = AtomicReference<String>()
        val draws = AtomicInteger()
        lateinit var vm: DiscoveryViewModel
        var vmReady = false
        try {
            instrumentation.runOnMainSync {
                vm = DiscoveryViewModel(context.applicationContext as Application, source)
                vmReady = true
                host.window.decorView.viewTreeObserver.addOnDrawListener { draws.incrementAndGet() }
                host.setContent { MaterialTheme {
                    val profile = remember { PortalProfile("Startup fixture", "https://fixture.invalid", "") }
                    val route = surface.value
                    DiscoveryPreviewScreen(
                        app = NikTvState(savedProfile = profile, homeOpen = true, discoverySurface = route,
                            tmdbSectionsBySurface = mapOf(route to emptyList())),
                        surface = route, selectSurface = { surface.value = it }, exit = {}, navigate = {},
                        playCard = {}, playEpisode = { _, _, _ -> }, openRecent = {}, openWatched = { _, _ -> },
                        toggleFavorite = {}, openCategory = {}, openSection = {}, removeRecent = {},
                        markWatched = { _, _ -> }, dismissEpisode = { _, _ -> }, vm = vm, experiment = trial.value)
                    SideEffect { rendered.set("${route.name}:${trial.value}") }
                } }
            }
            for (experiment in listOf(false, true)) for (destination in listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)) {
                val before = draws.get()
                instrumentation.runOnMainSync { surface.value = destination; trial.value = experiment }
                await("$destination trial=$experiment must compose and draw without ART rejecting the screen") {
                    rendered.get() == "${destination.name}:$experiment" && draws.get() > before &&
                        vm.state.value.surface == destination
                }
            }
            assertFalse(host.isFinishing)
        } finally { instrumentation.runOnMainSync { if (vmReady) vm.viewModelScope.cancel(); host.finish() } }
    }
}
