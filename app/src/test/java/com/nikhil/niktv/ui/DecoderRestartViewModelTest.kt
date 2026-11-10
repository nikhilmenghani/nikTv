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
class DecoderRestartViewModelTest {
    @Test fun connectionFailuresAndStartupTimeoutOfferFullSessionRestart() {
        assertTrue(restartSessionForPlaybackFailure(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, false, false))
        assertTrue(restartSessionForPlaybackFailure(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, false, false))
        assertTrue(restartSessionForPlaybackFailure(null, false, true))
        assertTrue(restartSessionForPlaybackFailure(null, true, false))
        assertFalse(restartSessionForPlaybackFailure(androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, false, false))
    }
    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: NikTvViewModel
    private lateinit var state: MutableStateFlow<NikTvState>
    private val failed = PlayingMedia(media = MediaItem("fixture", "Decoder fixture", null, null),
        url = "https://fixture.invalid/stream", catalogType = CatalogType.SERIES,
        progressKey = "decoder-fixture", decoderSessionId = "failed-session", decoderRecoveryCount = 3,
        playWhenReady = false, streamRequestHeaders = mapOf("Referer" to "https://fixture.invalid/"),
        episodeQueue = listOf(MediaItem("next", "Next episode", null, null)), profileKey = "fixture-owner")

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = NikTvViewModel(RuntimeEnvironment.getApplication())
        vm.viewModelScope.coroutineContext.cancelChildren()
        @Suppress("UNCHECKED_CAST")
        state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<NikTvState>
        state.value = NikTvState(nowPlaying = failed, restoring = false)
    }
    @After fun cleanup() {
        vm.viewModelScope.cancel(); scheduler.runCurrent(); Dispatchers.resetMain()
    }

    @Test fun explicitRetryReleasesAndResetsExhaustedPausedDecoderSessionWithoutChangingSource() = runTest(scheduler) {
        vm.restartPlaybackDecoderSession(32_000, failed)
        runCurrent()
        assertNull("Old player must leave composition before recreation", state.value.nowPlaying)
        advanceTimeBy(449); runCurrent(); assertNull(state.value.nowPlaying)
        advanceTimeBy(1); runCurrent()
        val resumed = requireNotNull(state.value.nowPlaying)
        assertNotEquals(failed.decoderSessionId, resumed.decoderSessionId)
        assertNotEquals(failed.playbackAttemptId, resumed.playbackAttemptId)
        assertEquals(0, resumed.decoderRecoveryCount)
        assertTrue(resumed.playWhenReady)
        assertEquals(32_000, resumed.resumePositionMillis)
        assertEquals(failed.copy(playbackAttemptId = resumed.playbackAttemptId,
            decoderSessionId = resumed.decoderSessionId, decoderRecoveryCount = 0,
            playWhenReady = true, resumePositionMillis = 32_000), resumed)
    }

    @Test fun automaticRecoveryKeepsFailedCodecsAndPauseIntentAndStopsAtLimit() = runTest(scheduler) {
        vm.retryPlaybackWithAlternateDecoder(1_000, failed)
        advanceUntilIdle(); assertEquals(failed, state.value.nowPlaying)
        val eligible = failed.copy(decoderRecoveryCount = 1)
        state.value = state.value.copy(nowPlaying = eligible)
        vm.retryPlaybackWithAlternateDecoder(1_000, eligible)
        advanceUntilIdle()
        val resumed = requireNotNull(state.value.nowPlaying)
        assertEquals(eligible.decoderSessionId, resumed.decoderSessionId)
        assertEquals(2, resumed.decoderRecoveryCount)
        assertFalse(resumed.playWhenReady)
    }

    @Test fun staleRetryCannotReplaceANewerPlaybackAttempt() = runTest(scheduler) {
        val current = failed.copy(playbackAttemptId = "new-attempt")
        state.value = state.value.copy(nowPlaying = current)
        vm.restartPlaybackDecoderSession(0, failed)
        advanceUntilIdle(); assertEquals(current, state.value.nowPlaying)
    }

    @Test fun leavingDuringReleaseGraceDoesNotReopenThePlayer() = runTest(scheduler) {
        vm.restartPlaybackDecoderSession(0, failed)
        runCurrent(); vm.closePlayer(); advanceUntilIdle()
        assertNull(state.value.nowPlaying)
    }
}
