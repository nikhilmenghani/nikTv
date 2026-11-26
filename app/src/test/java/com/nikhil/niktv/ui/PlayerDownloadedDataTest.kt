package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class PlayerDownloadedDataTest {
    @Test fun percentUsesOnlyVerifiedBytesAndNeverOverflowsOrCompletesEarly() {
        assertEquals(25, PlaybackDownloadedData(400L, 100L).percent)
        assertEquals(0, PlaybackDownloadedData(400L, -1L).percent)
        assertEquals(100, PlaybackDownloadedData(400L, 500L).percent)
        assertEquals(99, PlaybackDownloadedData(Long.MAX_VALUE, Long.MAX_VALUE - 1L).percent)
        assertEquals(100, PlaybackDownloadedData(Long.MAX_VALUE, Long.MAX_VALUE).percent)
        assertNull(PlaybackDownloadedData(0L, 100L).percent)
    }

    @Test fun verifiedSegmentsRetainGapsWhileClampingAndMergingOverlap() {
        val ranges = listOf(40L..59L, 50L..79L, -10L..9L, 90L..150L, 30L..20L, 150L..199L)
        assertEquals(listOf(0L..9L, 40L..79L, 90L..99L), normalizedDownloadedByteRanges(100L, ranges))
        assertTrue(normalizedDownloadedByteRanges(0L, ranges).isEmpty())
        assertTrue(normalizedDownloadedByteRanges(100L, emptyList()).isEmpty())
    }

    @Test fun hugeAndAdjacentByteRangesMergeWithoutLosingTheLastByte() {
        assertEquals(listOf(0L..Long.MAX_VALUE - 1L), normalizedDownloadedByteRanges(Long.MAX_VALUE,
            listOf(Long.MAX_VALUE - 5L..Long.MAX_VALUE, 0L..Long.MAX_VALUE - 6L)))
        assertEquals(listOf(0L..10L), normalizedDownloadedByteRanges(100L, listOf(0L..5L, 6L..10L)))
    }
}
