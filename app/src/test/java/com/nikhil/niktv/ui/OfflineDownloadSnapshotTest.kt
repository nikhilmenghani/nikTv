package com.nikhil.niktv.ui

import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadSnapshotTest {
    private val entry = OfflineMediaDownload(profileKey = "fixture", catalogType = CatalogType.MOVIES,
        media = MediaItem("fixture", "Fixture", null, null))
    @Test fun oneQueryPerEntryAndTerminalTransitionsAreIncluded() = runBlocking {
        var calls = 0
        val downloading = queryOfflineDownloadSnapshot(listOf(entry, entry)) {
            calls++; OfflineDownloadInfo(OfflineDownloadStatus.DOWNLOADING, 20f)
        }
        val completed = queryOfflineDownloadSnapshot(listOf(entry)) {
            calls++; OfflineDownloadInfo(OfflineDownloadStatus.COMPLETE, 100f)
        }
        assertEquals(2, calls)
        assertEquals(20f, downloading[entry.key]?.percent)
        assertEquals(OfflineDownloadStatus.COMPLETE, completed[entry.key]?.status)
    }
    @Test fun removingEntriesClearsTheirSnapshots() = runBlocking {
        val result = queryOfflineDownloadSnapshot(emptyList()) { error("No query expected") }
        assertTrue(result.isEmpty())
    }
}
