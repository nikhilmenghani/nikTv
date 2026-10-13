package com.nikhil.niktv.data

import com.nikhil.niktv.model.LiveProgramme
import org.junit.Assert.*
import org.junit.Test

class PortalGuideCacheTest {
    @Test fun emptyProviderGuideIsSharedBrieflyThenRetried() {
        val cache = PortalGuideCache("provider", 1000L, emptyMap())
        assertTrue(cache.reusableFor("provider", 1001L))
        assertTrue(cache.reusableFor("provider", 61_000L))
        assertFalse(cache.reusableFor("provider", 301_000L))
    }

    @Test fun manualRefreshAlwaysBypassesCacheAndOtherProfilesCannotReuseIt() {
        val cache = PortalGuideCache("provider", 1000L, mapOf("1" to listOf(LiveProgramme("Movie"))))
        assertTrue(cache.reusableFor("provider", 2000L))
        assertFalse(cache.reusableFor("provider", 2000L, force = true))
        assertFalse(cache.reusableFor("other", 2000L))
        assertFalse(cache.reusableFor("provider", 301_000L))
        assertFalse(cache.reusableFor("provider", 999L))
    }
}
