package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test

class ProfilePinTest {
    @Test fun saltedVerifiersAcceptOnlyProfilePinOrExplicitOverride() {
        val verifier = ProfilePin.create("1234")
        assertTrue(ProfilePin.verifies(verifier, "1234"))
        assertTrue(ProfilePin.verifies(verifier, "2564"))
        assertFalse(ProfilePin.verifies(verifier, "0000"))
        assertNotEquals(verifier, ProfilePin.create("1234"))
        assertTrue(verifier.matches(Regex("v1:[0-9a-f]{32}:[0-9a-f]{64}")))
    }
    @Test fun existingProfilesRequireOverrideUntilTheirOwnerSetsPin() {
        assertTrue(ProfilePin.verifies(null, "2564"))
        assertFalse(ProfilePin.verifies(null, "1234"))
        listOf("", "123", "12345", "abcd", " 2564").forEach { assertFalse(ProfilePin.verifies(null, it)) }
        assertFalse(ProfilePin.verifies("broken", "1234"))
    }
}
