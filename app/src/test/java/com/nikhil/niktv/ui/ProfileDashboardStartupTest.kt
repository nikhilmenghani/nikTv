package com.nikhil.niktv.ui

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.PortalProfile
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
    private lateinit var store: ProfileStore
    private lateinit var storeScope: CoroutineScope

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val application = RuntimeEnvironment.getApplication()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = storeScope,
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
