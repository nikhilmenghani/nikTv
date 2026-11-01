package com.nikhil.niktv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPosterDecodeSizeTest {
    @Test fun tabletPostersMatchPhysicalSizeIncludingFocusGrowth() {
        assertEquals(DiscoveryPosterDecodeSize(210, 315), discoveryPosterDecodeSize(200, 2f / 3f))
        assertEquals(DiscoveryPosterDecodeSize(200, 300), discoveryPosterDecodeSize(200, 2f / 3f, 1f))
    }
    @Test fun boundsLargeAndInvalidRequests() {
        val large = discoveryPosterDecodeSize(10000, 2f / 3f)
        assertEquals(780, large.width)
        assertTrue(large.height <= 1280)
        val invalid = discoveryPosterDecodeSize(-5, Float.NaN)
        assertTrue(invalid.width > 0 && invalid.height > 0)
    }
    @Test fun wideTilesRetainWideAspectAndSeparateCap() {
        assertEquals(DiscoveryPosterDecodeSize(320, 180), discoveryPosterDecodeSize(320, 16f / 9f, 1f))
        assertEquals(1280, discoveryPosterDecodeSize(10000, 16f / 9f).width)
    }
}
