package com.nikhil.niktv.ui

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.DashboardSurface
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
        NikTvViewModel::class.java.getDeclaredMethod("prepareProfileChooser")
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
        vm.openProfileChooser()
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
        vm.openSettingsFromProfileChooser()
        assertTrue(vm.state.value.settingsOpen)
        vm.closeSettings()
        assertFalse(vm.state.value.settingsOpen)
        assertFalse(vm.state.value.profileEditorOpen)
        assertNull(vm.state.value.session)
    }
}
