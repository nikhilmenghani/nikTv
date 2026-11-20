package com.nikhil.niktv.ui

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.TmdbSeries
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ViewerDestination
import com.nikhil.niktv.data.ViewerDestinationKind
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.OfflineMediaDownload
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real navigation entry points with disposable preferences; never authenticates a portal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ProfileDashboardStartupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: ProfileStore
    private lateinit var storeScope: CoroutineScope

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val application = RuntimeEnvironment.getApplication()
        UserSyncStore.get(application).disconnect()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { temporary.newFolder().resolve("dashboard.preferences_pb") })
        store = ProfileStore(application, preferences)
        vm = NikTvViewModel(application)
        // Isolate startup from unrelated background collectors and network work.
        vm.viewModelScope.coroutineContext.cancelChildren()
        NikTvViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.set(vm, store)
    }

    @After fun teardown() = runBlocking {
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
        storeScope.coroutineContext.job.cancelAndJoin()
        Dispatchers.resetMain()
    }

    private suspend fun launchDashboard() {
        NikTvViewModel::class.java.getDeclaredMethod("preparePortalConnections")
            .apply { isAccessible = true }.invoke(vm)
        vm.state.first { !it.restoring }
    }

    @Test fun explicitChooserEntryKeepsSavedProfilesAndReusableSession() = runTest(scheduler) {
        val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
        val session = PortalSession(profile, "fixture-token", "https://fixture.invalid/portal.php", "", "", "")
        store.save(session)
        @Suppress("UNCHECKED_CAST")
        val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        state.value = NikTvState(profiles = listOf(profile), savedProfile = profile, session = session,
            profileSwitcherOpen = true, settingsOpen = true, restoring = false)
        vm.resetPortalSession()
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.profileSwitcherOpen)
        assertFalse(vm.state.value.settingsOpen)
        assertFalse(vm.state.value.profileEditorOpen)
        assertEquals(listOf(profile), vm.state.value.profiles)
        assertEquals(profile, vm.state.value.savedProfile)
        assertEquals(session, store.activeSession.first())
        assertEquals(listOf(profile), store.profiles.first())
    }

    @Test fun retiredOptOutPreferencesCannotRestoreLegacyHomeOrClearProfileData() = runTest(scheduler) {
        val application = RuntimeEnvironment.getApplication()
        val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
        store.addProfile(profile)
        preferences.edit { it[intPreferencesKey("modern_ui_enabled")] = 0 }
        application.getSharedPreferences("discovery_experience", 0).edit().putBoolean("enabled", false).commit()
        application.getSharedPreferences("mobile_ui_preferences", 0).edit().putString("mobile_ui_design", "CLASSIC").commit()
        launchDashboard()
        vm.openHome()
        assertEquals(DashboardSurface.HOME, vm.state.value.discoverySurface)
        assertTrue(vm.state.value.homeOpen)
        assertEquals(listOf(profile), store.profiles.first())
    }

    @Test fun allDiscoveryTabsAndHomeReturnUseTheModernRoute() = runTest(scheduler) {
        val stateField = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<NikTvState>
        for (surface in listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)) {
            state.value = NikTvState(searchOpen = true, settingsOpen = true, favoritesOpen = true,
                offlineDownloadsOpen = true, watchProvidersOpen = true)
            vm.openDiscovery(surface)
            assertEquals(surface, vm.state.value.discoverySurface)
            assertTrue(vm.state.value.homeOpen)
            assertFalse(vm.state.value.searchOpen)
            assertFalse(vm.state.value.settingsOpen)
            assertFalse(vm.state.value.favoritesOpen)
            assertFalse(vm.state.value.offlineDownloadsOpen)
            assertFalse(vm.state.value.watchProvidersOpen)
        }
        vm.openHome()
        assertEquals(DashboardSurface.HOME, vm.state.value.discoverySurface)
    }

    @Test fun primaryDestinationsReplaceProviderDetailsMatchChoosersAndSearchSeriesRoutes() = runTest(scheduler) {
        @Suppress("UNCHECKED_CAST")
        val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        val item = MediaItem("fixture", "Fixture", null, null)
        val origin = NikTvState(searchOpen = true, selectedSeries = item,
            seriesOpenedFromHome = true, seriesOpenedFromFavorites = true, seriesOpenedFromModernSection = true,
            providerMovieDetails = com.nikhil.niktv.discovery.ProviderMovieDetails("fixture", item, listOf(item)),
            movieMatchSelection = TmdbMovie(1, "Movie", "Movie", null, null, null, null, null),
            movieMatchCandidates = listOf(item), movieMatchLoadingMore = true,
            seriesMatchSelection = TmdbSeries(2, "Series", "Series", null, null, null, null, null),
            seriesMatchCandidates = listOf(item))
        val destinations: List<() -> Unit> = listOf(
            vm::openHome, { vm.openDiscovery(DashboardSurface.MOVIES) },
            { vm.openDiscovery(DashboardSurface.SERIES) }, { vm.openCatalogType(CatalogType.LIVE_TV) },
            vm::openFavorites, vm::openOfflineDownloads)
        destinations.forEach { navigate ->
            state.value = origin
            navigate()
            val result = vm.state.value
            assertFalse(result.searchOpen)
            assertNull(result.selectedSeries)
            assertNull(result.providerMovieDetails)
            assertNull(result.movieMatchSelection)
            assertTrue(result.movieMatchCandidates.isEmpty())
            assertFalse(result.movieMatchLoadingMore)
            assertNull(result.seriesMatchSelection)
            assertTrue(result.seriesMatchCandidates.isEmpty())
            assertFalse(result.seriesOpenedFromHome)
            assertFalse(result.seriesOpenedFromFavorites)
            assertFalse(result.seriesOpenedFromModernSection)
        }
    }

    @Test fun optimizedHomeIsDefaultAndPreviousHomeRemainsAnExplicitDestination() = runTest(scheduler) {
        vm.openHome()
        assertTrue(vm.state.value.discoveryExperiment)
        val originalNavigation = vm.state.value.discoveryNavigationId
        vm.openPreviousHome()
        assertEquals(DashboardSurface.HOME, vm.state.value.discoverySurface)
        assertFalse(vm.state.value.discoveryExperiment)
        assertTrue(vm.state.value.discoveryNavigationId > originalNavigation)
        vm.openDiscovery(DashboardSurface.MOVIES)
        assertTrue(vm.state.value.discoveryExperiment)
        vm.openPreviousDiscovery(DashboardSurface.MOVIES)
        assertFalse(vm.state.value.discoveryExperiment)
        vm.openHomeExperiment()
        vm.openHome()
        assertTrue(vm.state.value.discoveryExperiment)
        assertEquals(DashboardSurface.HOME, vm.state.value.discoverySurface)
    }

    @Test fun optimizedCatalogsRetainExplicitPreviousRoutes() = runTest(scheduler) {
        for (surface in listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)) {
            vm.openDiscoveryExperiment(surface)
            assertEquals(surface, vm.state.value.discoverySurface)
            assertTrue(vm.state.value.discoveryExperiment)
            val navigation = vm.state.value.discoveryNavigationId
            vm.openDiscovery(surface)
            assertEquals(surface, vm.state.value.discoverySurface)
            assertTrue(vm.state.value.discoveryExperiment)
            vm.openPreviousDiscovery(surface)
            assertEquals(surface, vm.state.value.discoverySurface)
            assertFalse(vm.state.value.discoveryExperiment)
            assertTrue(vm.state.value.discoveryNavigationId > navigation)
        }
    }

    @Test fun newDeviceStartsAtDashboardWithoutAProfileOrSession() = runTest(scheduler) {
        launchDashboard()
        assertTrue(vm.state.value.profiles.isEmpty())
        assertFalse(vm.state.value.profileEditorOpen)
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.settingsOpen)
    }

    @Test fun savedProfilesRemainSelectableWithoutAutomaticAuthentication() = runTest(scheduler) {
        val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
        store.addProfile(profile)
        launchDashboard()
        assertEquals(listOf(profile), vm.state.value.profiles)
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.profileEditorOpen)
    }

    @Test fun cancelFirstProfileReturnsToDashboardWithoutSavingAnything() = runTest(scheduler) {
        launchDashboard()
        repeat(2) {
            vm.addProfile()
            assertTrue(vm.state.value.profileEditorOpen)
            vm.cancelProfileEditor()
            assertFalse(vm.state.value.profileEditorOpen)
            assertTrue(vm.state.value.profiles.isEmpty())
            assertNull(vm.state.value.savedProfile)
        }
        assertTrue(store.profiles.first().isEmpty())
    }

    @Test fun settingsCanOpenAndCloseWithoutAProfile() = runTest(scheduler) {
        launchDashboard()
        vm.openAccountSettings()
        assertTrue(vm.state.value.settingsOpen)
        vm.closeSettings()
        assertFalse(vm.state.value.settingsOpen)
        assertFalse(vm.state.value.profileEditorOpen)
        assertNull(vm.state.value.session)
    }

    @Test fun savedSessionOpensHomeWithoutWaitingForProviderAuthentication(): Unit = runBlocking {
        val profile = PortalProfile("Cached", "https://unreachable.invalid", "00:00:00:00:00:08")
        val session = PortalSession(profile, "cached-session", "https://unreachable.invalid/portal.php", "", "", "")
        store.save(session)
        com.nikhil.niktv.data.CatalogPreferences.setPreferLocal(RuntimeEnvironment.getApplication(), false)
        try {
            vm.switchProfile(profile)
            withTimeout(5_000) {
                while (vm.state.value.session == null) {
                    scheduler.runCurrent()
                    delay(10)
                }
            }
            assertEquals(session, vm.state.value.session)
            assertEquals(DashboardSurface.HOME, vm.state.value.discoverySurface)
            assertTrue(vm.state.value.homeOpen)
        } finally {
            com.nikhil.niktv.data.CatalogPreferences.setPreferLocal(RuntimeEnvironment.getApplication(), true)
        }
    }

    @Test fun profileDownloadsCancelQueuedPortalOpeningAndNavigationWithoutHttp(): Unit = runTest(scheduler) {
        // A socket sentinel detects even a connection attempt; it serves no provider responses.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300
            val endpoint = "http://127.0.0.1:${server.localPort}"
            val profile = PortalProfile("Unreachable cached provider", endpoint, "00:00:00:00:00:09")
            val session = PortalSession(profile, "local-fixture-session", "$endpoint/portal.php", "", "", "")
            store.save(session)
            val state = mutableState()
            state.value = NikTvState(profiles = listOf(profile), savedProfile = profile, session = session,
                profileSwitcherOpen = true, searchOpen = true, settingsOpen = true, homeOpen = true,
                loading = true, restoring = false)
            val navigationRan = java.util.concurrent.atomic.AtomicBoolean()
            val preparation = vm.viewModelScope.launch {
                delay(1_000)
                navigationRan.set(true)
                state.value = state.value.copy(homeOpen = true, offlineDownloadsOpen = false, session = session)
            }
            NikTvViewModel::class.java.getDeclaredField("profilePreparationJob").apply { isAccessible = true }.set(vm, preparation)
            val search = vm.viewModelScope.launch { delay(1_000); navigationRan.set(true) }
            NikTvViewModel::class.java.getDeclaredField("searchServerJob").apply { isAccessible = true }.set(vm, search)
            // A real queued portal opening would renew the cached session and prepare catalogs.
            vm.switchProfile(profile)
            @Suppress("UNCHECKED_CAST")
            val openings = (NikTvViewModel::class.java.getDeclaredField("viewerJobs").apply { isAccessible = true }
                .get(vm) as Set<Job>).filter { it.isActive }
            assertTrue("A portal opening is pending before choosing downloads", openings.isNotEmpty())

            vm.openProfileDownloads()
            assertTrue(openings.all { it.isCancelled })
            assertTrue(preparation.isCancelled)
            assertTrue(search.isCancelled)
            advanceUntilIdle()
            assertFalse("Canceled jobs cannot reopen a portal over downloads", navigationRan.get())
            assertTrue(vm.state.value.offlineDownloadsOpen)
            assertNull(vm.state.value.session)
            assertFalse(vm.state.value.homeOpen)
            assertFalse(vm.state.value.searchOpen)
            assertFalse(vm.state.value.settingsOpen)
            assertFalse(vm.state.value.profileSwitcherOpen)
            assertFalse(vm.state.value.loading)
            assertEquals("The reusable portal session is kept locally", session, store.activeSession.first())
            assertThrows("Downloads must not authenticate or request a catalog", java.net.SocketTimeoutException::class.java) {
                server.accept().use { fail("Opening local downloads contacted the provider") }
            }
        }
    }

    @Test fun profileDownloadsPreserveSelectedPersonLastPortalAndViewingData(): Unit = runTest(scheduler) {
        val users = UserSyncStore.get(RuntimeEnvironment.getApplication())
        val accountId = "90302"
        users.selectAccount(accountId)
        users.initializeDefaults(accountId)
        val family = UserScope(accountId, UserSyncStore.defaultProfileId(accountId, UserProfileType.GENERAL))
        val kids = UserScope(accountId, UserSyncStore.defaultProfileId(accountId, UserProfileType.KIDS))
        try {
            val profile = PortalProfile("Cached provider", "https://unreachable.invalid", "00:00:00:00:00:10")
            val session = PortalSession(profile, "local-fixture-session", "https://unreachable.invalid/portal.php", "", "", "")
            val familyDownload = OfflineMediaDownload(requestId = "family-local-file", profileKey = profile.cacheKey(),
                catalogType = CatalogType.MOVIES, media = MediaItem("movie-1", "Family download", null, null))
            val kidsDownload = familyDownload.copy(requestId = "kids-local-file", media = familyDownload.media.copy(id = "movie-2", title = "Kids download"))
            users.selectProfile(kids.profileId)
            store.saveOfflineDownloads(listOf(kidsDownload))
            users.selectProfile(family.profileId)
            store.save(session)
            store.saveOfflineDownloads(listOf(familyDownload))
            store.saveFavorites(listOf(FavoriteItem(FavoriteKind.MOVIE, familyDownload.media, profileKey = profile.cacheKey())))
            store.savePlaybackProgress(listOf(PlaybackProgress("progress-v3|${profile.cacheKey()}|MOVIES|movie:movie-1", 20_000, 200_000)))
            val destination = ViewerDestination(ViewerDestinationKind.PORTAL, profile.cacheKey())
            users.rememberDestination(family, destination)
            val beforeRecords = users.snapshot(accountId)
            val beforeOutbox = users.pending(accountId)
            val beforeLocal = users.dao.preferences(accountId, family.profileId)
            mutableState().value = NikTvState(profiles = listOf(profile), savedProfile = profile, session = session,
                offlineDownloads = store.offlineDownloads.first(), restoring = false)

            vm.openProfileDownloads()
            advanceUntilIdle()
            assertTrue(vm.state.value.offlineDownloadsOpen)
            assertNull(vm.state.value.session)
            assertEquals(family, users.selectedScope.value)
            assertEquals(family, store.captureScope())
            assertEquals(listOf(familyDownload), vm.state.value.offlineDownloads)
            assertEquals(listOf(kidsDownload), store.scoped(kids).offlineDownloads.first())
            assertEquals(session, store.activeSession.first())
            assertEquals(destination, users.rememberedDestination(family))
            assertEquals(profile.cacheKey(), users.rememberedPortalKey(family))
            assertEquals("Opening downloads never adds or edits synced viewing data", beforeRecords, users.snapshot(accountId))
            assertEquals(beforeOutbox, users.pending(accountId))
            assertEquals(beforeLocal, users.dao.preferences(accountId, family.profileId))
        } finally { users.disconnect() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableState(): MutableStateFlow<NikTvState> =
        NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
}
