package com.nikhil.niktv.update

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesFormattingTest {
    @Test
    fun downloadPercentHandlesKnownUnknownAndOversizedProgress() {
        assertNull(downloadPercent(50, null))
        assertNull(downloadPercent(50, 0))
        assertEquals(25, downloadPercent(25, 100))
        assertEquals(100, downloadPercent(150, 100))
    }

    @Test
    fun byteFormattingUsesReadableBinaryUnits() {
        assertEquals("0 B", formatDownloadBytes(-1))
        assertEquals("512 B", formatDownloadBytes(512))
        assertEquals("1.0 KB", formatDownloadBytes(1024))
        assertEquals("1.5 MB", formatDownloadBytes(1_572_864))
    }

    @Test
    fun downloadManagerReasonsAreMeaningful() {
        assertTrue(
            downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE)
                .contains("storage space")
        )
        assertTrue(
            pausedReasonMessage(DownloadManager.PAUSED_WAITING_FOR_NETWORK)
                .contains("network")
        )
        assertTrue(downloadFailureMessage(9876).contains("9876"))
    }
}
