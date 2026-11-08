package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test

class LanMediaRangeTest {
    @Test fun absentRangeUsesWholeFile() { assertNull(lanMediaRange(null, 1000)) }
    @Test fun fixedRangePreservesExactByteCount() { assertEquals(LanMediaRange(10, 99), lanMediaRange("bytes=10-99", 1000)); assertEquals(90, lanMediaRange("bytes=10-99", 1000)!!.length) }
    @Test fun openRangeSupportsSeekingToEnd() { assertEquals(LanMediaRange(900, 999), lanMediaRange("bytes=900-", 1000)) }
    @Test fun suffixAndOversizedSuffixAreClamped() { assertEquals(LanMediaRange(900, 999), lanMediaRange("bytes=-100", 1000)); assertEquals(LanMediaRange(0, 999), lanMediaRange("bytes=-2000", 1000)) }
    @Test fun endBeyondFileIsClamped() { assertEquals(LanMediaRange(900, 999), lanMediaRange("bytes=900-2000", 1000)) }
    @Test fun unsatisfiableAndMultiRangesAreRejected() {
        listOf("bytes=1000-", "bytes=99-10", "bytes=-0", "bytes=-", "bytes=0-1,4-5", "bytes=999999999999999999999-", "bad").forEach { range ->
            assertThrows(range, IllegalArgumentException::class.java) { lanMediaRange(range, 1000) }
        }
    }
    @Test fun filesOverTwoGigabytesKeepLongOffsets() { assertEquals(LanMediaRange(4_000_000_000, 4_000_000_099), lanMediaRange("bytes=4000000000-4000000099", 5_000_000_000)) }
    @Test fun emptyFileRejectsRange() { assertThrows(IllegalArgumentException::class.java) { lanMediaRange("bytes=0-", 0) } }
}
