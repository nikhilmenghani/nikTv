package com.nikhil.niktv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMediaDownloadsTest {
    @Test
    fun identifiesHlsWithoutTreatingDirectVideosAsSegmented() {
        assertTrue(isSegmentedOfflineStream("https://example.test/master.M3U8?token=abc"))
        assertFalse(isSegmentedOfflineStream("https://example.test/movie.mp4?token=abc"))
        assertFalse(isSegmentedOfflineStream("https://example.test/video/12345"))
    }
}
