package com.nikhil.niktv.data

import com.nikhil.niktv.model.LiveProgramme
import org.junit.Assert.*
import org.junit.Test

class PortalGuideCacheTest {
    @Test fun knownGuideWindowCanExpireBeforeTheSharedRefreshInterval() {
        val snapshot = boundedPortalGuide("wio", 1_000L, mapOf(
            "1" to listOf(LiveProgramme("Short", 900L, 2_000L)),
            "2" to listOf(LiveProgramme("Long", 900L, 2_000_000L))), 1_000L)
        assertFalse(snapshot.channelWindowExpired("1", null, 1_999L))
        assertTrue(snapshot.channelWindowExpired("1", null, 2_000L))
        assertTrue(snapshot.reusableFor("wio", 2_000L))
        assertFalse(snapshot.channelWindowExpired("missing", null, 2_000L))
    }

    @Test fun missingDataAtFetchTimeDoesNotTriggerARepeatedBulkRefresh() {
        val snapshot = boundedPortalGuide("wio", 2_000L,
            mapOf("1" to listOf(LiveProgramme("Already expired", 900L, 1_000L))), 2_000L)
        assertFalse(snapshot.channelWindowExpired("1", null, 3_000L))
    }
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
        assertTrue(cache.reusableFor("provider", 301_000L))
        assertFalse(cache.reusableFor("provider", 1_801_000L))
        assertFalse(cache.reusableFor("provider", 999L))
    }
}
