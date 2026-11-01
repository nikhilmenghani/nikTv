package com.nikhil.niktv.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AbibliPlaybackLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://provider.invalid", "00:00:00:00:00:00")
    private val movie = MediaItem("tmdb-movie:1726", "Iron Man", null, null, externalTmdbId = 1726)
    private val stream = AlternateStream("abibli:ABC123", "Auto · Abibli", "https://public.invalid/master.m3u8", abibli = AbibliPlaybackSpec("ABC123"))
    private class Lease(override val sourceKey: String = "ABC123") : AlternatePlaybackLease {
        override val url = "http://127.0.0.1:12345/$sourceKey/master.m3u8"
        var closes = 0
        override fun close() { closes++ }
    }
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("parental_controls", Context.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(app)
        vm.viewModelScope.coroutineContext.cancelChildren()
        scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val field = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        state = field
        state.value = NikTvState(savedProfile = profile, restoring = false)
        vm.alternateSourceLookup = { listOf(stream) }
    }
    @After fun cleanup() {
        vm.closePlayer(); vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain()
    }
    private fun select(item: MediaItem = movie) {
        vm.openAlternateSources(AlternateSourceRequest(item, CatalogType.MOVIES)); scheduler.runCurrent()
        vm.chooseAlternateStream(stream.id); scheduler.runCurrent()
    }
    @Test fun activePlaybackContinuesUntilPreparationFinishesAndLeaseIsSessionOwned() {
        val active = PlayingMedia(movie, "https://provider.invalid/current", CatalogType.MOVIES)
        state.value = state.value.copy(nowPlaying = active)
        val prepared = CompletableDeferred<AlternatePlaybackLease>()
        val lease = Lease()
        vm.alternatePlaybackPreparation = { prepared.await() }
        select()
        assertSame(active, state.value.nowPlaying)
        prepared.complete(lease); scheduler.runCurrent()
        assertEquals(lease.url, state.value.nowPlaying?.url)
        assertTrue(state.value.nowPlaying!!.streamRequestHeaders.isEmpty())
        state.value = state.value.copy(error = "Unrelated state update")
        scheduler.runCurrent(); assertEquals(0, lease.closes)
        vm.closePlayer(); scheduler.runCurrent(); assertEquals(1, lease.closes)
    }
    @Test fun aLateUncancellablePreparationCannotReplaceANewerSelection() {
        val delayed = CompletableDeferred<AlternatePlaybackLease>()
        val obsolete = Lease(); val selected = Lease("DEF456")
        var calls = 0
        vm.alternatePlaybackPreparation = { if (++calls == 1) withContext(NonCancellable) { delayed.await() } else selected }
        select()
        select(movie.copy(id = "second", title = "Second movie", externalTmdbId = 22))
        assertEquals(selected.url, state.value.nowPlaying?.url)
        delayed.complete(obsolete); scheduler.runCurrent()
        assertEquals(selected.url, state.value.nowPlaying?.url)
        assertEquals(1, obsolete.closes); assertEquals(0, selected.closes)
        assertNull(state.value.error)
    }
    @Test fun profileChangeRejectsPreparedLeaseAndPreparationFailurePreservesTheActiveStream() {
        val active = PlayingMedia(movie, "https://provider.invalid/current", CatalogType.MOVIES)
        state.value = state.value.copy(nowPlaying = active)
        val delayed = CompletableDeferred<AlternatePlaybackLease>()
        val lease = Lease()
        vm.alternatePlaybackPreparation = { delayed.await() }
        select()
        state.value = state.value.copy(savedProfile = profile.copy(portalUrl = "https://other.invalid"))
        delayed.complete(lease); scheduler.runCurrent()
        assertSame(active, state.value.nowPlaying); assertEquals(1, lease.closes)
        vm.alternatePlaybackPreparation = { throw java.io.IOException("fixture failure") }
        select(); assertSame(active, state.value.nowPlaying)
        assertFalse(state.value.error.orEmpty().contains("fixture failure"))
    }
    @Test fun pauseAndPositionChangedDuringPreparationAreSampledAtCommit() {
        val first = Lease()
        vm.alternatePlaybackPreparation = { first }; select()
        val active = requireNotNull(state.value.nowPlaying)
        val alternate = stream.copy(id = "abibli:DEF456", abibli = AbibliPlaybackSpec("DEF456"))
        state.value = state.value.copy(nowPlaying = active.copy(alternateSource = active.alternateSource!!.copy(streams = listOf(stream, alternate))))
        val delayed = CompletableDeferred<AlternatePlaybackLease>()
        var position = 12_000L
        vm.alternatePlaybackPreparation = { delayed.await() }
        vm.openPlayingAlternateSources(position, state.value.nowPlaying!!, { position })
        vm.chooseAlternateStream(alternate.id); scheduler.runCurrent()
        vm.setPlaybackIntent(active.playbackAttemptId, false); position = 25_000
        val second = Lease("DEF456")
        delayed.complete(second); scheduler.runCurrent()
        assertFalse(state.value.nowPlaying!!.playWhenReady)
        assertEquals(25_000L, state.value.nowPlaying!!.resumePositionMillis)
        assertEquals(1, first.closes)
        state.value = state.value.copy(nowPlaying = PlayingMedia(movie, "https://provider.invalid/replacement", CatalogType.MOVIES))
        scheduler.runCurrent()
        assertEquals(1, second.closes)
    }
}
