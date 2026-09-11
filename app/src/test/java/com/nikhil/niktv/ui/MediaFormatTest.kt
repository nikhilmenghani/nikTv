package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatTest {
    @Test
    fun labelsAdaptiveDirectAndDownloadedSources() {
        assertEquals("HLS", mediaFormatLabel("https://example.test/master.m3u8?token=1"))
        assertEquals("MP4", mediaFormatLabel("https://example.test/movie.mp4"))
        assertEquals("Downloaded video", mediaFormatLabel("content://media/external/video/42"))
    }
}
