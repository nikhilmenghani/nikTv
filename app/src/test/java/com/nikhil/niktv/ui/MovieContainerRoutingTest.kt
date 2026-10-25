package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class MovieContainerRoutingTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://vod.invalid", "")
    private val movie = MediaItem("first", "Fixture title", null, "command")
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren(); scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val mutable = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        state = mutable
        state.value = NikTvState(savedProfile = profile, restoring = false, selectedType = CatalogType.MOVIES,
            session = PortalSession(profile, "fixture", "https://vod.invalid", "", "", ""))
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain() }
    @Test fun ordinaryMovieAndUnavailableProbeRetainNormalDetailsWithoutGlobalError() {
        vm.movieStructureLookup = { _, _ -> false }
        vm.openProviderMovieDetails(movie); scheduler.runCurrent()
        assertEquals(movie, state.value.providerMovieDetails?.item)
        assertNull(state.value.selectedSeries)
        vm.movieStructureLookup = { _, _ -> throw java.io.IOException("Fixture offline") }
        vm.openProviderMovieDetails(movie); scheduler.runCurrent()
        assertEquals(movie, state.value.providerMovieDetails?.item)
        assertNull(state.value.error)
    }
    @Test fun latePositiveProbeCannotReopenClosedDetails() {
        val result = CompletableDeferred<Boolean>()
        vm.movieStructureLookup = { _, _ -> withContext(NonCancellable) { result.await() } }
        vm.openProviderMovieDetails(movie); scheduler.runCurrent()
        vm.closeProviderMovieDetails(); result.complete(true); scheduler.runCurrent()
        assertNull(state.value.providerMovieDetails)
        assertNull(state.value.selectedSeries)
    }
    @Test fun oldSelectionCannotReplaceNewSelectionEvenWhenLookupIgnoresCancellation() {
        val result = CompletableDeferred<Boolean>()
        val second = movie.copy(id = "second")
        vm.movieStructureLookup = { _, item -> if (item.id == movie.id) withContext(NonCancellable) { result.await() } else false }
        vm.openProviderMovieDetails(movie); scheduler.runCurrent()
        vm.openProviderMovieDetails(second); scheduler.runCurrent()
        result.complete(true); scheduler.runCurrent()
        assertEquals(second, state.value.providerMovieDetails?.item)
        assertNull(state.value.selectedSeries)
    }
    @Test fun latePositiveProbeCannotEnterEpisodesAfterProfileChange() {
        val result = CompletableDeferred<Boolean>()
        vm.movieStructureLookup = { _, _ -> result.await() }
        vm.openProviderMovieDetails(movie); scheduler.runCurrent()
        state.value = state.value.copy(session = state.value.session!!.copy(profile = profile.copy(portalUrl = "https://other.invalid")))
        result.complete(true); scheduler.runCurrent()
        assertNull(state.value.selectedSeries)
    }
}
