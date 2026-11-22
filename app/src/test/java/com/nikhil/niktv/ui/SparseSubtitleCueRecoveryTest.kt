package com.nikhil.niktv.ui

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.CueEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SparseSubtitleCueRecoveryTest {
    private fun format(id: String = "english", replace: Boolean = false): Format = Format.Builder()
        .setId(id).setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCueReplacementBehavior(if (replace) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE else Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
        .build()

    private fun packet(text: String, durationUs: Long = 6_500_000L): ByteArray = CueEncoder().encode(
        listOf(Cue.Builder().setText(text).build()), durationUs
    )

    private class EndStream(private val packet: ByteArray? = null) : SampleStream {
        var reads = 0
        private var sent = false
        override fun isReady(): Boolean = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long): Int = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            reads++
            buffer.clear()
            if (packet != null && !sent) {
                buffer.timeUs = 500_000L
                buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
                buffer.ensureSpaceForWrite(packet.size)
                checkNotNull(buffer.data).put(packet)
                if (readFlags and SampleStream.FLAG_PEEK == 0) sent = true
            } else buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            return C.RESULT_BUFFER_READ
        }
    }

    private class Output : TextOutput {
        var latest: CueGroup = CueGroup.EMPTY_TIME_ZERO
        override fun onCues(cueGroup: CueGroup) { latest = cueGroup }
        fun text(): String = latest.cues.joinToString("\n") { it.text.toString() }
    }

    private fun renderer(
        recovery: SparseSubtitleCueRecovery, format: Format, source: SampleStream, output: Output,
        positionUs: Long = 3_000_000L, offsetUs: Long = 0L
    ): Renderer = recovery.wrapRenderer(TextRenderer(output, null)).apply {
        init(0, PlayerId.UNSET, Clock.DEFAULT)
        enable(RendererConfiguration.DEFAULT, arrayOf(format), source, positionUs + offsetUs,
            false, true, offsetUs, offsetUs, MediaSource.MediaPeriodId("fixture"))
    }

    private fun Renderer.drawAt(positionUs: Long) { repeat(8) { render(positionUs, 0L) } }

    @Test fun nativeRendererRestoresSparseCueAfterBackwardSeekAndClearsAtExactEnd() {
        val recovery = SparseSubtitleCueRecovery()
        val format = format()
        recovery.capture(format, 500_000L, packet("Sparse caption"))
        val source = EndStream()
        val output = Output()
        val renderer = renderer(recovery, format, source, output, offsetUs = 1_000_000_000L)
        assertSame("Keep MediaPeriod stream identity", source, renderer.stream)
        renderer.drawAt(1_003_000_000L)
        assertEquals("Sparse caption", output.text())
        renderer.resetPosition(1_007_500_000L, true)
        renderer.drawAt(1_007_500_000L)
        assertEquals("", output.text())
        renderer.resetPosition(1_003_000_000L, true)
        renderer.drawAt(1_003_000_000L)
        assertEquals("Sparse caption", output.text())
        renderer.resetPosition(1_007_000_000L, true)
        renderer.drawAt(1_007_000_000L)
        assertEquals("", output.text())
        renderer.disable()
        assertNull(recovery.selectedTrackId)
    }

    @Test fun nativeRereadDoesNotDuplicateRecoveredCaptionAndOtherTracksRemainSeparate() {
        val recovery = SparseSubtitleCueRecovery()
        var releases = 0
        recovery.onRelease = { releases++ }
        val english = format()
        val bytes = packet("English caption")
        recovery.capture(english, 500_000L, bytes)
        recovery.capture(format("french"), 500_000L, packet("French caption"))
        val output = Output()
        val renderer = renderer(recovery, english, EndStream(bytes), output)
        renderer.drawAt(3_000_000L)
        assertEquals("English caption", output.text())
        assertEquals(1, output.latest.cues.size)
        renderer.disable()
        assertEquals("", output.text())
        assertEquals(0, releases)
        renderer.release()
        assertEquals(1, releases)
    }

    @Test fun recoveryKeepsNativeOverlapAndReplacementBehavior() {
        val recovery = SparseSubtitleCueRecovery()
        val merging = format()
        recovery.capture(merging, 500_000L, packet("First"))
        recovery.capture(merging, 2_000_000L, packet("Second", 2_000_000L))
        val output = Output()
        val renderer = renderer(recovery, merging, EndStream(), output)
        renderer.drawAt(3_000_000L)
        assertEquals(listOf("First", "Second"), output.latest.cues.map { it.text.toString() })
        renderer.disable()
        val replacing = format("replacing", replace = true)
        recovery.capture(replacing, 500_000L, packet("Old"))
        recovery.capture(replacing, 2_000_000L, packet("New", 1_000_000L))
        assertFalse("Expired replacement cannot reveal its predecessor", recovery.hasActiveCue("replacing", 4_000_000L))
    }

    @Test fun pendingTextOnlyLoadCannotEndRendererBeforeLateCueArrives() {
        val recovery = SparseSubtitleCueRecovery()
        val format = format()
        var pending = true
        recovery.cueLoader = { id, time ->
            assertEquals("english", id)
            assertEquals(3_000_000L, time)
            pending
        }
        val source = EndStream()
        val output = Output()
        val renderer = renderer(recovery, format, source, output)
        renderer.drawAt(3_000_000L)
        assertTrue("Try the native stream before extra range loading", source.reads > 0)
        assertFalse(renderer.isEnded)
        renderer.drawAt(4_000_000L) // Pending polls must retain the original 3s seek target.
        assertFalse(renderer.isEnded)
        recovery.capture(format, 500_000L, packet("Loaded after reset"))
        pending = false
        renderer.drawAt(4_000_000L)
        assertEquals("Loaded after reset", output.text())
        renderer.disable()
    }

    @Test fun cacheRejectsUnknownDurationAndEvictsBoundedOldPackets() {
        val recovery = SparseSubtitleCueRecovery(maxPackets = 2)
        val format = format()
        recovery.capture(format, 0L, packet("Unknown", C.TIME_UNSET))
        assertFalse(recovery.hasActiveCue("english", 1L))
        recovery.capture(format, 0L, packet("First", 500_000L))
        recovery.capture(format, 1_000_000L, packet("Second", 500_000L))
        recovery.capture(format, 2_000_000L, packet("Third", 500_000L))
        assertFalse(recovery.hasActiveCue("english", 100_000L))
        assertTrue(recovery.hasActiveCue("english", 1_100_000L))
        assertTrue(recovery.hasActiveCue("english", 2_100_000L))
        assertFalse(recovery.hasActiveCue("french", 2_100_000L))
    }
}
