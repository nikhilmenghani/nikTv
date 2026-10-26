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
class RecentHistoryViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var mutable: MutableStateFlow<NikTvState>
    private val profile = PortalProfile("Fixture", "https://fixture.invalid", "00:00:00:00:00:01")
    private fun recent(id: String, tmdb: Int? = 6060, owner: String = profile.cacheKey()) = RecentItem(FavoriteKind.SERIES,
        MediaItem(id, "Fixture series", null, null, externalTmdbId = tmdb), profileKey = owner)

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren()
        @Suppress("UNCHECKED_CAST")
        val value = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        mutable = value
        mutable.value = NikTvState(savedProfile = profile, session = PortalSession(profile, "fixture", "https://fixture.invalid/portal.php", "", "", ""), restoring = false)
    }
    @After fun teardown() { vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun removingTheVisibleContinueTileCannotRevealItsOlderAlternateAlias() {
        val one = recent("provider")
        val alternate = recent("tmdb-series:6060")
        val remake = recent("remake", 9999)
        mutable.value = mutable.value.copy(recentlyPlayed = listOf(one, alternate, remake))
        vm.removeRecent(one)
        scheduler.runCurrent()
        assertEquals(listOf(remake), vm.state.value.recentlyPlayed)
    }

    @Test fun staleRemovalFromAnotherProfileCannotDeleteTheActiveProfilesHistory() {
        val one = recent("provider")
        mutable.value = mutable.value.copy(recentlyPlayed = listOf(one))
        vm.removeRecent(one.copy(profileKey = "other"))
        scheduler.runCurrent()
        assertEquals(listOf(one), vm.state.value.recentlyPlayed)
    }
}
