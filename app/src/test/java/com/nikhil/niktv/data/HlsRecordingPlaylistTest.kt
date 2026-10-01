package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test

class HlsRecordingPlaylistTest {
    @Test fun actualExtinfDurationsDriveAccounting() {
        val parsed = parseHlsRecordingPlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:1.125,\na.ts\n#EXTINF:2.5,\nb.ts\n#EXT-X-ENDLIST")
        assertEquals(listOf(1125L, 2500L), parsed.segments.map { it.durationMillis })
        assertEquals(5000L, parsed.pollingIntervalMillis)
        assertTrue(parsed.finished)
    }
    @Test fun unsupportedFeaturesRejectTheWholePlaylist() {
        for (tag in listOf("#EXT-X-BYTERANGE:16@0", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-GAP", "#EXT-X-PART:DURATION=1,URI=\"part.ts\"")) {
            try { parseHlsRecordingPlaylist("#EXTM3U\n#EXTINF:1,\na.ts\n$tag\n#EXTINF:1,\nb.ts"); fail("Must reject $tag") }
            catch (expected: UnsupportedHlsRecordingException) { assertFalse(expected.message.isNullOrBlank()) }
        }
    }
    @Test fun unencryptedKeyAndDecimalDurationAreSupported() {
        assertEquals(1500L, parseHlsRecordingPlaylist("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:1.5,\na.ts").segments.single().durationMillis)
    }
    @Test fun malformedDurationsAndMissingUrisAreRejected() {
        for (body in listOf("a.ts", "#EXTINF:NaN,\na.ts", "#EXTINF:-1,\na.ts", "#EXTINF:2,", "#EXTINF:1,\na.ts\n#EXTINF:2,\na.ts")) {
            try { parseHlsRecordingPlaylist("#EXTM3U\n$body"); fail("Malformed playlist accepted") }
            catch (_: UnsupportedHlsRecordingException) { }
        }
    }
    @Test fun masterSessionEncryptionIsRejected() {
        try { validateHlsRecordingFeatures(listOf("#EXTM3U", "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key\"")); fail() }
        catch (_: UnsupportedHlsRecordingException) { }
    }
}
