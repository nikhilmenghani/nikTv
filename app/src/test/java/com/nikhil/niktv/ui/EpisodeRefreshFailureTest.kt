package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.data.TmdbSeries
import com.nikhil.niktv.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class EpisodeRefreshFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var storeScope: CoroutineScope
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private lateinit var server: HttpServer
    private val providerRequests = AtomicInteger()
    private val response = CompletableDeferred<List<TmdbEpisode>>()
    private var metadataRequested = false
    private val episode = MediaItem("episode", "Episode 1", null, "fixture-command", seasonNumber = 1,
        episodeNumber = 1, description = "Provider description")
    private val metadata = TmdbEpisode(1, 1, "Pilot", "TMDB synopsis", "1994-09-22", "https://fixture.invalid/still.jpg")

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/player_api.php") { exchange ->
                providerRequests.incrementAndGet()
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { temporary.newFolder().resolve("episode-refresh.preferences_pb") })
        NikTvViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }
            .set(vm, ProfileStore(RuntimeEnvironment.getApplication(), preferences))
        @Suppress("UNCHECKED_CAST")
        state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        val profile = PortalProfile("Fixture", "http://127.0.0.1:${server.address.port}", "",
            portalType = PortalType.XTREAM, username = "fixture", password = "fixture")
        state.value = NikTvState(savedProfile = profile,
            session = PortalSession(profile, "", profile.portalUrl, "", "", ""),
            selectedType = CatalogType.SERIES, selectedSeries = MediaItem("series", "Friends", null, null),
            selectedSeriesSeason = 1, availableSeriesSeasons = listOf(1), items = listOf(episode), restoring = false)
        vm.episodeTmdbSeriesLookup = { TmdbSeries(1668, "Friends", "Friends", null, null, null, "1994-09-22", null) }
        vm.episodeTmdbSeasonsLookup = { listOf(1) }
        vm.episodeTmdbSeasonLookup = { id, season ->
            assertEquals(1668, id)
            assertEquals(1, season)
            metadataRequested = true
            response.await()
        }
    }

    @After fun teardown() {
        vm.viewModelScope.cancel()
        response.cancel()
        server.stop(0)
        scheduler.runCurrent()
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Metadata operation did not finish")
    }

    private fun startRefresh() {
        vm.forceRefreshTmdbEpisodeCache()
        await { metadataRequested }
        assertTrue(state.value.tmdbEpisodeCacheRefreshing)
    }

    private fun finish() = await { !state.value.tmdbEpisodeCacheRefreshing }

    @Test fun provider404DoesNotBlockTmdbOrChangePlaybackIdentity() {
        startRefresh()
        response.complete(listOf(metadata))
        finish()
        assertNull(state.value.error)
        assertEquals(0, providerRequests.get())
        val actual = state.value.items.single()
        assertEquals("Pilot", actual.title)
        assertEquals("TMDB synopsis", actual.description)
        assertEquals(metadata.stillUrl, actual.logo)
        assertEquals(episode.id, actual.id)
        assertEquals(episode.command, actual.command)
        vm.setUseTmdbEpisodeMetadata(false)
        assertEquals(listOf(episode), state.value.items)
    }

    @Test fun tmdbFailureDoesNotCrashOrEraseEpisodes() {
        startRefresh()
        response.completeExceptionally(IllegalStateException("TMDB returned HTTP 404"))
        finish()
        assertTrue(state.value.error.orEmpty().contains("HTTP 404"))
        assertEquals(listOf(episode), state.value.items)
        assertEquals(0, providerRequests.get())
    }

    @Test fun oldSeriesFailureCannotReplaceNewScreenError() {
        startRefresh()
        state.value = state.value.copy(selectedSeries = MediaItem("other", "Other", null, null), error = "New screen")
        response.completeExceptionally(IllegalStateException("TMDB unavailable"))
        finish()
        assertEquals("New screen", state.value.error)
    }

    @Test fun cancellationClearsSpinnerWithoutShowingError() {
        startRefresh()
        vm.viewModelScope.coroutineContext.cancelChildren()
        finish()
        assertNull(state.value.error)
        assertEquals(listOf(episode), state.value.items)
    }

    @Test fun noMatchesPreserveProviderDetailsAndExplainResult() {
        startRefresh()
        response.complete(emptyList())
        finish()
        assertTrue(state.value.error.orEmpty().contains("no matching episode details"))
        assertEquals(listOf(episode), state.value.items)
    }

    @Test fun oldProfileSuccessCannotChangeCurrentEpisodes() {
        startRefresh()
        val other = state.value.session!!.profile.copy(username = "other")
        state.value = state.value.copy(session = state.value.session!!.copy(profile = other))
        response.complete(listOf(metadata))
        finish()
        assertEquals(listOf(episode), state.value.items)
    }

    @Test fun partialTmdbResponseKeepsExistingDetailsForUnmatchedEpisodes() {
        val second = episode.copy(id = "second", title = "Retained title", episodeNumber = 2,
            description = "Retained description", logo = "https://fixture.invalid/retained.jpg")
        state.value = state.value.copy(items = listOf(episode, second))
        startRefresh()
        response.complete(listOf(metadata))
        finish()
        assertEquals("Pilot", state.value.items.first().title)
        assertEquals(second, state.value.items.last())
    }

    @Test fun airDateMatchesDetailsWithoutChangingProviderOrdinalOrPlayableId() {
        state.value = state.value.copy(items = listOf(episode.copy(episodeNumber = 2, episodeAirDate = metadata.airDate)))
        startRefresh()
        response.complete(listOf(metadata))
        finish()
        assertEquals("Pilot", state.value.items.single().title)
        assertEquals(2, state.value.items.single().episodeNumber)
        assertEquals(episode.id, state.value.items.single().id)
        assertEquals(episode.command, state.value.items.single().command)
    }

    @Test fun providerSeasonGroupingMapsToSingleTmdbSeasonAndRetainsQueueIdentity() {
        val grouped = episode.copy(seasonNumber = 49, episodeNumber = 4850, title = "Series S49E4850")
        state.value = state.value.copy(selectedSeriesSeason = 49, availableSeriesSeasons = (1..49).toList(),
            items = listOf(grouped))
        startRefresh()
        response.complete(listOf(metadata.copy(episodeNumber = 4850, name = "Canonical episode 4850")))
        finish()
        assertNull(state.value.error)
        val actual = state.value.items.single()
        assertEquals("Canonical episode 4850", actual.title)
        assertEquals(49, actual.seasonNumber)
        assertEquals(4850, actual.episodeNumber)
        assertEquals(49, state.value.selectedSeriesSeason)
        assertEquals(grouped.id, actual.id)
        assertEquals(grouped.command, actual.command)
        assertEquals(0, providerRequests.get())
    }

    @Test fun singleEpisodeRefreshUsesOnlyOneIndividualRecordAndPreservesOtherRows() {
        val second = episode.copy(id = "second", episodeNumber = 2, title = "Other episode")
        state.value = state.value.copy(items = listOf(episode, second))
        var calls = 0
        vm.episodeTmdbSeasonLookup = { _, _ -> error("A single-episode retry must not fetch the season") }
        vm.episodeTmdbEpisodeLookup = { id, season, number ->
            assertEquals(1668, id); assertEquals(1, season); assertEquals(1, number)
            calls++; metadata
        }
        vm.refreshEpisodeTmdb(episode)
        await { state.value.episodeTmdbRefresh?.running == false }
        assertEquals(1, calls)
        assertEquals("Pilot", state.value.items.first().title)
        assertEquals(second, state.value.items.last())
        assertEquals(episode.command, state.value.items.first().command)
        assertEquals(0, providerRequests.get())
        assertNull(state.value.error)
        assertTrue(state.value.episodeTmdbRefresh!!.message!!.contains("description"))
    }

    @Test fun singleSparseRecordExplainsMissingFieldsAndKeepsMeaningfulProviderTitle() {
        val named = episode.copy(title = "A meaningful provider title")
        state.value = state.value.copy(items = listOf(named))
        vm.episodeTmdbEpisodeLookup = { _, _, _ -> metadata.copy(name = "Episode 1", overview = null, stillUrl = null) }
        vm.refreshEpisodeTmdb(named)
        await { state.value.episodeTmdbRefresh?.running == false }
        assertEquals(named.title, state.value.items.single().title)
        assertEquals(named.description, state.value.items.single().description)
        assertTrue(state.value.episodeTmdbRefresh!!.message!!.contains("no descriptive title"))
    }

    @Test fun newerSingleEpisodeRetryNeverWaitsForOrReceivesAnObsoleteResult() {
        val second = episode.copy(id = "second", episodeNumber = 2, title = "Other episode")
        state.value = state.value.copy(items = listOf(episode, second))
        val late = CompletableDeferred<TmdbEpisode?>()
        var firstStarted = false
        vm.episodeTmdbEpisodeLookup = { _, _, number ->
            if (number == 1) { firstStarted = true; withContext(NonCancellable) { late.await() } }
            else metadata.copy(episodeNumber = 2, name = "Second updated")
        }
        vm.refreshEpisodeTmdb(episode)
        await { firstStarted }
        vm.refreshEpisodeTmdb(second)
        await { state.value.episodeTmdbRefresh?.episode == second.id && state.value.episodeTmdbRefresh?.running == false }
        late.complete(metadata)
        scheduler.runCurrent()
        assertEquals(episode, state.value.items.first())
        assertEquals("Second updated", state.value.items.last().title)
        assertEquals(second.id, state.value.episodeTmdbRefresh?.episode)
    }

    @Test fun individualFailureShowsLocalResultWithoutPortalDiagnosticsOrDataLoss() {
        vm.episodeTmdbEpisodeLookup = { _, _, _ -> error("TMDB HTTP 404") }
        vm.refreshEpisodeTmdb(episode)
        await { state.value.episodeTmdbRefresh?.running == false }
        assertNull(state.value.error)
        assertEquals(listOf(episode), state.value.items)
        assertTrue(state.value.episodeTmdbRefresh!!.message!!.contains("Existing details were kept"))
        vm.dismissEpisodeTmdbRefresh()
        assertNull(state.value.episodeTmdbRefresh)
    }

    @Test fun slowerSeasonRefreshCannotOverwriteTheNewerIndividualEpisodeResult() {
        startRefresh()
        vm.episodeTmdbEpisodeLookup = { _, _, _ -> metadata.copy(name = "New individual result") }
        vm.refreshEpisodeTmdb(episode)
        await { state.value.episodeTmdbRefresh?.running == false }
        response.complete(listOf(metadata.copy(name = "Older season result")))
        finish()
        assertEquals("New individual result", state.value.items.single().title)
    }

    @Test fun individualResultFromAnOldProfileIsDiscarded() {
        val delayed = CompletableDeferred<TmdbEpisode?>()
        var started = false
        vm.episodeTmdbEpisodeLookup = { _, _, _ -> started = true; delayed.await() }
        vm.refreshEpisodeTmdb(episode)
        await { started }
        val other = state.value.session!!.profile.copy(username = "another-profile")
        state.value = state.value.copy(session = state.value.session!!.copy(profile = other))
        delayed.complete(metadata)
        await { state.value.episodeTmdbRefresh == null }
        assertEquals(listOf(episode), state.value.items)
        assertNull(state.value.error)
    }

    @Test fun openingFreshAlreadyCheckedCacheAutomaticallyRestoresProviderTitleWithoutNetwork() {
        val raw = episode.copy(title = "Episode 1. 1994-09-22 - Provider pilot")
        val cache = EpisodeSeasonCache(state.value.session!!.profile.cacheKey(), "series", 1, listOf(1),
            listOf(raw.copy(title = "Episode 1")), metadataVersion = 4, iptvEpisodes = listOf(raw),
            tmdbEpisodeKeys = setOf("already checked"), tmdbCachedAtMillis = System.currentTimeMillis(), seasonIndexValidated = true)
        NikTvViewModel::class.java.getDeclaredField("episodeSeasonCaches").apply { isAccessible = true }
            .set(vm, listOf(cache))
        vm.episodeTmdbSeriesLookup = { error("Cache repair must not request TMDB") }
        vm.loadSeriesSeason(1)
        await { !state.value.loading && state.value.items.singleOrNull()?.title == raw.title }
        assertEquals(0, providerRequests.get())
        assertNull(state.value.error)
        vm.setUseTmdbEpisodeMetadata(false)
        assertEquals(raw.title, state.value.items.single().title)
    }
}
