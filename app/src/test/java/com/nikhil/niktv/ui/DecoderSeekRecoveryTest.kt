package com.nikhil.niktv.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class, manifest = org.robolectric.annotation.Config.NONE)
class DecoderSeekRecoveryTest {
    private val software = "c2.android.avc.decoder"
    @Test fun newLiveSessionTriesHardwareDespiteOldDeviceAndChannelFallbacks() {
        val hardware = "OMX.MTK.VIDEO.DECODER.AVC"
        val names = listOf(hardware, software)
        assertEquals(names, iptvDecoderCandidateOrder(names, setOf(hardware.lowercase()), emptySet(), true, live = true))
        val failures = DecoderSessionFailures()
        failures.record("channel-A", hardware)
        assertEquals(listOf(software), iptvDecoderCandidateOrder(names, emptySet(), failures.failed("channel-A"), true, live = true))
        assertEquals(names, iptvDecoderCandidateOrder(names, setOf(hardware.lowercase()), failures.failed("channel-B"), true, live = true))
        failures.record("channel-A", software)
        assertTrue(iptvDecoderCandidateOrder(names, emptySet(), failures.failed("channel-A"), false, live = true).isEmpty())
        assertEquals(listOf(software), iptvDecoderCandidateOrder(names, setOf(hardware.lowercase()), emptySet(), true, live = false))
    }

    @Test fun liveAutoIgnoresRememberedBackendButExplicitPlayersAndVodRetainTheirPolicy() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = "historical-live-test"
        context.getSharedPreferences("player_engine_fallbacks", android.content.Context.MODE_PRIVATE)
            .edit().putInt("mtk:$scope", 3).commit()
        assertEquals(com.nikhil.niktv.model.PlaybackEngine.MEDIA3,
            com.nikhil.niktv.model.PlaybackEngine.AUTO.resolvePlayerEngine(context, scope, live = true))
        assertEquals(com.nikhil.niktv.model.PlaybackEngine.VLC,
            com.nikhil.niktv.model.PlaybackEngine.AUTO.resolvePlayerEngine(context, scope, live = false))
        assertEquals(com.nikhil.niktv.model.PlaybackEngine.VLC,
            com.nikhil.niktv.model.PlaybackEngine.VLC.resolvePlayerEngine(context, scope, live = true))
    }
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
