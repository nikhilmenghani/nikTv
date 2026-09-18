package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFailureFallbackTest {
    @Test fun recoversOnlyAudioOutputFailures() {
        assertTrue(shouldRecoverWithoutAudio(true, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(shouldRecoverWithoutAudio(true, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(shouldRecoverWithoutAudio(true, false, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(shouldRecoverWithoutAudio(true, false, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test fun optOutAndAlreadyDisabledNeverRetry() {
        assertFalse(shouldRecoverWithoutAudio(false, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertFalse(shouldRecoverWithoutAudio(true, true, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }
}
