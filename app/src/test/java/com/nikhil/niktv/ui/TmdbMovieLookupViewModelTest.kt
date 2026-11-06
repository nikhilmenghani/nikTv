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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/** Real movie lookup entry points backed by a local synthetic catalogue; no provider requests. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TmdbMovieLookupViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private lateinit var store: ProfileStore
    private var provider: HttpServer? = null
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
    @After fun cleanup() { vm.viewModelScope.cancel(); scheduler.runCurrent(); provider?.stop(0); Dispatchers.resetMain() }
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

    private fun stalkerFixture(categoryHasMovie: Boolean, rowsOverride: String? = null): Pair<PortalProfile, MutableList<String>> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal") { exchange ->
            val params = exchange.requestURI.rawQuery.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            val category = params["category"].orEmpty()
            requests += category
            val rows = if (categoryHasMovie && category == "hindi") rowsOverride ?: versions.take(2).joinToString(",") {
                """{"id":"${it.id}","name":"${it.title}","is_series":"0","category_id":"hindi"}"""
            } else ""
            val body = """{"js":{"data":[$rows],"max_page":1,"total_items":${if (rows.isEmpty()) 0 else 2}}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        provider = server
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/portal"
        val stalker = PortalProfile("Stalker fixture", url, "00:00:00:00:00:01", portalType = PortalType.STALKER)
        state.value = NikTvState(savedProfile = stalker, restoring = false,
            session = PortalSession(stalker, "fixture", url, "", "", ""),
            rawCategoriesByType = mapOf(CatalogType.MOVIES to listOf(Category("hindi", "Hindi movies", CatalogType.MOVIES))))
        return stalker to requests
    }

    @Test fun stalkerVersionLookupFindsConcreteCategoryAfterWildcardMiss() {
        val (_, requests) = stalkerFixture(categoryHasMovie = true)
        vm.chooseTmdbMovieVersion(TrendingMovie(movie))
        awaitPicker(versions.take(2).map { it.id }.toSet())
        assertEquals(listOf("*", "hindi"), requests.toList())
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }

    @Test fun stalkerVersionLookupReusesManualSearchResultsFromTheSameProfile() {
        val (stalker, requests) = stalkerFixture(categoryHasMovie = false)
        runBlocking {
            store.savePagedSearch(SearchResultCache(stalker.cacheKey(), SearchContentType.MOVIES,
                "Spider Man", lastPage = 1, hasMore = false, items = versions.take(2)))
            store.savePagedSearch(SearchResultCache(profile.cacheKey(), SearchContentType.MOVIES,
                "Spider Man", lastPage = 1, hasMore = false,
                items = listOf(versions.first().copy(id = "other-profile"))))
        }
        vm.chooseTmdbMovieVersion(TrendingMovie(movie))
        awaitPicker(versions.take(2).map { it.id }.toSet())
        assertEquals(listOf("*"), requests.toList())
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }

    @Test fun stalkerLookupAlsoUsesProviderTmdbIdentityFromSavedCatalogue() {
        val (stalker, requests) = stalkerFixture(categoryHasMovie = false)
        val identified = versions.first().copy(title = "Provider localized title", externalTmdbId = movie.id)
        runBlocking {
            store.saveSearchCatalog(SearchCatalogCache(stalker.cacheKey(), CatalogType.MOVIES, 1,
                listOf(identified)), scheduleMetadataSync = false)
        }
        vm.chooseTmdbMovieVersion(TrendingMovie(movie))
        awaitPicker(setOf(identified.id))
        assertEquals(listOf("*"), requests.toList())
    }

    @Test fun explicitMatchRefreshUsesTheSameCategoryFallbackAsPlay() {
        val (_, requests) = stalkerFixture(categoryHasMovie = true)
        state.value = state.value.copy(modernTmdbSection = TmdbHomeSection.TRENDING_MOVIES,
            modernTmdbMovies = listOf(TrendingMovie(movie)))
        vm.refreshTmdbMatches(movie.id, CatalogType.MOVIES)
        val end = System.nanoTime() + 8_000_000_000L
        while (vm.state.value.modernTmdbMovieMatches[movie.id].isNullOrEmpty() && System.nanoTime() < end) {
            scheduler.runCurrent(); Thread.sleep(10)
        }
        assertEquals(versions.take(2).map { it.id }.toSet(),
            vm.state.value.modernTmdbMovieMatches[movie.id]?.map { it.id }?.toSet())
        assertEquals(listOf("*", "hindi"), requests.toList())
        assertNull(vm.state.value.tmdbIptvLookupMiss)
        assertNull(vm.state.value.error)
    }

    @Test fun playOffersSingleTmdbRejectedTitleInsteadOfAutoplayOrNotFound() {
        val decorated = "EN | ${movie.title} (2025) Hindi 4K"
        stalkerFixture(true, """{"id":"review","name":"$decorated","is_movie":true,"is_series":"0"}""")
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(setOf("review"))
        assertEquals(decorated, vm.state.value.movieMatchCandidates.single().title)
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }

    @Test fun contradictoryStalkerVodFlagsRequireUserSelectionEvenWithExactTitle() {
        stalkerFixture(true, """{"id":"review","name":"${movie.title}","is_movie":true,"is_series":"1","episode_id":"0"}""")
        vm.openTrendingMovie(TrendingMovie(movie))
        awaitPicker(setOf("review"))
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }

    @Test fun refreshOffersUncertainResultWithoutSavingANegativeAvailabilityEntry() {
        val (stalker, _) = stalkerFixture(true,
            """{"id":"review","name":"EN | ${movie.title} Hindi","is_movie":true,"is_series":"0"}""")
        val availability = NikTvViewModel::class.java.getDeclaredField("tmdbAvailability")
            .apply { isAccessible = true }.get(vm) as TmdbAvailabilityRepository
        runBlocking { availability.save(stalker.cacheKey(), CatalogType.MOVIES, movie.id,
            TmdbAvailabilityEntry(System.currentTimeMillis(), emptyList(), matchingVersion = 4)) }
        state.value = state.value.copy(modernTmdbSection = TmdbHomeSection.TRENDING_MOVIES,
            modernTmdbMovies = listOf(TrendingMovie(movie)))
        vm.refreshTmdbMatches(movie.id, CatalogType.MOVIES)
        awaitPicker(setOf("review"))
        assertNull(runBlocking { availability.read(stalker.cacheKey(), CatalogType.MOVIES).entries[movie.id] })
        assertFalse(vm.state.value.modernTmdbMovieMatches.containsKey(movie.id))
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }
}
