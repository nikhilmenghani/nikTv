package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class VideoDecoderControlsTest {
    private val hardware = "OMX.MTK.VIDEO.DECODER.AVC"
    private val software = "c2.android.avc.decoder"
    @Test fun explicitHardwareCanOverrideOldSoftwarePreferenceButKeepsFallbackAvailable() {
        assertEquals(listOf(hardware, software), videoDecoderModeOrder(VideoDecoderMode.HARDWARE,
            listOf(software, hardware), setOf(hardware), setOf(software)))
        assertEquals(listOf(software), videoDecoderModeOrder(VideoDecoderMode.HARDWARE,
            listOf(software), emptySet(), setOf(software)))
    }
    @Test fun softwareModeCannotSilentlyInitializeHardware() {
        assertEquals(listOf(software), videoDecoderModeOrder(VideoDecoderMode.SOFTWARE,
            listOf(hardware, software), setOf(hardware), setOf(software)))
        assertTrue(videoDecoderModeOrder(VideoDecoderMode.SOFTWARE, listOf(hardware), setOf(hardware), emptySet()).isEmpty())
    }
    @Test fun autoPreservesSessionExclusionsAndPreferredOrder() {
        val failed = decoderCandidateOrder(listOf(hardware, software), setOf(hardware.lowercase()), false)
        assertEquals(listOf(software), videoDecoderModeOrder(VideoDecoderMode.AUTO, failed, setOf(hardware), setOf(software)))
        assertTrue(videoDecoderModeOrder(VideoDecoderMode.HARDWARE, emptyList(), setOf(hardware), setOf(software)).isEmpty())
    }
    @Test fun currentStatusUsesActualDecoderRatherThanTheRequestedMode() {
        assertEquals("Software", ActiveVideoDecoder(software, false).label)
        assertEquals("SW", ActiveVideoDecoder(software, false).badge)
        assertEquals("Hardware · $hardware", ActiveVideoDecoder(hardware, true).description)
        assertEquals("Decoder", ActiveVideoDecoder("vendor.unknown", null).label)
    }
}
