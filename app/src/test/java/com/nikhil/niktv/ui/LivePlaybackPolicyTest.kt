package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import com.nikhil.niktv.model.CatalogType
import org.junit.Assert.*
import org.junit.Test

class LivePlaybackPolicyTest {
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
