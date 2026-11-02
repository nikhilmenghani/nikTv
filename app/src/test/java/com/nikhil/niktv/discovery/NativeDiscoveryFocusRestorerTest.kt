package com.nikhil.niktv.discovery

import org.junit.Assert.*
import org.junit.Test

class NativeDiscoveryFocusRestorerTest {
    @Test fun detachedOrReplacedViewCannotReportFalseSuccessOrDetachNewOwner() {
        val restorer = NativeDiscoveryFocusRestorer()
        assertFalse(restorer.restore())
        val old = Any(); val current = Any()
        restorer.attach(old) { true }
        assertTrue(restorer.restore())
        restorer.attach(current) { false }
        restorer.detach(old)
        assertFalse(restorer.restore())
        restorer.attach(current) { true }
        restorer.detach(old)
        assertTrue(restorer.restore())
        restorer.detach(current)
        assertFalse(restorer.restore())
    }
}
