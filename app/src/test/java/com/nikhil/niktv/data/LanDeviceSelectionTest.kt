package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test

class LanDeviceSelectionTest {
    @Test fun selectedDeviceUsesItsCurrentlyAdvertisedPort() {
        val old = LanDevice("Tablet", "192.168.2.94", 37247)
        val current = old.copy(port = 40883)
        assertEquals(current, currentLanDevice(listOf(current), old))
    }
    @Test fun addressChangesAreAlsoRefreshedByServiceIdentity() {
        val old = LanDevice("Tablet", "192.168.2.94", 37247)
        val current = old.copy(host = "192.168.2.99", port = 40883)
        assertEquals(current, currentLanDevice(listOf(current), old))
    }
    @Test fun disappearedSelectionCannotReuseAStaleEndpointOrChooseAnotherDevice() {
        val old = LanDevice("Tablet", "192.168.2.94", 37247)
        assertNull(currentLanDevice(listOf(old.copy(name = "Other TV")), old))
        assertNull(currentLanDevice(listOf(old), null))
    }
}
