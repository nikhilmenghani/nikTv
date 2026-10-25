package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real movie lookup entry points backed by a local synthetic catalogue; no provider requests. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TmdbMovieLookupViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private lateinit var store: ProfileStore
    private val profile = PortalProfile("Fixture", "https://lookup.invalid", "", portalType = PortalType.XTREAM)
    private val movie = TmdbMovie(123, "Spider-Man: Brand New Day", "Spider-Man: Brand New Day",
        null, null, null, "2026-07-29", null)
    private val versions = listOf("Kannada", "Turkish", "English", "Hindi", "Tamil", "Telugu").mapIndexed { i, language ->
        MediaItem("version-$i", if (i == 0) "Spider Man Brand New Day (2026) ($language)"
            else "Spider-Man: Brand New Day (2026) ($language)", null, null)
    }

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren()
        scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val mutable = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        state = mutable
        store = NikTvViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.get(vm) as ProfileStore
        state.value = NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://lookup.invalid", "", "", ""))
        cache(versions)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun cache(items: List<MediaItem>) = runBlocking {
        store.saveSearchCatalog(SearchCatalogCache(profile.cacheKey(), CatalogType.MOVIES, 1, items), scheduleMetadataSync = false)
    }
    private fun awaitPicker(expected: Set<String>) {
        val end = System.nanoTime() + 8_000_000_000L
        while (vm.state.value.movieMatchSelection == null && System.nanoTime() < end) {
            scheduler.runCurrent(); Thread.sleep(10)
        }
        assertEquals(movie.id, vm.state.value.movieMatchSelection?.id)
        assertEquals(expected, vm.state.value.movieMatchCandidates.map { it.id }.toSet())
        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.error)
    }
    @Test fun xtreamRetainsEveryLanguageInsteadOfAutomaticallyChoosingKannada() {
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(versions.map { it.id }.toSet())
    }
    @Test fun allTwentyCachedVariantsAreVisibleWithoutAResultCap() {
        val many = (1..20).map { MediaItem("many-$it", "Spider-Man: Brand New Day (2026) Version $it", null, null) }
        cache(many)
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(many.map { it.id }.toSet())
    }
    @Test fun authoritativeProviderIdStillRetainsEveryVariant() {
        cache(versions.map { it.copy(externalTmdbId = movie.id) })
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(versions.map { it.id }.toSet())
    }
    @Test fun previousAutomaticMappingCannotHideOtherVersions() {
        runBlocking { store.saveTmdbMapping(TmdbIptvMapping(profile.cacheKey(), CatalogType.MOVIES, movie.id,
            versions.first().copy(externalTmdbId = movie.id), playbackVerified = true)) }
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(versions.map { it.id }.toSet())
    }
    @Test fun explicitChooseVersionBypassesRememberedChoiceAndPartialPrefetchWithoutDeletingIt() {
        val mapping = TmdbIptvMapping(profile.cacheKey(), CatalogType.MOVIES, movie.id,
            versions.first(), confirmedByUser = true, playbackVerified = true)
        runBlocking { store.saveTmdbMapping(mapping) }
        state.value = state.value.copy(modernTmdbMovieMatches = mapOf(movie.id to listOf(versions.first())))
        vm.chooseTmdbMovieVersion(TrendingMovie(movie))
        awaitPicker(versions.map { it.id }.toSet())
        vm.closeTmdbMovieMatches()
        assertNull(vm.state.value.movieMatchSelection)
        assertEquals(mapping, runBlocking { store.tmdbMappings.first() }.first { it.key == mapping.key })
        assertNull(vm.state.value.nowPlaying)
    }
    @Test fun explicitChooseVersionShowsEvenASingleResultWithoutStartingPlayback() {
        cache(listOf(versions.first()))
        vm.chooseTmdbMovieVersion(TrendingMovie(movie))
        awaitPicker(setOf(versions.first().id))
    }
}
