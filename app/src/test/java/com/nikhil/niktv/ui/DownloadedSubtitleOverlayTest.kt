package com.nikhil.niktv.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadedSubtitleOverlayTest {
    @Test fun webVttMetadataIsNotDisplayedAndFractionalTimesKeepTheirScale() {
        val cues = parseDownloadedSubtitleText(
            "\uFEFFWEBVTT\r\n\r\n" +
                "NOTE example\r\n00:01.000 --> 00:02.000\r\nNot a caption\r\n\r\n" +
                "STYLE\r\n::cue { color: white; }\r\n\r\n" +
                "cue-1\r\n01:30.5 --> 01:32.05 align:middle\r\nCaption"
        )
        assertEquals(listOf(DownloadedSubtitleCue(90_500L, 92_050L, "Caption")), cues)
    }

    @Test fun malformedTimestampsCannotBecomePlausibleCaptionsOrOverflow() {
        val starts = listOf(
            "-00:00:01,000", "garbage00:00:01,000", "00:60:01,000",
            "00:00:60,000", "00:00:01,0000", "999999999999999999999:00:01,000",
            "2562047788016:00:00,000"
        )
        starts.forEach { start ->
            assertTrue(start, parseDownloadedSubtitleText("1\n$start --> 00:00:02,000\nBad").isEmpty())
        }
        listOf("00:00:00,000", "00:00:01,000", "-00:00:02,000").forEach { end ->
            assertTrue(end, parseDownloadedSubtitleText("1\n00:00:01,000 --> $end\nBad").isEmpty())
        }
    }

    @Test fun markupAndEntitiesProduceReadableMultilineCaptions() {
        val cues = parseDownloadedSubtitleText(
            "1\n00:00:01,000 --> 00:00:03,000\n" +
                "{\\an8}<i>Tom &amp; Zo&euml;</i><BR />&quot;Hello&quot; &#x1F44B; &lt;door&gt;"
        )
        assertEquals("Tom & Zoë\n\"Hello\" 👋 <door>", cues.single().text)
    }

    @Test fun overlappingDialogueIsCombinedAndSeeksRespectExclusiveEndTimes() {
        val timeline = DownloadedSubtitleTimeline(parseDownloadedSubtitleText(
            "1\n00:00:02,000 --> 00:00:04,000\nSecond\n\n" +
                "2\n00:00:01,000 --> 00:00:03,000\nFirst\n\n" +
                "3\n00:00:05,000 --> 00:00:06,000\nLater"
        ))
        assertNull(timeline.textAt(999))
        assertEquals("First", timeline.textAt(1_000))
        assertEquals("First\nSecond", timeline.textAt(2_500))
        assertEquals("Second", timeline.textAt(3_000))
        assertNull(timeline.textAt(4_000))
        assertEquals("Later", timeline.textAt(5_000))
        assertEquals("First\nSecond", timeline.textAt(2_000))
        assertNull(timeline.textAt(0))
    }

    @Test fun positiveDelayShowsLaterAndChangingDelayDoesNotLeaveStaleText() {
        val timeline = DownloadedSubtitleTimeline(listOf(DownloadedSubtitleCue(1_000, 2_000, "Caption")))
        assertNull(timeline.textAt(1_000, 500))
        assertEquals("Caption", timeline.textAt(1_500, 500))
        assertEquals("Caption", timeline.textAt(2_000, 500))
        assertNull(timeline.textAt(2_500, 500))
        assertEquals("Caption", timeline.textAt(500, -500))
        assertNull(timeline.textAt(500, 0))
        assertEquals("Caption", timeline.textAt(1_000, 0))
    }

    @Test fun utf16DownloadsPreserveUnicodeAndRemovedFilesDoNotCrashPlayback() {
        val file = File.createTempFile("niktv-subtitles", ".srt")
        try {
            file.writeText("1\n00:00:01,000 --> 00:00:02,000\n日本語 हिन्दी", Charsets.UTF_16)
            assertEquals("日本語 हिन्दी", readDownloadedSubtitle(file).single().text)
            assertTrue(file.delete())
            assertTrue(readDownloadedSubtitle(file).isEmpty())
        } finally {
            file.delete()
        }
    }
}
