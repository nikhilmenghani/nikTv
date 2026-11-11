package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRowOverflowTest {
    @Test fun emptyAndCompleteRowsDoNotOfferAnotherScreen() {
        assertFalse(discoveryRowHasMore(0, 0))
        assertFalse(discoveryRowHasMore(0, 0, nextPage = true))
        assertFalse(discoveryRowHasMore(1, 1))
        assertFalse(discoveryRowHasMore(30, 30))
    }
    @Test fun omittedItemsAndNextPagesOfferViewAll() {
        assertTrue(discoveryRowHasMore(31, 30))
        assertTrue(discoveryRowHasMore(12, 10))
        assertTrue(discoveryRowHasMore(8, 8, nextPage = true))
        assertFalse(discoveryRowHasMore(31, 31))
    }
}
