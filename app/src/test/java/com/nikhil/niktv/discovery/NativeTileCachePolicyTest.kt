package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class NativeTileCachePolicyTest {
    @Test fun foregroundOnlyAndPressureCannotBeUndoneByFocusGain() {
        val cache = NativeTileCachePolicy(true)
        assertFalse(cache.enabled)
        cache.windowFocus(true); assertTrue(cache.enabled)
        cache.windowFocus(false); assertFalse(cache.enabled)
        cache.windowFocus(true); assertTrue(cache.enabled)
        cache.memoryPressure(); assertFalse(cache.enabled)
        cache.windowFocus(false); cache.windowFocus(true); assertFalse(cache.enabled)
    }
    @Test fun unrequestedCacheNeverTurnsOn() {
        val cache = NativeTileCachePolicy(false)
        cache.windowFocus(true)
        assertFalse(cache.enabled)
    }
}
