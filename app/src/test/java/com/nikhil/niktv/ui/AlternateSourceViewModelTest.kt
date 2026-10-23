package com.nikhil.niktv.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.data.AlternatePlaybackRoute
import com.nikhil.niktv.data.AlternateSourceProvider
import com.nikhil.niktv.data.vidyFallbackStream
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.ParentalControls
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Real ViewModel entry points with synthetic streams; no portal or source transport is called. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AlternateSourceViewModelTest {
    @Test fun iptvMissDoesNotLookUpExternalSourcesUntilChosenAndCanBeDismissed() {
        mutableState.value = mutableState.value.copy(items = listOf(movie))
        vm.showTmdbIptvLookupMiss(AlternateSourceRequest(movie, CatalogType.MOVIES), profile.cacheKey())
        scheduler.runCurrent()
        assertNotNull(vm.state.value.tmdbIptvLookupMiss)
        assertNull(vm.state.value.alternateSources)
        assertNull(vm.state.value.error)
        vm.dismissTmdbIptvLookupMiss()
        assertNull(vm.state.value.tmdbIptvLookupMiss)
        assertEquals(listOf(movie), vm.state.value.items)
    }

    @Test fun iptvMovieMissOpensExistingNativePickerWithoutStartingPlayback() {
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { calls += it; listOf(stream("external")) }
        vm.showTmdbIptvLookupMiss(AlternateSourceRequest(movie, CatalogType.MOVIES), profile.cacheKey())
        vm.findAlternateSourcesAfterIptvMiss()
        scheduler.runCurrent()
        assertNull(vm.state.value.tmdbIptvLookupMiss)
        assertEquals(12, calls.single().tmdbId)
        assertEquals("external", vm.state.value.alternateSources!!.streams.single().id)
        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.session)
    }

    @Test fun iptvSeriesMissLetsUserChooseEpisodeBeforeNetworkLookup() {
        vm.showTmdbIptvLookupMiss(AlternateSourceRequest(series, CatalogType.SERIES), profile.cacheKey())
        vm.findAlternateSourcesAfterIptvMiss()
        scheduler.runCurrent()
        assertTrue(vm.state.value.alternateSources!!.target.series)
        assertEquals(21, vm.state.value.alternateSources!!.target.tmdbId)
        assertFalse(vm.state.value.alternateSources!!.loading)
    }

    @Test fun iptvEpisodeMissPreservesSeasonAndEpisode() {
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { calls += it; listOf(stream("episode")) }
        val episode = series.copy(title = "Episode nine", seasonNumber = 3, episodeNumber = 9)
        vm.showTmdbIptvLookupMiss(AlternateSourceRequest(episode, CatalogType.SERIES, series, 3, 9), profile.cacheKey())
        vm.findAlternateSourcesAfterIptvMiss()
        scheduler.runCurrent()
        val target = calls.single()
        assertEquals(series.title, target.title)
        assertEquals(21, target.tmdbId)
        assertEquals(3, target.season)
        assertEquals(9, target.episode)
    }

    @Test fun obsoleteProfileAndPlaybackRequestsCannotPublishOrUseMissingMatchActions() {
        val request = AlternateSourceRequest(movie, CatalogType.MOVIES)
        vm.showTmdbIptvLookupMiss(request, "other-profile")
        assertNull(vm.state.value.tmdbIptvLookupMiss)
        vm.showTmdbIptvLookupMiss(request, profile.cacheKey(), Long.MIN_VALUE)
        assertNull(vm.state.value.tmdbIptvLookupMiss)
        vm.showTmdbIptvLookupMiss(request, profile.cacheKey())
        mutableState.value = mutableState.value.copy(savedProfile = profile.copy(name = "Other", portalUrl = "https://other.invalid"))
        vm.findAlternateSourcesAfterIptvMiss()
        scheduler.runCurrent()
        assertNull(vm.state.value.alternateSources)
        assertNull(vm.state.value.tmdbIptvLookupMiss)
    }

    @Test fun externalFallbackRetainsParentalChecks() {
        vm.showTmdbIptvLookupMiss(AlternateSourceRequest(movie.copy(isAdult = true), CatalogType.MOVIES), profile.cacheKey())
        vm.findAlternateSourcesAfterIptvMiss()
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertNull(vm.state.value.alternateSources)
    }

    @Test fun dismissingRecommendationRemovesOnlyThatProfilesRowImmediately() {
        val row = com.nikhil.niktv.data.HistoryRecommendationRow("MOVIES:12", "Because you chose Fixture", profile = profile.cacheKey())
        val other = row.copy(profile = "other-profile")
        mutableState.value = mutableState.value.copy(session = PortalSession(profile, "fixture", "https://provider.invalid", "fixture", "fixture", "fixture"),
            historyRecommendations = listOf(row, other))
        vm.dismissRecommendation(other)
        assertEquals(2, vm.state.value.historyRecommendations.size)
        vm.dismissRecommendation(row)
        assertEquals(listOf(other), vm.state.value.historyRecommendations)
    }
    private val scheduler = TestCoroutineScheduler()
    private val pending = mutableListOf<CompletableDeferred<List<AlternateStream>>>()
    private lateinit var vm: NikTvViewModel
    private lateinit var mutableState: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://provider.invalid", "00:00:00:00:00:00")
    private val movie = MediaItem("provider-movie:12", "Fixture movie", null, "provider-command-fixture", externalTmdbId = 12)
    private val series = MediaItem("provider-series:21", "Fixture series", null, null, externalTmdbId = 21)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("parental_controls", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(app)
        // Constructor collectors/profile preparation are unrelated to these entry points.
        // Cancel them before the test scheduler executes, retaining the live ViewModel scope.
        vm.viewModelScope.coroutineContext.cancelChildren()
        scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val field = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        mutableState = field
        mutableState.value = NikTvState(savedProfile = profile, session = null, restoring = false)
        vm.alternateSourceLookup = { error("Unexpected alternate source lookup") }
    }

    @After fun tearDown() {
        pending.forEach { it.complete(emptyList()) }
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun lookupAndSelectionPlayWithoutAPortalSessionAndPreserveProviderDataAndMappings() {
        val category = Category("movies", "Fixture movies", CatalogType.MOVIES)
        val cache = BrowseCatalogCache(profile.cacheKey(), CatalogType.MOVIES, 1L,
            listOf(category), mapOf(category.id to listOf(movie)))
        val mapping = TmdbIptvMapping(profile.cacheKey(), CatalogType.MOVIES, 12, movie,
            confirmedByUser = true, playbackVerified = true)
        val store = NikTvViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.get(vm) as ProfileStore
        runBlocking { store.saveTmdbMapping(mapping) }
        mutableState.value = mutableState.value.copy(items = listOf(movie), selectedCategory = category,
            browseCachesByType = mapOf(CatalogType.MOVIES to cache))
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { target -> calls += target; listOf(stream("high")) }

        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        assertEquals(1, calls.size)
        assertNull(vm.state.value.nowPlaying)
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()

        val playing = vm.state.value.nowPlaying!!
        assertNull(vm.state.value.session)
        assertEquals(stream("high").url, playing.url)
        assertEquals(stream("high").headers, playing.streamRequestHeaders)
        assertSame(movie, playing.media)
        assertNotNull(playing.alternateSource)
        assertEquals(listOf(movie), vm.state.value.items)
        assertEquals(cache, vm.state.value.browseCachesByType[CatalogType.MOVIES])
        assertEquals(listOf(mapping), runBlocking { store.tmdbMappings.first() })
        assertNull(PlayingMedia(movie, "https://fixture.invalid/normal.m3u8", CatalogType.MOVIES).alternateSource)
    }

    @Test fun obsoleteLookupCannotReplaceNewerUserSelectionEvenWhenCancellationIsIgnored() {
        val delayed = deferred()
        val old = movie.copy(id = "old", title = "Old fixture")
        val newer = movie.copy(id = "new", title = "New fixture")
        vm.alternateSourceLookup = { target ->
            if (target.title == old.title) withContext(NonCancellable) { delayed.await() }
            else listOf(stream("new"))
        }
        vm.openAlternateSources(AlternateSourceRequest(old, CatalogType.MOVIES))
        scheduler.runCurrent()
        vm.openAlternateSources(AlternateSourceRequest(newer, CatalogType.MOVIES))
        scheduler.runCurrent()
        delayed.complete(listOf(stream("obsolete")))
        scheduler.runCurrent()

        assertEquals(newer, vm.state.value.alternateSources?.request?.item)
        assertEquals(listOf(stream("new")), vm.state.value.alternateSources?.streams)
        assertNull(vm.state.value.nowPlaying)
    }

    @Test fun profileChangeRejectsLateLookupWithoutPublishingIntoTheNewProfile() {
        val delayed = deferred()
        vm.alternateSourceLookup = { withContext(NonCancellable) { delayed.await() } }
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        mutableState.value = mutableState.value.copy(savedProfile = profile.copy(name = "Other", portalUrl = "https://other.invalid"),
            alternateSources = null)
        delayed.complete(listOf(stream("obsolete")))
        scheduler.runCurrent()

        assertNull(vm.state.value.alternateSources)
        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.error)
    }

    @Test fun failedLookupLeavesTheActivePlaybackSessionUnchanged() {
        val active = PlayingMedia(movie, "https://fixture.invalid/active.m3u8", CatalogType.MOVIES,
            resumePositionMillis = 18_000, playWhenReady = false)
        mutableState.value = mutableState.value.copy(nowPlaying = active)
        vm.alternateSourceLookup = { throw java.io.IOException("Fixture lookup unavailable") }

        vm.openAlternateSources(AlternateSourceRequest(movie.copy(id = "other"), CatalogType.MOVIES))
        scheduler.runCurrent()

        assertSame(active, vm.state.value.nowPlaying)
        assertEquals("Fixture lookup unavailable", vm.state.value.alternateSources?.error)
        assertFalse(vm.state.value.alternateSources!!.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun aNewBrowseSelectionReplacesThePreviousPlayersStaleReturnFocus() {
        vm.alternateSourceLookup = { listOf(stream("high")) }
        startMovie()
        assertEquals(movie.id, vm.state.value.playbackReturnFocusId)
        vm.closePlayer()
        assertNull(vm.state.value.nowPlaying)
        assertEquals(movie.id, vm.state.value.playbackReturnFocusId)
        val next = movie.copy(id = "provider-movie:13", title = "Another fixture movie", externalTmdbId = 13)

        vm.openAlternateSources(AlternateSourceRequest(next, CatalogType.MOVIES))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()

        assertEquals(next.id, vm.state.value.playbackReturnFocusId)
        assertEquals(next, vm.state.value.nowPlaying?.media)
        assertNull(vm.state.value.error)
    }

    @Test fun retryUsesAlternateLookupAndKeepsPositionIdentityAndPauseMadeWhileResolving() {
        val calls = mutableListOf<AlternateSourceTarget>()
        val delayed = deferred()
        vm.alternateSourceLookup = { target ->
            calls += target
            if (calls.size == 1) listOf(stream("high")) else delayed.await()
        }
        startMovie()
        val playing = vm.state.value.nowPlaying!!

        vm.retryPlayback(68_000, playing)
        scheduler.runCurrent()
        vm.setPlaybackIntent(playing.playbackAttemptId, false)
        delayed.complete(listOf(stream("high", "renewed")))
        scheduler.runCurrent()

        val retried = vm.state.value.nowPlaying!!
        assertEquals(2, calls.size)
        assertEquals(calls.first(), calls.last())
        assertEquals(68_000L, retried.resumePositionMillis)
        assertEquals(playing.progressKey, retried.progressKey)
        assertEquals(stream("high", "renewed").url, retried.url)
        assertFalse(retried.playWhenReady)
        assertNotEquals(playing.playbackAttemptId, retried.playbackAttemptId)
        assertNull(vm.state.value.error)
    }

    @Test fun alternateNextAndPreviousResolveExactEpisodesWithoutCallingAPortal() {
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { target -> calls += target; listOf(stream("high", "episode-${target.episode}")) }
        val episode = series.copy(id = "provider-episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, series, 2, 3))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()
        val originalKey = vm.state.value.nowPlaying!!.progressKey
        assertEquals(episode.id, vm.state.value.playbackReturnFocusId)

        vm.playNextEpisode()
        scheduler.runCurrent()
        assertEquals(listOf(3, 4), calls.map { it.episode })
        assertTrue(calls.all { it.season == 2 && it.tmdbId == 21 && it.title == series.title })
        assertEquals(4, vm.state.value.nowPlaying?.media?.episodeNumber)
        assertEquals(0L, vm.state.value.nowPlaying?.resumePositionMillis)
        assertNotEquals(originalKey, vm.state.value.nowPlaying?.progressKey)
        assertEquals(episode.id, vm.state.value.playbackReturnFocusId)

        vm.playPreviousEpisode()
        scheduler.runCurrent()
        assertEquals(listOf(3, 4, 3), calls.map { it.episode })
        assertEquals(originalKey, vm.state.value.nowPlaying?.progressKey)
        assertEquals(episode.id, vm.state.value.playbackReturnFocusId)
        assertNull(vm.state.value.session)
        assertNull(vm.state.value.error)
    }

    @Test fun switchingQualityKeepsCurrentPositionStableProgressKeyAndPauseIntentWithoutRefetch() {
        var lookups = 0
        vm.alternateSourceLookup = { lookups++; listOf(stream("high"), stream("low")) }
        startMovie()
        mutableState.value = mutableState.value.copy(playbackReturnFocusId = "original-browse-origin")
        val playing = vm.state.value.nowPlaying!!
        vm.setPlaybackIntent(playing.playbackAttemptId, false)
        val paused = vm.state.value.nowPlaying!!
        vm.openPlayingAlternateSources(42_000, paused) { 42_000L }
        vm.chooseAlternateStream("low")
        scheduler.runCurrent()

        val lower = vm.state.value.nowPlaying!!
        assertEquals(1, lookups)
        assertEquals(stream("low").url, lower.url)
        assertEquals(42_000L, lower.resumePositionMillis)
        assertEquals(paused.progressKey, lower.progressKey)
        assertSame(paused.media, lower.media)
        assertEquals("original-browse-origin", vm.state.value.playbackReturnFocusId)
        assertFalse(lower.playWhenReady)
        assertNull(vm.state.value.error)
    }

    @Test fun qualitySelectionReadsTheCurrentPlayerPositionAfterTheDialogWaitAndKeepsALaterPause() {
        vm.alternateSourceLookup = { listOf(stream("high"), stream("low")) }
        startMovie()
        val playing = vm.state.value.nowPlaying!!
        var currentPosition = 42_000L
        var positionReads = 0
        vm.openPlayingAlternateSources(currentPosition, playing) { positionReads++; currentPosition }
        assertEquals(0, positionReads)

        // The old decoder continues playing while the source picker is open.
        currentPosition = 67_000L
        vm.setPlaybackIntent(playing.playbackAttemptId, false)
        vm.chooseAlternateStream("low")
        scheduler.runCurrent()

        assertEquals(1, positionReads)
        assertEquals(67_000L, vm.state.value.nowPlaying?.resumePositionMillis)
        assertFalse(vm.state.value.nowPlaying!!.playWhenReady)
        assertEquals(playing.progressKey, vm.state.value.nowPlaying?.progressKey)
        assertNull(vm.state.value.alternateSources)
    }

    @Test fun dismissingTheSourcePickerReleasesItsPlayerReaderAndCannotReuseItOnReopen() {
        vm.alternateSourceLookup = { listOf(stream("high"), stream("low")) }
        startMovie()
        val playing = vm.state.value.nowPlaying!!
        var positionReads = 0
        vm.openPlayingAlternateSources(42_000L, playing) { positionReads++; 99_000L }
        vm.closeAlternateSources()
        assertNull(NikTvViewModel::class.java.getDeclaredField("alternatePositionReader")
            .apply { isAccessible = true }.get(vm))

        vm.openPlayingAlternateSources(51_000L, playing)
        vm.chooseAlternateStream("low")
        scheduler.runCurrent()

        assertEquals(0, positionReads)
        assertEquals(51_000L, vm.state.value.nowPlaying?.resumePositionMillis)
    }

    @Test fun aReplacedPlaybackAttemptCannotReadThePreviousDecodersPosition() {
        vm.alternateSourceLookup = { listOf(stream("high"), stream("low")) }
        startMovie()
        val playing = vm.state.value.nowPlaying!!
        var positionReads = 0
        vm.openPlayingAlternateSources(42_000L, playing) { positionReads++; error("Disposed player was accessed") }
        mutableState.value = mutableState.value.copy(nowPlaying = playing.copy(playbackAttemptId = "new-attempt"))

        vm.chooseAlternateStream("low")
        scheduler.runCurrent()

        assertEquals(0, positionReads)
        assertEquals(42_000L, vm.state.value.nowPlaying?.resumePositionMillis)
        assertNull(vm.state.value.error)
    }

    @Test fun changingTheTitleCannotReadOrReuseTheActiveMoviesPosition() {
        vm.alternateSourceLookup = { listOf(stream("high"), stream("low")) }
        startMovie()
        val playing = vm.state.value.nowPlaying!!
        var positionReads = 0
        vm.openPlayingAlternateSources(42_000L, playing) { positionReads++; 67_000L }
        val otherTitle = vm.state.value.alternateSources!!.target.copy(title = "Another fixture movie", tmdbId = null)
        vm.findAlternateSources(otherTitle)
        scheduler.runCurrent()
        vm.chooseAlternateStream("low")
        scheduler.runCurrent()

        assertEquals(0, positionReads)
        assertEquals(0L, vm.state.value.nowPlaying?.resumePositionMillis)
        assertNotEquals(playing.progressKey, vm.state.value.nowPlaying?.progressKey)
    }

    @Test fun changingTheEpisodeInTheSourcePickerDoesNotReuseThePreviousEpisodesPosition() {
        vm.alternateSourceLookup = { target -> listOf(stream("high", "episode-${target.episode}")) }
        val episode = series.copy(id = "provider-episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, series, 2, 3))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()
        val original = vm.state.value.nowPlaying!!

        var positionReads = 0
        vm.openPlayingAlternateSources(2_400_000, original) { positionReads++; 2_450_000L }
        val nextTarget = vm.state.value.alternateSources!!.target.copy(episode = 4)
        vm.findAlternateSources(nextTarget)
        scheduler.runCurrent()
        assertNull(vm.state.value.alternateSources?.resumePositionMillis)
        assertNull(vm.state.value.alternateSources?.selectedId)
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()

        val next = vm.state.value.nowPlaying!!
        assertEquals(4, next.media.episodeNumber)
        assertEquals(0L, next.resumePositionMillis)
        assertEquals(0, positionReads)
        assertNotEquals(original.progressKey, next.progressKey)
        assertNull(vm.state.value.error)
    }

    @Test fun editingABenignTileToASensitiveTitleRequiresAPinBeforeSourceLookup() {
        var lookups = 0
        vm.alternateSourceLookup = { lookups++; listOf(stream("high")) }
        // A series tile opens episode selection without a speculative lookup.
        vm.openAlternateSources(AlternateSourceRequest(series, CatalogType.SERIES))
        scheduler.runCurrent()
        assertEquals(0, lookups)
        val picker = vm.state.value.alternateSources!!
        vm.findAlternateSources(picker.target.copy(title = "Adult fixture", tmdbId = null, season = 1, episode = 1))
        scheduler.runCurrent()

        assertNotNull(vm.state.value.parentalPrompt)
        assertEquals(0, lookups)
        assertNull(vm.state.value.nowPlaying)
        vm.cancelParentalPin()
        scheduler.runCurrent()
        assertNull(vm.state.value.parentalPrompt)
        assertEquals(0, lookups)
        assertNull(vm.state.value.nowPlaying)
    }

    @Test fun editingASeriesDropsTheOriginalShowsMetadataAndKeepsItsReturnOrigin() {
        vm.alternateSourceLookup = { listOf(stream("high")) }
        val originalSeries = series.copy(logo = "https://fixture.invalid/old.jpg", description = "Original show plot", cast = listOf("Original actor"))
        val episode = originalSeries.copy(id = "provider-episode:3", title = "Original episode", seasonNumber = 2, episodeNumber = 3,
            episodeAirDate = "2020-01-01", episodeAddedAtMillis = 123L)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, originalSeries, 2, 3))
        scheduler.runCurrent()
        vm.findAlternateSources(AlternateSourceTarget("Another show", series = true, tmdbId = 44, season = 2, episode = 3))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()

        val playing = vm.state.value.nowPlaying!!
        assertEquals("Another show", playing.series?.title)
        assertEquals(44, playing.series?.externalTmdbId)
        assertNotEquals(originalSeries.id, playing.series?.id)
        assertNull(playing.series?.logo)
        assertNull(playing.series?.description)
        assertTrue(playing.series!!.cast.isEmpty())
        assertNull(playing.media.description)
        assertNull(playing.media.episodeAirDate)
        assertNull(playing.media.episodeAddedAtMillis)
        assertEquals(episode.id, vm.state.value.playbackReturnFocusId)
        vm.playNextEpisode()
        scheduler.runCurrent()
        assertEquals("Another show", vm.state.value.nowPlaying?.series?.title)
        assertEquals(44, vm.state.value.nowPlaying?.series?.externalTmdbId)
        assertEquals(4, vm.state.value.nowPlaying?.media?.episodeNumber)
    }

    @Test fun parentSeriesCategoryProtectionAppliesAtEntrySelectionAndExpiredUnlockRetry() {
        val app = RuntimeEnvironment.getApplication()
        ParentalControls.setRelockMinutes(app, 1)
        val protectedSeries = series.copy(portalCategoryId = "sensitive-category")
        val episode = series.copy(id = "episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3, portalCategoryId = null)
        mutableState.value = mutableState.value.copy(rawCategoriesByType = mapOf(CatalogType.SERIES to
            listOf(Category("sensitive-category", "Restricted collection", CatalogType.SERIES, isAdult = true))))
        var calls = 0
        vm.alternateSourceLookup = { calls++; listOf(stream("high")) }
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, protectedSeries, 2, 3))
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertEquals(0, calls)
        vm.submitParentalPin("0000")
        scheduler.runCurrent()
        assertEquals(1, calls)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertNull(vm.state.value.nowPlaying)
        vm.submitParentalPin("0000")
        scheduler.runCurrent()
        val playing = vm.state.value.nowPlaying!!
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        vm.retryPlayback(5_000L, playing)
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertEquals(1, calls)
        assertSame(playing, vm.state.value.nowPlaying)
        vm.cancelParentalPin()
        scheduler.runCurrent()
    }

    @Test fun anExpiredUnlockRequiresAuthorizationBeforeAdvancingAnAlternateEpisode() {
        val app = RuntimeEnvironment.getApplication()
        ParentalControls.setRelockMinutes(app, 1)
        ParentalControls.unlock(app)
        var lookups = 0
        vm.alternateSourceLookup = { target -> lookups++; listOf(stream("high", "episode-${target.episode}")) }
        val protectedSeries = series.copy(isAdult = true)
        val episode = series.copy(id = "provider-episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, protectedSeries, 2, 3))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()
        val original = vm.state.value.nowPlaying!!
        assertEquals(1, lookups)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertFalse(ParentalControls.unlocked(app))

        vm.playNextEpisode()
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertEquals(1, lookups)
        assertSame(original, vm.state.value.nowPlaying)
        vm.cancelParentalPin()
        scheduler.runCurrent()
        assertSame(original, vm.state.value.nowPlaying)
        assertEquals(1, lookups)

        vm.playNextEpisode()
        scheduler.runCurrent()
        vm.submitParentalPin("0000")
        scheduler.runCurrent()
        assertEquals(2, lookups)
        assertEquals(4, vm.state.value.nowPlaying?.media?.episodeNumber)
        assertNull(vm.state.value.parentalPrompt)
        assertNull(vm.state.value.error)
    }

    @Test fun explicitMovyLookupOffersNativeThenEmbeddedWithoutStartingEitherAutomatically() {
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { target ->
            calls += target
            listOfNotNull(stream("movy-native"), vidyFallbackStream(target))
        }
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        vm.findAlternateSources(vm.state.value.alternateSources!!.target.copy(provider = AlternateSourceProvider.MOVY))
        scheduler.runCurrent()

        val picker = requireNotNull(vm.state.value.alternateSources)
        assertEquals(AlternateSourceProvider.MOVY, picker.target.provider)
        assertEquals(listOf(AlternatePlaybackRoute.NATIVE, AlternatePlaybackRoute.VIDY_EMBEDDED), picker.streams.map { it.route })
        assertEquals(2, calls.size)
        assertNull("Resolving choices must not create a decoder or auto-select a fallback", vm.state.value.nowPlaying)
        vm.chooseAlternateStream("movy-native")
        scheduler.runCurrent()
        val committed = requireNotNull(vm.state.value.nowPlaying)
        scheduler.advanceTimeBy(30_000L)
        scheduler.runCurrent()
        assertSame("Elapsed time alone must not switch native playback to an embed", committed, vm.state.value.nowPlaying)
        assertEquals(2, calls.size)
    }

    @Test fun nativeToEmbeddedSelectionKeepsLivePositionPauseEngineAndReturnOrigin() {
        var calls = 0
        vm.alternateSourceLookup = { target -> calls++; listOfNotNull(stream("high"), vidyFallbackStream(target)) }
        startMovie()
        val original = requireNotNull(vm.state.value.nowPlaying)
        val originalEngine = vm.state.value.playbackEngine
        mutableState.value = mutableState.value.copy(playbackReturnFocusId = "origin-tile")
        vm.openPlayingAlternateSources(12_000L, original) { 67_000L }
        vm.setPlaybackIntent(original.playbackAttemptId, false)
        vm.chooseAlternateStream("vidy-embedded")
        scheduler.runCurrent()

        val embedded = requireNotNull(vm.state.value.nowPlaying)
        val source = requireNotNull(embedded.alternateSource)
        assertEquals(AlternatePlaybackRoute.VIDY_EMBEDDED,
            source.streams.single { it.id == source.selectedId }.route)
        assertEquals(original.progressKey, embedded.progressKey)
        assertEquals(67_000L, embedded.resumePositionMillis)
        assertFalse(embedded.playWhenReady)
        assertEquals(originalEngine, vm.state.value.playbackEngine)
        assertEquals("origin-tile", vm.state.value.playbackReturnFocusId)
        assertEquals(1, calls)
        assertNull(vm.state.value.session)
        assertTrue(vm.state.value.playbackUrls.isEmpty())
        assertNull(vm.state.value.alternateSources)
    }

    @Test fun obsoleteMovyEmbedResultsCannotReplaceNewerNativeChoices() {
        val delayed = deferred()
        vm.alternateSourceLookup = { target ->
            if (target.provider == AlternateSourceProvider.MOVY) withContext(NonCancellable) { delayed.await() }
            else listOf(stream("new-native"))
        }
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        val oldTarget = vm.state.value.alternateSources!!.target.copy(provider = AlternateSourceProvider.MOVY)
        vm.findAlternateSources(oldTarget)
        scheduler.runCurrent()
        val newer = movie.copy(id = "newer-movie", title = "Newer fixture", externalTmdbId = 99)
        vm.openAlternateSources(AlternateSourceRequest(newer, CatalogType.MOVIES))
        scheduler.runCurrent()
        delayed.complete(listOf(requireNotNull(vidyFallbackStream(oldTarget))))
        scheduler.runCurrent()

        val picker = requireNotNull(vm.state.value.alternateSources)
        assertSame(newer, picker.request.item)
        assertEquals(AlternateSourceProvider.AUTO, picker.target.provider)
        assertEquals(listOf("new-native"), picker.streams.map { it.id })
        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.error)
    }

    @Test fun profileChangeBetweenEmbeddedSelectionAndCommitLeavesActivePlaybackUntouched() {
        vm.alternateSourceLookup = { target -> listOfNotNull(stream("high"), vidyFallbackStream(target)) }
        startMovie()
        val active = requireNotNull(vm.state.value.nowPlaying)
        vm.openPlayingAlternateSources(12_000L, active)
        vm.chooseAlternateStream("vidy-embedded")
        mutableState.value = mutableState.value.copy(savedProfile = profile.copy(portalUrl = "https://other.invalid"))
        scheduler.runCurrent()

        assertSame(active, vm.state.value.nowPlaying)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.session)
        assertTrue(vm.state.value.playbackUrls.isEmpty())
    }

    @Test fun closingThePlayerBeforeAnEmbeddedCommitCannotResurrectPlayback() {
        vm.alternateSourceLookup = { target -> listOfNotNull(stream("high"), vidyFallbackStream(target)) }
        startMovie()
        vm.openPlayingAlternateSources(12_000L, requireNotNull(vm.state.value.nowPlaying))
        vm.chooseAlternateStream("vidy-embedded")
        vm.closePlayer()
        scheduler.runCurrent()

        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.alternateSources)
        assertNull(vm.state.value.error)
        assertEquals(movie.id, vm.state.value.playbackReturnFocusId)
    }

    @Test fun embeddedEpisodeNavigationRetainsMovyIdentityWithoutOpeningAPortal() {
        val calls = mutableListOf<AlternateSourceTarget>()
        vm.alternateSourceLookup = { target -> calls += target; listOfNotNull(stream("native"), vidyFallbackStream(target)) }
        val episode = series.copy(id = "provider-episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, series, 2, 3))
        scheduler.runCurrent()
        vm.findAlternateSources(vm.state.value.alternateSources!!.target.copy(provider = AlternateSourceProvider.MOVY))
        scheduler.runCurrent()
        vm.chooseAlternateStream("vidy-embedded")
        scheduler.runCurrent()
        val first = requireNotNull(vm.state.value.nowPlaying)
        vm.playNextEpisode()
        scheduler.runCurrent()
        assertEquals(4, vm.state.value.nowPlaying?.media?.episodeNumber)
        assertEquals("vidy-embedded", vm.state.value.nowPlaying?.alternateSource?.selectedId)
        assertEquals(0L, vm.state.value.nowPlaying?.resumePositionMillis)
        vm.playPreviousEpisode()
        scheduler.runCurrent()

        assertEquals(listOf(3, 3, 4, 3), calls.map { it.episode })
        assertTrue(calls.drop(1).all { it.provider == AlternateSourceProvider.MOVY && it.tmdbId == 21 && it.season == 2 })
        assertEquals(first.progressKey, vm.state.value.nowPlaying?.progressKey)
        assertEquals(episode.id, vm.state.value.playbackReturnFocusId)
        assertNull(vm.state.value.session)
        assertTrue(vm.state.value.playbackUrls.isEmpty())
    }

    @Test fun expiredParentalUnlockBlocksEmbeddedSelectionAndEpisodeNavigation() {
        val app = RuntimeEnvironment.getApplication()
        ParentalControls.setRelockMinutes(app, 1)
        ParentalControls.unlock(app)
        var calls = 0
        vm.alternateSourceLookup = { target -> calls++; listOfNotNull(stream("native"), vidyFallbackStream(target)) }
        val protected = series.copy(isAdult = true)
        val episode = series.copy(id = "episode:3", title = "Episode 3", seasonNumber = 2, episodeNumber = 3)
        vm.openAlternateSources(AlternateSourceRequest(episode, CatalogType.SERIES, protected, 2, 3))
        scheduler.runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        vm.chooseAlternateStream("vidy-embedded")
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertNull(vm.state.value.nowPlaying)
        assertEquals(1, calls)
        vm.submitParentalPin("0000")
        scheduler.runCurrent()
        val active = requireNotNull(vm.state.value.nowPlaying)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        vm.playNextEpisode()
        scheduler.runCurrent()
        assertNotNull(vm.state.value.parentalPrompt)
        assertSame(active, vm.state.value.nowPlaying)
        assertEquals(1, calls)
        vm.cancelParentalPin()
        scheduler.runCurrent()
        assertSame(active, vm.state.value.nowPlaying)
        assertNull(vm.state.value.error)
    }

    private fun startMovie() {
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        vm.chooseAlternateStream("high")
        scheduler.runCurrent()
        assertNotNull("Synthetic alternate selection must start without a PortalSession", vm.state.value.nowPlaying)
    }

    private fun deferred() = CompletableDeferred<List<AlternateStream>>().also { pending += it }
    private fun stream(id: String, revision: String = "first") = AlternateStream(id, "Fixture $id",
        "https://fixture.invalid/$id/$revision.m3u8", mapOf("Referer" to "https://fixture.invalid/"))

    @Test fun cinejoyNativeHandoffPreservesIdentityPositionAndPause() {
        vm.alternateSourceLookup = { listOf(com.nikhil.niktv.data.cinejoyWebStream(it)) }
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES))
        scheduler.runCurrent()
        vm.findAlternateSources(vm.state.value.alternateSources!!.target.copy(provider = AlternateSourceProvider.CINEJOY))
        scheduler.runCurrent()
        vm.chooseAlternateStream("cinejoy-web"); scheduler.runCurrent()
        val original = vm.state.value.nowPlaying!!
        vm.setPlaybackIntent(original.playbackAttemptId, false)
        val paused = vm.state.value.nowPlaying!!
        vm.cinejoyNativeLookup = { listOf(stream("cinejoy-native")) }
        vm.playDetectedCinejoyStream(stream("observed"), 65_000, paused) { fail(it) }
        scheduler.runCurrent()
        assertEquals(stream("cinejoy-native").url, vm.state.value.nowPlaying?.url)
        assertEquals(65_000L, vm.state.value.nowPlaying?.resumePositionMillis)
        assertFalse(vm.state.value.nowPlaying!!.playWhenReady)
        assertEquals(original.progressKey, vm.state.value.nowPlaying?.progressKey)
        assertTrue(vm.state.value.playbackUrls.isEmpty())
    }

    @Test fun closingCinejoyDuringValidationCannotResurrectItsNativeStream() {
        vm.alternateSourceLookup = { listOf(com.nikhil.niktv.data.cinejoyWebStream(it)) }
        vm.openAlternateSources(AlternateSourceRequest(movie, CatalogType.MOVIES)); scheduler.runCurrent()
        vm.findAlternateSources(vm.state.value.alternateSources!!.target.copy(provider = AlternateSourceProvider.CINEJOY)); scheduler.runCurrent()
        vm.chooseAlternateStream("cinejoy-web"); scheduler.runCurrent()
        val delayed = deferred()
        vm.cinejoyNativeLookup = { withContext(NonCancellable) { delayed.await() } }
        vm.playDetectedCinejoyStream(stream("observed"), 60_000, vm.state.value.nowPlaying!!) { fail(it) }
        scheduler.runCurrent()
        vm.closePlayer(); delayed.complete(listOf(stream("late"))); scheduler.runCurrent()
        assertNull(vm.state.value.nowPlaying)
        assertNull(vm.state.value.alternateSources)
    }
}
