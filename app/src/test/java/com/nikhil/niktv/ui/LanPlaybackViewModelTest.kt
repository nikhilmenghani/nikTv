package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.*
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
class LanPlaybackViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private val item = MediaItem("42", "Movie", null, null)
    private fun resetAccountFixture() {
        // AccountSyncManager is process-wide in the app; each Robolectric method has a new files directory.
        val field = AccountSyncManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null)
        if (previous != null) {
            val scope = AccountSyncManager::class.java.getDeclaredField("syncScope").apply { isAccessible = true }.get(previous) as CoroutineScope
            scope.cancel()
        }
        field.set(null, null)
    }
    @Before fun setup() {
        resetAccountFixture()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren(); scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val mutable = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<NikTvState>
        state = mutable
        state.value = NikTvState(restoring = false)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); scheduler.runCurrent(); resetAccountFixture(); Dispatchers.resetMain() }
    @Test fun receiverRequiresOpenPortalBeforeDoingLookup() = runTest(scheduler) {
        val reply = vm.receiveLanCommand(LanCommand("play", "fixture", RemoteMovie("a".repeat(64), "42", "Movie")))
        assertFalse(reply.ok); assertTrue(reply.message.contains("Open an IPTV portal"))
        assertNull(state.value.nowPlaying)
    }
    @Test fun externalVideoDoesNotRequireMatchingPortalOrProviderLookup() = runTest(scheduler) {
        val video = RemoteVideo("http://192.168.1.20:1234/selected/video", "A local movie", "mkv", positionMillis = 21_000)
        val reply = vm.receiveLanCommand(LanCommand("play", "fixture", video = video))
        assertTrue(reply.message, reply.ok)
        val playing = requireNotNull(state.value.nowPlaying)
        assertTrue(playing.externalPlayback)
        assertEquals(video.url, playing.url)
        assertEquals(video.positionMillis, playing.resumePositionMillis)
        assertEquals(video.format, playing.playbackFormat)
        assertNull(state.value.session)
        assertFalse(vm.receiveLanCommand(LanCommand("status", "fixture")).videoReady)
        vm.observeLivePlayback(playing, failed = false)
        vm.savePlaybackProgress(playing, playing.progressKey, 23_000, 100_000)
        val status = vm.receiveLanCommand(LanCommand("status", "fixture"))
        assertTrue(status.videoReady); assertEquals(23_000, status.positionMillis)
        vm.retryPlayback(45_000, playing)
        val retried = requireNotNull(state.value.nowPlaying)
        assertEquals(video.url, retried.url)
        assertNotEquals(playing.playbackAttemptId, retried.playbackAttemptId)
        assertEquals(45_000, retried.resumePositionMillis)
    }
    @Test fun differentPortalCannotAccidentallyPlaySameProviderId() = runTest(scheduler) {
        val profile = PortalProfile("Fixture", "https://receiver.invalid", "", portalType = PortalType.XTREAM)
        state.value = state.value.copy(session = PortalSession(profile, "", profile.portalUrl, "", "", ""))
        val reply = vm.receiveLanCommand(LanCommand("play", "fixture", RemoteMovie(remotePortalIdentity("XTREAM", "https://sender.invalid"), "42", "Movie")))
        assertFalse(reply.ok); assertTrue(reply.message.contains("same IPTV portal"))
        assertNull(state.value.nowPlaying)
    }
    @Test fun resolvedIptvLinkStartsWithoutReceiverCatalogOrNetworkLookup() = runTest(scheduler) {
        val profile = PortalProfile("Receiver", "https://no-catalog-lookup.invalid", "", portalType = PortalType.STALKER)
        state.value = state.value.copy(session = PortalSession(profile, "receiver-token", profile.portalUrl, "", "", ""))
        val video = RemoteVideo("https://stream.invalid/selected.mp4", "The Odyssey", "mp4",
            mapOf("User-Agent" to "NikTV fixture"), 25_000, remotePortalIdentity("STALKER", profile.portalUrl))
        val reply = vm.receiveLanCommand(LanCommand("play", "fixture", video = video))
        assertTrue(reply.message, reply.ok)
        assertEquals(video.url, state.value.nowPlaying!!.url)
        assertEquals(video.headers, state.value.nowPlaying!!.streamRequestHeaders)
        assertEquals(25_000, state.value.nowPlaying!!.resumePositionMillis)
    }
    @Test fun resolvedIptvLinkRequiresMatchingOpenPortalBeforeReplacingPlayback() = runTest(scheduler) {
        val old = PlayingMedia(item, "https://fixture.invalid/old", CatalogType.MOVIES)
        state.value = state.value.copy(nowPlaying = old)
        val video = RemoteVideo("https://stream.invalid/movie.mp4", "Movie", portal = remotePortalIdentity("STALKER", "https://sender.invalid"))
        assertFalse(vm.receiveLanCommand(LanCommand("play", "fixture", video = video)).ok)
        assertEquals(old, state.value.nowPlaying)
        val profile = PortalProfile("Other", "https://other.invalid", "", portalType = PortalType.STALKER)
        state.value = state.value.copy(session = PortalSession(profile, "", profile.portalUrl, "", "", ""))
        assertFalse(vm.receiveLanCommand(LanCommand("play", "fixture", video = video)).ok)
        assertEquals(old, state.value.nowPlaying)
    }
    @Test fun controlsPreservePlaybackAttemptAndChangePauseIntent() = runTest(scheduler) {
        val playing = PlayingMedia(item, "https://fixture.invalid/video", CatalogType.MOVIES)
        state.value = state.value.copy(nowPlaying = playing)
        assertTrue(vm.receiveLanCommand(LanCommand("pause", "fixture")).ok)
        assertFalse(state.value.nowPlaying!!.playWhenReady)
        assertEquals(playing.playbackAttemptId, state.value.nowPlaying!!.playbackAttemptId)
        assertTrue(vm.receiveLanCommand(LanCommand("resume", "fixture")).ok)
        assertTrue(state.value.nowPlaying!!.playWhenReady)
        assertEquals(playing.playbackAttemptId, state.value.nowPlaying!!.playbackAttemptId)
    }
    @Test fun pauseOnIdleDoesNotClaimSuccess() = runTest(scheduler) {
        assertFalse(vm.receiveLanCommand(LanCommand("pause", "fixture")).ok)
        assertTrue(vm.receiveLanCommand(LanCommand("status", "fixture")).message.contains("Ready"))
    }
    @Test fun movieUsesReceiverCatalogAndReceiverCredentials() = runTest(scheduler) {
        val profile = PortalProfile("Receiver", "https://receiver.invalid", "", portalType = PortalType.XTREAM,
            username = "receiver-user", password = "receiver-password")
        val stored = item.copy(command = "fixture-command", streamType = "mp4")
        val store = NikTvViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }.get(vm) as ProfileStore
        store.saveSearchCatalog(SearchCatalogCache(profile.cacheKey(), CatalogType.MOVIES, 1, listOf(stored)), scheduleMetadataSync = false)
        state.value = state.value.copy(savedProfile = profile, session = PortalSession(profile, "", profile.portalUrl, "", "", ""))
        val reply = vm.receiveLanCommand(LanCommand("play", "fixture", RemoteMovie(remotePortalIdentity("XTREAM", profile.portalUrl), item.id, item.title)))
        assertTrue(reply.message, reply.ok)
        val playing = requireNotNull(state.value.nowPlaying)
        assertEquals(stored.id, playing.media.id)
        assertTrue(playing.url.contains("/receiver-user/receiver-password/"))
        assertTrue(playing.directFullscreen)
        vm.closePlayer()
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
    }
}
