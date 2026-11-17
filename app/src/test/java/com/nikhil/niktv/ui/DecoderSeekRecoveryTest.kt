package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class, manifest = org.robolectric.annotation.Config.NONE)
class DecoderSeekRecoveryTest {
    private val software = "c2.android.avc.decoder"
    private fun error(code: Int = PlaybackException.ERROR_CODE_DECODING_FAILED) =
        PlaybackException("Decoder failed: $software", IllegalStateException(), code)

    @Test fun softwareRuntimeFailureGetsOneFreshInstanceThenIsExcluded() {
        val failures = DecoderSessionFailures()
        val retryOnce = recreateSoftwareDecoderOnce(error(), software)
        assertTrue(retryOnce)
        failures.record("episode", software, retryOnce)
        assertTrue("First runtime crash must leave the software candidate available", failures.failed("episode").isEmpty())
        failures.record("episode", software, retryOnce)
        assertEquals(setOf(software), failures.failed("episode"))
        failures.record("episode", software, retryOnce)
        assertEquals("Further crashes cannot put the codec back", setOf(software), failures.failed("episode"))
    }

    @Test fun initializationAndHardwareFailuresRemainExcludedImmediately() {
        val failures = DecoderSessionFailures()
        val hardware = "OMX.MTK.VIDEO.DECODER.AVC"
        val initialization = error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertFalse(recreateSoftwareDecoderOnce(initialization, software))
        assertFalse(recreateSoftwareDecoderOnce(error(), hardware))
        failures.record("episode", software, recreateSoftwareDecoderOnce(initialization, software))
        failures.record("episode", hardware, recreateSoftwareDecoderOnce(error(), hardware))
        assertEquals(setOf(software, hardware.lowercase()), failures.failed("episode"))
    }

    @Test fun retriesAreIsolatedAndNeverRestoreOtherFailedCandidates() {
        val failures = DecoderSessionFailures()
        failures.record("episode", "omx.mtk.video.decoder.avc")
        failures.record("episode", software, true)
        assertEquals(listOf(software), iptvDecoderCandidateOrder(listOf("omx.mtk.video.decoder.avc", software),
            setOf("omx.mtk.video.decoder.avc"), failures.failed("episode"), true))
        failures.record("episode", software, true)
        failures.record("different-episode", software, true)
        assertTrue(failures.failed("different-episode").isEmpty())
        assertEquals(emptyList<String>(), decoderCandidateOrder(listOf(software), failures.failed("episode"), false))
    }

    @Test fun wrappedRuntimeFailureIsRecognizedWithoutTreatingNetworkErrorsAsDecoderCrashes() {
        assertTrue(recreateSoftwareDecoderOnce(IllegalStateException(error()), "C2.ANDROID.AVC.DECODER"))
        assertTrue(recreateSoftwareDecoderOnce(error(), "OMX.google.h264.decoder"))
        assertFalse(recreateSoftwareDecoderOnce(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS), software))
        assertFalse(recreateSoftwareDecoderOnce(IllegalStateException("Decoder failed: $software"), software))
    }
}
