package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
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
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class EpisodeDestinationViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var mutable: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
    private val session = PortalSession(profile, "fixture", "https://fixture.invalid/portal.php", "", "", "")
    private val series = MediaItem("series", "Fixture series", null, null)
    private fun episode(n: Int) = MediaItem("e$n", "Episode $n", null, null, seasonNumber = 1, episodeNumber = n,
        portalSeasonId = "season-one")

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren()
        @Suppress("UNCHECKED_CAST")
        val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        mutable = state
        mutable.value = NikTvState(savedProfile = profile, session = session, selectedSeries = series,
            selectedType = CatalogType.SERIES, selectedSeriesSeason = 1, availableSeriesSeasons = listOf(1),
            items = (481..500).map(::episode), episodePage = 1, episodeHasMore = true, loading = false, restoring = false)
        vm.episodePageLookup = { _, _, _, _, _ -> error("Unexpected page lookup") }
        vm.episodePageEnrichment = { _, _ -> }
    }
    @After fun teardown() {
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Episode operation did not finish: ${vm.state.value.episodeDestination}")
    }

    @Test fun cachedJumpIsImmediateAndNeverStartsPlayback() {
        vm.goToSeriesEpisode(492)
        scheduler.runCurrent()
        assertEquals("e492", vm.state.value.episodeDestination?.focusId)
        assertFalse(vm.state.value.episodeDestination!!.running)
        assertNull(vm.state.value.nowPlaying)
    }

    @Test fun earliestLoadsRemainingPagesCachesThemAndPreservesPlayback() {
        val playing = PlayingMedia(episode(499), "https://fixture.invalid/video", CatalogType.SERIES, series = series,
            profileKey = profile.cacheKey())
        mutable.value = mutable.value.copy(nowPlaying = playing)
        val requests = mutableListOf<Int>()
        vm.episodePageLookup = { _, selected, season, page, seasonId ->
            assertEquals(series, selected); assertEquals(1, season); assertEquals("season-one", seasonId)
            requests += page
            EpisodeSeasonResult((1..480).map(::episode), listOf(1), 1, page, false)
        }
        vm.goToSeriesEpisode()
        await { vm.state.value.episodeDestination?.running == false }
        assertEquals("e1", vm.state.value.episodeDestination?.focusId)
        assertEquals(500, vm.state.value.items.size)
        assertEquals(listOf(2), requests)
        assertEquals(playing.url, vm.state.value.nowPlaying?.url)
        assertEquals(playing.playbackAttemptId, vm.state.value.nowPlaying?.playbackAttemptId)
        vm.goToSeriesEpisode(37)
        scheduler.runCurrent()
        assertEquals("e37", vm.state.value.episodeDestination?.focusId)
        assertEquals(listOf(2), requests)
    }

    @Test fun oldProfileCannotReceiveLateEpisodesOrFocus() {
        val reply = CompletableDeferred<EpisodeSeasonResult>()
        vm.episodePageLookup = { _, _, _, _, _ -> withContext(NonCancellable) { reply.await() } }
        vm.goToSeriesEpisode(1)
        scheduler.runCurrent()
        val other = profile.copy(name = "Other", macAddress = "00:00:00:00:00:02")
        mutable.value = mutable.value.copy(savedProfile = other, session = session.copy(profile = other),
            items = listOf(episode(77)), episodeDestination = null)
        reply.complete(EpisodeSeasonResult(listOf(episode(1)), listOf(1), 1, 2, false))
        scheduler.runCurrent()
        assertEquals(listOf("e77"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.episodeDestination)
        assertNull(vm.state.value.error)
    }

    @Test fun cancellationCannotPublishOrOverrideANewerCachedJump() {
        val reply = CompletableDeferred<EpisodeSeasonResult>()
        vm.episodePageLookup = { _, _, _, _, _ -> withContext(NonCancellable) { reply.await() } }
        vm.goToSeriesEpisode(1)
        scheduler.runCurrent()
        vm.cancelEpisodeDestination()
        vm.goToSeriesEpisode(490)
        scheduler.runCurrent()
        assertEquals("Cached jumps must not wait for a canceled provider request to finish", "e490", vm.state.value.episodeDestination?.focusId)
        reply.complete(EpisodeSeasonResult(listOf(episode(1)), listOf(1), 1, 2, false))
        await { vm.state.value.episodeDestination?.focusId == "e490" }
        assertEquals(20, vm.state.value.items.size)
        assertNull(vm.state.value.error)
    }

    @Test fun repeatedProviderPageDoesNotPretendTheEarliestLoadedEpisodeIsTheFirst() {
        vm.episodePageLookup = { _, _, _, page, _ ->
            EpisodeSeasonResult((481..500).map(::episode), listOf(1), 1, page, true)
        }
        vm.goToSeriesEpisode()
        await { vm.state.value.episodeDestination?.running == false }
        assertNull(vm.state.value.episodeDestination?.focusId)
        assertTrue(vm.state.value.episodeDestination!!.message!!.contains("repeated"))
        assertTrue(vm.state.value.episodeHasMore)
        assertEquals(20, vm.state.value.items.size)
    }

    @Test fun scrollPaginationIsDeduplicatedBeforeCoroutineDispatch() {
        val reply = CompletableDeferred<EpisodeSeasonResult>()
        var calls = 0
        vm.episodePageLookup = { _, _, _, _, _ -> calls++; reply.await() }
        vm.loadMoreEpisodes(); vm.loadMoreEpisodes()
        scheduler.runCurrent()
        assertEquals(1, calls)
        reply.complete(EpisodeSeasonResult((461..480).map(::episode), listOf(1), 1, 2, true))
        await { !vm.state.value.episodeLoadingMore }
        assertEquals(40, vm.state.value.items.size)
    }
}
