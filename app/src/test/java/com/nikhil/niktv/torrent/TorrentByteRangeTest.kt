package com.nikhil.niktv.torrent

import org.junit.Assert.*
import org.junit.Test

class TorrentByteRangeTest {
    @Test fun fullOpenAndSuffixRangesHaveExactBounds() {
        assertEquals(TorrentByteRange(0, 99, false), TorrentByteRange.parse(null, 100))
        assertEquals(TorrentByteRange(12, 99, true), TorrentByteRange.parse("bytes=12-", 100))
        assertEquals(TorrentByteRange(12, 25, true), TorrentByteRange.parse("bytes=12-25", 100))
        assertEquals(TorrentByteRange(80, 99, true), TorrentByteRange.parse("bytes=-20", 100))
        assertEquals(TorrentByteRange(0, 99, true), TorrentByteRange.parse("bytes=-200", 100))
        assertEquals(TorrentByteRange(90, 99, true), TorrentByteRange.parse("bytes=90-200", 100))
    }
    @Test fun invalidAndMultiRangesAreRejectedRatherThanServingWrongBytes() {
        for (header in listOf("bytes=100-", "bytes=30-20", "bytes=-0", "bytes=-", "bytes=0-2,4-6", "bytes=999999999999999999999999-", "items=0-1")) {
            assertThrows(header, IllegalArgumentException::class.java) { TorrentByteRange.parse(header, 100) }
        }
    }
}
