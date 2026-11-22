package com.nikhil.niktv.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MkvSubtitleCaptureTest {
    @Test fun nativeExtractorDefaultSampleDataOverloadActuallyPopulatesRecovery() {
        val archive = listOf(File("src/androidTest/assets/caption-fixtures.zip"),
            File("app/src/androidTest/assets/caption-fixtures.zip")).first { it.isFile }
        val bytes = ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry("case03-subrip-ar.mkv")).readBytes() }
        val recovery = SparseSubtitleCueRecovery()
        val support = MkvSubtitleSeekSupport(recovery)
        val extractor = support.extractorsFactory.createExtractors(Uri.parse("file:///case03.mkv"), emptyMap()).first()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(map: SeekMap) = Unit
        }
        extractor.init(output)
        val seek = PositionHolder()
        var position = 0
        try {
            for (iteration in 0..2_000) {
                val input = DefaultExtractorInput(DataReader { target, offset, length ->
                    if (position >= bytes.size) -1 else minOf(length, bytes.size - position).also { count ->
                        bytes.copyInto(target, offset, position, position + count)
                        position += count
                    }
                }, position.toLong(), bytes.size.toLong())
                var result: Int
                do { result = extractor.read(input, seek) } while (result == Extractor.RESULT_CONTINUE)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                assertEquals(Extractor.RESULT_SEEK, result)
                position = seek.position.toInt()
            }
            assertTrue("Native SRT packet has known timing independently of AV queues", recovery.hasActiveCue("3", 3_000_000))
            assertFalse(recovery.hasActiveCue("3", 7_000_000))
            assertFalse(recovery.hasActiveCue("4", 3_000_000))
        } finally {
            extractor.release()
            support.close()
        }
    }
}
