package com.nikhil.niktv.ui

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/** Exercise the actual player entry points against local provider responses. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PlayerTransitionViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var storage: CoroutineScope
    private lateinit var vm: NikTvViewModel
    private lateinit var mutable: MutableStateFlow<NikTvState>
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var root: String
    private val series = MediaItem("show", "Fixture series", null, null)

    @Before fun setup() {
        server = ServerSocket(0)
        root = "http://127.0.0.1:${server.localPort}"
        worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    requests += URLDecoder.decode(request, "UTF-8")
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = when {
                        "action=get_series_info" in request -> """{"episodes":{"1":[
                            {"id":"1","title":"Episode 1","episode_num":1,"container_extension":"mkv"},
                            {"id":"2","title":"Episode 2","episode_num":2,"container_extension":"mkv"}]}}"""
                        "action=create_link" in request -> """{"js":{"cmd":"ffmpeg https://media.invalid/fresh.m3u8"}}"""
                        else -> """{"js":{"data":[{"id":"file2"}]}}"""
                    }.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val app = RuntimeEnvironment.getApplication()
        UserSyncStore.get(app).disconnect()
        storage = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = storage,
            produceFile = { temporary.newFolder().resolve("player-transitions.preferences_pb") })
        vm = NikTvViewModel(app, ProfileStore(app, preferences))
        vm.viewModelScope.coroutineContext.cancelChildren()
        scheduler.runCurrent()
        @Suppress("UNCHECKED_CAST")
        val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        mutable = state
    }

    @After fun teardown() {
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
        runBlocking { storage.coroutineContext.job.cancelAndJoin() }
        server.close(); worker.join(1_000)
        Dispatchers.resetMain()
    }

    private fun start(type: PortalType, missingNextCommand: Boolean = false): PlayingMedia {
        val profile = PortalProfile("Fixture", root, "00:00:00:00:00:00", portalType = type,
            username = "fixture", password = "fixture")
        val session = PortalSession(profile, "old-token", "$root/server/load.php", "", "", "")
        val queue = (1..2).map { n -> MediaItem(n.toString(), "Episode $n", null,
            if (missingNextCommand && n == 2) null else "/media/file_$n.mpg",
            seasonNumber = 1, episodeNumber = n, portalSeasonId = "season1", portalEpisodeId = "provider$n",
            streamType = "mkv") }
        val playing = PlayingMedia(queue.first(), "https://media.invalid/stale.m3u8", CatalogType.SERIES,
            nextEpisode = queue.last(), series = series, episodeQueue = queue,
            profileKey = profile.cacheKey(), decoderSessionId = "failed-decoder", decoderRecoveryCount = 2,
            progressKey = providerProgressKey(profile.cacheKey(), queue.first(), CatalogType.SERIES, series),
            playWhenReady = false)
        mutable.value = NikTvState(savedProfile = profile, session = session, nowPlaying = playing,
            selectedSeries = series, selectedType = CatalogType.SERIES, selectedSeriesSeason = 1,
            items = queue, episodeHasMore = false, restoring = false)
        return playing
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Player transition did not finish: ${mutable.value.error}")
    }

    @Test fun xtreamNextWithRestoredCommandlessItemUsesStreamIdWithoutRefetchingSeries() {
        start(PortalType.XTREAM, missingNextCommand = true)
        vm.playNextEpisode()
        await { mutable.value.nowPlaying?.media?.id == "2" }
        assertEquals("$root/series/fixture/fixture/2.mkv", mutable.value.nowPlaying?.url)
        assertEquals(0, requests.size)
        assertEquals(listOf("1", "2"), mutable.value.nowPlaying?.episodeQueue?.map { it.id })
        assertNull(mutable.value.error)
    }

    @Test fun stalkerNextUsesLoadedEpisodeAndCreatesFreshLinkWithoutRescanningSeasons() {
        start(PortalType.STALKER)
        vm.playNextEpisode()
        await { mutable.value.nowPlaying?.media?.id == "2" }
        assertEquals("https://media.invalid/fresh.m3u8", mutable.value.nowPlaying?.url)
        assertEquals(2, requests.size)
        assertTrue(requests.first(), "episode_id=provider2" in requests.first())
        assertTrue(requests.first(), "season_id=season1" in requests.first())
        assertTrue(requests.last(), "action=create_link" in requests.last())
        assertTrue(requests.last(), "series=2" in requests.last())
        assertNull(mutable.value.error)
    }

    @Test fun explicitProviderRetryResetsExhaustedDecoderSessionAndKeepsResumePosition() {
        val failed = start(PortalType.XTREAM)
        vm.retryPlayback(32_000L, failed)
        await { mutable.value.nowPlaying?.playbackAttemptId != failed.playbackAttemptId }
        val resumed = requireNotNull(mutable.value.nowPlaying)
        assertNotEquals(failed.decoderSessionId, resumed.decoderSessionId)
        assertEquals(0, resumed.decoderRecoveryCount)
        assertEquals(32_000L, resumed.resumePositionMillis)
        assertTrue(resumed.playWhenReady)
        assertEquals(failed.media.id, resumed.media.id)
        assertEquals("$root/series/fixture/fixture/1.mkv", resumed.url)
        assertEquals(1, requests.size)
        assertNull(mutable.value.error)
    }

    @Test fun automaticAuthorizationRefreshPreservesDecoderHistoryAndPausedIntent() {
        val failed = start(PortalType.XTREAM)
        vm.authenticatePortal = { mutable.value.session!!.copy(token = "new-token") }
        vm.retryPlaybackAfterAuthorizationFailure(32_000L, failed)
        await { mutable.value.nowPlaying?.playbackAttemptId != failed.playbackAttemptId }
        val resumed = requireNotNull(mutable.value.nowPlaying)
        assertEquals(failed.decoderSessionId, resumed.decoderSessionId)
        assertEquals(failed.decoderRecoveryCount, resumed.decoderRecoveryCount)
        assertEquals(1, resumed.authorizationRetryCount)
        assertEquals(32_000L, resumed.resumePositionMillis)
        assertFalse(resumed.playWhenReady)
        assertEquals(1, requests.size)
        assertNull(mutable.value.error)
    }

    @Test fun directPortalSwitchClearsOldPlayerBeforeAuthenticationAndKeepsBrowseDestination() {
        val old = start(PortalType.XTREAM)
        mutable.value = mutable.value.copy(homeOpen = true, discoverySurface = DashboardSurface.SERIES,
            playbackQueueScope = "old-portal-season", playbackQueuePage = 8,
            playbackQueueHasMore = true, playbackQueueLoadingMore = true)
        val target = mutable.value.session!!.profile.copy(portalUrl = "$root/other", portalType = PortalType.STALKER)
        val authentication = CompletableDeferred<PortalSession>()
        vm.authenticatePortal = { authentication.await() }

        vm.switchProfile(target)
        // Clearing must happen synchronously, before the activation coroutine or
        // a native player's late authorization/end-of-stream callback can run.
        assertNull(mutable.value.nowPlaying)
        assertNull(mutable.value.playbackQueueScope)
        assertEquals(1, mutable.value.playbackQueuePage)
        assertFalse(mutable.value.playbackQueueHasMore)
        assertFalse(mutable.value.playbackQueueLoadingMore)
        vm.retryPlayback(12_000L, old)
        vm.retryPlaybackAfterAuthorizationFailure(12_000L, old)
        vm.restartPlaybackDecoderSession(12_000L, old)
        vm.playNextEpisode()
        vm.playPreviousEpisode()
        assertNull(mutable.value.nowPlaying)
        assertEquals(0, requests.size)

        authentication.complete(PortalSession(target, "new-token", "$root/server/load.php", "", "", ""))
        await { mutable.value.session?.profile?.cacheKey() == target.cacheKey() }
        vm.viewModelScope.coroutineContext.cancelChildren()
        assertNull(mutable.value.nowPlaying)
        assertNull(mutable.value.playbackQueueScope)
        assertFalse(mutable.value.playbackQueueHasMore)
        assertEquals(DashboardSurface.SERIES, mutable.value.discoverySurface)
        assertTrue(mutable.value.homeOpen)
        assertNull(mutable.value.selectedSeries)
    }

    @Test fun selectingTheActivePortalKeepsItsPlayerAndQueue() {
        val playing = start(PortalType.XTREAM)
        vm.authenticatePortal = { awaitCancellation() }
        vm.switchProfile(mutable.value.session!!.profile)
        assertSame(playing, mutable.value.nowPlaying)
        assertEquals(playing.episodeQueue, mutable.value.nowPlaying?.episodeQueue)
        assertEquals(0, requests.size)
    }
}
