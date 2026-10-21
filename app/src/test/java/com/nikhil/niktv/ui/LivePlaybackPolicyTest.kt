package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import com.nikhil.niktv.model.CatalogType
import org.junit.Assert.*
import org.junit.Test

class LivePlaybackPolicyTest {
    @org.junit.Test fun slidingLiveWindowDoesNotHideProgressOrInventProgressDuringAFreeze() {
        val recovery = LiveStreamRecovery()
        for (second in 0..70) {
            val offset = second * 1_000L
            org.junit.Assert.assertFalse(recovery.shouldRecover(offset,
                liveRecoveryPosition(30_000L, offset), true, false))
        }
        val frozen = LiveStreamRecovery()
        for (second in 0..19) {
            val offset = second * 1_000L
            org.junit.Assert.assertFalse(frozen.shouldRecover(offset,
                liveRecoveryPosition(30_000L - offset, offset), true, false))
        }
        org.junit.Assert.assertTrue(frozen.shouldRecover(20_000,
            liveRecoveryPosition(10_000, 20_000), true, false))
    }

    @org.junit.Test fun autoDeviceFailureCanUseCompatibleEngineWithoutChangingExplicitChoices() {
        val failure = androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED
        for (engine in com.nikhil.niktv.model.PlaybackEngine.entries) {
            org.junit.Assert.assertEquals(engine == com.nikhil.niktv.model.PlaybackEngine.AUTO,
                automaticLivePlayerFallback(engine, true, true, false, true, failure))
        }
        val auto = com.nikhil.niktv.model.PlaybackEngine.AUTO
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, true, false, false, true, failure))
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, false, true, false, true, failure))
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, true, true, true, true, failure))
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, true, true, false, false, failure))
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, true, true, false, true,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        org.junit.Assert.assertFalse(automaticLivePlayerFallback(auto, true, true, false, true,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test fun stuckOrResetLivePlaylistsUseBoundedRecoveryWithoutTreatingAuthAsNetwork() {
        assertTrue(recoverableLiveSourceFailure(true, true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null, true))
        assertFalse(recoverableLiveSourceFailure(false, true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null, true))
        assertFalse(recoverableLiveSourceFailure(true, false, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null, true))
        assertFalse(recoverableLiveSourceFailure(true, true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 403, true))
    }

    @Test fun missingLiveChunksSkipRetriesButStartupAndPlaylistLoadsKeepNormalPolicy() {
        assertTrue(expiredLiveSegment(true, C.DATA_TYPE_MEDIA, 404))
        assertTrue(expiredLiveSegment(true, C.DATA_TYPE_MEDIA, 410))
        assertFalse(expiredLiveSegment(false, C.DATA_TYPE_MEDIA, 404))
        assertFalse(expiredLiveSegment(true, C.DATA_TYPE_MANIFEST, 404))
        assertFalse(expiredLiveSegment(true, C.DATA_TYPE_MEDIA, 429))
        assertFalse(expiredLiveSegment(true, C.DATA_TYPE_MEDIA, 503))
    }
    @Test fun liveWindowPositionsAreNotResumedAcrossPlayersOrLinks() {
        assertEquals(0L, playbackRestartPosition(CatalogType.LIVE_TV, 160_000L))
        assertEquals(160_000L, playbackRestartPosition(CatalogType.MOVIES, 160_000L))
        assertEquals(160_000L, playbackRestartPosition(CatalogType.SERIES, 160_000L))
        assertEquals(0L, playbackRestartPosition(CatalogType.MOVIES, -1L))
    }

    @Test fun expiredLiveSegmentsAndConnectionFailuresCanRecoverAfterPlayback() {
        for (status in listOf(404, 410, 500, 502, 503, 504)) {
            assertTrue(recoverableLiveSourceFailure(true, true,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status))
        }
        assertTrue(recoverableLiveSourceFailure(true, true,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null))
        assertTrue(recoverableLiveSourceFailure(true, true,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
    }

    @Test fun recoveryDoesNotMaskAuthRateLimitsStartupOrNonLiveFailures() {
        for (status in listOf(401, 403, 429)) {
            assertFalse(recoverableLiveSourceFailure(true, true,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status))
        }
        assertFalse(recoverableLiveSourceFailure(false, true,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertFalse(recoverableLiveSourceFailure(true, false,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertFalse(recoverableLiveSourceFailure(true, true,
            PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }
}
