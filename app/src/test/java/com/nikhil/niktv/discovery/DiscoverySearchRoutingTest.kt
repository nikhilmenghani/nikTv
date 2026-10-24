package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoverySearchRoutingTest {
    @Test fun modernSearchRetainsModernChromeAcrossUnderlyingDestinations() {
        // Search covers Home, category collections, My List and retained details;
        // their flags must not send a wide/tablet layout back to the legacy rail.
        assertTrue(usesDiscoverySearchShell(true, true, false, false, false))
        assertFalse(usesDiscoverySearchShell(false, true, false, false, false))
        assertFalse(usesDiscoverySearchShell(true, false, false, false, false))
    }

    @Test fun higherPriorityOverlaysDoNotAcquireASearchHeader() {
        assertFalse(usesDiscoverySearchShell(true, true, true, false, false))
        assertFalse(usesDiscoverySearchShell(true, true, false, true, false))
        assertFalse(usesDiscoverySearchShell(true, true, false, false, true))
    }
}
