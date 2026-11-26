package com.nikhil.niktv.torrent

import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet

class TorrentStreamStoragePolicyTest {
    @Test fun lowSpaceWarnsForStreamingButBlocksAnIncompleteOfflineDownload() {
        val stream = torrentStoragePolicy(1024, 40L * 1024 * 1024 * 1024, 0, 0, false)
        assertFalse(stream.blocked)
        assertNotNull(stream.warning)
        assertTrue(torrentStoragePolicy(1024, 40L * 1024 * 1024 * 1024, 0, 0, true).blocked)
        assertFalse(torrentStoragePolicy(256L * 1024 * 1024, 1000, 0, 0, true).blocked)
        assertTrue(torrentStoragePolicy(1024, Long.MAX_VALUE, 0, Long.MAX_VALUE, true).blocked)
    }

    @Test fun verifiedRangesClipSharedPiecesAndNeverBridgeMissingBytes() {
        val pieces = BitSet().apply { set(0); set(1); set(3); set(4) }
        assertEquals(listOf(TorrentVerifiedRange(0, 456), TorrentVerifiedRange(712, 1000)),
            verifiedTorrentRanges(pieces, 56, 1000, 256))
        assertTrue(verifiedTorrentRanges(BitSet(), 56, 1000, 256).isEmpty())
        assertNull(torrentStoragePolicy(1000, 1500, 600, 0, false).warning)
    }
}
