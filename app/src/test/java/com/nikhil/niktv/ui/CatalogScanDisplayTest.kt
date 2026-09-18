package com.nikhil.niktv.ui

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogScanDisplayTest {
    @Test fun liveWorkerOverridesOldCompletionAndPreventsAnotherStart() {
        assertEquals(CatalogScanDisplay.SCANNING, catalogScanDisplay("Ready", listOf(WorkInfo.State.RUNNING), -1, 100))
        assertEquals(CatalogScanDisplay.QUEUED, catalogScanDisplay("Ready", listOf(WorkInfo.State.ENQUEUED), 1, 100))
    }
    @Test fun pauseTakesEffectWhileInFlightRequestFinishes() {
        assertEquals(CatalogScanDisplay.PAUSED, catalogScanDisplay("Paused", listOf(WorkInfo.State.RUNNING), 1, 0))
        assertEquals(CatalogScanDisplay.STOPPED, catalogScanDisplay("Stopped", listOf(WorkInfo.State.RUNNING), 1, 0))
    }
    @Test fun savedCursorWithoutWorkerRequiresResumeNotNewScan() {
        assertEquals(CatalogScanDisplay.INTERRUPTED, catalogScanDisplay("Ready", emptyList(), 1, 100))
        assertEquals(CatalogScanDisplay.COMPLETE, catalogScanDisplay("Ready", emptyList(), -1, 100))
        assertEquals(CatalogScanDisplay.IDLE, catalogScanDisplay("Ready", emptyList(), -1, 0))
        assertEquals(CatalogScanDisplay.CHECKING, catalogScanDisplay("Ready", null, -1, 0))
    }
}
